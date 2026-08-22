package com.realityengine.models

/** How a machine folds its collection of potential outputs into the one output
  * the Reality Engine presents to the Perception Engine.
  *
  * At the completion boundary of a machine's atomic matching action the machine
  * holds one potential output per completed Reality Event. Presenting that
  * collection is the Reality Engine's job and the last thing it does in the
  * step.
  *
  * Declared per machine (`outputMergeTransformation`, default "or"), read when
  * the machine is interned, and mutable between steps at runtime. It is a
  * training variable, which is why it belongs to the machine rather than to a
  * deployment.
  *
  * Two families, disjoint, and a consumer can tell which from the name alone:
  *
  *   - the five Boolean gates, defined over {0,1};
  *   - the five chain transformations, defined over an ordered chain {0..k}.
  *
  * Defined in RealityEngine_Machines `semantics/ontology/re-core.ttl`; that
  * ontology is the definition and this is an implementation of it. The chain
  * family's reference implementation, with its properties verified by
  * exhaustion rather than asserted, is RealityEngine_CI
  * `scripts/experiment-mv-transforms.py`. This agrees with it.
  */
object OutputMergeTransformation {

  // ── Boolean gates, domain {0,1} ────────────────────────────────────────────

  val Or   = "or"
  val And  = "and"
  val Xor  = "xor"
  val Nor  = "nor"
  val Nand = "nand"

  // ── Chain transformations, domain {0..k} ───────────────────────────────────
  //
  // A Boolean gate reads a cell as asserted or not, so it flattens every value
  // above 1 to 1. Fold a multi-valued machine with one and the machine's
  // alphabet is gone: FallDetection ranks severity over {0,1,2,3,4} and `or`
  // answers that ladder with 1 — asserted, rung unknown (RealityEngine_CI#158).
  // 55 corpus machines declare bitsPerElement > 1 and two use values above 1
  // today, so this is a live gap rather than a hypothetical one.

  val Meet              = "meet"
  val Join              = "join"
  val StrongDisjunction = "strong-disjunction"
  val StrongConjunction = "strong-conjunction"
  val DiscreteMedian    = "discrete-median"

  /** The Boolean family. Named `BooleanGates`, not `Boolean`, so it cannot
    * shadow the type of that name inside this object. */
  val BooleanGates: Set[String] = Set(Or, And, Xor, Nor, Nand)

  /** The chain family. */
  val ChainTransformations: Set[String] =
    Set(Meet, Join, StrongDisjunction, StrongConjunction, DiscreteMedian)

  val All: Set[String] = BooleanGates ++ ChainTransformations

  /** The two Łukasiewicz operations, and the only transformations that cannot
    * be computed without a chain top.
    *
    * ⊕ is `min(k, Σx)` and ⊙ is `max(0, Σx − k(n−1))`: k is where one saturates
    * and the threshold the other has to clear, so neither has a defined value
    * without it. Meet, join and discrete-median take no k — join reads one only
    * as an early-exit bound — so they are unaffected by the question.
    */
  val RequiresChainTop: Set[String] = Set(StrongDisjunction, StrongConjunction)

  /** Normalise a declared name. Anything absent or unrecognised means "or". */
  def normalise(name: Option[String]): String =
    name.map(_.trim.toLowerCase).filter(All.contains).getOrElse(Or)

  /** Fold a machine's collection of potential outputs into one.
    *
    * `chainTop` is k, the top of the ordered chain {0..k} the chain family is
    * defined over — absolute truth in the Łukasiewicz sense. It is a parameter
    * because it is a property of the machine's alphabet and NOT derivable from
    * anything the corpus declares today: FallDetection declares
    * `bitsPerElement` 4, representable 0..15, and uses the alphabet {0..4}.
    * Folding that ladder with k=15 makes ⊕ a plain sum that yields 14 — outside
    * the alphabet entirely — and makes ⊙ extinguish nearly everything. Guessing
    * a derivation rule here would be worse than having none, so there is none.
    *
    * WHEN NO CHAIN TOP IS DECLARED (`None`, or a value below 1, which is not a
    * chain):
    *
    *   - the five Boolean gates never read it and are unaffected;
    *   - meet and discrete-median take no k and fold normally;
    *   - join folds normally — k is only an early exit for it, and the answer
    *     with and without one is the same;
    *   - strong-disjunction and strong-conjunction REFUSE, returning None.
    *
    * That last line is a judgement call, it is NOT settled, and the C++ engine
    * currently reads it the other way. It is decided in exactly one place —
    * `undeclaredChainTopPolicy` below — which carries the argument on both
    * sides and is a one-line edit to flip.
    *
    * `None` for an empty collection, unchanged: a machine that completed no
    * Reality Event presents no output, which is not the same as presenting a
    * vector of zeros. A chain refusal shares that return value — the only one
    * the signature can express — which is why it is documented here and in the
    * spec rather than left to be discovered.
    *
    * CONTESTED, not settled: it has been asserted that any transformation added
    * later must likewise be a function of `k` and `n` alone, i.e. symmetric in
    * its inputs. That generalisation is disputed. What is established is
    * narrower — these ten transformations are each symmetric, the Boolean five
    * verified by exhaustion in RealityEngine_Machines
    * `tests/contracts/owl_semantics_test.py` and the chain five in
    * RealityEngine_CI `scripts/experiment-mv-transforms.py`.
    */
  def fold(outputs: Seq[Vector[Double]],
           transformation: String,
           chainTop: Option[Int] = None): Option[Vector[Double]] = {
    if (outputs.isEmpty) return None
    val name = normalise(Some(transformation))
    if (ChainTransformations.contains(name)) foldChain(outputs, name, chainTop)
    else foldBoolean(outputs, name)
  }

  // ── Boolean fold ───────────────────────────────────────────────────────────

  /** An n-input gate applied to the whole collection at once, not a chain of
    * two-input gates: the truth table is a function of `k`, how many
    * contributions assert a cell, and `n`, how many contributions there are.
    *
    * {{{
    *   or(k,n)  = k >= 1    and(k,n)  = k = n    xor(k,n)  = k odd
    *   nor(k,n) = k = 0     nand(k,n) = k < n
    * }}}
    *
    * Chaining two-input gates would not be equivalent — NOR and NAND are
    * commutative but not associative, so a pairwise fold of them depends on
    * ordering. That is a property of the chained circuit, not of the n-input
    * gate, and it is why this is written over counts.
    *
    * `k` here is the count of asserted contributions and has nothing to do with
    * the chain top of the same name below. The collision is inherited from the
    * ontology, which keeps both names deliberately; the two never share a
    * formula.
    *
    * Untouched by the chain family: 1273 binary corpus machines fold through
    * this path and their results may not move.
    */
  private def foldBoolean(outputs: Seq[Vector[Double]], name: String): Option[Vector[Double]] = {
    // Widest contributor wins the width; shorter ones contribute 0 beyond their
    // end. Every potential output of one machine comes from the same output
    // region, so they agree in practice — this only keeps a malformed corpus
    // from truncating a peer's contribution silently.
    val width = outputs.map(_.length).max
    val n     = outputs.length

    // `!= 0.0` rather than `== 1.0` so a value the arbiter scaled still reads as
    // asserted.
    def asserts(v: Vector[Double], i: Int): Boolean = i < v.length && v(i) != 0.0

    Some(Vector.tabulate(width) { i =>
      val k = outputs.count(asserts(_, i))
      val on = name match {
        case Or   => k >= 1
        case And  => k == n
        case Xor  => k % 2 == 1
        case Nor  => k == 0
        case Nand => k < n
        case _    => k >= 1
      }
      if (on) 1.0 else 0.0
    })
  }

  // ── Chain fold ─────────────────────────────────────────────────────────────

  /** Sentinel for "no chain top declared". Not Option, because this value is
    * read inside the per-cell loops and an Option there would allocate nothing
    * but would branch on a boxed read every iteration. */
  private final val NoChainTop = -1L

  /** Below this size, sorting the median buffer outright beats partitioning it.
    * n is the number of Reality Events a machine completed in one step, which is
    * a handful in every corpus machine measured, so this is the branch that
    * actually runs — quickselect is there for the case that stops being true. */
  private final val InsertionSortMax = 12

  /** What the fold does when no usable chain top was declared. */
  private sealed trait UndeclaredChainTop
  /** Fold anyway, with this k. */
  private final case class FallBackTo(k: Long) extends UndeclaredChainTop
  /** Present nothing. */
  private case object Refuse extends UndeclaredChainTop

  /** THE CHAIN-TOP FALLBACK POLICY — the single place this decision is made.
    *
    * UNSETTLED, AND THE RUNTIMES DISAGREE TODAY. The C++ engine degenerates to
    * the two-element chain Ł₂ = {0,1}, where ⊕ and ⊙ are exactly classical OR
    * and AND; this engine refuses. Both are defended in their own source and
    * neither is settled — the reconciliation is being done centrally, which is
    * why this is one named function returning one value rather than a condition
    * spread through the fold. To adopt the C++ reading, return `FallBackTo(1L)`
    * here and change nothing else.
    *
    * The case for refusing, for whoever settles it: falling back to {0,1}
    * silently re-flattens the alphabet of the machine that asked for MV
    * semantics, which is RealityEngine_CI#158 arriving through the back door and
    * indistinguishable, in the presented output, from a machine that genuinely
    * ranks over {0,1}. A machine that presents nothing every step is a visible,
    * diagnosable signature; a machine presenting a plausible wrong severity is
    * not. The case against it is that it takes a machine off the air for a
    * missing parameter that nothing in the corpus can supply yet.
    *
    * (The third option, taking k from the collection's own maximum, is not open
    * to either side: min(max, Σ) is max for non-negative values, so ⊕ would
    * silently become join.)
    *
    * Only ⊕ and ⊙ ever reach this — see `RequiresChainTop`.
    */
  private def undeclaredChainTopPolicy: UndeclaredChainTop = Refuse

  private def foldChain(outputs: Seq[Vector[Double]],
                        name: String,
                        chainTop: Option[Int]): Option[Vector[Double]] = {
    // A declared top below 1 is not a chain; treated as undeclared rather than
    // folded into a one-element alphabet nobody asked for.
    var k: Long = chainTop.filter(_ >= 1).map(_.toLong).getOrElse(NoChainTop)
    if (k == NoChainTop && RequiresChainTop.contains(name)) {
      undeclaredChainTopPolicy match {
        case Refuse           => return None
        case FallBackTo(fall) => k = fall
      }
    }

    // Materialised once for the whole fold, not once per cell. The call site
    // hands this in as whatever `sequenceResults.values.flatMap(...).toSeq`
    // produced — a List, whose apply is O(n) — and the per-cell loops below
    // index it width times.
    val c: Array[Vector[Double]] = outputs.toArray
    val n = c.length

    var width = 0
    var m     = 0
    while (m < n) { if (c(m).length > width) width = c(m).length; m += 1 }

    // ⊙'s threshold depends only on n and k, so it is computed once here rather
    // than width times inside the loop.
    val threshold = if (name == StrongConjunction) k * (n - 1) else 0L

    // One buffer for the whole fold; refilled per cell, never reallocated.
    val buf: Array[Long] = if (name == DiscreteMedian) new Array[Long](n) else null

    // The name is matched once, out of the hot loop, so the per-cell work is a
    // single call rather than a string comparison chain.
    val cell: Int => Long = name match {
      case Meet              => i => meet(c, n, i)
      case Join              => i => join(c, n, i, k)
      case StrongDisjunction => i => strongDisjunction(c, n, i, k)
      case StrongConjunction => i => strongConjunction(c, n, i, k, threshold)
      case DiscreteMedian    => i => discreteMedian(c, n, i, buf)
      case _                 => i => meet(c, n, i) // unreachable; ChainTransformations gates entry
    }

    Some(Vector.tabulate(width)(i => cell(i).toDouble))
  }

  /** Read cell `i` of one contribution as a chain value.
    *
    * The one place floating point meets the chain, and it is explicit because
    * three runtimes have to land on the same integer. Values are carried as
    * Double for the arbiter's benefit; a chain value is conceptually a
    * non-negative integer, so:
    *
    *   - a cell past the end of a shorter contribution reads as 0, the same
    *     "absent means not asserted" rule the Boolean fold uses;
    *   - anything at or below 0 reads as 0 — the chain has no negative rungs;
    *   - otherwise the nearest integer, ties away from zero, which for
    *     non-negative values is the same as half-up.
    *
    * Ties away from zero rather than each runtime's language default: C++
    * `std::llround` rounds that way, but Common Lisp `round` is round-half-even
    * and would answer 2 where this answers 3 for an input of 2.5. Letting the
    * default decide is how the same corpus produces two severities.
    */
  @inline private def chainValue(v: Vector[Double], i: Int): Long =
    if (i >= v.length) 0L
    else {
      val x = v(i)
      if (x <= 0.0) 0L else math.round(x)
    }

  /** meet — the weakest link, min(x_i). A single 0 vetoes.
    *
    * O(n) per cell, O(n·width) per fold, no allocation, early exit at the chain
    * bottom, which absorbs. Takes no chain top: min of chain members is a chain
    * member, so closure holds without one.
    */
  private def meet(c: Array[Vector[Double]], n: Int, i: Int): Long = {
    var out = chainValue(c(0), i)
    var j   = 1
    while (j < n && out > 0L) {
      val v = chainValue(c(j), i)
      if (v < out) out = v
      j += 1
    }
    out
  }

  /** join — the envelope, max(x_i). Safety-preserving: the highest rung any
    * contribution asserts survives.
    *
    * O(n) per cell, no allocation, early exit at the chain top when one is
    * declared.
    *
    * Reads clamp to k when it is known, and that clamp is what makes the early
    * exit safe rather than merely fast. Without it a contribution above the
    * declared top would stop the scan at its own value and a larger one behind
    * it would never be seen — the result would depend on arrival order, which
    * the collection does not carry and the contract forbids. Out-of-chain
    * contributions are a corpus error either way; being wrong symmetrically is
    * the difference between a reproducible defect and a heisenbug.
    */
  private def join(c: Array[Vector[Double]], n: Int, i: Int, k: Long): Long = {
    val bounded = k != NoChainTop
    var out = chainValue(c(0), i)
    if (bounded && out > k) out = k
    var j = 1
    while (j < n && !(bounded && out >= k)) {
      var v = chainValue(c(j), i)
      if (bounded && v > k) v = k
      if (v > out) out = v
      j += 1
    }
    out
  }

  /** strong-disjunction, Łukasiewicz ⊕ — the truncated sum min(k, Σx).
    *
    * Evidence accumulation, saturating at k: several weak independent
    * indicators combine into a strong one, 1 ⊕ 1 ⊕ 1 = 3 on the chain {0..3}.
    * NOT idempotent, by design — x ⊕ x saturates, which is the point of it.
    *
    * O(n) per cell, single pass sum, early exit the moment k is reached: the
    * running total only grows, so once saturated nothing behind it can change
    * the answer.
    */
  private def strongDisjunction(c: Array[Vector[Double]], n: Int, i: Int, k: Long): Long = {
    var total = 0L
    var j     = 0
    while (j < n) {
      val v = chainValue(c(j), i)
      total += (if (v > k) k else v)
      if (total >= k) return k
      j += 1
    }
    total
  }

  /** strong-conjunction, Łukasiewicz ⊙ — max(0, Σx − k(n−1)).
    *
    * A strict simultaneous-onset threshold. It rises above 0 only on
    * near-unanimous high agreement, and one absolute disagreement extinguishes
    * it: [3,3,2] → 2 but [3,3,0] → 0 on the chain {0..3}. NOT idempotent, by
    * design. It is the De Morgan dual of ⊕ under ¬x = k − x, verified in the
    * reference implementation.
    *
    * O(n) per cell, single pass sum. The threshold k(n−1) is a function of n and
    * k alone and is computed once per fold, above, rather than per cell. Two
    * early exits: none downward, since the total only grows, but the result is
    * already fixed at 0 as soon as every remaining contribution asserting the
    * chain top could not clear the threshold.
    */
  private def strongConjunction(c: Array[Vector[Double]], n: Int, i: Int,
                                k: Long, threshold: Long): Long = {
    var total = 0L
    var j     = 0
    while (j < n) {
      val v = chainValue(c(j), i)
      total += (if (v > k) k else v)
      // Everything left can add at most k apiece.
      if (total + (n - 1 - j) * k <= threshold) return 0L
      j += 1
    }
    if (total > threshold) total - threshold else 0L
  }

  /** discrete-median — majority consensus, rejecting anomalous outliers.
    * [3,3,0,3] → 3, the transient dropout filtered.
    *
    * For odd n the middle element; for even n floor(median), which over
    * integers is exactly the LOWER of the two middle elements. So this is a
    * selection and never a division, and no float arithmetic enters the step —
    * a determinism property as much as an efficiency one, since an averaging
    * median would give three runtimes three roundings of x.5.
    *
    * O(n) expected per cell: a selection, not an O(n log n) sort, and it fills a
    * buffer allocated once for the whole fold. Takes no chain top — the result
    * is one of its inputs, so closure comes free.
    */
  private def discreteMedian(c: Array[Vector[Double]], n: Int, i: Int, buf: Array[Long]): Long = {
    var j = 0
    while (j < n) { buf(j) = chainValue(c(j), i); j += 1 }
    select(buf, n, (n - 1) / 2)
  }

  /** The `rank`-th smallest of `buf(0 until n)`, destructively.
    *
    * Hybrid because n is the number of Reality Events one machine completed in
    * one step: small in every corpus machine measured, and below the threshold
    * an insertion sort beats quickselect's partitioning outright.
    */
  private def select(buf: Array[Long], n: Int, rank: Int): Long =
    if (n <= InsertionSortMax) { insertionSort(buf, n); buf(rank) }
    else quickselect(buf, 0, n - 1, rank)

  private def insertionSort(a: Array[Long], n: Int): Unit = {
    var i = 1
    while (i < n) {
      val v = a(i)
      var j = i - 1
      while (j >= 0 && a(j) > v) { a(j + 1) = a(j); j -= 1 }
      a(j + 1) = v
      i += 1
    }
  }

  /** Hoare-partition quickselect, iterative so the recursion cannot deepen.
    *
    * The pivot is the middle element rather than a random one: the answer is
    * pivot-independent, but the *work* is not, and a runtime whose timing
    * depends on a seed is a runtime whose timing cannot be compared to another's.
    */
  private def quickselect(a: Array[Long], loIn: Int, hiIn: Int, rank: Int): Long = {
    var lo = loIn
    var hi = hiIn
    while (lo < hi) {
      val pivot = a(lo + (hi - lo) / 2)
      var i = lo
      var j = hi
      while (i <= j) {
        while (a(i) < pivot) i += 1
        while (a(j) > pivot) j -= 1
        if (i <= j) {
          val t = a(i); a(i) = a(j); a(j) = t
          i += 1; j -= 1
        }
      }
      if (rank <= j) hi = j
      else if (rank >= i) lo = i
      else return a(rank)
    }
    a(lo)
  }
}
