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
  outputVector:    Option[Vector[Double]],
  inputRegion:     RegionMapping,
  outputRegion:    Option[RegionMapping],
  transitionResult: MachineTransitionResult
)

case class SimulationStep(
  stepNumber:     Int,
  timestamp:      Long,
  perceptualSpace: Vector[Double],
  machineResults: Map[String, MachineStepResult],
  activeRegions:  List[ActiveRegion]
)

case class SimulationConfig(
  inputSequence: Vector[Vector[Double]],
  inputRegion:   RegionMapping,
  stepDelayMs:   Long,
  maxSteps:      Option[Int] = None
)
