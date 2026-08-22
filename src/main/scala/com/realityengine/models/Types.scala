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

/** `outputAlphabetTop` is k, the top of the ordered chain {0..k} this machine's
  * output cells range over, and the chain top the fold is given
  * (FOLD_PLACEMENT.md §2). Deliberately NOT `bitsPerElement`, which is the
  * REPRESENTABLE range rather than the alphabet: FallDetection declares 4 bits
  * — representable 0..15 — and ranges over {0..4}, so folding that ladder at
  * k=15 makes the truncated sum yield 14, outside the machine's alphabet
  * entirely (RealityEngine_CI#158). Optional because no corpus machine declares
  * one today; required by `schemas/machine.schema.json` only when the
  * transformation is one of the Łukasiewicz pair, which is undefined without it.
  */
case class PerceptualMapping(input: RegionMapping, output: RegionMapping, bitsPerElement: Int = 8,
                             outputAlphabetTop: Option[Int] = None)

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

// The machine's single contribution into the arbitration path — one operation
// per machine per output region per step (FOLD_PLACEMENT.md §1). Required of
// every runtime by SURFACE_SPEC.md and previously absent here: C++ and LSP both
// emitted `mergeBatch` in the step and this runtime did not, so a consumer
// walking the step saw a different shape depending on which engine answered.
//
// It used to carry one entry per ASSERTED OUTPUT, so a machine with seven
// completed Reality Events handed seven values to the same cell and the cell
// arbiter resolved contention that belonged to the machine. FallDetection made
// that concrete: seven sequences assert 0/1/2/3/4/4/0 on output index 0, and at
// the sweep the resolved value was 2.0 on C++ and LSP and 0.0 here — neither the
// maximum nor the minimum, because only a subset fires on any step and the
// answer depended on which subset plus how each runtime's arbiter broke ties
// among same-machine contributions. Folding first removes the contention rather
// than resolving it consistently, which is the stronger property.
//
// `sequenceIds` is the evidence for the folded value: the set of CESs that
// contributed, sorted and deduplicated. It replaces the scalar `sequenceId`,
// which a folded contribution cannot supply — and it is what the audit paths
// and the event bus read instead.
//
// `outputIndex` is gone: it indexed within one sequence's asserted outputs and
// is meaningless once one operation covers the machine.
//
// `provenance` is emitted for shape, empty for now: this runtime's OutputVector
// carries no evidence chain, where C++'s does. Tracked separately — filling it
// is a change to the machine model, not to the step surface.
//
// `governance` is the joined paging decision (§3) — resolved per contributing
// sequence against that sequence's OWN asserted values, then joined by severity
// rank. It is carried HERE rather than recomputed at the arbiter so the value
// the arbiter ranks by and the value the operation reports cannot drift apart.
// A full PagingDecision, matching C++ and LSP: this runtime carried only the RAG
// code and emitted no `governance` key at all, so a consumer reading
// `governance.ragStatusCode` got nothing from Scala and an object from the other
// two (FOLD_PLACEMENT.md A2).
//
// `deprecation` is present when ANY contributor's sequence is deprecated,
// reporting the lexicographically smallest deprecated contributor so the mark is
// deterministic (§4).
case class MergeOperation(
  region:      RegionMapping,
  machineId:   String,
  sequenceIds: List[String],
  values:      Vector[Double],
  provenance:  List[String]            = Nil,
  governance:  Option[PagingDecision]  = None,
  deprecation: Option[DeprecationMark] = None
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
