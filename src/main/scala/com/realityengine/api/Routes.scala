package com.realityengine.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import com.realityengine.engine._
import com.realityengine.models._
import com.realityengine.services.MachineLoader
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import io.circe.syntax._
import JsonProtocol._

import akka.actor.Cancellable
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import akka.http.scaladsl.model.headers.RawHeader
import com.realityengine.logging.{AuditConfig, AuditLogger}

// Routes — Akka HTTP route definitions mirroring all TypeScript /api/... endpoints.
class Routes(
  engine:      RealityEngine,
  simulator:   PerceptualSpaceSimulator,
  auditCfg:    AuditConfig,
  machinesDir: String = sys.env.getOrElse("MACHINES_DIR", "../RealityEngine_Machines/machines")
)(implicit system: ActorSystem, ec: ExecutionContext) {

  private val perception = new PerceptionOfReality(
    sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680))
  private val sampler = new AtomicReference[Option[RealitySampler]](None)

  private def stableNumber(value: Double): Json =
    if (value.isWhole && value >= Int.MinValue && value <= Int.MaxValue) Json.fromInt(value.toInt)
    else Json.fromDoubleOrNull(value)

  private def stableNumberArray(value: Json): Json =
    Json.arr(value.asArray.getOrElse(Vector.empty).map { item =>
      item.asNumber.map(n => stableNumber(n.toDouble)).getOrElse(item)
    }: _*)

  private def activeVectorJson(vector: RealityVector): Json = {
    val src = vector.toJson.hcursor
    val elements = src.downField("elements").as[Vector[Json]].getOrElse(Vector.empty).map { element =>
      val c = element.hcursor
      val fields = Vector(
        c.downField("comparatorType").as[String].toOption.map(v => "comparatorType" -> Json.fromString(v)),
        c.downField("threshold").as[Double].toOption.map(v => "threshold" -> stableNumber(v)),
        c.downField("value").as[Double].toOption.map(v => "value" -> stableNumber(v))
      ).flatten
      Json.obj(fields: _*)
    }
    val outputs = src.downField("outputVectors").as[Vector[Json]].getOrElse(Vector.empty).map { output =>
      val c = output.hcursor
      Json.obj(
        "id"       -> c.downField("id").focus.getOrElse(Json.fromString("")),
        "metadata" -> c.downField("metadata").focus.getOrElse(Json.obj()),
        "vector"   -> stableNumberArray(c.downField("vector").focus.getOrElse(Json.arr()))
      )
    }
    Json.obj(
      "elements"        -> Json.arr(elements: _*),
      "id"              -> src.downField("id").focus.getOrElse(Json.fromString("")),
      "isActive"        -> src.downField("isActive").focus.getOrElse(Json.fromBoolean(false)),
      "isInitial"       -> src.downField("isInitial").focus.getOrElse(Json.fromBoolean(false)),
      "matchAlgorithm"  -> src.downField("matchAlgorithm").focus.getOrElse(Json.fromString("gte")),
      "metadata"        -> src.downField("metadata").focus.getOrElse(Json.obj()),
      "nextVectorIds"   -> src.downField("nextVectorIds").focus.getOrElse(Json.arr()),
      "outputVectors"   -> Json.arr(outputs: _*),
      "state"           -> src.downField("state").focus.getOrElse(Json.fromString("inactive")),
      "wasJustMatched"  -> src.downField("wasJustMatched").focus.getOrElse(Json.fromBoolean(false))
    )
  }

  // JSON file cache: path -> (lastModified, rawJson).
  // Invalidated automatically when the file's mtime changes.
  private val jsonFileCache = TrieMap.empty[String, (Long, String)]

  // Runtime options — mutable defaults exposed via /api/runtime/options
  private val historyLimitRef           = new AtomicReference[Int](1000)
  private val includeMachineResultsRef  = new AtomicReference[Boolean](true)
  private val includePerceptualSpaceRef = new AtomicReference[Boolean](true)

  private def readJsonFile(file: File): String = {
    val mtime = file.lastModified()
    val key   = file.getAbsolutePath
    jsonFileCache.get(key) match {
      case Some((cachedMtime, json)) if cachedMtime == mtime => json
      case _ =>
        val json = new String(Files.readAllBytes(file.toPath))
        jsonFileCache.update(key, (mtime, json))
        json
    }
  }

  private def collectJsonFiles(dir: File): List[File] = {
    import java.nio.file.{Files => NioFiles}
    import scala.jdk.CollectionConverters._
    NioFiles.walk(dir.toPath)
      .iterator().asScala
      .filter(p => p.toFile.isFile && p.toString.toLowerCase.endsWith(".json"))
      .map(_.toFile)
      .toList
      .sortBy(_.getAbsolutePath)
  }

  private def semanticBusRegistryFile: File = {
    sys.env.get("SEMANTIC_BUS_REGISTRY").filter(_.nonEmpty).map(new File(_)).getOrElse {
      val start = new File(machinesDir).getAbsoluteFile
      val candidates = Iterator.iterate(start)(_.getParentFile).takeWhile(_ != null).take(6).map { dir =>
        new File(new File(dir, "domains"), "semantic-bus-registry.json")
      }
      candidates.find(_.exists()).getOrElse(new File(new File(start.getParentFile, "domains"), "semantic-bus-registry.json"))
    }
  }

  private def semanticBusRegistryJson: Try[Json] = Try {
    val file = semanticBusRegistryFile
    val parsed = io.circe.parser.parse(readJsonFile(file)).fold(throw _, identity)
    if (parsed.hcursor.downField("semanticBuses").focus.exists(_.isArray)) parsed
    else throw new RuntimeException(s"invalid registry shape at ${file.getAbsolutePath}")
  }

  // Staging buffer for chunk-based simulation configure protocol
  private val sequenceBuffer = new AtomicReference[Vector[Vector[Double]]](Vector.empty)
  private val sequenceBufferConfig = new AtomicReference[Option[(RegionMapping, Long, Option[Int])]](None)

  // Auto-play scheduler
  private val autoPlayTask = new AtomicReference[Option[Cancellable]](None)

  // SSE broadcast hub — dropHead means slow subscribers always see the latest step,
  // never accumulate a backlog that would stall the PE.x.RE.x.PE cycle.
  private val (sseQueue, sseBroadcast) = {
    implicit val m: Materializer = Materializer(system)
    Source.queue[SimulationStep](1, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[SimulationStep](bufferSize = 1))(Keep.both)
      .run()
  }

  private def cancelAutoPlay(): Unit = {
    autoPlayTask.getAndSet(None).foreach(_.cancel())
  }

  private def startAutoPlay(delayMs: Long): Unit = {
    cancelAutoPlay()
    val task = system.scheduler.scheduleWithFixedDelay(0.milliseconds, delayMs.milliseconds) { () =>
      simulator.step() match {
        case None       => cancelAutoPlay()
        case Some(step) => sseQueue.offer(step); ()
      }
    }
    autoPlayTask.set(Some(task))
  }

  implicit val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: NoSuchElementException => complete(StatusCodes.NotFound      -> Json.obj("error" -> Json.fromString(e.getMessage)))
    case e: IllegalArgumentException => complete(StatusCodes.BadRequest   -> Json.obj("error" -> Json.fromString(e.getMessage)))
    case e: Exception              => complete(StatusCodes.InternalServerError -> Json.obj("error" -> Json.fromString(e.getMessage)))
  }

  // ── Startup: load machine JSON files ─────────────────────────────────────

  def loadDefaultMachines(): Unit = {
    val dir = new File(machinesDir)
    if (!dir.exists()) { println(s"Machines directory not found: $machinesDir"); return }

    // Auto-discover every .json file in the machines directory so newly-added
    // example machines (DC*, AIWellnessCoach, future additions) get loaded
    // without having to edit this list. Sorted for stable, reproducible
    // startup logs.
    val jsonFiles = collectJsonFiles(dir)

    if (jsonFiles.isEmpty) {
      println(s"No machine JSON files found in $machinesDir")
      return
    }

    var loaded = 0; var failed = 0
    println(s"Loading ${jsonFiles.length} example machines from $machinesDir...")

    jsonFiles.foreach { file =>
      val filename = file.getName
      Try {
        val json     = readJsonFile(file)
        val baseName = filename.replaceAll("\\.json$", "").toLowerCase.replaceAll("[^a-z0-9]+", "-")
        val machine  = MachineLoader.loadFromJson(json, Some(s"machine-$baseName"))
        addMachineToSystem(machine)
        println(s"  ✓ ${machine.name} loaded from $filename")
        loaded += 1
      }.failed.foreach { e =>
        println(s"  ✗ Failed to load $filename: ${e.getMessage}")
        failed += 1
      }
    }
    println(s"\nMachine loading complete: $loaded loaded, $failed failed")
  }

  private def addMachineToSystem(machine: Machine): Unit = {
    engine.addMachine(machine)
    if (machine.perceptualMapping.isDefined) {
      simulator.addMachine(machine)
      println(s"""  ✓ Machine "${machine.name}" registered with perceptual simulator""")
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def runtimeOptionsJson(): Json = Json.obj(
    "historyLimit"           -> Json.fromInt(historyLimitRef.get()),
    "includeMachineResults"  -> Json.fromBoolean(includeMachineResultsRef.get()),
    "includePerceptualSpace" -> Json.fromBoolean(includePerceptualSpaceRef.get()),
    "projectionControls"     -> Json.obj(
      "includeMachineResults"  -> Json.fromString("boolean request field on /api/perceive"),
      "includePerceptualSpace" -> Json.fromString("boolean request field on /api/perceive"),
      "compact"                -> Json.fromString("sets includeMachineResults false when includeMachineResults is omitted")
    )
  )

  private def storageFootprintJson(): Json = {
    val mappedMachines = engine.getAllMachines.filter(_.perceptualMapping.isDefined)
    var totalFloat64Bytes = 0L
    var totalPackedBytes  = 0L
    var hist1 = 0; var hist2 = 0; var hist4 = 0; var hist8 = 0
    val perMachine = mappedMachines.map { m =>
      val mapping   = m.perceptualMapping.get
      val bits      = m.perceptualMapping.map(_.bitsPerElement).getOrElse(8)
      val cells     = mapping.input.length + mapping.output.length
      val f64Bytes  = cells * 8L
      val packBytes = math.ceil(cells.toDouble * bits / 8).toInt
      val shrink    = if (packBytes == 0) 0.0 else f64Bytes.toDouble / packBytes
      totalFloat64Bytes += f64Bytes
      totalPackedBytes  += packBytes
      bits match { case 1 => hist1 += 1; case 2 => hist2 += 1; case 4 => hist4 += 1; case _ => hist8 += 1 }
      Json.obj(
        "machineId"      -> Json.fromString(m.id),
        "machineName"    -> Json.fromString(m.name),
        "bitsPerElement" -> Json.fromInt(bits),
        "inputCells"     -> Json.fromInt(mapping.input.length),
        "outputCells"    -> Json.fromInt(mapping.output.length),
        "totalCells"     -> Json.fromInt(cells),
        "float64Bytes"   -> Json.fromLong(f64Bytes),
        "packedBytes"    -> Json.fromInt(packBytes),
        "shrinkFactor"   -> Json.fromDoubleOrNull(shrink)
      )
    }
    val totalShrink = if (totalPackedBytes == 0) 0.0 else totalFloat64Bytes.toDouble / totalPackedBytes
    Json.obj(
      "perMachine"        -> Json.arr(perMachine: _*),
      "widthHistogram"    -> Json.obj("1" -> Json.fromInt(hist1), "2" -> Json.fromInt(hist2), "4" -> Json.fromInt(hist4), "8" -> Json.fromInt(hist8)),
      "totalCells"        -> Json.fromLong(mappedMachines.map(m => { val mp = m.perceptualMapping.get; (mp.input.length + mp.output.length).toLong }).sum),
      "totalFloat64Bytes" -> Json.fromLong(totalFloat64Bytes),
      "totalPackedBytes"  -> Json.fromLong(totalPackedBytes),
      "totalShrinkFactor" -> Json.fromDoubleOrNull(totalShrink)
    )
  }

  private def resolveGovernance(machine: Machine, sequenceId: String, values: Vector[Double]): Option[Json] = {
    val rulesOpt = for {
      tc    <- machine.metadata.get("triggerConfig")
      rules <- tc.hcursor.downField("rules").as[Vector[Json]].toOption
    } yield rules
    rulesOpt.flatMap { rules =>
      rules.find { rule =>
        val rc      = rule.hcursor
        val rSeqId  = rc.get[String]("sequenceId").getOrElse("")
        val matches = rc.downField("outputMatches").as[Vector[Double]].getOrElse(Vector.empty)
        rSeqId == sequenceId && matches.length == values.length &&
          matches.zip(values).forall { case (a, b) => math.abs(a - b) < 1e-9 }
      }.map { rule =>
        val rc         = rule.hcursor
        val machineGov = machine.metadata.get("governance")
        val ruleGov    = rc.downField("governance").as[Json].toOption
        def rgStr(f: String): Option[String] = ruleGov.flatMap(_.hcursor.get[String](f).toOption)
        def mgStr(f: String): Option[String] = machineGov.flatMap(_.hcursor.get[String](f).toOption)
        val processStatus  = rc.get[String]("processStatus").toOption
        val slaFromRule    = ruleGov.flatMap(_.hcursor.get[Int]("slaSeconds").toOption)
        val slaFromMachine = machineGov.flatMap { mg =>
          processStatus.flatMap(ps => mg.hcursor.downField("sla").get[Int](ps).toOption)
        }
        val ruleContact    = ruleGov.flatMap(_.hcursor.downField("contact").as[Json].toOption)
        val machineContact = machineGov.flatMap(_.hcursor.downField("contact").as[Json].toOption)
        def cStr(j: Option[Json], f: String): Option[String] = j.flatMap(_.hcursor.get[String](f).toOption)
        val contactFields = List(
          cStr(ruleContact, "primary").orElse(cStr(machineContact, "primary")).map("primary" -> Json.fromString(_)),
          cStr(ruleContact, "secondary").orElse(cStr(machineContact, "secondary")).map("secondary" -> Json.fromString(_))
        ).flatten
        val sourceStr = if (ruleGov.isDefined) "rule-with-override"
                        else if (machineGov.isDefined) "rule-only"
                        else "machine-fallback"
        Json.obj(
          "machineId"            -> Json.fromString(machine.id),
          "machineName"          -> Json.fromString(machine.name),
          "sequenceId"           -> Json.fromString(sequenceId),
          "ragStatusCode"        -> rc.get[String]("ragStatusCode").toOption.fold(Json.Null)(Json.fromString),
          "processStatus"        -> processStatus.fold(Json.Null)(Json.fromString),
          "ownerTeam"            -> Json.fromString(rgStr("ownerTeam").orElse(mgStr("ownerTeam")).getOrElse("unrouted")),
          "slaSeconds"           -> slaFromRule.orElse(slaFromMachine).fold(Json.Null)(Json.fromInt),
          "runbook"              -> rgStr("runbook").orElse(mgStr("runbook")).fold(Json.Null)(Json.fromString),
          "escalationPolicy"     -> rgStr("escalationPolicy").orElse(mgStr("escalationPolicy")).fold(Json.Null)(Json.fromString),
          "contact"              -> Json.fromFields(contactFields),
          "source"               -> Json.fromString(sourceStr),
          "hasMachineGovernance" -> Json.fromBoolean(machineGov.isDefined),
          "description"          -> Json.fromString(s"Governance resolved for machine ${machine.name}, sequence $sequenceId")
        )
      }
    }
  }

  // ── Demo machine response ─────────────────────────────────────────────────

  private def demoMachineResponse(machineName: String, fileName: String, displayName: String): Route =
    engine.getAllMachines.find(_.name == machineName) match {
      case None =>
        complete(StatusCodes.NotFound ->
          Json.obj("error" -> s"$displayName machine not found. Please ensure $fileName is loaded.".asJson))
      case Some(machine) =>
        val seqNames = Json.arr(machine.getAllSequences.map(s => Json.fromString(s.name)): _*)
        val inputSeqs = machine.metadata.getOrElse("inputSequences", Json.arr())
        val inputVectorCount = inputSeqs.asArray
          .flatMap(_.headOption)
          .flatMap(_.hcursor.downField("vectors").as[Vector[Json]].toOption)
          .map(_.length)
          .getOrElse(0)
        val extraMeta = Json.obj(
          "name"              -> machine.name.asJson,
          "description"       -> machine.description.asJson,
          "machineId"         -> machine.id.asJson,
          "totalSequences"    -> machine.getSequenceCount.asJson,
          "sequenceNames"     -> seqNames,
          "totalInputVectors" -> inputVectorCount.asJson
        )
        val metaJson = Json.fromFields(machine.metadata.toSeq).deepMerge(extraMeta)
        complete(Json.obj(
          "success"            -> true.asJson,
          "machine"            -> machine.toFullJson,
          "metadata"           -> metaJson,
          "sequencesLoaded"    -> machine.getSequenceCount.asJson,
          "inputVectorsLoaded" -> inputVectorCount.asJson
        ))
    }

  // ── Route tree ────────────────────────────────────────────────────────────

  // ── Prometheus metrics ───────────────────────────────────────────────────
  // Emits text/plain Prometheus exposition format on /api/metrics.  Carries
  // the runtime="scala" label so a single Grafana dashboard can pivot across
  // Scala / CPP / LSP runtimes without scrape-time relabels.
  //
  // Metric names match the corpus expected by the existing Grafana
  // dashboards in config/dashboards/ — ces_* and re_runtime_* prefixed.
  // Gauges are recomputed on every scrape (engine state is the source of
  // truth); counter metrics that require per-step instrumentation are
  // emitted as zero for now so dashboards have a series to plot rather
  // than "No Data".
  private val metricsRuntimeLabel = "scala"

  private def renderPrometheusMetrics(): String = {
    val sb = new StringBuilder
    val runtime = metricsRuntimeLabel

    def escape(s: String): String =
      s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    def emitMeta(name: String, help: String, kind: String): Unit = {
      sb.append(s"# HELP $name $help\n")
      sb.append(s"# TYPE $name $kind\n")
    }

    def emit(name: String, value: Double, labels: Map[String, String] = Map.empty): Unit = {
      val all = Map("runtime" -> runtime) ++ labels
      val ls  = all.toList.sortBy(_._1)
        .map { case (k, v) => s"""$k="${escape(v)}"""" }
        .mkString(",")
      sb.append(s"$name{$ls} $value\n")
    }

    val stats       = engine.getStats.hcursor
    val machines    = engine.getAllMachines
    val totalSeqs   = stats.get[Int]("totalSequences").getOrElse(0)
    val totalVecs   = stats.get[Int]("totalVectors").getOrElse(0)
    val totalActive = stats.get[Int]("totalActiveVectors").getOrElse(0)
    val historySize = engine.getHistory().size
    val simStep     = simulator.getCurrentStep

    // ── Engine totals — match dashboard queries ────────────────────────────
    emitMeta("ces_machines_total",  "Total machines registered with the engine.", "gauge")
    emit   ("ces_machines_total",   machines.size.toDouble)

    emitMeta("ces_sequences_total", "Total CES sequences across all machines.",   "gauge")
    emit   ("ces_sequences_total",  totalSeqs.toDouble)

    emitMeta("ces_vectors_total",   "Total reality vectors across all sequences.","gauge")
    emit   ("ces_vectors_total",    totalVecs.toDouble)

    emitMeta("ces_active_vectors_total", "Currently-active reality vectors.",     "gauge")
    emit   ("ces_active_vectors_total",  totalActive.toDouble)

    emitMeta("ces_history_size", "Length of the transition history.", "gauge")
    emit   ("ces_history_size",  historySize.toDouble)

    emitMeta("ces_simulator_current_step", "Current step of the configured simulation.", "gauge")
    emit   ("ces_simulator_current_step",  simStep.toDouble)

    // ── Per-machine gauges + counter-label baselines ──────────────────────
    // Per-machine series back the `$machine` template variable and let
    // `by (machine)` aggregations on the counter metrics resolve at zero
    // instead of "No Data".  Event-keyed counter series below carry
    // sequence / vector sub-labels — distinct Prom label sets — so they
    // coexist with these baselines without duplication.
    emitMeta("ces_machine_sequence_count", "Number of sequences belonging to a machine.",       "gauge")
    emitMeta("ces_machine_vector_count",   "Total vectors declared by this machine.",           "gauge")
    emitMeta("ces_machine_steps_total",    "process_input calls observed for this machine.",    "counter")
    emitMeta("ces_unfired_sequences",      "Sequences that have not produced an output yet.",   "gauge")
    emitMeta("ces_unfired_vectors",        "Vectors that have not been matched yet.",           "gauge")
    emitMeta("ces_vector_matched_total",   "Vector matches accumulated across all sequences.",  "counter")
    emitMeta("ces_vector_activated_total", "Vector activations as successors in transitions.",  "counter")
    emitMeta("ces_sequence_outputs_total", "Outputs produced by CES sequences.",                "counter")
    emitMeta("ces_paging_decisions_total", "CES paging decisions emitted (by RAG status).",     "counter")
    emitMeta("ces_deprecated_fires_total", "Fires from machines tagged deprecated.",            "counter")

    val stepsSnap     = engine.coverage.stepsSnap
    val matchedSnap   = engine.coverage.matchedSnap
    val activatedSnap = engine.coverage.activatedSnap
    val outputsSnap   = engine.coverage.outputsSnap
    // ces_machine_steps_total shares its baseline's {machine, machine_id}
    // label set, so we suppress the baseline for machines that already
    // have a real step count to avoid duplicate-series warnings.
    val seenSteps = stepsSnap.keysIterator
      .map(k => com.realityengine.services.CesCoverageRegistry.splitKey(k))
      .collect { case Array(id, _) => id }
      .toSet

    machines.foreach { m =>
      val machineLabel = Map("machine" -> m.name, "machine_id" -> m.id)
      val seqs         = m.getAllSequences
      val allVecs      = seqs.flatMap(_.getAllVectors)
      // "Unfired" semantics: a sequence is currently inactive (no live
      // active vectors); a vector is currently inactive.  Engine state
      // at scrape time, which is what the dashboard top-K panels need.
      val unfiredSeqs  = seqs.count(_.getActiveVectors.isEmpty)
      val unfiredVecs  = allVecs.count(!_.isActive)
      emit("ces_machine_sequence_count", seqs.size.toDouble,    machineLabel)
      emit("ces_machine_vector_count",   allVecs.size.toDouble, machineLabel)
      emit("ces_unfired_sequences",      unfiredSeqs.toDouble,  machineLabel)
      emit("ces_unfired_vectors",        unfiredVecs.toDouble,  machineLabel)
      emit("ces_vector_matched_total",   0.0,                   machineLabel)
      emit("ces_vector_activated_total", 0.0,                   machineLabel)
      emit("ces_sequence_outputs_total", 0.0,                   machineLabel)
      emit("ces_deprecated_fires_total", 0.0,                   machineLabel)
      emit("ces_paging_decisions_total", 0.0,
           machineLabel ++ Map("owner_team" -> "unknown", "rag_status_code" -> "GREEN"))
      if (!seenSteps.contains(m.id))
        emit("ces_machine_steps_total",  0.0,                   machineLabel)
    }

    // ── Event-keyed counter series from the live coverage registry ────────
    stepsSnap.foreach { case (k, c) =>
      val parts = com.realityengine.services.CesCoverageRegistry.splitKey(k)
      if (parts.length == 2)
        emit("ces_machine_steps_total", c.toDouble,
             Map("machine_id" -> parts(0), "machine" -> parts(1)))
    }
    matchedSnap.foreach { case (k, c) =>
      val parts = com.realityengine.services.CesCoverageRegistry.splitKey(k)
      if (parts.length == 4)
        emit("ces_vector_matched_total", c.toDouble,
             Map("machine_id" -> parts(0), "machine" -> parts(1),
                 "sequence"   -> parts(2), "vector"  -> parts(3)))
    }
    activatedSnap.foreach { case (k, c) =>
      val parts = com.realityengine.services.CesCoverageRegistry.splitKey(k)
      if (parts.length == 4)
        emit("ces_vector_activated_total", c.toDouble,
             Map("machine_id" -> parts(0), "machine" -> parts(1),
                 "sequence"   -> parts(2), "vector"  -> parts(3)))
    }
    outputsSnap.foreach { case (k, c) =>
      val parts = com.realityengine.services.CesCoverageRegistry.splitKey(k)
      if (parts.length == 3)
        emit("ces_sequence_outputs_total", c.toDouble,
             Map("machine_id" -> parts(0), "machine" -> parts(1), "sequence" -> parts(2)))
    }

    // ── Runtime parity gauges — cross-runtime-parity.json dashboard ────────
    val vectorDim = simulator.getPerceptualSpace.getPerceptualVector.length
    emitMeta("re_runtime_dimension",          "Current perceptual-space dimension.", "gauge")
    emit   ("re_runtime_dimension",           vectorDim.toDouble)
    emitMeta("re_runtime_required_dimension", "Dimension required to fit all machine mappings.", "gauge")
    emit   ("re_runtime_required_dimension",  vectorDim.toDouble)
    emitMeta("re_runtime_mapping_version",    "Mapping schema version (1.0.0 → 100).", "gauge")
    emit   ("re_runtime_mapping_version",     100.0)

    sb.toString
  }

  private val corsHeaders = List(
    RawHeader("Access-Control-Allow-Origin", "*"),
    RawHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS"),
    RawHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization"),
  )

  private val innerRoutes: Route = AuditLogger.directive(auditCfg) { handleExceptions(exceptionHandler) {
    concat(
      pathEndOrSingleSlash { get { complete(Json.obj(
        "name"    -> Json.fromString("Reality Engine"),
        "version" -> Json.fromString("1.0.0"),
        "status"  -> Json.fromString("running")
      )) } },
      pathPrefix("api") {
        concat(
        // Health
        path("health") { get { complete(Json.obj("status" -> Json.fromString("healthy"))) } },

        // Prometheus metrics — text/plain exposition format on /api/metrics
        path("metrics") { get {
          complete(HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, renderPrometheusMetrics()),
          ))
        } },

        // Config
        pathPrefix("config") {
          concat(
            pathEnd { get { complete(Json.obj(
              "vectorDimension"   -> Json.fromInt(sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)),
              "matchThreshold"    -> Json.fromDouble(0.5).get,
              "qdrantUrl"         -> Json.fromString(sys.env.getOrElse("QDRANT_URL", "http://localhost:4333")),
              "collectionName"    -> Json.fromString(sys.env.getOrElse("COLLECTION_NAME", "reality-vectors"))
            )) } },
            path("dimension") { put { parameter("dimension".as[Int].?(sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680))) { dim =>
              complete(Json.obj("success" -> Json.fromBoolean(true), "dimension" -> Json.fromInt(dim)))
            } } },
            path("threshold") { put { parameter("threshold".as[Double].?(0.5)) { t =>
              complete(Json.obj("success" -> Json.fromBoolean(true), "threshold" -> Json.fromDoubleOrNull(t)))
            } } }
          )
        },

        // Vectors
        pathPrefix("vectors") {
          concat(
            path("search") { post { entity(as[Json]) { body =>
              val qv    = body.hcursor.downField("vector").as[Vector[Double]].getOrElse(Vector.empty)
              val limit = body.hcursor.get[Int]("limit").getOrElse(10)
              val thr   = body.hcursor.get[Double]("threshold").toOption
              onComplete(engine.searchVectors(qv, limit, thr)) {
                case Success(results) => complete(Json.obj("results" ->
                  Json.arr(results.map { case (v, s) => Json.obj("vector" -> v.toJson, "score" -> Json.fromDoubleOrNull(s)) }: _*)))
                case Failure(e) => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } } },
            pathEnd { post { entity(as[Json]) { body =>
              val elementsJ = body.hcursor.downField("elements").as[Vector[Json]].getOrElse(Vector.empty)
              val isInitial = body.hcursor.get[Boolean]("isInitial").getOrElse(false)
              val elems = elementsJ.map { ej =>
                val ec = ej.hcursor
                VectorElement(
                  value          = ec.get[Double]("value").getOrElse(0.0),
                  comparatorType = ec.get[String]("comparatorType").toOption.map(ComparatorType.fromString),
                  threshold      = ec.get[Double]("threshold").toOption
                )
              }
              val vector = new RealityVector(elems, isInitial)
              complete(Json.obj("success" -> Json.fromBoolean(true), "vector" -> vector.toJson))
            } } },
            path(Segment) { id =>
              concat(
                get  { complete(Json.obj("message" -> Json.fromString("Vector retrieval endpoint"), "id" -> Json.fromString(id))) },
                delete { onComplete(engine.vectorStore.deleteVector(id)) {
                  case Success(_) => complete(Json.obj("success" -> Json.fromBoolean(true), "id" -> Json.fromString(id)))
                  case Failure(e) => complete(StatusCodes.InternalServerError -> Json.obj("error" -> Json.fromString(e.getMessage)))
                } }
              )
            }
          )
        },

        // Sequences
        pathPrefix("sequences") {
          concat(
            path("persist") { post { onComplete(engine.persistAllSequences()) {
              case Success(_) => complete(Json.obj("success" -> Json.fromBoolean(true)))
              case Failure(e) => complete(StatusCodes.InternalServerError -> Json.obj("error" -> Json.fromString(e.getMessage)))
            } } },
            pathEnd {
              concat(
                get  { complete(Json.obj("sequences" -> Json.arr(engine.getAllSequences.map(_.toJson): _*))) },
                post { entity(as[Json]) { body =>
                  val name  = body.hcursor.get[String]("name").getOrElse("unnamed")
                  val seq   = new CriticalEventSequence(name)
                  body.hcursor.downField("vectors").as[Vector[Json]].getOrElse(Vector.empty).foreach { vj =>
                    val vc = vj.hcursor
                    val elems = vc.downField("elements").as[Vector[Json]].getOrElse(Vector.empty).map { ej =>
                      VectorElement(
                        value          = ej.hcursor.get[Double]("value").getOrElse(0.0),
                        comparatorType = ej.hcursor.get[String]("comparatorType").toOption.map(ComparatorType.fromString),
                        threshold      = ej.hcursor.get[Double]("threshold").toOption
                      )
                    }
                    val vec = new RealityVector(elems, vc.get[Boolean]("isInitial").getOrElse(false),
                      vc.get[String]("id").getOrElse(java.util.UUID.randomUUID().toString))
                    vc.downField("nextVectorIds").as[Vector[String]].getOrElse(Vector.empty).foreach(vec.addNextVector)
                    vc.downField("outputVectors").as[Vector[Json]].getOrElse(Vector.empty).foreach { oj =>
                      vec.addOutputVector(OutputVector(
                        id        = oj.hcursor.get[String]("id").getOrElse(java.util.UUID.randomUUID().toString),
                        vector    = oj.hcursor.downField("vector").as[Vector[Double]].getOrElse(Vector.empty),
                        metadata  = oj.hcursor.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty),
                        timestamp = System.currentTimeMillis()
                      ))
                    }
                    seq.addVector(vec)
                  }
                  val (valid, errors) = seq.validate()
                  if (!valid) complete(StatusCodes.BadRequest -> Json.obj("error" -> errors.asJson))
                  else { engine.addSequence(seq); complete(Json.obj("success" -> Json.fromBoolean(true), "sequence" -> seq.toJson)) }
                } }
              )
            },
            path(Segment) { id =>
              concat(
                get    { engine.getSequence(id).fold(complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Sequence not found"))))(s => complete(Json.obj("sequence" -> s.toJson))) },
                delete { if (engine.getSequence(id).isDefined) { engine.removeSequence(id); complete(Json.obj("success" -> Json.fromBoolean(true))) }
                         else complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Sequence not found"))) }
              )
            },
            path(Segment / "reset") { id =>
              post { if (engine.resetSequence(id)) complete(Json.obj("success" -> Json.fromBoolean(true)))
                     else complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Sequence not found"))) }
            },
            path(Segment / "vectors") { id =>
              post { entity(as[Json]) { body =>
                engine.getSequence(id) match {
                  case None => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Sequence not found")))
                  case Some(seq) =>
                    val elems = body.hcursor.downField("elements").as[Vector[Json]].getOrElse(Vector.empty).map { ej =>
                      VectorElement(
                        value          = ej.hcursor.get[Double]("value").getOrElse(0.0),
                        comparatorType = ej.hcursor.get[String]("comparatorType").toOption.map(ComparatorType.fromString),
                        threshold      = ej.hcursor.get[Double]("threshold").toOption
                      )
                    }
                    val vec = new RealityVector(elems, body.hcursor.get[Boolean]("isInitial").getOrElse(false))
                    seq.addVector(vec)
                    complete(Json.obj("success" -> Json.fromBoolean(true), "vector" -> vec.toJson))
                }
              } }
            }
          )
        },

        // Engine
        pathPrefix("engine") {
          concat(
            path("process") { post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("vector").as[Vector[Double]].getOrElse(Vector.empty)
              val result = engine.processInputLegacy(vec)
              complete(Json.obj("result" -> result.asJson))
            } } },
            path("reset") { post { engine.resetAllSequences(); complete(Json.obj("success" -> Json.fromBoolean(true))) } },
            path("stats") { get { complete(Json.obj("stats" -> engine.getStats)) } },
            path("active") { get {
              val rows = engine.getAllMachines.sortBy(_.id).flatMap { machine =>
                machine.getAllSequences.sortBy(_.id).flatMap { sequence =>
                  sequence.getActiveVectors.sortBy(_.id).map { vector =>
                    Json.obj(
                      "machineId"  -> Json.fromString(machine.id),
                      "sequenceId" -> Json.fromString(sequence.id),
                      "vector"     -> activeVectorJson(vector)
                    )
                  }
                }
              }
              complete(Json.obj("activeVectors" -> Json.arr(rows: _*)))
            } },
            path("history") { get { parameter("limit".as[Int].?) { limit =>
              complete(Json.obj("history" -> engine.getHistory(limit).asJson))
            } } }
          )
        },

        // Perception
        path("perception" / "observe") { post { entity(as[Json]) { body =>
          val data     = body.hcursor.downField("data").as[Vector[Double]].getOrElse(Vector.empty)
          val source   = body.hcursor.get[String]("source").toOption
          val meta     = body.hcursor.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
          val obs      = PerceptionOfReality.createObservation(data, source, meta)
          val perceived = perception.perceive(obs)
          complete(Json.obj(
            "success"            -> Json.fromBoolean(true),
            "inputVector"        -> perceived.inputVector.asJson,
            "transformations"    -> perceived.transformations.asJson,
            "processingTimestamp" -> Json.fromLong(perceived.processingTimestamp)
          ))
        } } },

        // Sampler
        pathPrefix("sampler") {
          concat(
            path("start") { post { entity(as[Json]) { body =>
              val strat = body.hcursor.get[String]("strategy").toOption.map {
                case "periodic"     => SamplingStrategy.PERIODIC
                case "continuous"   => SamplingStrategy.CONTINUOUS
                case "event-driven" => SamplingStrategy.EVENT_DRIVEN
                case _              => SamplingStrategy.MANUAL
              }.getOrElse(SamplingStrategy.MANUAL)
              val intervalMs = body.hcursor.get[Long]("intervalMs").toOption
              if (sampler.get().isEmpty) sampler.compareAndSet(None, Some(new RealitySampler(perception, engine,
                SamplingConfig(strat, intervalMs))))
              sampler.get().get.start()
              complete(Json.obj("success" -> Json.fromBoolean(true), "stats" -> sampler.get().get.getStats))
            } } },
            path("stop") { post {
              sampler.get().foreach(_.stop())
              complete(Json.obj("success" -> Json.fromBoolean(true)))
            } },
            path("sample") { post { entity(as[Json]) { body =>
              val data   = body.hcursor.downField("data").as[Vector[Double]].getOrElse(Vector.empty)
              val source = body.hcursor.get[String]("source").toOption
              val meta   = body.hcursor.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
              val obs    = PerceptionOfReality.createObservation(data, source, meta)
              if (sampler.get().isEmpty) sampler.compareAndSet(None, Some(new RealitySampler(perception, engine, SamplingConfig(SamplingStrategy.MANUAL))))
              val result = sampler.get().get.sample(obs)
              complete(Json.obj("success" -> Json.fromBoolean(true), "result" -> result.asJson))
            } } },
            path("stats") { get {
              val statsJson = sampler.get().map(_.getStats).getOrElse(Json.obj("isRunning" -> Json.fromBoolean(false), "sampleCount" -> Json.fromInt(0), "bufferSize" -> Json.fromInt(0), "strategy" -> Json.fromString("MANUAL")))
              complete(Json.obj("stats" -> statsJson))
            } }
          )
        },

        pathPrefix("buses" / "semantic") {
          concat(
            pathEnd { get {
              semanticBusRegistryJson match {
                case Success(registry) => complete(registry)
                case Failure(e)        => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(s"semantic bus registry unavailable: ${e.getMessage}")))
              }
            } },
            path(Segment) { id => get {
              semanticBusRegistryJson match {
                case Failure(e) =>
                  complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(s"semantic bus registry unavailable: ${e.getMessage}")))
                case Success(registry) =>
                  val buses = registry.hcursor.downField("semanticBuses").as[Vector[Json]].getOrElse(Vector.empty)
                  buses.find(_.hcursor.get[String]("id").contains(id)) match {
                    case Some(bus) => complete(Json.obj("bus" -> bus))
                    case None      => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Semantic bus not found")))
                  }
              }
            } }
          )
        },

        // Machines — fixed paths BEFORE parameterized
        pathPrefix("machines") {
          concat(
            // Fixed: /machines/json/...
            path("json" / "list") { get {
              val dir = new File(machinesDir)
              val dirPath = dir.getAbsoluteFile.toPath
              val files = if (dir.exists()) collectJsonFiles(dir) else Nil
              val machineList = files.flatMap { file =>
                Try {
                  val json = readJsonFile(file)
                  val root = io.circe.parser.parse(json).getOrElse(io.circe.Json.obj())
                  val m    = root.hcursor.downField("machine")
                  Json.obj(
                    "filename"      -> Json.fromString(file.getName),
                    "relFile"       -> Json.fromString(dirPath.relativize(file.getAbsoluteFile.toPath).toString.replace(File.separatorChar, '/')),
                    "name"          -> Json.fromString(m.get[String]("name").getOrElse(file.getName)),
                    "description"   -> Json.fromString(m.get[String]("description").getOrElse("")),
                    "version"       -> Json.fromString(root.hcursor.get[String]("version").getOrElse("1.0.0")),
                    "metadata"      -> m.downField("metadata").as[Json].getOrElse(Json.obj()),
                    "sequenceCount" -> Json.fromInt(m.downField("sequences").as[Vector[Json]].map(_.length).getOrElse(0))
                  )
                }.toOption
              }
              complete(Json.obj("machines" -> Json.arr(machineList: _*)))
            } },
            path("json" / Segment) { name =>
              get {
                val filename = if (name.endsWith(".json")) name else s"$name.json"
                val flat = new File(machinesDir, filename)
                // Corpus files may live in domain subdirectories — fall back to
                // a recursive basename search (corpus filenames are unique).
                val file =
                  if (flat.exists() || name.contains("..")) flat
                  else collectJsonFiles(new File(machinesDir)).find(_.getName == filename).getOrElse(flat)
                if (!file.exists()) complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(s"Machine file not found: $name")))
                else Try {
                  val json    = readJsonFile(file)
                  val baseName = name.replaceAll("\\.json$", "").toLowerCase.replaceAll("[^a-z0-9]+", "-")
                  val machine = MachineLoader.loadFromJson(json, Some(s"machine-$baseName"))
                  addMachineToSystem(machine)
                  machine
                } match {
                  case Failure(e)       => complete(StatusCodes.InternalServerError -> Json.obj("error" -> Json.fromString(e.getMessage)))
                  case Success(machine) => complete(Json.obj("success" -> Json.fromBoolean(true), "machine" -> machine.toJson, "message" -> Json.fromString(s"Machine ${machine.name} loaded successfully")))
                }
              }
            },
            path("json" / "import") { post { entity(as[String]) { body =>
              Try(MachineLoader.loadFromJson(body)) match {
                case Failure(e)       => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
                case Success(machine) => addMachineToSystem(machine); complete(Json.obj("success" -> Json.fromBoolean(true), "machine" -> machine.toJson))
              }
            } } },
            // Fixed: /machines/process-universal/all
            path("process-universal" / "all") { post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("universalInputSpace").as[Vector[Double]].getOrElse(Vector.empty)
              complete(engine.processUniversalInputForAllMachines(vec).map { results =>
                Json.obj("results" -> Json.fromFields(results.view.mapValues(_.asJson).toSeq))
              })
            } } },
            // Fixed: /machines/machine-graph
            pathEnd {
              concat(
                get  { complete(Json.obj("machines" -> Json.arr(engine.getAllMachines.map(_.toJson): _*))) },
                post { entity(as[Json]) { body =>
                  Try(MachineLoader.loadFromJson(body.noSpaces)) match {
                    case Failure(e) => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
                    case Success(machine) =>
                      addMachineToSystem(machine)
                      complete(Json.obj("success" -> Json.fromBoolean(true), "machine" -> machine.toJson))
                  }
                } }
              )
            },
            path(Segment) { id =>
              concat(
                get    { engine.getMachine(id).fold(complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Machine not found"))))(m => complete(Json.obj("machine" -> m.toJson))) },
                patch  { entity(as[Json]) { body =>
                  engine.getMachine(id).fold(
                    complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Machine not found")))
                  ) { existing =>
                    val c           = body.hcursor
                    val newName     = c.get[String]("name").getOrElse(existing.name)
                    val newDesc     = c.get[String]("description").getOrElse(existing.description)
                    val metaPatch   = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
                    val newMetadata = existing.metadata ++ metaPatch
                    val updated     = new Machine(newName, newDesc, newMetadata, existing.getArbiter.getRule, existing.perceptualMapping, id)
                    updated.matchAlgorithm = existing.matchAlgorithm
                    existing.getAllSequences.foreach(updated.addSequence)
                    engine.removeMachine(id); simulator.removeMachine(id)
                    addMachineToSystem(updated)
                    complete(Json.obj("success" -> Json.fromBoolean(true), "machine" -> updated.toJson))
                  }
                } },
                put    { entity(as[Json]) { body =>
                  Try(MachineLoader.loadFromJson(body.noSpaces, Some(id))) match {
                    case Failure(e) => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
                    case Success(machine) =>
                      engine.removeMachine(id); simulator.removeMachine(id)
                      addMachineToSystem(machine)
                      complete(Json.obj("success" -> Json.fromBoolean(true), "machine" -> machine.toJson))
                  }
                } },
                delete { engine.removeMachine(id); simulator.removeMachine(id); complete(Json.obj("success" -> Json.fromBoolean(true))) }
              )
            },
            path(Segment / "process") { id => post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("inputVector").as[Vector[Double]].getOrElse(Vector.empty)
              onComplete(engine.processMachineInput(id, vec)) {
                case Success(r) => complete(r.asJson)
                case Failure(e: NoSuchElementException)  => complete(StatusCodes.NotFound  -> Json.obj("error" -> Json.fromString(e.getMessage)))
                case Failure(e)                          => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } } },
            path(Segment / "process-universal") { id => post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("universalInputSpace").as[Vector[Double]].getOrElse(Vector.empty)
              onComplete(engine.processUniversalInput(vec, id)) {
                case Success(r) => complete(r.asJson)
                case Failure(e: NoSuchElementException)  => complete(StatusCodes.NotFound  -> Json.obj("error" -> Json.fromString(e.getMessage)))
                case Failure(e)                          => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } } },
            path(Segment / "whatif") { id => post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("inputVector").as[Vector[Double]].getOrElse(Vector.empty)
              Try(engine.processWhatIf(id, vec)) match {
                case Success(r) => complete(r.asJson)
                case Failure(e) => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } } },
            path(Segment / "whatif-universal") { id => post { entity(as[Json]) { body =>
              val vec = body.hcursor.downField("universalInputSpace").as[Vector[Double]].getOrElse(Vector.empty)
              Try(engine.processUniversalWhatIf(vec, id)) match {
                case Success(r) => complete(r.asJson)
                case Failure(e) => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } } },
            path(Segment / "checkpoints") { id =>
              concat(
                get  { complete(Json.arr(engine.listCheckpoints(id).map(_.asJson): _*)) },
                post { entity(as[Json]) { body =>
                  val label = body.hcursor.get[String]("label").toOption
                  Try(engine.createCheckpoint(id, label)) match {
                    case Success(cpId) => complete(Json.obj("success" -> Json.fromBoolean(true), "checkpointId" -> Json.fromString(cpId)))
                    case Failure(e)    => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(e.getMessage)))
                  }
                } }
              )
            },
            path(Segment / "checkpoints" / Segment / "restore") { (machineId, cpId) =>
              post { Try(engine.restoreCheckpoint(machineId, cpId)) match {
                case Success(_) => complete(Json.obj("success" -> Json.fromBoolean(true)))
                case Failure(e) => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(e.getMessage)))
              } }
            },
            path(Segment / "checkpoints" / Segment) { (machineId, cpId) =>
              delete { complete(Json.obj("success" -> Json.fromBoolean(engine.deleteCheckpoint(machineId, cpId)))) }
            },
            path(Segment / "export") { id =>
              get { engine.getMachine(id).fold(complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString("Machine not found")))) { m =>
                complete(HttpEntity(ContentTypes.`application/json`, MachineLoader.saveToJson(m)))
              } }
            }
          )
        },

        // Machine graph
        path("machine-graph") { get { complete(simulator.getMachineGraphData) } },

        // Perceptual simulation
        pathPrefix("perceptual-simulation") {
          concat(
            path("configure" / "chunk") { post { entity(as[Json]) { body =>
              val chunk = body.hcursor.downField("vectors").as[Vector[Vector[Double]]].getOrElse(Vector.empty)
              if (body.hcursor.downField("reset").as[Boolean].getOrElse(false)) sequenceBuffer.set(Vector.empty)
              val newLen = sequenceBuffer.updateAndGet(_ ++ chunk).length
              // Accept config from nested "config" field OR top-level fields (backwards compat)
              val cfgSrc = body.hcursor.downField("config").as[Json].toOption.getOrElse(body)
              val c = cfgSrc.hcursor
              val hasRegion = c.downField("inputRegion").as[Json].isRight
              if (hasRegion || body.hcursor.downField("inputRegion").as[Json].isRight) {
                val src = if (hasRegion) c else body.hcursor
                val iOff = src.downField("inputRegion").get[Int]("offset").getOrElse(0)
                val iLen = src.downField("inputRegion").get[Int]("length").getOrElse(1)
                val delay = src.get[Long]("stepDelayMs").getOrElse(100L)
                val maxS  = src.get[Int]("maxSteps").toOption
                sequenceBufferConfig.set(Some((RegionMapping(iOff, iLen), delay, maxS)))
              }
              complete(Json.obj("success" -> Json.fromBoolean(true), "bufferedVectors" -> Json.fromInt(newLen)))
            } } },
            path("configure" / "commit") { post {
              sequenceBufferConfig.get() match {
                case None => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString("No config buffered. Send a chunk with config first.")))
                case Some((region, delay, maxS)) =>
                  val cfg = SimulationConfig(sequenceBuffer.get(), region, delay, maxS)
                  simulator.configure(cfg)
                  sequenceBuffer.set(Vector.empty); sequenceBufferConfig.set(None)
                  complete(Json.obj("success" -> Json.fromBoolean(true)))
              }
            } },
            path("start")   { post {
              try {
                simulator.start()
                val delayMs = simulator.getStepDelayMs
                startAutoPlay(delayMs)
                complete(Json.obj("success" -> Json.fromBoolean(true)))
              } catch { case e: Exception =>
                complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString(e.getMessage)))
              }
            } },
            path("stop")    { post { cancelAutoPlay(); simulator.stop(); complete(Json.obj("success" -> Json.fromBoolean(true))) } },
            path("step")    { post { simulator.step() match {
              case None    => complete(Json.obj("done" -> Json.fromBoolean(true), "success" -> Json.fromBoolean(true)))
              case Some(s) => complete(Json.obj("success" -> Json.fromBoolean(true), "step" -> s.asJson))
            } } },
            path("reset")   { post { simulator.reset(); complete(Json.obj("success" -> Json.fromBoolean(true))) } },
            path("state")   { get {
              val ps = simulator.getPerceptualSpace.getPerceptualVector
              val stateObj = Json.obj(
                "perceptualSpace" -> Json.arr(ps.map(Json.fromDoubleOrNull): _*),
                "currentStep"     -> Json.fromInt(simulator.getCurrentStep),
                "isRunning"       -> Json.fromBoolean(simulator.getIsRunning),
                "machines"        -> simulator.toJson.hcursor.downField("machines").as[Json].getOrElse(Json.arr())
              )
              complete(Json.obj("success" -> Json.fromBoolean(true), "state" -> stateObj))
            } },
            path("history") { get { complete(Json.obj("history" -> simulator.getHistory.asJson)) } }
          )
        },

        // Perception diagnostic
        path("perception" / "diagnostic") { post { entity(as[Json]) { body =>
          val vec = body.hcursor.downField("universalInputSpace").as[Vector[Double]].getOrElse(Vector.empty)
          complete(engine.getDiagnosticMapping(vec))
        } } },

        // Perceive (Perception Engine push endpoint)
        path("perceive") { post { entity(as[Json]) { body =>
          val vec      = body.hcursor.downField("vector").as[Vector[Double]].getOrElse(Vector.empty)
          val matchOvr = body.hcursor.get[String]("matchAlgorithmOverride").toOption.map(ComparatorType.fromString)
          val step     = simulator.processImmediate(vec, matchOvr)
          // Sync PerceptionEngine's space with post-merge state
          engine.perceptionEngine.getPerceptualSpace.setPerceptualVector(step.perceptualSpace)
          // Non-blocking push to the SSE broadcast hub so observers never stall the caller
          sseQueue.offer(step)
          complete(step.asJson)
        } } },

        // Runtime introspection — parity with /api/runtime/* on LSP and CPP runtimes
        pathPrefix("runtime") { concat(
          path("metrics") { get { complete(Json.obj(
            "stats"            -> engine.getStats,
            "domainWorkerPool" -> Json.obj("semantics" -> Json.fromString("akka-futures"))
          )) } },
          path("vector-space") { get {
            val dim    = simulator.getPerceptualSpace.getPerceptualVector.length
            val reqDim = engine.getAllMachines.flatMap(_.perceptualMapping).map(m => m.input.offset + m.input.length).foldLeft(dim)(math.max)
            complete(Json.obj(
              "dimension"                 -> Json.fromInt(dim),
              "requiredDimension"         -> Json.fromInt(reqDim),
              "encoding"                  -> Json.fromString("dense-float64-clamped-0-1"),
              "mappingVersion"            -> Json.fromInt(100),
              "eventBusSubscriptionCount" -> Json.fromInt(simulator.eventBusSubscriptionCount)
            ))
          } },
          path("storage-footprint") { get { complete(storageFootprintJson()) } },
          path("options") { concat(
            get  { complete(runtimeOptionsJson()) },
            patch { entity(as[Json]) { body =>
              val c = body.hcursor
              c.get[Int]("historyLimit").toOption.foreach(historyLimitRef.set)
              c.get[Boolean]("includeMachineResults").toOption.foreach(includeMachineResultsRef.set)
              c.get[Boolean]("includePerceptualSpace").toOption.foreach(includePerceptualSpaceRef.set)
              complete(runtimeOptionsJson())
            } }
          ) }
        ) },

        // Governance / paging decision resolver — single source of truth for on-call routing
        path("governance" / "route") { get {
          parameter("machineId".?) { mid =>
          parameter("sequenceId".?) { sid =>
          parameter("values".?) { vals =>
            (mid, sid, vals) match {
              case (Some(machineId), Some(sequenceId), Some(valuesStr)) =>
                engine.getMachine(machineId) match {
                  case None => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(s"Machine not found: $machineId")))
                  case Some(machine) =>
                    val values = valuesStr.split(",").flatMap(s => Try(s.trim.toDouble).toOption).toVector
                    resolveGovernance(machine, sequenceId, values) match {
                      case None    => complete(StatusCodes.NotFound -> Json.obj("error" -> Json.fromString(s"No triggerConfig rule matches (sequenceId=$sequenceId, values=$valuesStr)")))
                      case Some(d) => complete(Json.obj("success" -> Json.fromBoolean(true), "decision" -> d))
                    }
                }
              case _ => complete(StatusCodes.BadRequest -> Json.obj("error" -> Json.fromString("machineId, sequenceId, and values query parameters are required")))
            }
          }}}
        } },

        // ── Demos ────────────────────────────────────────────────────────────────
        pathPrefix("demo") { concat(
          path("multi-step")  { get { demoMachineResponse("Multi-Step State Machine", "MultiStep.json",           "Multi-Step State Machine") } },
          path("data-center") { get { demoMachineResponse("Data Center Monitoring",   "DataCenterMonitoring.json", "Data Center Monitoring") } },
          path("kleene-star") { get { demoMachineResponse("Kleene Star Operator",     "KleeneStar.json",           "Kleene Star Operator") } },
        ) },

        // SSE step-stream — Visualizer Backend subscribes here as a passive observer.
        // Each subscriber gets a live-only feed; the dropHead queue ensures the VB
        // always sees the latest step and never blocks the PE.x.RE.x.PE cycle.
        // keepAlive injects a heartbeat comment every 15 s so the connection
        // survives Akka HTTP's default 60 s idle-timeout when no steps flow.
        path("engine" / "stream") { get {
          complete(sseBroadcast
            .map(step => ServerSentEvent(step.asJson.noSpaces))
            .keepAlive(15.seconds, () => ServerSentEvent.heartbeat))
        } },

          // Root /api
          pathEndOrSingleSlash { get { complete(Json.obj(
            "name"    -> Json.fromString("Reality Engine"),
            "version" -> Json.fromString("1.0.0"),
            "status"  -> Json.fromString("running")
          )) } }
        )
      }
    )
  } }

  val routes: Route = respondWithHeaders(corsHeaders) {
    options { complete(StatusCodes.NoContent) } ~ innerRoutes
  }
}
