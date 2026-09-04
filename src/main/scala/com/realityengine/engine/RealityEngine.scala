package com.realityengine.engine

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.realityengine.actors.MachineActor
import com.realityengine.models._
import com.realityengine.services.VectorStore
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import io.circe.Json

case class MachineCheckpoint(
  id:          String,
  machineId:   String,
  machineName: String,
  label:       Option[String],
  timestamp:   Long,
  snapshot:    Machine
)

/**
 * RealityEngine — core processing engine for Reality Events.
 *
 * Responsibilities:
 *  - Manage Machines and CriticalEventSequences
 *  - Route inputs through the 3-phase Reality Engine workflow
 *  - Coordinate PerceptionEngine (universal input resolution)
 *  - Checkpoint / what-if analytic workflows
 *  - Interface with VectorStore for persistence
 *
 * RC-1: each Machine is owned by a MachineActor whose FIFO mailbox serialises
 *       processInput calls per machine; cross-machine processing is parallel.
 * RC-2: all top-level registries use TrieMap (lock-free reads, atomic CAS
 *       writes) instead of unsynchronised var reassignment. The legacy history
 *       deque uses ConcurrentLinkedDeque + AtomicInteger size counter.
 */
class RealityEngine(
  val vectorStore:        VectorStore,
  val maxHistorySize:     Int     = 1000,
  val universalDimension: Int     = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680),
  val verboseLogging:     Boolean = false
)(implicit system: ActorSystem, ec: ExecutionContext) {

  import RealityEngine.askTimeout

  // ── Thread-safe registries (RC-2) ─────────────────────────────────────────
  // TrieMap: lock-free reads via a RDCSS snapshot; writes use CAS.
  // Machine registration is rare; reads during processing dominate → ideal fit.

  // Sequences created directly over the API (`POST /api/sequences`), and only
  // those. Machines are **not** mirrored in here.
  //
  // They used to be: `addMachine` pushed every machine's sequences into this
  // map, making it a shadow copy of machine state keyed by sequence id. Ids are
  // unique within a machine but deliberately reused across machines — the
  // corpus contract says so, and `rs-set-sequence` appears in three flip-flops
  // — so `put(seq.id, seq)` silently collapsed 30 entries into 10 and the
  // engine disagreed with its own machine list by 20 sequences and 28 events
  // (RealityEngine_Scala#92).
  //
  // Machine state has exactly one home: `machines`. Anything that wants to
  // count, walk or reset a machine's sequences reads it from there.
  private val apiSequences: TrieMap[String, CriticalEventSequence]                 = TrieMap.empty
  private val machines:     TrieMap[String, Machine]                                = TrieMap.empty
  private val machineActors: TrieMap[String, ActorRef]                              = TrieMap.empty
  private val checkpoints:  TrieMap[String, TrieMap[String, MachineCheckpoint]]    = TrieMap.empty
  // T-6: per-machine (inputHash, lastResult) — skips actor ask for quiet machines on identical input
  private val inputCache:   TrieMap[String, (Int, MachineTransitionResult)]        = TrieMap.empty

  // ConcurrentLinkedDeque: thread-safe prepend/pollLast; AtomicInteger tracks
  // approximate size so we can cap without taking a global lock.
  private val transitionHistory = new ConcurrentLinkedDeque[TransitionResult]()
  private val historySize       = new AtomicInteger(0)

  val perceptionEngine = new PerceptionEngine(universalDimension)

  // CES coverage counters bump on every non-speculative transition.  Read
  // from Routes.scala /api/metrics; what-if paths use machine.clone() and
  // intentionally bypass this registry, matching CPP/LSP semantics.
  val coverage = new com.realityengine.services.CesCoverageRegistry

  // ── Initialization ────────────────────────────────────────────────────────

  def initialize()(implicit ec: ExecutionContext): Future[Unit] =
    vectorStore.initialize().map { _ =>
      println("RealityEngine initialized")
    }

  // ── Sequence management ───────────────────────────────────────────────────

  def addSequence(seq: CriticalEventSequence): Unit = {
    val (valid, errors) = seq.validate()
    require(valid, s"Invalid sequence: ${errors.mkString(", ")}")
    apiSequences.put(seq.id, seq)
    println(s"Added sequence: ${seq.name} (${seq.id})")
  }

  def removeSequence(sequenceId: String): Unit = apiSequences.remove(sequenceId)
  def getSequence(id: String): Option[CriticalEventSequence] = apiSequences.get(id)
  def getAllSequences: List[CriticalEventSequence] = apiSequences.values.toList

  // ── Machine management ────────────────────────────────────────────────────

  def addMachine(machine: Machine): Unit = {
    val actor = system.actorOf(MachineActor.props(machine))
    machines.put(machine.id, machine)
    machineActors.put(machine.id, actor)
    println(s"Added machine: ${machine.name} (${machine.id}) with ${machine.getSequenceCount} sequences")
  }

  def removeMachine(machineId: String): Boolean =
    machines.remove(machineId) match {
      case None => false
      case Some(machine) =>
        machineActors.remove(machineId).foreach(system.stop)
        inputCache.remove(machineId)
        true
    }

  /** Every machine's sequences, paired with the id each machine knows it by.
    *
    * A `List`, not a `Map`: sequence ids are unique within a machine and
    * deliberately reused across machines, so keying by id here would collapse
    * them again — which is the whole of #92. Canonical machine order, then each
    * machine's own `getAllSequences` order, so the walk is deterministic.
    */
  private def machineSequences: List[(String, CriticalEventSequence)] =
    getAllMachines.flatMap(m => m.getAllSequences.map(s => s.id -> s))

  def getMachine(id: String): Option[Machine]  = machines.get(id)
  /** Canonical order — see Machine.canonicalOrder. */
  def getAllMachines: List[Machine]             = Machine.inCanonicalOrder(machines.values)

  // ── Processing ────────────────────────────────────────────────────────────

  def processMachineInput(machineId: String, inputVector: Vector[Double]): Future[MachineTransitionResult] =
    machines.get(machineId) match {
      case None => Future.failed(new NoSuchElementException(s"Machine not found: $machineId"))
      case Some(machine) =>
        machine.perceptualMapping match {
          case None => Future.failed(new IllegalStateException(
            s"""Machine "${machine.name}" has no perceptual mapping — configure one or use /process-universal."""))
          case Some(mapping) =>
            if (inputVector.length != mapping.input.length)
              Future.failed(new IllegalArgumentException(
                s"Input vector length ${inputVector.length} does not match machine input region length ${mapping.input.length}"))
            else {
              val universalSpace = Vector.fill(universalDimension)(0.0)
                .patch(mapping.input.offset, inputVector, inputVector.length)
              processUniversalInput(universalSpace, machineId)
            }
        }
    }

  def processUniversalInput(universalInputSpace: Vector[Double], machineId: String): Future[MachineTransitionResult] =
    machines.get(machineId) match {
      case None => Future.failed(new NoSuchElementException(s"Machine not found: $machineId"))
      case Some(machine) =>
        val actor        = machineActors(machineId)
        val machineInput = perceptionEngine.resolveInputEventVectorForMachine(universalInputSpace, machine)

        val tagMachineId   = Json.fromString(machineId)
        val tagMachineName = Json.fromString(machine.name)
        val tagDim         = Json.fromInt(universalInputSpace.length)
        val hasMerge       = machine.perceptualMapping.isDefined

        (actor ? MachineActor.ProcessInput(machineInput)).mapTo[MachineActor.ProcessInputResult].map { pr =>
          val result = pr.result
          coverage.record(machine, result)

          if (verboseLogging)
            println(s"[RealityEngine] machine=${machine.name} id=$machineId " +
              s"sequencesWithOutput=${result.arbiterMetadata.sequencesWithOutput} " +
              s"shouldOutput=${result.arbiterMetadata.shouldOutput} ts=${result.timestamp}")

          machine.perceptualMapping.foreach { mapping =>
            if (result.arbiterMetadata.shouldOutput) {
              result.sequenceResults.values.foreach { sr =>
                sr.assertedOutputs.foreach { ao =>
                  perceptionEngine.mergeOutputIntoPerceptualSpace(ao.vector, mapping)
                }
              }
            }
          }

          val tagMerged = Json.fromBoolean(result.arbiterMetadata.shouldOutput && hasMerge)
          result.copy(machineOutput = result.machineOutput.map { ov =>
            ov.copy(metadata = ov.metadata ++
              Map("machineId"                     -> tagMachineId,
                  "machineName"                   -> tagMachineName,
                  "perceptionUsed"                -> RealityEngine.JsonTrue,
                  "universalSpaceDimension"       -> tagDim,
                  "outputMergedToPerceptualSpace" -> tagMerged))
          })
        }
    }

  /**
   * Process universal input through ALL machines in parallel across actors.
   *
   * Phase 1: snapshot all machine inputs (sequential — prevents read-your-own-write
   *          within a single cycle).
   * Phase 2: dispatch ProcessInput to every machine actor simultaneously; each
   *          actor's FIFO mailbox serialises intra-machine calls.
   * Phase 3: after all Futures resolve, merge outputs into perceptual space
   *          (sequential — preserves deterministic merge order within a cycle).
   */
  def processUniversalInputForAllMachines(universalInputSpace: Vector[Double]): Future[Map[String, MachineTransitionResult]] = {
    val resolvedInputs = perceptionEngine.resolveInputsForMachines(universalInputSpace, machines)
    val tagDim         = Json.fromInt(universalInputSpace.length)

    val askFutures: List[Future[(Machine, String, MachineTransitionResult)]] =
      machines.iterator.flatMap { case (machineId, machine) =>
        for {
          machineInput <- resolvedInputs.get(machineId)
          actor        <- machineActors.get(machineId)
        } yield {
          val inputHash      = machineInput.hashCode
          val tagMachineId   = Json.fromString(machineId)
          val tagMachineName = Json.fromString(machine.name)

          inputCache.get(machineId) match {
            case Some((h, cached)) if h == inputHash && !cached.arbiterMetadata.shouldOutput =>
              Future.successful((machine, machineId, cached))
            case _ =>
              (actor ? MachineActor.ProcessInput(machineInput)).mapTo[MachineActor.ProcessInputResult].map { pr =>
                coverage.record(machine, pr.result)
                val tagged = pr.result.copy(machineOutput = pr.result.machineOutput.map { ov =>
                  ov.copy(metadata = ov.metadata ++
                    Map("machineId"               -> tagMachineId,
                        "machineName"             -> tagMachineName,
                        "perceptionUsed"          -> RealityEngine.JsonTrue,
                        "universalSpaceDimension" -> tagDim))
                })
                if (!tagged.arbiterMetadata.shouldOutput) inputCache.put(machineId, (inputHash, tagged))
                else inputCache.remove(machineId)
                (machine, machineId, tagged)
              }.recover { case e: Exception =>
                System.err.println(s"Error processing machine $machineId: ${e.getMessage}")
                throw e
              }
          }
        }
      }.toList

    Future.sequence(askFutures).map { triples =>
      // Phase 3: merge all outputs — sequential within this callback.
      for ((machine, machineId, result) <- triples if result.arbiterMetadata.shouldOutput) {
        machine.perceptualMapping.foreach { mapping =>
          result.sequenceResults.valuesIterator.flatMap(_.assertedOutputs).foreach { ao =>
            try perceptionEngine.mergeOutputIntoPerceptualSpace(ao.vector, mapping)
            catch { case e: Exception =>
              System.err.println(s"Failed to merge output for machine $machineId: ${e.getMessage}")
            }
          }
        }
      }
      triples.map { case (_, machineId, result) => machineId -> result }.toMap
    }
  }

  def getDiagnosticMapping(universalInputSpace: Vector[Double]): io.circe.Json =
    perceptionEngine.getDiagnosticMapping(universalInputSpace, machines)

  // ── What-if ───────────────────────────────────────────────────────────────

  def processWhatIf(machineId: String, inputVector: Vector[Double]): MachineTransitionResult = {
    val machine = machines.getOrElse(machineId, throw new NoSuchElementException(s"Machine not found: $machineId"))
    machine.clone().processInput(inputVector, audit = false)
  }

  def processUniversalWhatIf(universalInputSpace: Vector[Double], machineId: String): MachineTransitionResult = {
    val machine      = machines.getOrElse(machineId, throw new NoSuchElementException(s"Machine not found: $machineId"))
    val machineInput = perceptionEngine.resolveInputEventVectorForMachine(universalInputSpace, machine)
    machine.clone().processInput(machineInput, audit = false)
  }

  // ── Checkpoints ───────────────────────────────────────────────────────────

  def createCheckpoint(machineId: String, label: Option[String] = None): String = {
    val machine = machines.getOrElse(machineId, throw new NoSuchElementException(s"Machine not found: $machineId"))
    val cpId    = s"cp-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
    // deepClone, not clone: clone() is copy-on-write and shares the live
    // machine's Reality Events, so the snapshot advanced with its subject and
    // restoring it handed back the current state (#51).
    val cp      = MachineCheckpoint(cpId, machineId, machine.name, label, System.currentTimeMillis(), machine.deepClone())
    checkpoints.getOrElseUpdate(machineId, TrieMap.empty).put(cpId, cp)
    cpId
  }

  def listCheckpoints(machineId: String): List[MachineCheckpoint] =
    checkpoints.get(machineId).fold(List.empty[MachineCheckpoint])(_.values.toList)

  def restoreCheckpoint(machineId: String, checkpointId: String): Unit = {
    val cp = checkpoints.get(machineId).flatMap(_.get(checkpointId))
      .getOrElse(throw new NoSuchElementException(s"Checkpoint $checkpointId not found for machine $machineId"))
    removeMachine(machineId)
    // deepClone so the checkpoint survives being restored: a COW clone would
    // hand the registry the snapshot's own sequence objects, and the next run
    // would mutate the checkpoint through them, making it restorable once.
    addMachine(cp.snapshot.deepClone())
  }

  def deleteCheckpoint(machineId: String, checkpointId: String): Boolean =
    checkpoints.get(machineId).exists(_.remove(checkpointId).isDefined)

  // ── Engine-wide processing — POST /api/engine/process ────────────────────
  //
  // SURFACE_SPEC, "POST /api/engine/process — map across machines, in parallel":
  // the unit of iteration is the machine, never the sequence. Driving the input
  // through the machine is what applies its arbiter rule, its output-merge
  // transformation and its perceptual mapping; a walk over sequences skips all
  // three and reports raw assertions no consumer can resolve back to a machine's
  // actual output. That is what this route did until #254.
  //
  // Three properties, in order:
  //
  //   1. Atomic collection — `machines` is snapshotted once, before any actor is
  //      asked. TrieMap's iterator is already a consistent O(1) snapshot, so a
  //      machine registered mid-call joins the next call or none, never half of
  //      this one.
  //   2. Machine-level parallelism — one ask per machine, dispatched together.
  //      Each MachineActor's FIFO mailbox serialises calls *within* a machine
  //      while machines advance concurrently, which is the property RC-1 was
  //      built for and the reason the machine is the only safe unit here.
  //   3. Atomic join — `Future.sequence` completes the whole fan-in before any
  //      output is emitted. A partial join is not a shorter answer, it is a
  //      wrong one.
  //
  // Canonical machine order so the emitted `outputs` sequence is deterministic
  // across runs and comparable across runtimes.
  def processAcrossMachines(inputVector: Vector[Double]): Future[List[OutputVector]] = {
    val snapshot = getAllMachines
    val asks: List[Future[Option[OutputVector]]] =
      snapshot.flatMap { machine =>
        machineActors.get(machine.id).map { actor =>
          (actor ? MachineActor.ProcessInput(inputVector))
            .mapTo[MachineActor.ProcessInputResult]
            .map { pr =>
              coverage.record(machine, pr.result)
              pr.result.machineOutput
            }
        }
      }
    Future.sequence(asks).map(_.flatten)
  }

  // ── Legacy sequence-level processing ─────────────────────────────────────

  // Machines first, then anything created over the API. This used to walk a
  // map that mirrored machine sequences keyed by sequence id, so 20 of them —
  // every id shared between machines — were silently skipped (#92).
  def processInputLegacy(inputVector: Vector[Double]): TransitionResult = {
    val walked = machineSequences ++ apiSequences.toList.map { case (id, s) => (id, s) }
    val outputs = walked.flatMap { case (seqId, seq) =>
      val sr = seq.transition(inputVector)
      sr.assertedOutputs.map(o =>
        o.copy(metadata = o.metadata ++
          Map("sequenceId"   -> Json.fromString(seqId),
              "sequenceName" -> Json.fromString(seq.name)))
      )
    }.toList

    val result = TransitionResult(inputVector, System.currentTimeMillis(), outputs)
    addToHistory(result)
    result
  }

  def processInputSequence(inputVectors: List[Vector[Double]]): List[TransitionResult] =
    inputVectors.map(processInputLegacy)

  // ── Sequences active vectors ──────────────────────────────────────────────

  def getAllActiveVectors: Map[String, List[RealityEvent]] =
    (machineSequences ++ apiSequences.toList)
      .map { case (seqId, seq) => seqId -> seq.getActiveVectors }
      .filter { case (_, active) => active.nonEmpty }
      .toMap

  def resetAllSequences(): Unit = {
    machineActors.values.foreach(_ ! MachineActor.Reset)
    apiSequences.values.foreach(_.reset())
    inputCache.clear()
    println("All sequences reset to initial state")
  }

  def resetSequence(sequenceId: String): Boolean =
    apiSequences.get(sequenceId).exists { seq => seq.reset(); true }

  // ── VectorStore bridge ────────────────────────────────────────────────────
  //
  // `persistAllSequences` used to live here, backing `POST /api/sequences/persist`.
  // It wrote whatever the engine-level mirror happened to hold — which was a
  // deduplicated copy of machine state, so it persisted 5108 of 5128 sequences
  // and reported success (#92).
  //
  // Removed rather than repaired. Bulk-populating Qdrant from a registry that
  // shadows machine state is the shortcut that produced the defect: the mirror
  // existed to make persistence and counting convenient, and being a mirror is
  // exactly what made it wrong. The store itself stays — `storeVector`,
  // `storeSequence`, `getSequence` and `searchSimilar` are all still here, and
  // the collection is created at boot — so repopulating it later is a matter of
  // choosing a writer that reads `machines` directly, not of restoring this.

  def loadSequence(sequenceId: String)(implicit ec: ExecutionContext): Future[Option[CriticalEventSequence]] =
    vectorStore.getSequence(sequenceId).map { optSeq =>
      optSeq.foreach(addSequence)
      optSeq
    }

  def searchVectors(queryVector: Vector[Double], limit: Int = 10, threshold: Option[Double] = None): Future[List[(RealityEvent, Double)]] =
    vectorStore.searchSimilar(queryVector, limit, threshold)

  // ── Stats ─────────────────────────────────────────────────────────────────

  // Derived from `machines`, which is the single source of truth for machine
  // state. C++ (`reality.cpp`, `for machines / for m.all_sequences()`) and LSP
  // compute the same totals the same way; this used to report the size of an
  // id-keyed mirror and so under-reported by 20 sequences and 28 events (#92).
  def getStats: Json = {
    import io.circe.syntax._
    val allSeqs       = machineSequences.map(_._2) ++ apiSequences.values.toList
    val totalVectors  = allSeqs.map(_.getAllVectors.length).sum
    val totalActive   = allSeqs.map(_.getActiveVectors.length).sum
    val seqStats = allSeqs.map { seq =>
      Json.obj(
        "id"    -> Json.fromString(seq.id),
        "name"  -> Json.fromString(seq.name),
        "stats" -> seq.getStats.asJson
      )
    }
    Json.obj(
      "totalSequences"     -> Json.fromInt(allSeqs.size),
      "totalEvents"       -> Json.fromInt(totalVectors),
      "totalActiveEvents"  -> Json.fromInt(totalActive),
      "sequenceStats"      -> Json.arr(seqStats: _*)
    )
  }

  def getHistory(limit: Option[Int] = None): List[TransitionResult] = {
    import scala.jdk.CollectionConverters._
    val iter = transitionHistory.iterator.asScala
    limit.fold(iter.toList)(n => iter.take(n).toList)
  }

  def clearHistory(): Unit = {
    transitionHistory.clear()
    historySize.set(0)
  }

  private def addToHistory(result: TransitionResult): Unit = {
    transitionHistory.addFirst(result)
    // Size cap is approximate: two concurrent addToHistory calls may both see
    // the counter exceed maxHistorySize and each remove one extra element.
    // The legacy history path is not latency-critical; occasional ±1 overshoot
    // is acceptable.
    if (historySize.incrementAndGet() > maxHistorySize) {
      transitionHistory.pollLast()
      historySize.decrementAndGet()
    }
    if (verboseLogging && result.totalOutputs.nonEmpty)
      println(s"""{"level":"info","event":"transition","outputs":${result.totalOutputs.length},"ts":${result.timestamp}}""")
  }
}

object RealityEngine {
  val JsonTrue: Json = Json.fromBoolean(true)
  implicit val askTimeout: Timeout = Timeout(5.seconds)
}
