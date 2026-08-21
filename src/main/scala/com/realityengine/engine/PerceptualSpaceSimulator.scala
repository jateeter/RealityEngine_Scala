package com.realityengine.engine

import com.realityengine.models._
import io.circe.Json

/**
 * PerceptualSpaceSimulator — simulates reality vector flows through interconnected machines.
 *
 * Manages a shared PerceptualSpace and orchestrates the 3-phase
 * snapshot → process → merge loop over all registered machines.
 */
// Compose event-bus subscription: one record per (producerMachineId|producerSequenceId) → subscriber.
private case class ComposeSubscription(subscriberMachineId: String, bitOffset: Int,
                                       producerMachineId: String, producerSequenceId: String)

class PerceptualSpaceSimulator(dimension: Int = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)) {
  private val perceptualSpace    = new PerceptualSpace(dimension)
  private var machines:          Map[String, Machine] = Map.empty
  private var history:           List[SimulationStep] = Nil
  // Trajectory histories — see SURFACE_SPEC.md, "Trajectory histories".
  private var isreHistory:       Vector[TrajectoryEntry] = Vector.empty
  private var orevHistory:       Vector[TrajectoryEntry] = Vector.empty
  private val maxTrajectory:     Int                     = 1024
  private var currentStep:       Int                  = 0
  private var immediateStepCount: Int                 = 0
  private var isRunning:         Boolean              = false
  private var config:            Option[SimulationConfig] = None
  private var onStepComplete:    Option[(SimulationStep, Vector[Double]) => Unit] = None
  private var cachedEdges:       List[Json]           = Nil
  // Arbitration records for the most recent step. Observability is not optional:
  // the domain bus exists to make dynamic operation visible, and a resolution
  // nobody can see is indistinguishable from no resolution at all.
  private var lastArbitration:   List[Arbiter.ArbitrationRecord] = Nil
  // CES coverage registry — attached at boot in Main.scala to the same
  // instance the engine holds, so /api/perceive and /api/process-universal
  // bump the same counters that /api/metrics emits.
  private var coverageRegistry:  Option[com.realityengine.services.CesCoverageRegistry] = None

  // Compose / meta-CES event bus — mirrors C++ PerceptualSpaceSimulator.
  // Keyed by "producerMachineId|producerSequenceId" → list of subscribers.
  private var eventBusSubscriptions: Map[String, List[ComposeSubscription]] = Map.empty
  private var latchedEventBits:      Set[Int]                               = Set.empty

  private def composeKey(machineId: String, seqId: String): String = s"$machineId|$seqId"

  def eventBusSubscriptionCount: Int = eventBusSubscriptions.values.map(_.size).sum

  private def registerComposeSubscriptions(machine: Machine): Unit = {
    machine.metadata.get("compose").foreach { composeJson =>
      composeJson.hcursor.downField("subscriptions").as[Vector[io.circe.Json]]
        .getOrElse(Vector.empty)
        .foreach { sub =>
          val c = sub.hcursor
          for {
            pmId  <- c.get[String]("producerMachineId").toOption if pmId.nonEmpty
            psId  <- c.get[String]("producerSequenceId").toOption if psId.nonEmpty
            bit   <- c.get[Int]("bitOffset").toOption if bit >= 0
          } {
            val key  = composeKey(pmId, psId)
            val sub  = ComposeSubscription(machine.id, bit, pmId, psId)
            eventBusSubscriptions = eventBusSubscriptions +
              (key -> (sub :: eventBusSubscriptions.getOrElse(key, Nil)))
            perceptualSpace.growTo(bit + 1)
          }
        }
    }
  }

  private def unregisterComposeSubscriptions(machineId: String): Unit = {
    eventBusSubscriptions = eventBusSubscriptions.flatMap { case (key, subs) =>
      val remaining = subs.filterNot(_.subscriberMachineId == machineId)
      if (remaining.isEmpty) None else Some(key -> remaining)
    }
  }

  // Returns the writes it performed. They used to be applied and discarded, so
  // the step could not carry `eventBus` — a key SURFACE_SPEC.md requires of
  // every runtime and which C++ and LSP both emitted.
  private def applyEventBus(firedSequences: Seq[(String, String)]): List[EventBusWrite] = {
    if (eventBusSubscriptions.isEmpty || firedSequences.isEmpty) return Nil
    val seen    = scala.collection.mutable.Set.empty[String]
    val writes  = List.newBuilder[EventBusWrite]
    for ((machineId, seqId) <- firedSequences) {
      val key = composeKey(machineId, seqId)
      eventBusSubscriptions.getOrElse(key, Nil).foreach { sub =>
        val dedup = s"${sub.subscriberMachineId}|${sub.bitOffset}|$machineId|$seqId"
        if (seen.add(dedup)) {
          perceptualSpace.growTo(sub.bitOffset + 1)
          perceptualSpace.updateRegion(sub.bitOffset, Vector(1.0))
          latchedEventBits = latchedEventBits + sub.bitOffset
          writes += EventBusWrite(machineId, seqId, sub.subscriberMachineId, sub.bitOffset, 1.0)
        }
      }
    }
    writes.result()
  }

  private def applyLatchedEventBits(): Unit =
    latchedEventBits.foreach { bit =>
      perceptualSpace.growTo(bit + 1)
      perceptualSpace.updateRegion(bit, Vector(1.0))
    }

  // ── Configuration ─────────────────────────────────────────────────────────

  def setOnStepComplete(cb: (SimulationStep, Vector[Double]) => Unit): Unit =
    { onStepComplete = Some(cb) }

  def setCoverageRegistry(reg: com.realityengine.services.CesCoverageRegistry): Unit =
    { coverageRegistry = Some(reg) }

  def addMachine(machine: Machine): Unit = {
    require(machine.perceptualMapping.isDefined,
      s"Machine ${machine.name} does not have a perceptual mapping")
    val mapping  = machine.perceptualMapping.get
    val required = math.max(
      mapping.input.offset  + mapping.input.length,
      mapping.output.offset + mapping.output.length,
    )
    perceptualSpace.growTo(required)
    unregisterComposeSubscriptions(machine.id)
    machines = machines + (machine.id -> machine)
    registerComposeSubscriptions(machine)
    rebuildEdgeCache()
  }

  def removeMachine(machineId: String): Unit = {
    unregisterComposeSubscriptions(machineId)
    machines = machines - machineId
    rebuildEdgeCache()
  }

  private def rebuildEdgeCache(): Unit = {
    val machineList = Machine.inCanonicalOrder(machines.values)
    cachedEdges = for {
      sourceM <- machineList if sourceM.perceptualMapping.isDefined
      targetM <- machineList if targetM.id != sourceM.id && targetM.perceptualMapping.isDefined
      srcOut   = sourceM.perceptualMapping.get.output
      tgtIn    = targetM.perceptualMapping.get.input
      srcEnd   = srcOut.offset + srcOut.length
      tgtEnd   = tgtIn.offset + tgtIn.length
      if !(srcEnd <= tgtIn.offset || srcOut.offset >= tgtEnd)
    } yield Json.obj(
      "source"       -> Json.fromString(sourceM.id),
      "target"       -> Json.fromString(targetM.id),
      "sourceRegion" -> Json.obj("offset" -> Json.fromInt(srcOut.offset), "length" -> Json.fromInt(srcOut.length)),
      "targetRegion" -> Json.obj("offset" -> Json.fromInt(tgtIn.offset),  "length" -> Json.fromInt(tgtIn.length)),
      "overlap"      -> Json.fromBoolean(true)
    )
  }
  /** Canonical order — see Machine.canonicalOrder. */
  def getMachines: List[Machine]             = Machine.inCanonicalOrder(machines.values)
  def getPerceptualSpace: PerceptualSpace    = perceptualSpace

  def configure(cfg: SimulationConfig): Unit = {
    config = Some(cfg)
    reset()
  }

  def reset(): Unit = {
    stop()
    perceptualSpace.reset()
    history = Nil
    isreHistory = Vector.empty
    orevHistory = Vector.empty
    currentStep = 0
    // Reset cleared the histories and left this counting, so the first step of
    // a reset engine was stepNumber 19 while its history held one entry. LSP
    // zeroed its step counter here and this did not, which makes `stepNumber`
    // mean something different per runtime the moment anything resets — and the
    // trajectory histories are compared by it (RealityEngine_CI#148).
    immediateStepCount = 0
    latchedEventBits = Set.empty
    machines.values.foreach(_.reset())
  }

  // ── Step execution ────────────────────────────────────────────────────────

  def step(): Option[SimulationStep] = {
    val cfg = config.getOrElse(throw new IllegalStateException("Simulation not configured. Call configure() first."))
    if (currentStep >= cfg.inputSequence.length)     { stop(); return None }
    if (cfg.maxSteps.exists(currentStep >= _))        { stop(); return None }

    val inputVector = cfg.inputSequence(currentStep)
    perceptualSpace.updateRegion(cfg.inputRegion.offset, inputVector)

    val result = runPhases(currentStep)
    currentStep += 1
    history = result :: history
    onStepComplete.foreach(_(result, perceptualSpace.getPerceptualVector))
    if (isRunning && currentStep >= cfg.inputSequence.length) stop()
    Some(result)
  }

  /**
   * Process a pre-assembled full perceptual vector (used by Perception Engine integration).
   * Does not touch currentStep or the configured input sequence.
   */
  def processImmediate(vector: Vector[Double], matchAlgorithmOverride: Option[ComparatorType] = None): SimulationStep = {
    perceptualSpace.setPerceptualVector(vector)
    val result = runPhases(immediateStepCount, matchAlgorithmOverride)
    immediateStepCount += 1
    history = result :: history
    onStepComplete.foreach(_(result, perceptualSpace.getPerceptualVector))
    result
  }

  private def runPhases(stepNum: Int, matchOverride: Option[ComparatorType] = None): SimulationStep = {
    val mappedMachines = Machine.inCanonicalOrder(machines.values.filter(_.perceptualMapping.isDefined))

    // Phase 1: snapshot + re-apply latched event bits
    applyLatchedEventBits()

    // ISRE(n) observation point. This is the input space reality event the
    // corpus is about to be presented with: the snapshots below are extracted
    // from exactly this state, so capturing it here — after the latched bits
    // are re-applied and before the first extract — records what the corpus
    // read rather than an approximation of it. The arbitration feedback from
    // step n-1 is already merged in; the gap between this and the seed is what
    // arbitration did.
    val isre = sparseTrajectory(stepNum, perceptualSpace.getPerceptualVector)

    val inputSnapshots: Map[String, Vector[Double]] =
      mappedMachines.map(m => m.id -> perceptualSpace.extractMachineInput(m.perceptualMapping.get)).toMap

    // Phase 2: process
    val machineResults    = scala.collection.mutable.Map.empty[String, MachineStepResult]
    // GATHER — contributions, not writes. Nothing touches the perceptual array
    // until the arbiter has resolved every contended cell (ARBITER_CONTRACT.md 2).
    // The contribution carries cesId and the output vector because the
    // trigger-rule join (4.3.1) needs them; the previous
    // ListBuffer[(Machine, Vector[Double])] discarded both, which is what made
    // SEVERITY unimplementable.
    val contributions     = scala.collection.mutable.ListBuffer.empty[Arbiter.Contribution]
    val firedSequences    = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val mergeOps          = scala.collection.mutable.ListBuffer.empty[MergeOperation]

    for (machine <- mappedMachines) {
      val snapshot     = inputSnapshots(machine.id)
      val transition   = machine.processInput(snapshot, matchOverride)
      coverageRegistry.foreach(_.record(machine, transition))
      val outputVector = transition.machineOutput.map(_.vector)
      val mapping      = machine.perceptualMapping.get

      if (transition.arbiterMetadata.shouldOutput) {
        transition.sequenceResults.foreach { case (seqId, sr) =>
          if (sr.assertedOutputs.nonEmpty) {
            firedSequences += ((machine.id, seqId))
            sr.assertedOutputs.zipWithIndex.foreach { case (ao, outputIndex) =>
              mergeOps += MergeOperation(
                region      = RegionMapping(mapping.output.offset, mapping.output.length),
                machineId   = machine.id,
                sequenceId  = seqId,
                outputIndex = outputIndex,
                values      = ao.vector
              )
              val rag = Arbiter.joinSeverity(machine.metadata, seqId, ao.vector)
              var i = 0
              while (i < ao.vector.length && i < mapping.output.length) {
                contributions += Arbiter.Contribution(
                  cell           = mapping.output.offset + i,
                  value          = ao.vector(i),
                  provider       = "machine",
                  originId       = machine.id,
                  cesId          = Some(seqId),
                  outputVectorId = Some(ao.id),
                  ragStatusCode  = rag
                )
                i += 1
              }
            }
          }
        }
      }

      // Presenting the machine's output is the Reality Engine's job and the
      // last thing it does in the step. Folded here, in the sequential machine
      // loop — the parallel work in this step is the arbiter's cell sharding
      // further down, which this precedes and does not touch.
      //
      // The collection is one potential output per completed Reality Event.
      // `outputVector` above is a single member of it chosen by the arbiter,
      // and which member that is has differed per runtime — the same corpus
      // presented one runtime's pick to its PE and another's to its own
      // (RealityEngine_CI#154). The fold replaces the pick; `outputVector` is
      // left as it was so nothing reading it today changes.
      val potentialOutputs = transition.sequenceResults.values.flatMap(_.assertedOutputs.map(_.vector)).toSeq
      val merged           = OutputMergeTransformation.fold(potentialOutputs, machine.outputMergeTransformation)

      machineResults(machine.id) = MachineStepResult(
        machineId        = machine.id,
        machineName      = machine.name,
        inputVector      = snapshot,
        outputVector     = outputVector,
        mergedOutputVector        = merged,
        outputMergeTransformation = machine.outputMergeTransformation,
        inputRegion      = RegionMapping(mapping.input.offset, mapping.input.length),
        outputRegion     = (outputVector orElse merged).map(_ => RegionMapping(mapping.output.offset, mapping.output.length)),
        transitionResult = transition
      )
    }

    // Phase 3: RESOLVE then COMMIT.
    //
    // Cells never interact, so the resolve is sharded across cell groups and
    // joined. That is safe only because every admissible rule is a commutative
    // monoid (4.1) — a rule that depended on ordering would make the result
    // depend on the sharding, which the contract forbids and byte equivalence
    // would catch.
    val byCell: Map[Int, List[Arbiter.Contribution]] =
      contributions.toList.groupBy(_.cell)

    val shardCount = math.min(ArbiterParallelism.shards, math.max(1, byCell.size))
    val shards     = byCell.toVector.grouped(math.max(1, (byCell.size + shardCount - 1) / shardCount)).toVector

    val resolvedShards: Vector[Vector[(Int, Double, Option[Arbiter.ArbitrationRecord])]] =
      if (shards.length <= 1) {
        shards.map(_.map { case (cell, cs) =>
          val (v, rec) = Arbiter.resolve(cell, stepNum, cs, ArbitrationRegistry.entryFor(cell))
          (cell, v, rec)
        })
      } else {
        import scala.concurrent.{Await, Future}
        import scala.concurrent.duration._
        implicit val ec: scala.concurrent.ExecutionContext = ArbiterParallelism.ec
        val futures = shards.map { shard =>
          Future {
            shard.map { case (cell, cs) =>
              val (v, rec) = Arbiter.resolve(cell, stepNum, cs, ArbitrationRegistry.entryFor(cell))
              (cell, v, rec)
            }
          }
        }
        Await.result(Future.sequence(futures), 30.seconds)
      }

    // COMMIT — exactly one write per cell, into the head of the input event.
    //
    // OREV(n) observation point. The corpus's output for this step exists as a
    // single-valued vector at exactly one instant: after resolution, as it is
    // committed. Recording it in the same loop as the writes is what makes the
    // entry and the space agree by construction rather than by a later read
    // that could observe a different state. Sorted by cell because the shards
    // join in completion order and ordering is part of the contract.
    val records   = scala.collection.mutable.ListBuffer.empty[Arbiter.ArbitrationRecord]
    val orevCells = scala.collection.mutable.ListBuffer.empty[TrajectoryCell]
    resolvedShards.foreach(_.foreach { case (cell, value, rec) =>
      perceptualSpace.updateRegion(cell, Vector(value))
      if (value != 0.0) orevCells += TrajectoryCell(cell, value)
      rec.foreach(records += _)
    })
    val orev = TrajectoryEntry(stepNum, perceptualSpace.getPerceptualVector.length,
                               orevCells.toList.sortBy(_.index))
    lastArbitration = records.toList
    val eventBusWrites = applyEventBus(firedSequences.toSeq)

    val activeRegions = machineResults.values.flatMap { mr =>
      val inp = ActiveRegion(mr.inputRegion.offset, mr.inputRegion.length, mr.machineId, "input")
      mr.outputRegion match {
        case Some(out) => List(inp, ActiveRegion(out.offset, out.length, mr.machineId, "output"))
        case None      => List(inp)
      }
    }.toList

    recordTrajectory(isre, orev)

    SimulationStep(
      stepNumber      = stepNum,
      timestamp       = System.currentTimeMillis(),
      perceptualSpace = perceptualSpace.getPerceptualVector,
      machineResults  = machineResults.toMap,
      activeRegions   = activeRegions,
      // Canonical merge ordering — (machineId, sequenceId, outputIndex), the
      // same comparator C++ applies, so the batch is a function of the corpus
      // and the input rather than of map iteration order.
      mergeBatch      = mergeOps.toList.sortBy(op => (op.machineId, op.sequenceId, op.outputIndex)),
      eventBus        = eventBusWrites
    )
  }

  /** Dense vector -> sparse trajectory entry. A cell absent from the result is
    * zero; `length` keeps the dense width so the reconstruction is exact. */
  private def sparseTrajectory(stepNum: Int, dense: Vector[Double]): TrajectoryEntry =
    TrajectoryEntry(stepNum, dense.length,
                    dense.iterator.zipWithIndex.collect {
                      case (v, i) if v != 0.0 => TrajectoryCell(i, v)
                    }.toList)

  /** Appends ISRE(n) and OREV(n) together. They are captured at their own
    * observation points inside the step and recorded in one action, so no
    * observer can see a step whose trajectories are half-written.
    *
    * Ascending stepNumber — oldest first. The step history is newest first
    * because it is read as "what just happened"; these are read as sequences to
    * be compared element by element, and the index of the first disagreement is
    * the answer they exist to give. */
  private def recordTrajectory(isre: TrajectoryEntry, orev: TrajectoryEntry): Unit = {
    isreHistory = (isreHistory :+ isre).takeRight(maxTrajectory)
    orevHistory = (orevHistory :+ orev).takeRight(maxTrajectory)
  }

  // ── Auto-play (synchronous scheduler stub) ────────────────────────────────

  def start(): Unit = {
    require(config.isDefined, "Simulation not configured. Call configure() first.")
    isRunning = true
    // Auto-play stepping is driven externally (e.g. Akka scheduler) in JVM context.
    // Call step() repeatedly from the scheduler.
  }

  def stop(): Unit = { isRunning = false }

  /** Arbitration records from the most recent step — contributors, rule applied,
    * resolved value, and what was suppressed. Observability is not optional: a
    * suppressed agent assessment must stay attributable, since "the agent's
    * answer was discarded" is exactly the operational fact the domain bus exists
    * to surface. */
  def getLastArbitration: List[Arbiter.ArbitrationRecord] = lastArbitration

  def getCurrentStep: Int = currentStep
  def getHistory: List[SimulationStep] = history

  /** Ascending stepNumber, oldest first. `from` is the first stepNumber to
    * include; `limit` caps the entries returned from there (0 = all). A
    * comparison walks these by index across engines and reports the first
    * disagreement, so the window has to be selectable by step, not by recency. */
  def getOrevHistory(from: Int = 0, limit: Int = 0): List[TrajectoryEntry] = window(orevHistory, from, limit)
  def getIsreHistory(from: Int = 0, limit: Int = 0): List[TrajectoryEntry] = window(isreHistory, from, limit)

  private def window(entries: Vector[TrajectoryEntry], from: Int, limit: Int): List[TrajectoryEntry] = {
    val fromStep = entries.dropWhile(_.stepNumber < from)
    (if (limit > 0) fromStep.take(limit) else fromStep).toList
  }
  def getIsRunning: Boolean = isRunning
  def getConfig: Option[SimulationConfig] = config
  def getStepDelayMs: Long = config.map(_.stepDelayMs).getOrElse(100L)

  // ── Graph data ────────────────────────────────────────────────────────────

  def getMachineGraphData: Json = {
    import io.circe.syntax._
    val nodes = Machine.inCanonicalOrder(machines.values).map { machine =>
      val mapping = machine.perceptualMapping.get
      Json.obj(
        "id"           -> Json.fromString(machine.id),
        "name"         -> Json.fromString(machine.name),
        "description"  -> Json.fromString(machine.description),
        "inputMapping" -> Json.obj(
          "offset" -> Json.fromInt(mapping.input.offset),
          "length" -> Json.fromInt(mapping.input.length)
        ),
        "outputMapping" -> Json.obj(
          "offset" -> Json.fromInt(mapping.output.offset),
          "length" -> Json.fromInt(mapping.output.length)
        ),
        "metadata" -> machine.metadata.asJson
      )
    }

    Json.obj(
      "nodes"                    -> Json.arr(nodes: _*),
      "edges"                    -> Json.arr(cachedEdges: _*),
      "perceptualSpaceDimension" -> Json.fromInt(perceptualSpace.dimension)
    )
  }

  def toJson: Json = {
    import io.circe.syntax._
    Json.obj(
      "perceptualSpace" -> perceptualSpace.toJson,
      "machines"        -> Json.arr(Machine.inCanonicalOrder(machines.values).map(_.toJson): _*),
      "currentStep"     -> Json.fromInt(currentStep),
      "historyLength"   -> Json.fromInt(history.length),
      "isRunning"       -> Json.fromBoolean(isRunning)
    )
  }
}
