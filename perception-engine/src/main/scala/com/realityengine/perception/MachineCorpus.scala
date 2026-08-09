package com.realityengine.perception

import io.circe.Json
import com.realityengine.perception.models.{Region, TestSourceConfig}

import java.io.File
import scala.io.Source
import scala.util.Using

/** What the PE needs to know about machines in order to own the mapping from
  * machine outputs to the next Reality Event vector.
  *
  * That mapping is the PE's responsibility alone, so everything the merge
  * batch reports — including `provenance` and the resolved governance
  * contract — is resolved here rather than asked of the Reality Engine.  The
  * RE is a source of machine *facts*; it is not consulted about the merge.
  *
  * Two inputs, because they carry different things:
  *
  *   - `GET /api/machines` supplies id, name, `perceptualMapping`, and the
  *     full `metadata` block (`triggerConfig`, `governance`, `inputSequences`).
  *     Governance resolution and source seeding need only this.
  *   - The machine corpus on disk supplies the CES definitions —
  *     `sequences[].vectors[].isInitial` — which `/api/machines` does not
  *     serialize (`Machine.toJson` reduces each sequence to `{id, name}`).
  *     Provenance needs this, and nothing else does.
  *
  * Reading the corpus from `MACHINES_DIR` follows the precedent already set by
  * `SemanticMetrics`, which resolves corpus identity the same way so both
  * halves of a runtime agree on what they loaded.
  */
final class MachineCorpus(
  machinesById: Map[String, Json],
  initialVectorIdsByMachineName: Map[String, Map[String, Vector[String]]],
) {

  def size: Int = machinesById.size

  def machine(machineId: String): Option[Json] = machinesById.get(machineId)

  private def machineName(machineId: String): Option[String] =
    machinesById.get(machineId).flatMap(_.hcursor.get[String]("name").toOption)

  /** The ids of the fired sequence's Initial Reality Event vectors.
    *
    * Every machine CES carries at least one initial event vector — it may
    * carry more, never fewer — so for a sequence that actually fired this is
    * expected to be non-empty.  It returns empty rather than failing when the
    * corpus is unreachable, because a missing audit trail must not stop the
    * merge itself.
    */
  def provenance(machineId: String, sequenceId: String): Vector[String] =
    machineName(machineId)
      .flatMap(initialVectorIdsByMachineName.get)
      .flatMap(_.get(sequenceId))
      .getOrElse(Vector.empty)

  /** Resolve the governance contract for a fired sequence, or `None` when the
    * machine declares no matching trigger rule.
    *
    * Mirrors `resolve_governance` in `RealityEngine_CPP/src/reality.cpp`,
    * which is the canonical definition (RealityEngine_CI#91): match a
    * `triggerConfig.rules` entry on both `sequenceId` and an exact
    * `outputMatches` comparison against the asserted values, then layer
    * rule-level governance over machine-level defaults.
    *
    * Note this is deliberately *not* the Scala RE's own governance route
    * (`api/Routes.scala`), which emits a generated `description` rather than
    * the rule's.  That endpoint serves a different consumer and is left alone.
    */
  def governance(machineId: String, sequenceId: String, values: Vector[Double]): Option[Json] =
    for {
      machine <- machinesById.get(machineId)
      meta     = machine.hcursor.downField("metadata")
      rules    = meta.downField("triggerConfig").downField("rules").as[Vector[Json]].getOrElse(Vector.empty)
      rule    <- rules.find { r =>
                   val rc = r.hcursor
                   rc.get[String]("sequenceId").toOption.contains(sequenceId) &&
                     rc.downField("outputMatches").as[Vector[Double]].toOption.contains(values)
                 }
    } yield resolved(machineId, sequenceId, meta, rule)

  private def resolved(
    machineId:  String,
    sequenceId: String,
    meta:       io.circe.ACursor,
    rule:       Json,
  ): Json = {
    val rc         = rule.hcursor
    val machineGov = meta.downField("governance").focus.filter(_.isObject)
    val ruleGov    = rc.downField("governance").focus.filter(_.isObject)

    // Precedence throughout: rule override → machine default → absent.
    def pick(field: String): Option[String] =
      ruleGov.flatMap(_.hcursor.get[String](field).toOption)
        .orElse(machineGov.flatMap(_.hcursor.get[String](field).toOption))

    val processStatus = rc.get[String]("processStatus").toOption.filter(_.nonEmpty)

    // SLA: the rule's own value wins; otherwise the machine's per-status table
    // keyed by this rule's processStatus.  Corpus tables carry explicit nulls
    // for statuses with no SLA, which must stay unset rather than become 0.
    val slaSeconds: Option[Int] =
      ruleGov.flatMap(_.hcursor.get[Int]("slaSeconds").toOption)
        .orElse(processStatus.flatMap { status =>
          machineGov.flatMap(_.hcursor.downField("sla").get[Int](status).toOption)
        })

    def contact(field: String): Option[String] =
      ruleGov.flatMap(_.hcursor.downField("contact").get[String](field).toOption)
        .orElse(machineGov.flatMap(_.hcursor.downField("contact").get[String](field).toOption))

    val contactFields = Vector(
      contact("primary").map("primary" -> Json.fromString(_)),
      contact("secondary").map("secondary" -> Json.fromString(_)),
    ).flatten

    val description = rc.get[String]("description").toOption.filter(_.nonEmpty)

    // Matches C++'s labelling exactly, including the case where a rule matched
    // but carries no governance override of its own.
    val source =
      if (ruleGov.isDefined) "rule-with-override"
      else if (machineGov.isDefined) "rule-only"
      else "machine-fallback"

    val base = Vector(
      "machineId"            -> Json.fromString(machineId),
      "machineName"          -> Json.fromString(machineName(machineId).getOrElse("")),
      "sequenceId"           -> Json.fromString(sequenceId),
      "ragStatusCode"        -> rc.get[String]("ragStatusCode").toOption.filter(_.nonEmpty).fold(Json.Null)(Json.fromString),
      "processStatus"        -> processStatus.fold(Json.Null)(Json.fromString),
      "ownerTeam"            -> Json.fromString(pick("ownerTeam").getOrElse("unrouted")),
      "slaSeconds"           -> slaSeconds.fold(Json.Null)(Json.fromInt),
      "runbook"              -> pick("runbook").filter(_.nonEmpty).fold(Json.Null)(Json.fromString),
      "escalationPolicy"     -> pick("escalationPolicy").filter(_.nonEmpty).fold(Json.Null)(Json.fromString),
      "contact"              -> Json.fromFields(contactFields),
    )

    // `description` is omitted rather than nulled when the rule carries none —
    // C++ only adds the key when it is non-empty.
    val withDescription = description.fold(base)(d => base :+ ("description" -> Json.fromString(d)))

    Json.fromFields(withDescription ++ Vector(
      "source"               -> Json.fromString(source),
      "hasMachineGovernance" -> Json.fromBoolean(machineGov.isDefined),
    ))
  }
}

object MachineCorpus {

  val empty: MachineCorpus = new MachineCorpus(Map.empty, Map.empty)

  /** Build from the RE's machine list, reading CES initial vectors from the
    * corpus directory when one is reachable. */
  def build(machines: Vector[Json], machinesDir: Option[String] = corpusDir): MachineCorpus =
    new MachineCorpus(
      machinesById = machines.flatMap { m =>
        m.hcursor.get[String]("id").toOption.filter(_.nonEmpty).map(_ -> m)
      }.toMap,
      initialVectorIdsByMachineName = machinesDir.map(readInitialVectorIds).getOrElse(Map.empty),
    )

  private def corpusDir: Option[String] =
    sys.env.get("MACHINES_DIR").filter(_.nonEmpty).map(d => new File(d, "machines").getPath)
      .orElse(Some("../RealityEngine_Machines/machines"))
      .filter(d => new File(d).isDirectory)

  /** machineName → sequenceId → ids of that sequence's `isInitial` vectors. */
  private def readInitialVectorIds(dir: String): Map[String, Map[String, Vector[String]]] = {
    def jsonFiles(f: File): Vector[File] =
      if (f.isDirectory) Option(f.listFiles()).map(_.toVector.flatMap(jsonFiles)).getOrElse(Vector.empty)
      else if (f.getName.endsWith(".json")) Vector(f)
      else Vector.empty

    jsonFiles(new File(dir)).flatMap { file =>
      val parsed = Using(Source.fromFile(file, "UTF-8"))(_.mkString).toOption
        .flatMap(io.circe.parser.parse(_).toOption)
      val machine = parsed.map(_.hcursor.downField("machine")).flatMap(_.focus).getOrElse(Json.Null)
      val name    = machine.hcursor.get[String]("name").toOption.filter(_.nonEmpty)

      name.map { n =>
        val bySequence = machine.hcursor.downField("sequences").as[Vector[Json]].getOrElse(Vector.empty)
          .flatMap { seq =>
            val sc = seq.hcursor
            sc.get[String]("id").toOption.filter(_.nonEmpty).map { sid =>
              val initials = sc.downField("vectors").as[Vector[Json]].getOrElse(Vector.empty)
                .filter(_.hcursor.get[Boolean]("isInitial").getOrElse(false))
                .flatMap(_.hcursor.get[String]("id").toOption)
              sid -> initials
            }
          }.toMap
        n -> bySequence
      }
    }.toMap
  }

  // ── Shared source construction ────────────────────────────────────────────

  /** The one place a corpus machine becomes a PE test source.
    *
    * Startup seeding and `POST /api/sources/bootstrap` both build sources from
    * the same corpus and must produce the same thing.  They used to build it
    * independently and disagreed on both granularity and activation, and
    * because seeding runs first its answer silently won.  Keeping one builder
    * is what stops that from recurring.
    *
    * Canonical shape (C++ is the definition, RealityEngine_CI#91): one source
    * per *machine*, every input sequence concatenated into `inputs`, and the
    * per-sequence boundaries retained in `metadata.segments`.
    */
  def testSourceFor(machine: Json): Option[TestSourceConfig] = {
    val c           = machine.hcursor
    val machineId   = c.get[String]("id").getOrElse("")
    val machineName = c.get[String]("name").getOrElse(machineId)
    val offsetOpt   = c.downField("perceptualMapping").downField("input").get[Int]("offset").toOption
    val lengthOpt   = c.downField("perceptualMapping").downField("input").get[Int]("length").toOption

    val inputSeqs = c.downField("metadata").downField("inputSequences")
      .as[Vector[Json]].getOrElse(Vector.empty)

    val segments = inputSeqs.flatMap { seq =>
      val sc      = seq.hcursor
      val seqName = sc.get[String]("name").getOrElse("Test sequence")
      val vectors = sc.downField("vectors").as[Vector[Vector[Double]]].getOrElse(Vector.empty)
      // An explicit opt-in is honoured if a corpus ever declares one.  None
      // does today: `schemas/machine.schema.json` does not define `active` on
      // an input sequence and no corpus entry sets it, so in practice this
      // resolves to inactive — which is what C++ and LSP both do, and what
      // keeps scenario stimulus out of the shared reality vector until an
      // operator asks for it.
      val optedIn = sc.get[Boolean]("active").getOrElse(false)
      if (vectors.nonEmpty) Some((seqName, vectors, optedIn)) else None
    }

    val inputs = segments.flatMap(_._2)

    (machineId.nonEmpty, offsetOpt, lengthOpt, inputs.nonEmpty) match {
      case (true, Some(offset), Some(length), true) =>
        val label =
          if (segments.length == 1) segments.head._1
          else s"${segments.length} sequences"
        Some(TestSourceConfig(
          id           = s"test-$machineId",
          name         = s"$machineName / $label",
          region       = Region(offset, length),
          active       = segments.exists(_._3),
          machineId    = machineId,
          machineName  = machineName,
          sequenceName = label,
          inputs       = inputs,
          loop         = true,
          sequenceMetadata = Json.obj(
            "segments" -> Json.arr(segments.map { case (segName, vectors, _) =>
              Json.obj("name" -> Json.fromString(segName), "length" -> Json.fromInt(vectors.length))
            }: _*)
          ),
        ))
      case _ => None
    }
  }
}
