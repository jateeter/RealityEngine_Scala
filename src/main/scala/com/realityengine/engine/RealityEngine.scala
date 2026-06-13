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
 * RealityEngine — core processing engine for Reality Vectors.
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

  private val sequences:    TrieMap[String, CriticalEventSequence]                 = TrieMap.empty
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
    sequences.put(seq.id, seq)
    println(s"Added sequence: ${seq.name} (${seq.id})")
  }

  def removeSequence(sequenceId: String): Unit = sequences.remove(sequenceId)
  def getSequence(id: String): Option[CriticalEventSequence] = sequences.get(id)
  def getAllSequences: List[CriticalEventSequence] = sequences.values.toList

  // ── Machine management ────────────────────────────────────────────────────

  def addMachine(machine: Machine): Unit = {
    val actor = system.actorOf(MachineActor.props(machine))
    machines.put(machine.id, machine)
    machineActors.put(machine.id, actor)
    machine.getAllSequences.foreach(addSequence)
    println(s"Added machine: ${machine.name} (${machine.id}) with ${machine.getSequenceCount} sequences")
  }

  def removeMachine(machineId: String): Boolean =
    machines.remove(machineId) match {
      case None => false
      case Some(machine) =>
        machine.getSequenceIds.foreach(sequences.remove)
        machineActors.remove(machineId).foreach(system.stop)
        inputCache.remove(machineId)
        true
    }

  def getMachine(id: String): Option[Machine]  = machines.get(id)
  def getAllMachines: List[Machine]             = machines.values.toList

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
    machine.clone().processInput(inputVector)
  }

  def processUniversalWhatIf(universalInputSpace: Vector[Double], machineId: String): MachineTransitionResult = {
    val machine      = machines.getOrElse(machineId, throw new NoSuchElementException(s"Machine not found: $machineId"))
    val machineInput = perceptionEngine.resolveInputEventVectorForMachine(universalInputSpace, machine)
    machine.clone().processInput(machineInput)
  }

  // ── Checkpoints ───────────────────────────────────────────────────────────

  def createCheckpoint(machineId: String, label: Option[String] = None): String = {
    val machine = machines.getOrElse(machineId, throw new NoSuchElementException(s"Machine not found: $machineId"))
    val cpId    = s"cp-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
    val cp      = MachineCheckpoint(cpId, machineId, machine.name, label, System.currentTimeMillis(), machine.clone())
    checkpoints.getOrElseUpdate(machineId, TrieMap.empty).put(cpId, cp)
    cpId
  }

  def listCheckpoints(machineId: String): List[MachineCheckpoint] =
    checkpoints.get(machineId).fold(List.empty[MachineCheckpoint])(_.values.toList)

  def restoreCheckpoint(machineId: String, checkpointId: String): Unit = {
    val cp = checkpoints.get(machineId).flatMap(_.get(checkpointId))
      .getOrElse(throw new NoSuchElementException(s"Checkpoint $checkpointId not found for machine $machineId"))
    removeMachine(machineId)
    addMachine(cp.snapshot.clone())
  }

  def deleteCheckpoint(machineId: String, checkpointId: String): Boolean =
    checkpoints.get(machineId).exists(_.remove(checkpointId).isDefined)

  // ── Legacy sequence-level processing ─────────────────────────────────────

  def processInputLegacy(inputVector: Vector[Double]): TransitionResult = {
    val outputs = sequences.flatMap { case (seqId, seq) =>
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

  def getAllActiveVectors: Map[String, List[RealityVector]] =
    sequences.iterator
      .map { case (seqId, seq) => seqId -> seq.getActiveVectors }
      .filter { case (_, active) => active.nonEmpty }
      .toMap

  def resetAllSequences(): Unit = {
    machineActors.values.foreach(_ ! MachineActor.Reset)
    sequences.values.foreach(_.reset())
    inputCache.clear()
    println("All sequences reset to initial state")
  }

  def resetSequence(sequenceId: String): Boolean =
    sequences.get(sequenceId).exists { seq => seq.reset(); true }

  // ── VectorStore bridge ────────────────────────────────────────────────────

  def persistAllSequences()(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(sequences.values.map(vectorStore.storeSequence).toList).map { _ =>
      println(s"Persisted ${sequences.size} sequences to vector store")
    }

  def loadSequence(sequenceId: String)(implicit ec: ExecutionContext): Future[Option[CriticalEventSequence]] =
    vectorStore.getSequence(sequenceId).map { optSeq =>
      optSeq.foreach(addSequence)
      optSeq
    }

  def searchVectors(queryVector: Vector[Double], limit: Int = 10, threshold: Option[Double] = None): Future[List[(RealityVector, Double)]] =
    vectorStore.searchSimilar(queryVector, limit, threshold)

  // ── Stats ─────────────────────────────────────────────────────────────────

  def getStats: Json = {
    import io.circe.syntax._
    val totalVectors  = sequences.values.map(_.getAllVectors.length).sum
    val totalActive   = sequences.values.map(_.getActiveVectors.length).sum
    val seqStats = sequences.values.toList.map { seq =>
      Json.obj(
        "id"    -> Json.fromString(seq.id),
        "name"  -> Json.fromString(seq.name),
        "stats" -> seq.getStats.asJson
      )
    }
    Json.obj(
      "totalSequences"     -> Json.fromInt(sequences.size),
      "totalVectors"       -> Json.fromInt(totalVectors),
      "totalActiveVectors" -> Json.fromInt(totalActive),
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
