package com.realityengine.models

import com.realityengine.api.JsonProtocol._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A transition reports the fold as well as the arbiter's pick.
 *
 * Two different things can be said about a machine that completed several
 * Reality Events: which member the arbiter presents as representative, and what
 * the collection folds to. A step already says both, as `outputVector` and
 * `mergedOutputVector`. The single-machine transition routes said only the
 * first, so a caller of `POST /api/machines/:id/process` could not obtain what
 * the machine presents — on a surface where no step result exists to consult
 * instead — while the pick carried `combinedFrom` and `sources` metadata
 * describing a combination it was not (RealityEngine_CI#418).
 *
 * Measured on `localai/session_rag_context` before the fix: the route reported
 * `[0,0,1,0]` while the step wrote `[1,1,1,0]` for the same machine on the same
 * stimulus — and all three runtimes did it identically, which is why it read as
 * agreement rather than as a defect.
 */
class TransitionFoldSpec extends AnyFlatSpec with Matchers {

  private def machine(id: String, outputs: List[(String, Vector[Double])]): Machine = {
    val m = new Machine("Fold Probe", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(0, 1), RegionMapping(20, 2))), id)
    outputs.foreach { case (seqId, asserted) =>
      val seq = new CriticalEventSequence(s"Seq $seqId", seqId)
      val ev  = new RealityEvent(Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))), true, s"$seqId-v1")
      ev.addOutputVector(OutputVector(s"out-$seqId", asserted, Map.empty, 0L, Nil))
      seq.addVector(ev)
      m.addSequence(seq)
    }
    m
  }

  "a multi-sequence transition" should "report the fold and the pick, and they must differ" in {
    val r = machine("machine-both", List(
      "a-seq" -> Vector(1.0, 0.0),
      "b-seq" -> Vector(0.0, 1.0))).processInput(Vector(1.0))

    r.machineOutput shouldBe defined
    r.mergedOutput  shouldBe Some(Vector(1.0, 1.0))

    // If these ever coincide the fixture has stopped exercising the
    // distinction, and every assertion here would pass vacuously.
    withClue("pick and fold coincide — fixture no longer exercises the defect: ") {
      r.machineOutput.map(_.vector) should not be r.mergedOutput
    }
  }

  it should "fold a single contributor to itself" in {
    // The case that would hide a broken fold if it were the only one tested.
    val r = machine("machine-solo", List("only-seq" -> Vector(1.0, 0.0)))
      .processInput(Vector(1.0))
    r.mergedOutput shouldBe Some(Vector(1.0, 0.0))
    r.machineOutput.map(_.vector) shouldBe r.mergedOutput
  }

  it should "report no fold but keep the pick when the fold refuses" in {
    // The Łukasiewicz pair without a declared chain top refuses rather than
    // guessing. The sequences DID complete, and the pick is the evidence they
    // did — which is why mergedOutput is an added field and not a redefinition
    // of machineOutput (FOLD_PLACEMENT.md 2; #158).
    val m = machine("machine-refuse", List(
      "a-seq" -> Vector(1.0, 0.0),
      "b-seq" -> Vector(0.0, 1.0)))
    m.outputMergeTransformation = OutputMergeTransformation.StrongDisjunction

    val r = m.processInput(Vector(1.0))
    r.mergedOutput  shouldBe None
    withClue("the refusal deleted the evidence that the sequences fired: ") {
      r.machineOutput shouldBe defined
    }

    // Declaring the chain top makes the same machine fold normally, which
    // confirms the refusal was about k and not about the machine.
    m.perceptualMapping = Some(PerceptualMapping(
      RegionMapping(0, 1), RegionMapping(20, 2), 8, Some(1)))
    m.processInput(Vector(1.0)).mergedOutput shouldBe defined
  }

  it should "serialise an absent fold as null, never as an empty array" in {
    // An absent fold is the machine presenting nothing; [] reads as a machine
    // that presented zeros.
    val m = machine("machine-null", List(
      "a-seq" -> Vector(1.0, 0.0),
      "b-seq" -> Vector(0.0, 1.0)))
    m.outputMergeTransformation = OutputMergeTransformation.StrongDisjunction
    val json = m.processInput(Vector(1.0)).asJson.hcursor

    json.downField("mergedOutput").focus.map(_.isNull) shouldBe Some(true)

    val folded = machine("machine-arr", List("a-seq" -> Vector(1.0, 0.0)))
      .processInput(Vector(1.0)).asJson.hcursor
    folded.downField("mergedOutput").focus.map(_.isArray) shouldBe Some(true)
  }
}
