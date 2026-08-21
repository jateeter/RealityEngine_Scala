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
  * Defined in RealityEngine_Machines `semantics/ontology/re-core.ttl`; that
  * ontology is the definition and this is an implementation of it.
  */
object OutputMergeTransformation {

  val Or   = "or"
  val And  = "and"
  val Xor  = "xor"
  val Nor  = "nor"
  val Nand = "nand"

  val All: Set[String] = Set(Or, And, Xor, Nor, Nand)

  /** Normalise a declared name. Anything absent or unrecognised means "or". */
  def normalise(name: Option[String]): String =
    name.map(_.trim.toLowerCase).filter(All.contains).getOrElse(Or)

  /** Fold a machine's collection of potential outputs into one.
    *
    * An n-input gate applied to the whole collection at once, not a chain of
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
    * `None` for an empty collection: a machine that completed no Reality Event
    * presents no output, which is not the same as presenting a vector of zeros.
    *
    * CONTESTED, not settled: it has been asserted that any transformation added
    * later must likewise be a function of `k` and `n` alone, i.e. symmetric in
    * its inputs. That generalisation is disputed. What is established is
    * narrower — these five gates are each symmetric, verified by exhaustion in
    * RealityEngine_Machines `tests/contracts/owl_semantics_test.py`.
    */
  def fold(outputs: Seq[Vector[Double]], transformation: String): Option[Vector[Double]] = {
    if (outputs.isEmpty) return None

    // Widest contributor wins the width; shorter ones contribute 0 beyond their
    // end. Every potential output of one machine comes from the same output
    // region, so they agree in practice — this only keeps a malformed corpus
    // from truncating a peer's contribution silently.
    val width = outputs.map(_.length).max
    val n     = outputs.length
    val name  = normalise(Some(transformation))

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
}
