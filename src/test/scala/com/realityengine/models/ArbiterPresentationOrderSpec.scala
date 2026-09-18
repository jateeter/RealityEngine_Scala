package com.realityengine.models

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The arbiter presents by ascending sequence id, whatever order it is handed.
 *
 * `combineOutputs` presents `outputs.head`, so the order the flattened list is
 * built in decides which member a consumer actually sees. This runtime walked
 * `Map.values` — hash order, its own and nobody else's — while C++ keys its
 * sequence results in a `std::map` and LSP sorts. Measured on
 * `localai/session_rag_context`: C++ and LSP presented `out-sess-rag-abort`
 * `[0,0,1,0]`, this runtime presented `out-sess-rag-generate` `[1,0,0,0]`, on 1
 * output in 173 (RealityEngine_CI#418).
 *
 * The fix (#135) is a single `.sortBy(_._1)`. Nothing pinned it, and the
 * property it protects is close to invisible: all three runtimes report the
 * machine's sequences in the same order on the wire, so the divergence shows up
 * nowhere except in the one value finally presented. A refactor could drop that
 * call and only a live three-runtime parity run would notice.
 *
 * Both Map shapes are exercised deliberately. Scala 2.13 uses `Map1`..`Map4`
 * below five entries, which iterate in INSERTION order, and `HashMap` at five
 * or more, which iterates in hash order. Measured for the ids used here — both
 * differ from sorted, so neither case is vacuous:
 *
 *   n=3 Map3     iteration=generate,rewrite,abort   sorted=abort,generate,rewrite
 *   n=6 HashMap  iteration=05,02,04,00,03,01        sorted=00,01,02,03,04,05
 *
 * A test written only against a three-entry map would still be meaningful, but
 * only because the corpus declares these three in an order that happens to
 * differ from sorted. That is too thin a thread to hang the property on.
 */
class ArbiterPresentationOrderSpec extends AnyFlatSpec with Matchers {

  private def out(id: String, vector: Vector[Double]): OutputVector =
    OutputVector(id = id, vector = vector, metadata = Map.empty, timestamp = 0L,
                 provenance = List(s"in-$id"))

  private def arbiter(rule: ArbiterRule): OutputArbiter = {
    val a = new OutputArbiter()
    a.setRule(rule)
    a
  }

  // The real machine: PASSTHROUGH, three sequences, one output each, declared
  // in an order that is not sorted order.
  private val sessionRag: Map[String, List[OutputVector]] = Map(
    "sess-rag-generate" -> List(out("out-sess-rag-generate", Vector(1, 0, 0, 0))),
    "sess-rag-rewrite"  -> List(out("out-sess-rag-rewrite",  Vector(0, 1, 0, 0))),
    "sess-rag-abort"    -> List(out("out-sess-rag-abort",    Vector(0, 0, 1, 0))),
  )

  "the arbiter" should "present the ascending-id member, not the first inserted" in {
    // Guard the guard: if this map ever iterates in sorted order, the assertion
    // below proves nothing and should be rewritten rather than quietly passing.
    withClue("fixture no longer exercises the defect — iteration equals sorted order: ") {
      sessionRag.keys.toList should not be sessionRag.keys.toList.sorted
    }

    val decision = arbiter(ArbiterRule.PASSTHROUGH).arbitrate(sessionRag, totalSequences = 3)
    decision.shouldOutput shouldBe true
    // `sess-rag-abort` sorts first. This is the exact value C++ and LSP present.
    decision.machineOutput.map(_.vector) shouldBe Some(Vector[Double](0, 0, 1, 0))
  }

  it should "give the same answer however the map was built" in {
    val presented = sessionRag.keys.toList.permutations.map { order =>
      val rebuilt = order.map(k => k -> sessionRag(k)).toMap
      arbiter(ArbiterRule.PASSTHROUGH).arbitrate(rebuilt, 3).machineOutput.map(_.vector)
    }.toSet
    withClue("presentation depends on insertion order: ") {
      presented should have size 1
    }
    presented.head shouldBe Some(Vector[Double](0, 0, 1, 0))
  }

  it should "hold for a HashMap, where iteration is hash order rather than insertion order" in {
    val many = (0 to 5).map { i =>
      f"seq-$i%02d" -> List(out(f"out-seq-$i%02d", Vector(i.toDouble)))
    }.toMap
    withClue("fixture no longer exercises hash order: ") {
      many.keys.toList should not be many.keys.toList.sorted
    }
    arbiter(ArbiterRule.PASSTHROUGH).arbitrate(many, 6)
      .machineOutput.map(_.vector) shouldBe Some(Vector[Double](0))
  }

  it should "order metadata.sources canonically too" in {
    // `sources` names every folded output. A consumer joining on it across
    // runtimes — which is how #418 was diagnosed, since machine ids are
    // engine-scoped — needs the same order from each.
    val sources = arbiter(ArbiterRule.PASSTHROUGH).arbitrate(sessionRag, 3)
      .machineOutput.flatMap(_.metadata.get("sources"))
      .flatMap(_.asArray).map(_.toList.flatMap(_.asString))
    sources shouldBe Some(List("out-sess-rag-abort", "out-sess-rag-generate", "out-sess-rag-rewrite"))
  }

  it should "count sequences with output independently of order" in {
    val withEmpty = sessionRag + ("sess-rag-silent" -> List.empty[OutputVector])
    val decision  = arbiter(ArbiterRule.OR).arbitrate(withEmpty, totalSequences = 4)
    decision.sequencesWithOutput shouldBe 3
    decision.totalInputs shouldBe 4
    decision.machineOutput.map(_.vector) shouldBe Some(Vector[Double](0, 0, 1, 0))
  }
}
