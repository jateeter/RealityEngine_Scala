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

  def mergeBatch(machineResults: Json): Vector[Json] =
    mergeRecords(machineResults).sortBy(_.machineId).toVector.map { rec =>
      Json.obj(
        "machineId" -> Json.fromString(rec.machineId),
        "region" -> Json.obj(
          "offset" -> Json.fromInt(rec.outputOffset),
          "length" -> Json.fromInt(rec.outputLength),
        ),
        "vector" -> Json.arr(rec.outputVector.map(Json.fromDoubleOrNull): _*),
      )
    }
}
