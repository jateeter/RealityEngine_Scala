package com.realityengine.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Conformance checks for the output merge fold against RealityEngine_CI
 * `scripts/experiment-mv-transforms.py`, which is the specification for the
 * chain family, and against the five Boolean gates already in force.
 *
 * The properties here are the ones a live probe cannot establish. Closure and
 * symmetry hold for every collection or for none, and a single run exercises
 * one collection — so they are checked by exhaustion over the chain, the same
 * way the reference implementation checks them, rather than sampled. Symmetry
 * in particular is invisible in any single run and is the whole basis for
 * folding a collection that carries no order.
 */
class OutputMergeTransformationSpec extends AnyFlatSpec with Matchers {

  import OutputMergeTransformation._

  /** One cell, one value per contribution — the shape every property below is
    * stated over. */
  private def one(values: Seq[Int], t: String, k: Option[Int] = None): Int =
    fold(values.map(v => Vector(v.toDouble)), t, k).get.head.toInt

  /** All collections of size n over the alphabet, with repetition. */
  private def product(alphabet: List[Int], n: Int): List[List[Int]] =
    if (n == 0) List(Nil)
    else for { a <- alphabet; rest <- product(alphabet, n - 1) } yield a :: rest

  private val K         = 3
  private val chain     = (0 to K).toList
  private val chainName = s"chain {0..$K}"

  // ── Boolean gates: 1273 corpus machines fold through these ─────────────────

  "the Boolean gates" should "be unchanged by the chain family being added" in {
    // n-input gates over k, the count of asserted contributions. Stated here as
    // a regression lock, not as a restatement: every binary corpus machine folds
    // through this path and any drift in it is a corpus-wide behaviour change.
    val v = (xs: Seq[Double]) => xs.map(x => Vector(x)).toSeq

    one(Seq(0, 0, 1), Or)   shouldBe 1
    one(Seq(0, 0, 0), Or)   shouldBe 0
    one(Seq(1, 1, 1), And)  shouldBe 1
    one(Seq(1, 1, 0), And)  shouldBe 0
    one(Seq(1, 1, 1), Xor)  shouldBe 1
    one(Seq(1, 1, 0), Xor)  shouldBe 0
    one(Seq(0, 0, 0), Nor)  shouldBe 1
    one(Seq(0, 0, 1), Nor)  shouldBe 0
    one(Seq(1, 1, 0), Nand) shouldBe 1
    one(Seq(1, 1, 1), Nand) shouldBe 0

    // Asserted is `!= 0.0`, not `== 1.0`, so a value the arbiter scaled still
    // counts — and a Boolean gate still flattens it to 1, which is exactly the
    // limitation the chain family exists to answer (RealityEngine_CI#158).
    fold(v(Seq(0.0, 0.4)), Or).get shouldBe Vector(1.0)
    fold(v(Seq(0.0, 4.0)), Or).get shouldBe Vector(1.0)
  }

  it should "present nothing for an empty collection" in {
    // Not a vector of zeros: a machine that completed no Reality Event presents
    // no output at all.
    fold(Seq.empty, Or) shouldBe None
    fold(Seq.empty, Meet, Some(K)) shouldBe None
  }

  "normalise" should "accept both families and default anything else to or" in {
    All should have size 10
    All shouldBe (BooleanGates ++ ChainTransformations)
    BooleanGates.intersect(ChainTransformations) shouldBe empty
    normalise(Some("  MEET ")) shouldBe Meet
    normalise(Some("Strong-Disjunction")) shouldBe StrongDisjunction
    normalise(Some("bogus")) shouldBe Or
    normalise(None) shouldBe Or
  }

  // ── Worked examples from the specification ─────────────────────────────────

  "the chain transformations" should "reproduce the worked examples" in {
    one(Seq(1, 1, 1), StrongDisjunction, Some(3)) shouldBe 3 // weak evidence accumulates
    one(Seq(3, 3, 2), StrongConjunction, Some(3)) shouldBe 2 // near-unanimous survives
    one(Seq(3, 3, 0), StrongConjunction, Some(3)) shouldBe 0 // one absolute veto extinguishes
    one(Seq(3, 3, 0, 3), DiscreteMedian)          shouldBe 3 // transient dropout filtered
    one(Seq(0, 1, 2, 3, 4, 4, 0), Meet)           shouldBe 0
    one(Seq(0, 1, 2, 3, 4, 4, 0), Join)           shouldBe 4
    one(Seq(0, 1, 2, 3, 4, 4, 0), DiscreteMedian) shouldBe 2
  }

  // ── Properties, by exhaustion ──────────────────────────────────────────────

  it should s"be closed over $chainName for collections to n=5" in {
    // The fold may not invent a value outside the chain. Note this is weaker
    // than the no-fabrication check below.
    for {
      t     <- ChainTransformations
      n     <- 1 to 5
      combo <- product(chain, n)
    } withClue(s"$t$combo: ") { chain should contain(one(combo, t, Some(K))) }
  }

  it should "be invariant under permutation of the collection" in {
    // The collection carries no order, so a result that depends on one depends
    // on how the runtime happened to enumerate completed Reality Events — the
    // divergence class of RealityEngine_CI#154.
    for {
      t     <- ChainTransformations
      n     <- 1 to 4
      combo <- product(chain, n)
    } withClue(s"$t$combo: ") {
      combo.permutations.map(one(_, t, Some(K))).toSet should have size 1
    }
  }

  it should "leave meet, join and discrete-median idempotent" in {
    for { t <- Set(Meet, Join, DiscreteMedian); n <- 1 to 5; x <- chain }
      withClue(s"$t, n=$n, x=$x: ") { one(List.fill(n)(x), t, Some(K)) shouldBe x }
  }

  it should "leave the Łukasiewicz pair non-idempotent, which is the point of them" in {
    // x ⊕ x saturates and x ⊙ x extinguishes. Idempotence is not required of a
    // fold and is deliberately not assumed; symmetry is what makes it well
    // defined over a collection.
    Seq(1 -> 2, 2 -> 3, 3 -> 3).foreach { case (x, y) =>
      one(Seq(x, x), StrongDisjunction, Some(K)) shouldBe y
    }
    Seq(1 -> 0, 2 -> 1, 3 -> 3).foreach { case (x, y) =>
      one(Seq(x, x), StrongConjunction, Some(K)) shouldBe y
    }
  }

  it should "never fabricate a value no contribution asserted, for meet/join/median" in {
    // Stronger than closure, and the reason a bitwise merge is not admissible on
    // an ordinal ladder: 1 | 2 is 3, inside the chain but asserted by nobody —
    // an invented rung.
    for {
      t     <- Set(Meet, Join, DiscreteMedian)
      n     <- 1 to 4
      combo <- product(chain, n)
    } withClue(s"$t$combo: ") { combo should contain(one(combo, t, Some(K))) }
  }

  it should "make strong-conjunction the De Morgan dual of strong-disjunction" in {
    // ⊙ = ¬(¬x ⊕ ¬y) under ¬x = k − x. Checked because the implementation
    // computes ⊙ directly from its threshold rather than through the dual, so
    // nothing else would catch the two drifting apart.
    for { n <- 1 to 4; combo <- product(chain, n) } withClue(s"$combo: ") {
      one(combo, StrongConjunction, Some(K)) shouldBe
        K - one(combo.map(K - _), StrongDisjunction, Some(K))
    }
  }

  // ── The chain top ──────────────────────────────────────────────────────────

  "an undeclared chain top" should "not stop meet, join or discrete-median" in {
    // They take no k. Join reads one only as an early-exit bound, so its answer
    // is the same with and without.
    one(Seq(0, 1, 4, 2), Meet)           shouldBe 0
    one(Seq(0, 1, 4, 2), Join)           shouldBe 4
    one(Seq(0, 1, 4, 2), DiscreteMedian) shouldBe 1
    one(Seq(0, 1, 4, 2), Join, Some(9))  shouldBe 4
  }

  it should "make the Łukasiewicz pair refuse rather than guess" in {
    // LOCKS A DECISION THAT IS NOT SETTLED. The C++ engine reads this the other
    // way — it degenerates to Ł₂ = {0,1} — and the reconciliation is being done
    // centrally. The decision itself lives in exactly one place,
    // `OutputMergeTransformation.undeclaredChainTopPolicy`; this expectation is
    // the second and last site, and moves with it.
    //
    // Why this engine refuses: {0,1} would re-flatten the alphabet the machine
    // asked to keep (RealityEngine_CI#158 through the back door), and taking k
    // from the collection's own maximum turns min(max, Σ) into max —
    // strong-disjunction silently becoming join. A machine presenting nothing is
    // diagnosable; a machine presenting a plausible wrong severity is not.
    val outs = Seq(Vector(1.0), Vector(1.0), Vector(1.0))
    fold(outs, StrongDisjunction)          shouldBe None
    fold(outs, StrongConjunction)          shouldBe None
    fold(outs, StrongDisjunction, Some(0)) shouldBe None // not a chain
    fold(outs, StrongConjunction, Some(0)) shouldBe None
    fold(outs, StrongDisjunction, Some(3)) shouldBe Some(Vector(3.0))
    RequiresChainTop shouldBe Set(StrongDisjunction, StrongConjunction)
  }

  it should "keep join symmetric when a contribution sits above it" in {
    // An out-of-chain contribution is a corpus error, but the early exit must
    // not make the answer depend on which one arrived first.
    val combos = List(5, 7, 1).permutations.map(one(_, Join, Some(4))).toSet
    combos shouldBe Set(4)
  }

  // ── Reading Double as a chain value ────────────────────────────────────────

  "a chain value" should "be read as a non-negative integer, ties away from zero" in {
    // The one place floating point meets the chain. Half-up rather than each
    // runtime's default: Common Lisp's round is half-even and would answer 2
    // where this answers 3.
    one(Seq(2), Join) shouldBe 2
    fold(Seq(Vector(2.5)), Join, Some(9)).get  shouldBe Vector(3.0)
    fold(Seq(Vector(3.5)), Join, Some(9)).get  shouldBe Vector(4.0)
    fold(Seq(Vector(2.99)), Join, Some(9)).get shouldBe Vector(3.0)
    // The chain has no negative rungs.
    fold(Seq(Vector(-1.0), Vector(2.0)), Meet).get shouldBe Vector(0.0)
  }

  "a shorter contribution" should "read as 0 beyond its end, as in the Boolean fold" in {
    // Widest contributor wins the width; absent means not asserted, so it vetoes
    // a meet and cannot lift a join.
    fold(Seq(Vector(3.0, 2.0), Vector(3.0)), Meet).get shouldBe Vector(3.0, 0.0)
    fold(Seq(Vector(3.0, 2.0), Vector(3.0)), Join).get shouldBe Vector(3.0, 2.0)
    fold(Seq(Vector(1.0, 1.0), Vector(1.0)), StrongDisjunction, Some(3)).get shouldBe
      Vector(2.0, 1.0)
  }

  "discrete-median" should "take the lower middle element for even n" in {
    // floor(median) over integers is a selection, never a division — no float
    // arithmetic enters the step and no runtime gets to round x.5 its own way.
    one(Seq(0, 1, 2, 3), DiscreteMedian) shouldBe 1
    one(Seq(1, 2), DiscreteMedian)       shouldBe 1
    one(Seq(2, 2, 3, 3), DiscreteMedian) shouldBe 2
  }

  it should "agree with a full sort past the quickselect threshold" in {
    // Above 12 contributions the selection switches from an insertion sort to
    // quickselect; the two paths must not disagree at the boundary.
    for (n <- 1 to 25) {
      val values = (0 until n).map(i => (i * 7) % 5).toList
      withClue(s"n=$n: ") {
        one(values, DiscreteMedian) shouldBe values.sorted.apply((n - 1) / 2)
      }
    }
  }

  // ── FallDetection, the machine that exposed the gap ────────────────────────

  "FallDetection's severity ladder" should "survive the chain fold and not the Boolean one" in {
    // alphabet {0..4} declared as bitsPerElement 4, representable 0..15. `or`
    // answers the ladder with 1 — asserted, rung unknown.
    val ladder = Seq(0, 1, 2, 3, 4, 4, 0)
    one(ladder, Or) shouldBe 1
    one(ladder, Join, Some(4)) shouldBe 4
    // And k is not derivable from bitsPerElement: at the representable maximum
    // ⊕ yields 14, outside the alphabet entirely. Which is why it is a
    // parameter and not a derivation.
    one(ladder, StrongDisjunction, Some(4))  shouldBe 4
    one(ladder, StrongDisjunction, Some(15)) shouldBe 14
  }
}
