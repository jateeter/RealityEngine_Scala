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

class PerceptualSpaceSimulator(dimension: Int = sys.env.getOrElse("VECTOR_DIMENSION", "768").toIntOption.getOrElse(768)) {
  private val perceptualSpace    = new PerceptualSpace(dimension)
  private var machines:          Map[String, Machine] = Map.empty
  private var history:           List[SimulationStep] = Nil
  private var currentStep:       Int                  = 0
  private var immediateStepCount: Int                 = 0
  private var isRunning:         Boolean              = false
  private var config:            Option[SimulationConfig] = None
  private var onStepComplete:    Option[(SimulationStep, Vector[Double]) => Unit] = None
  private var cachedEdges:       List[Json]           = Nil
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

  private def applyEventBus(firedSequences: Seq[(String, String)]): Unit = {
    if (eventBusSubscriptions.isEmpty || firedSequences.isEmpty) return
    val seen = scala.collection.mutable.Set.empty[String]
    for ((machineId, seqId) <- firedSequences) {
      val key = composeKey(machineId, seqId)
      eventBusSubscriptions.getOrElse(key, Nil).foreach { sub =>
        val dedup = s"${sub.subscriberMachineId}|${sub.bitOffset}|$machineId|$seqId"
        if (seen.add(dedup)) {
          perceptualSpace.growTo(sub.bitOffset + 1)
          perceptualSpace.updateRegion(sub.bitOffset, Vector(1.0))
          latchedEventBits = latchedEventBits + sub.bitOffset
        }
      }
    }
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
    val machineList = machines.values.toList
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
  def getMachines: List[Machine]             = machines.values.toList
  def getPerceptualSpace: PerceptualSpace    = perceptualSpace

  def configure(cfg: SimulationConfig): Unit = {
    config = Some(cfg)
    reset()
  }

  def reset(): Unit = {
    stop()
    perceptualSpace.reset()
    history = Nil
    currentStep = 0
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
    val mappedMachines = machines.values.filter(_.perceptualMapping.isDefined).toList

    // Phase 1: snapshot + re-apply latched event bits
    applyLatchedEventBits()
    val inputSnapshots: Map[String, Vector[Double]] =
      mappedMachines.map(m => m.id -> perceptualSpace.extractMachineInput(m.perceptualMapping.get)).toMap

    // Phase 2: process
    val machineResults    = scala.collection.mutable.Map.empty[String, MachineStepResult]
    val pendingOutputs    = scala.collection.mutable.ListBuffer.empty[(Machine, Vector[Double])]
    val firedSequences    = scala.collection.mutable.ListBuffer.empty[(String, String)]

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
            sr.assertedOutputs.foreach { ao => pendingOutputs += ((machine, ao.vector)) }
          }
        }
      }

      machineResults(machine.id) = MachineStepResult(
        machineId        = machine.id,
        machineName      = machine.name,
        inputVector      = snapshot,
        outputVector     = outputVector,
        inputRegion      = RegionMapping(mapping.input.offset, mapping.input.length),
        outputRegion     = outputVector.map(_ => RegionMapping(mapping.output.offset, mapping.output.length)),
        transitionResult = transition
      )
    }

    // Phase 3: merge outputs, then apply compose event-bus subscriptions
    pendingOutputs.foreach { case (machine, vec) =>
      perceptualSpace.mergeMachineOutput(vec, machine.perceptualMapping.get)
    }
    applyEventBus(firedSequences.toSeq)

    val activeRegions = machineResults.values.flatMap { mr =>
      val inp = ActiveRegion(mr.inputRegion.offset, mr.inputRegion.length, mr.machineId, "input")
      mr.outputRegion match {
        case Some(out) => List(inp, ActiveRegion(out.offset, out.length, mr.machineId, "output"))
        case None      => List(inp)
      }
    }.toList

    SimulationStep(
      stepNumber      = stepNum,
      timestamp       = System.currentTimeMillis(),
      perceptualSpace = perceptualSpace.getPerceptualVector,
      machineResults  = machineResults.toMap,
      activeRegions   = activeRegions
    )
  }

  // ── Auto-play (synchronous scheduler stub) ────────────────────────────────

  def start(): Unit = {
    require(config.isDefined, "Simulation not configured. Call configure() first.")
    isRunning = true
    // Auto-play stepping is driven externally (e.g. Akka scheduler) in JVM context.
    // Call step() repeatedly from the scheduler.
  }

  def stop(): Unit = { isRunning = false }

  def getCurrentStep: Int = currentStep
  def getHistory: List[SimulationStep] = history
  def getIsRunning: Boolean = isRunning
  def getConfig: Option[SimulationConfig] = config
  def getStepDelayMs: Long = config.map(_.stepDelayMs).getOrElse(100L)

  // ── Graph data ────────────────────────────────────────────────────────────

  def getMachineGraphData: Json = {
    import io.circe.syntax._
    val nodes = machines.values.toList.map { machine =>
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
      "machines"        -> Json.arr(machines.values.map(_.toJson).toList: _*),
      "currentStep"     -> Json.fromInt(currentStep),
      "historyLength"   -> Json.fromInt(history.length),
      "isRunning"       -> Json.fromBoolean(isRunning)
    )
  }
}
