package com.realityengine.perception

import io.circe.Json
import scala.collection.mutable

// VectorAggregator — PE machine output aggregator
//
// Merges gated machine CES output vectors from RE's SimulationStep.machineResults
// into the base perceptual space vector to produce the next InputSpaceVector.
//
// Gating:      only machines whose transitionResult.arbiterMetadata.shouldOutput
//              is true contribute to the merge.
// Merge order: deterministic — records sorted by machineId before writing.
//
// This is a thin, stateless object so the aggregation restriction (all machine
// outputs must be present before the next input vector is assembled) can be
// relaxed in the future without changing call sites.

object VectorAggregator {

  private case class MergeRecord(
    machineId:    String,
    outputOffset: Int,
    outputLength: Int,
    outputVector: Vector[Double]
  )

  // Merge gated machine CES output vectors into a copy of `baseVector` and
  // return the merged nextInputSpaceVector.  `machineResults` is the
  // `machineResults` field from RE's SimulationStep JSON response.
  private def mergeRecords(machineResults: Json): List[MergeRecord] = {
    machineResults.asObject.toList.flatMap(_.toList).flatMap { case (machineId, result) =>
      val shouldOutput = result.hcursor
        .downField("transitionResult")
        .downField("arbiterMetadata")
        .get[Boolean]("shouldOutput")
        .getOrElse(false)

      if (!shouldOutput) None
      else for {
        offset <- result.hcursor.downField("outputRegion").get[Int]("offset").toOption
        length <- result.hcursor.downField("outputRegion").get[Int]("length").toOption
        if length > 0
        vec    <- result.hcursor.downField("outputVector").as[Vector[Double]].toOption
        if vec.nonEmpty
      } yield MergeRecord(machineId, offset, length, vec)
    }
  }

  def aggregate(baseVector: Vector[Double], machineResults: Json): Vector[Double] = {
    val records = mergeRecords(machineResults)

    if (records.isEmpty) return baseVector

    // Deterministic merge order — sort by machineId
    val sorted = records.sortBy(_.machineId)

    val buf = mutable.ArrayBuffer[Double](baseVector: _*)
    for (rec <- sorted) {
      val writeLen = math.min(rec.outputVector.length, rec.outputLength)
      val needed   = rec.outputOffset + writeLen
      if (needed > buf.length)
        buf.appendAll(Array.fill(needed - buf.length)(0.0))
      for (i <- 0 until writeLen)
        buf(rec.outputOffset + i) = rec.outputVector(i)
    }
    buf.toVector
  }

  /** One operation per asserted output, which is the canonical merge unit.
    *
    * C++ is the definition here (RealityEngine_CI#91): when the arbiter says
    * shouldOutput, it emits one entry per assertedOutput of each sequence,
    * carrying the sequenceId and the index within that sequence. This emitted
    * one entry per *machine* instead, so it lost which sequence fired — and
    * sequenceId is exactly what the cross-runtime parity check compares, so
    * every entry read as a mismatch against C++ and LSP.
    */
  private case class MergeOp(
    machineId:    String,
    sequenceId:   String,
    outputIndex:  Int,
    outputOffset: Int,
    outputLength: Int,
    values:       Vector[Double],
  )

  private def mergeOps(machineResults: Json): List[MergeOp] =
    machineResults.asObject.toList.flatMap(_.toList).flatMap { case (machineId, result) =>
      val cursor       = result.hcursor
      val transition   = cursor.downField("transitionResult")
      val shouldOutput = transition.downField("arbiterMetadata").get[Boolean]("shouldOutput").getOrElse(false)

      val region = for {
        offset <- cursor.downField("outputRegion").get[Int]("offset").toOption
        length <- cursor.downField("outputRegion").get[Int]("length").toOption
        if length > 0
      } yield (offset, length)

      (shouldOutput, region) match {
        case (true, Some((offset, length))) =>
          val sequences = transition.downField("sequenceResults").focus
            .flatMap(_.asObject).map(_.toList).getOrElse(Nil)
          sequences.flatMap { case (sequenceId, sequenceResult) =>
            sequenceResult.hcursor.downField("assertedOutputs").focus
              .flatMap(_.asArray).getOrElse(Vector.empty)
              .zipWithIndex
              .toList
              .flatMap { case (asserted, index) =>
                asserted.hcursor.get[Vector[Double]]("vector").toOption
                  .filter(_.nonEmpty)
                  .map(MergeOp(machineId, sequenceId, index, offset, length, _))
              }
          }
        case _ => Nil
      }
    }

  def mergeBatch(machineResults: Json): Vector[Json] =
    // Canonical merge ordering — (machineId, sequenceId, outputIndex), the
    // same triple C++ sorts on, so both runtimes emit the same sequence for
    // the same input.
    mergeOps(machineResults)
      .sortBy(op => (op.machineId, op.sequenceId, op.outputIndex))
      .toVector
      .map { op =>
        Json.obj(
          "machineId"   -> Json.fromString(op.machineId),
          "sequenceId"  -> Json.fromString(op.sequenceId),
          "outputIndex" -> Json.fromInt(op.outputIndex),
          "region" -> Json.obj(
            "offset" -> Json.fromInt(op.outputOffset),
            "length" -> Json.fromInt(op.outputLength),
          ),
          // "values", not "vector" — C++ and LSP both name it values, and the
          // C++ PE's trigger dispatch reads op.at("values").
          "values" -> Json.arr(op.values.map(Json.fromDoubleOrNull): _*),
        )
      }
}
