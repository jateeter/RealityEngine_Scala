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
    // Ascending sequence id, not `Map.values`.
    //
    // `combineOutputs` presents `outputs.head` as the machine's output, so the
    // order this list is built in decides which member a consumer sees. Walking
    // a Map walks it in hash order, which is this runtime's own and nobody
    // else's: C++ keys its sequenceResults in a `std::map` and therefore walks
    // sorted, LSP sorts, and this runtime did not.
    //
    // Measured on `localai/session_rag_context` (PASSTHROUGH, three sequences
    // each asserting one output): CPP and LSP presented `out-sess-rag-abort`
    // [0,0,1,0] and this runtime presented `out-sess-rag-generate` [1,0,0,0] —
    // the same three outputs, a different pick, on 1 of 173 outputs
    // (RealityEngine_CI#417). All three report the machine's sequences in the
    // same order on the wire, so the divergence was invisible everywhere except
    // in the value finally presented.
    //
    // Sorting by id rather than by the reported order because the id is
    // corpus-declared and identical across runtimes, which is the property the
    // canonical-ordering rule in SURFACE_SPEC is built on (#197).
    for ((_, outs) <- sequenceOutputs.toSeq.sortBy(_._1)) {
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

    // `provenance` names the INPUT events that caused this output; `sources`
    // above names the OUTPUT events folded into it. Different facts, and both
    // contractual (SURFACE_SPEC.md, "A combined machine output reports both
    // where it came from and what it is").
    //
    // This runtime carried only `sources` and CPP and LSP only `provenance`, so
    // a consumer asking either question got an answer from some runtimes and
    // null from the rest (RealityEngine_CI#410). Taken from `outputs.head`,
    // matching the representative whose vector is used.
    OutputVector(
      id         = s"machine-output-${System.currentTimeMillis()}-${UUID.randomUUID().toString.take(8)}",
      vector     = outputs.head.vector,
      metadata   = meta,
      timestamp  = System.currentTimeMillis(),
      provenance = outputs.head.provenance
    )
  }
}
