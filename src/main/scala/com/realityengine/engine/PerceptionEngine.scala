package com.realityengine.engine

import com.realityengine.models._

/**
 * PerceptionEngine — resolves the universal input space to machine-specific event vectors.
 *
 * Flow:
 *   Universal Input Space → per-machine extraction via PerceptualMapping
 *   → Machine processes its specific view of reality
 */
class PerceptionEngine(val universalDimension: Int = sys.env.getOrElse("VECTOR_DIMENSION", "768").toIntOption.getOrElse(768)) {
  private val perceptualSpace = new PerceptualSpace(universalDimension)

  // ── Core resolution ───────────────────────────────────────────────────────

  def resolveInputEventVector(
    universalInputSpace: Vector[Double],
    perceptualMapping:   PerceptualMapping
  ): Vector[Double] = {
    require(universalInputSpace.length == universalDimension,
      s"Universal input space must be $universalDimension bytes, got ${universalInputSpace.length}")
    val RegionMapping(offset, length) = perceptualMapping.input
    universalInputSpace.slice(offset, offset + length)
  }

  def resolveInputEventVectorForMachine(
    universalInputSpace: Vector[Double],
    machine:             Machine
  ): Vector[Double] = {
    val mapping = machine.perceptualMapping.getOrElse(
      throw new IllegalStateException(
        s"Machine ${machine.name} does not have a perceptual mapping configured"))
    resolveInputEventVector(universalInputSpace, mapping)
  }

  /** Batch resolve for multiple machines from the same snapshot (input-atomic). */
  def resolveInputsForMachines(
    universalInputSpace: Vector[Double],
    machines:            scala.collection.Map[String, Machine]
  ): Map[String, Vector[Double]] = {
    require(universalInputSpace.length == universalDimension,
      s"Universal input space must be $universalDimension bytes, got ${universalInputSpace.length}")

    machines.iterator.flatMap { case (machineId, machine) =>
      machine.perceptualMapping match {
        case Some(mapping) =>
          try {
            val RegionMapping(offset, length) = mapping.input
            Some(machineId -> universalInputSpace.slice(offset, offset + length))
          } catch { case e: Exception =>
            System.err.println(s"Failed to resolve input for machine $machineId: ${e.getMessage}")
            None
          }
        case None =>
          System.err.println(s"Machine $machineId (${machine.name}) has no perceptual mapping, skipping")
          None
      }
    }.toMap
  }

  def mergeOutputIntoPerceptualSpace(outputVector: Vector[Double], mapping: PerceptualMapping): Unit =
    perceptualSpace.mergeMachineOutput(outputVector, mapping)

  def validateMapping(mapping: PerceptualMapping): (Boolean, List[String]) =
    PerceptualSpace.validateMapping(mapping, universalDimension)

  def getPerceptualSpace: PerceptualSpace = perceptualSpace
  def getUniversalDimension: Int         = universalDimension

  def reset(): Unit = perceptualSpace.reset()

  // ── Diagnostics ───────────────────────────────────────────────────────────

  def getDiagnosticMapping(
    universalInputSpace: Vector[Double],
    machines:            scala.collection.Map[String, Machine]
  ): io.circe.Json = {
    import io.circe.Json
    import io.circe.syntax._

    val nonZeroValues = universalInputSpace.zipWithIndex.collect {
      case (v, i) if v != 0.0 => Json.obj("index" -> Json.fromInt(i), "value" -> Json.fromDoubleOrNull(v))
    }

    val resolvedInputs = resolveInputsForMachines(universalInputSpace, machines)

    val machineMappings = machines.values.flatMap { machine =>
      machine.perceptualMapping.map { mapping =>
        val resolved       = resolvedInputs.getOrElse(machine.id, Vector.empty)
        val universalIdxs  = (mapping.input.offset until (mapping.input.offset + mapping.input.length)).toVector
        Json.obj(
          "machineId"    -> Json.fromString(machine.id),
          "machineName"  -> Json.fromString(machine.name),
          "inputMapping" -> Json.obj(
            "offset" -> Json.fromInt(mapping.input.offset),
            "length" -> Json.fromInt(mapping.input.length)
          ),
          "resolvedInput"    -> resolved.asJson,
          "universalIndices" -> universalIdxs.asJson
        )
      }
    }.toList

    Json.obj(
      "universalSpace"  -> Json.obj(
        "dimension"     -> Json.fromInt(universalDimension),
        "nonZeroValues" -> Json.arr(nonZeroValues: _*)
      ),
      "machineMappings" -> Json.arr(machineMappings: _*)
    )
  }
}
