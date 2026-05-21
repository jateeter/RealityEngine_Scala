package com.realityengine.models

import java.util.UUID

/**
 * ArbiterRule — defines how the arbiter combines sequence outputs.
 */
sealed trait ArbiterRule
object ArbiterRule {
  case object AND         extends ArbiterRule
  case object OR          extends ArbiterRule
  case object PASSTHROUGH extends ArbiterRule

  def fromString(s: String): ArbiterRule = s.toLowerCase match {
    case "and"         => AND
    case "or"          => OR
    case "passthrough" => PASSTHROUGH
    case other         => throw new IllegalArgumentException(s"Unknown arbiter rule: $other")
  }

  def serialize(r: ArbiterRule): String = r match {
    case AND         => "and"
    case OR          => "or"
    case PASSTHROUGH => "passthrough"
  }
}

case class ArbiterDecision(
  shouldOutput:        Boolean,
  machineOutput:       Option[OutputVector],
  rule:                ArbiterRule,
  totalInputs:         Int,
  sequencesWithOutput: Int
)

/**
 * OutputArbiter — collects sequence outputs and applies combinatorial logic.
 *
 * AND:         output only when ALL sequences produced output.
 * OR:          output when at least ONE sequence produced output.
 * PASSTHROUGH: output whenever any outputs exist.
 */
class OutputArbiter(private var rule: ArbiterRule = ArbiterRule.AND) {

  def getRule: ArbiterRule = rule
  def setRule(r: ArbiterRule): Unit = { rule = r }

  def arbitrate(
    sequenceOutputs: Map[String, List[OutputVector]],
    totalSequences:  Int
  ): ArbiterDecision = {
    // Single pass: accumulate flattened output list and non-empty count together,
    // avoiding the double iteration of the previous .flatten.toList then .count.
    var sequencesWithOutput = 0
    val allOutputs = List.newBuilder[OutputVector]
    for (outs <- sequenceOutputs.values) {
      if (outs.nonEmpty) {
        sequencesWithOutput += 1
        allOutputs ++= outs
      }
    }
    val outputList = allOutputs.result()

    val shouldOutput = rule match {
      case ArbiterRule.AND         => sequencesWithOutput == totalSequences && totalSequences > 0
      case ArbiterRule.OR          => sequencesWithOutput > 0
      case ArbiterRule.PASSTHROUGH => outputList.nonEmpty
    }

    val machineOutput =
      if (shouldOutput && outputList.nonEmpty) Some(combineOutputs(outputList))
      else None

    ArbiterDecision(
      shouldOutput        = shouldOutput,
      machineOutput       = machineOutput,
      rule                = rule,
      totalInputs         = totalSequences,
      sequencesWithOutput = sequencesWithOutput
    )
  }

  /**
   * Use the first output as the representative value.  Its vector is written to
   * the perceptual space by the caller (Machine / RealityEngine).
   */
  private def combineOutputs(outputs: List[OutputVector]): OutputVector = {
    import io.circe.Json
    val sources      = outputs.map(o => Json.fromString(o.id))
    val descriptions = outputs.flatMap(o =>
      o.metadata.get("description").flatMap(_.asString)
    )
    val meta = Map(
      "arbiter"       -> Json.fromBoolean(true),
      "combinedFrom"  -> Json.fromInt(outputs.length),
      "sources"       -> Json.arr(sources: _*)
    ) ++ (if (descriptions.nonEmpty) Map("descriptions" -> Json.arr(descriptions.map(Json.fromString): _*)) else Map.empty)

    OutputVector(
      id        = s"machine-output-${System.currentTimeMillis()}-${UUID.randomUUID().toString.take(9)}",
      vector    = outputs.head.vector,
      metadata  = meta,
      timestamp = System.currentTimeMillis()
    )
  }
}
