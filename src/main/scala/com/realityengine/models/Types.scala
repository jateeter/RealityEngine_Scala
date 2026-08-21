package com.realityengine.models

import io.circe.Json

// ── Comparator ──────────────────────────────────────────────────────────────

sealed trait ComparatorType
object ComparatorType {
  case object Equals    extends ComparatorType
  case object Exact     extends ComparatorType
  case object Threshold extends ComparatorType
  case object Pattern   extends ComparatorType
  case object Custom    extends ComparatorType
  case object GTE       extends ComparatorType

  def fromString(s: String): ComparatorType = s.toLowerCase match {
    case "equals"    => Equals
    case "exact"     => Exact
    case "threshold" => Threshold
    case "pattern"   => Pattern
    case "custom"    => Custom
    case "gte"       => GTE
    case other       => throw new IllegalArgumentException(s"Unknown comparator type: $other")
  }

  def serialize(t: ComparatorType): String = t match {
    case Equals    => "equals"
    case Exact     => "exact"
    case Threshold => "threshold"
    case Pattern   => "pattern"
    case Custom    => "custom"
    case GTE       => "gte"
  }
}

// ── Match result ─────────────────────────────────────────────────────────────

case class MatchResult(
  matched:  Boolean,
  score:    Double            = 0.0,
  metadata: Map[String, Json] = Map.empty
)

// ── Vector element ────────────────────────────────────────────────────────────

case class VectorElement(
  value:          Double,
  comparatorType: Option[ComparatorType] = None,
  threshold:      Option[Double]         = None
)

// ── Vector state ──────────────────────────────────────────────────────────────

sealed trait VectorState
object VectorState {
  case object Active   extends VectorState
  case object Inactive extends VectorState
}

// ── Output vector ─────────────────────────────────────────────────────────────

case class OutputVector(
  id:        String,
  vector:    Vector[Double],
  metadata:  Map[String, Json] = Map.empty,
  timestamp: Long
)

// ── Perceptual mapping ────────────────────────────────────────────────────────

case class RegionMapping(offset: Int, length: Int)
case class PerceptualMapping(input: RegionMapping, output: RegionMapping, bitsPerElement: Int = 8)

// ── Sequence result ───────────────────────────────────────────────────────────

case class SequenceResult(
  matchedVectors:   List[String],
  activatedVectors: List[String],
  assertedOutputs:  List[OutputVector]
)

// ── Arbiter metadata ──────────────────────────────────────────────────────────

case class ArbiterMetadata(
  rule:                String,
  totalInputs:         Int,
  sequencesWithOutput: Int,
  shouldOutput:        Boolean
)

// ── Machine transition result ─────────────────────────────────────────────────

case class MachineTransitionResult(
  inputVector:      Vector[Double],
  timestamp:        Long,
  sequenceResults:  Map[String, SequenceResult],
  machineOutput:    Option[OutputVector],
  arbiterMetadata:  ArbiterMetadata
)

// ── Simulation types ──────────────────────────────────────────────────────────

case class ActiveRegion(
  offset:    Int,
  length:    Int,
  machineId: String,
  `type`:    String   // "input" | "output"
)

case class MachineStepResult(
  machineId:       String,
  machineName:     String,
  inputVector:     Vector[Double],
  // A single member of the machine's collection of potential outputs, chosen by
  // the arbiter. Which member that is has differed per runtime. Kept as-is so
  // existing consumers are unaffected; new ones should read mergedOutputVector.
  outputVector:    Option[Vector[Double]],
  // The collection folded under the machine's outputMergeTransformation — what
  // the Reality Engine presents to the Perception Engine. None when the machine
  // completed no Reality Event, which is not the same as a zero vector.
  mergedOutputVector: Option[Vector[Double]] = None,
  // The transformation in force for this machine on this step, reported so the
  // knob can be observed rather than inferred.
  outputMergeTransformation: String = "or",
  inputRegion:     RegionMapping,
  outputRegion:    Option[RegionMapping],
  transitionResult: MachineTransitionResult
)

/** One event-bus write performed during a step.
  *
  * Reported so the step can carry `eventBus`, which SURFACE_SPEC.md requires of
  * every runtime. The writes were previously applied to the perceptual space and
  * discarded, so this runtime could not report what C++ and LSP both reported.
  * Shape follows C++'s EventBusWrite.
  */
case class EventBusWrite(
  producerMachineId:   String,
  producerSequenceId:  String,
  subscriberMachineId: String,
  bitOffset:           Int,
  value:               Double
)

// One machine output staged for merge into the perceptual space. Required of
// every runtime by SURFACE_SPEC.md and previously absent here: C++ and LSP both
// emitted `mergeBatch` in the step and this runtime did not, so a consumer
// walking the step saw a different shape depending on which engine answered.
//
// `provenance` is emitted for shape, empty for now: this runtime's OutputVector
// carries no evidence chain, where C++'s does. Tracked separately — filling it
// is a change to the machine model, not to the step surface.
case class MergeOperation(
  region:      RegionMapping,
  machineId:   String,
  sequenceId:  String,
  outputIndex: Int,
  values:      Vector[Double],
  provenance:  List[String] = Nil
)

case class SimulationStep(
  stepNumber:     Int,
  timestamp:      Long,
  perceptualSpace: Vector[Double],
  machineResults: Map[String, MachineStepResult],
  activeRegions:  List[ActiveRegion],
  mergeBatch:     List[MergeOperation] = Nil,
  eventBus:       List[EventBusWrite] = Nil
)

// One step of a trajectory history — see SURFACE_SPEC.md, "Trajectory
// histories". Sparse: a cell absent from `nonZero` is zero, and `length` keeps
// the dense width so the reconstruction is exact.
case class TrajectoryCell(index: Int, value: Double)
case class TrajectoryEntry(stepNumber: Int, length: Int, nonZero: List[TrajectoryCell])

case class SimulationConfig(
  inputSequence: Vector[Vector[Double]],
  inputRegion:   RegionMapping,
  stepDelayMs:   Long,
  maxSteps:      Option[Int] = None
)
