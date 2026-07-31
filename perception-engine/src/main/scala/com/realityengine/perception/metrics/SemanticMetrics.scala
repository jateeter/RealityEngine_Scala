package com.realityengine.perception.metrics

import io.circe.Json

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.util.Try

/**
 * Semantic guardrail metrics for the Scala Perception Engine.
 *
 * Renders the exposition defined by RealityEngine_Machines
 * docs/PE_METRICS_CONTRACT.md. The `semantic_*` block must be byte-identical
 * across the C++, Lisp, Scala, and TypeScript PEs after normalizing the
 * `runtime` label, so the writer here is deliberately literal: fixed HELP
 * strings, labels sorted by key, integer values, label values sorted
 * ascending.
 */
object SemanticMetrics {

  private val Runtime = "scala"

  // ── Corpus semantics manifest ────────────────────────────────────────────
  // Resolution mirrors the RE (SEMANTICS_MANIFEST, else walk up from
  // MACHINES_DIR) so both halves of a runtime agree on corpus identity.

  private def manifestFile: Option[File] = {
    val explicit = sys.env.get("SEMANTICS_MANIFEST").filter(_.nonEmpty).map(new File(_))
    explicit.orElse {
      val machinesDir = sys.env.getOrElse("MACHINES_DIR", "../RealityEngine_Machines/machines")
      val start = new File(machinesDir).getAbsoluteFile
      Iterator.iterate(start)(_.getParentFile)
        .takeWhile(_ != null).take(6)
        .map(dir => new File(new File(dir, "semantics"), "abox-manifest.json"))
        .find(_.exists())
    }.filter(_.exists())
  }

  // machine name -> ABox base IRI (manifest iri minus the #machine fragment).
  @volatile private var manifestCache: Option[(Long, Map[String, String])] = None

  private def manifestBases: Map[String, String] = manifestFile match {
    case None => Map.empty
    case Some(file) =>
      val stamp = file.lastModified()
      manifestCache match {
        case Some((cached, bases)) if cached == stamp => bases
        case _ =>
          val bases = Try {
            val raw = new String(Files.readAllBytes(file.toPath))
            val doc = io.circe.parser.parse(raw).getOrElse(Json.Null)
            doc.hcursor.downField("machines").as[Map[String, Json]].getOrElse(Map.empty)
              .flatMap { case (_, entry) =>
                for {
                  name <- entry.hcursor.get[String]("name").toOption
                  iri  <- entry.hcursor.get[String]("iri").toOption
                  hash = iri.indexOf('#') if hash > 0
                } yield name -> iri.substring(0, hash)
              }
          }.getOrElse(Map.empty)
          manifestCache = Some((stamp, bases))
          bases
      }
  }

  def manifestAvailable: Boolean = manifestFile.isDefined
  def manifestMachines: Int = manifestBases.size

  /** ABox base IRI for a machine name, or None when it is not in the corpus. */
  def baseIriFor(machineName: Option[String]): Option[String] =
    machineName.flatMap(manifestBases.get)

  // ── Counters ─────────────────────────────────────────────────────────────
  // Monotonic for the process lifetime and incremented where records are
  // created, so a ring-buffer eviction never loses a count.

  private val events       = TrieMap.empty[String, AtomicLong]
  private val eventsJoined = TrieMap.empty[String, AtomicLong]
  private val escalations  = TrieMap.empty[String, AtomicLong]
  private val dispatchTotal  = new AtomicLong(0L)
  private val dispatchJoined = new AtomicLong(0L)

  private def bump(table: TrieMap[String, AtomicLong], key: String): Unit =
    table.getOrElseUpdate(key, new AtomicLong(0L)).incrementAndGet()

  /** Count one re:PerceptionEvent from `integration`, joined when an IRI resolved. */
  def recordPerceptionEvent(integration: String, joined: Boolean): Unit = {
    bump(events, integration)
    if (joined) bump(eventsJoined, integration) else eventsJoined.getOrElseUpdate(integration, new AtomicLong(0L))
  }

  /** Count one dispatch record; `rag` present only for escalation-class actions. */
  def recordDispatch(joined: Boolean, escalationRag: Option[String]): Unit = {
    dispatchTotal.incrementAndGet()
    if (joined) dispatchJoined.incrementAndGet()
    escalationRag.foreach(rag => bump(escalations, if (rag.isEmpty) "unstated" else rag))
  }

  def reset(): Unit = {
    events.clear(); eventsJoined.clear(); escalations.clear()
    dispatchTotal.set(0L); dispatchJoined.set(0L)
    manifestCache = None
  }

  // ── Exposition ───────────────────────────────────────────────────────────

  private def line(name: String, help: String, kind: String,
                   labels: Seq[(String, String)], value: Long): String = {
    val rendered = (labels :+ ("runtime" -> Runtime))
      .sortBy(_._1)
      .map { case (k, v) =>
        val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        k + "=\"" + escaped + "\""
      }
      .mkString(",")
    s"# HELP $name $help\n# TYPE $name $kind\n$name{$rendered} $value\n"
  }

  /**
   * The `semantic_*` block. `sources`, `globalStep`, `vectorSize` and
   * `lastPushMs` are supplied by the route so this object stays free of
   * engine state.
   */
  def render(sources: Int, globalStep: Long, vectorSize: Int,
             lastPushMs: Long, auditBufferRecords: Int): String = {
    val sb = new StringBuilder

    sb ++= line("perception_engine_sources_total",
      "Total sensor/test/simulated sources registered.", "gauge", Nil, sources.toLong)
    sb ++= line("perception_engine_global_step",
      "Engine globalStep counter (push count since start).", "gauge", Nil, globalStep)
    sb ++= line("perception_engine_vector_size",
      "Configured vector dimension.", "gauge", Nil, vectorSize.toLong)
    sb ++= line("perception_engine_last_push_ms",
      "Wall-clock timestamp of the last successful push (0 if never).", "gauge", Nil, lastPushMs)

    sb ++= line("semantic_manifest_available",
      "Corpus OWL semantics manifest resolved (1/0).", "gauge", Nil,
      if (manifestAvailable) 1L else 0L)
    sb ++= line("semantic_manifest_machines",
      "Machines carrying a semantic identity in the manifest.", "gauge", Nil,
      manifestMachines.toLong)
    sb ++= line("semantic_audit_buffer_records",
      "re:PerceptionEvent records held in the audit ring buffer.", "gauge", Nil,
      auditBufferRecords.toLong)

    events.keys.toList.sorted.foreach { integration =>
      sb ++= line("semantic_perception_events_total",
        "re:PerceptionEvent records emitted, by originating integration.", "counter",
        Seq("integration" -> integration), events(integration).get())
    }
    eventsJoined.keys.toList.sorted.foreach { integration =>
      sb ++= line("semantic_perception_events_iri_joined_total",
        "Perception events whose machine resolved to a corpus ABox IRI.", "counter",
        Seq("integration" -> integration), eventsJoined(integration).get())
    }

    sb ++= line("semantic_dispatch_records_total",
      "Dispatch records created with a semantics link.", "counter", Nil, dispatchTotal.get())
    sb ++= line("semantic_dispatch_records_iri_joined_total",
      "Dispatch records whose machine resolved to a corpus ABox IRI.", "counter", Nil,
      dispatchJoined.get())

    escalations.keys.toList.sorted.foreach { rag =>
      sb ++= line("semantic_escalation_dispatches_total",
        "Escalation-class actions dispatched, by RAG status of the determination.", "counter",
        Seq("rag" -> rag), escalations(rag).get())
    }

    sb.result()
  }
}
