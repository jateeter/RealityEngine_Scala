package com.realityengine.perception.engine

import io.circe.Json

/** A source on an OSRE cell is folded with the OSRE value by the writing
  * machine's declared outputMergeTransformation, over [0..1]
  * (ARBITER_CONTRACT.md §4.4b, owner decision 2026-10-02) — the operator the
  * fold already applies to that machine's outputs. The PE meets LLM-provided
  * values in [0..1], so each operator is used in its multi-valued form with
  * chain top 1; a Boolean gate's first-order form would collapse them.
  */
object OsreFold {

  /** The declared operator applied to source value `s` and OSRE value `o`.
    * Unknown names fold as the default, `or`. */
  def apply(transformation: String, s: Double, o: Double): Double = transformation match {
    case "and" | "meet" | "discrete-median" => math.min(s, o)
    case "strong-disjunction"               => math.min(1.0, s + o)
    case "strong-conjunction"               => math.max(0.0, s + o - 1.0)
    case "xor"                              => math.max(math.min(s, 1.0 - o), math.min(1.0 - s, o))
    case "nor"                              => 1.0 - math.max(s, o)
    case "nand"                             => 1.0 - math.min(s, o)
    case _                                  => math.max(s, o) // or, join
  }

  /** Every cell of every mergeBatch output region in `step`, mapped to the
    * writing machine's operator. Where several machines' outputs cover a cell,
    * the first by machine NAME decides — ids are minted per runtime, so id
    * order would differ between runtimes. */
  def cells(step: Json, operator: String => Option[(String, String)]): Map[Int, String] = {
    val ops = step.hcursor.downField("mergeBatch").as[Vector[Json]].getOrElse(Vector.empty)
    val byCell = scala.collection.mutable.Map.empty[Int, (String, String)]
    for (op <- ops) {
      val c         = op.hcursor
      val machineId = c.get[String]("machineId").getOrElse("")
      val offset    = c.downField("region").get[Int]("offset").getOrElse(-1)
      val length    = c.downField("region").get[Int]("length").getOrElse(0)
      if (machineId.nonEmpty && offset >= 0) {
        val (name, transformation) = operator(machineId)
          .getOrElse((c.get[String]("machineName").getOrElse(machineId), "or"))
        for (cell <- offset until offset + length) {
          if (byCell.get(cell).forall { case (prior, _) => name < prior }) byCell(cell) = (name, transformation)
        }
      }
    }
    byCell.view.mapValues(_._2).toMap
  }
}
