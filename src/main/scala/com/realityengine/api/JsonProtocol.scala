package com.realityengine.api

import com.realityengine.models._
import com.realityengine.engine._
import io.circe.{Encoder, Json}
import io.circe.syntax._

/**
 * JsonProtocol — circe encoders for all domain types used in API responses.
 * Import `JsonProtocol._` to bring all encoders into scope.
 */
object JsonProtocol {

  implicit val encodeOutputVector: Encoder[OutputVector] = Encoder.instance { ov =>
    Json.obj(
      "id"        -> Json.fromString(ov.id),
      "vector"    -> ov.vector.asJson,
      "metadata"  -> ov.metadata.asJson,
      "timestamp" -> Json.fromLong(ov.timestamp)
    )
  }

  implicit val encodeSequenceResult: Encoder[SequenceResult] = Encoder.instance { sr =>
    Json.obj(
      "matchedVectors"   -> sr.matchedVectors.asJson,
      "activatedVectors" -> sr.activatedVectors.asJson,
      "assertedOutputs"  -> sr.assertedOutputs.asJson
    )
  }

  implicit val encodeArbiterMetadata: Encoder[ArbiterMetadata] = Encoder.instance { am =>
    Json.obj(
      "rule"                -> Json.fromString(am.rule),
      "totalInputs"         -> Json.fromInt(am.totalInputs),
      "sequencesWithOutput" -> Json.fromInt(am.sequencesWithOutput),
      "shouldOutput"        -> Json.fromBoolean(am.shouldOutput)
    )
  }

  implicit val encodeMachineTransitionResult: Encoder[MachineTransitionResult] = Encoder.instance { r =>
    Json.obj(
      "inputVector"     -> r.inputVector.asJson,
      "timestamp"       -> Json.fromLong(r.timestamp),
      "sequenceResults" -> Json.fromFields(r.sequenceResults.view.mapValues(_.asJson).toSeq),
      "machineOutput"   -> r.machineOutput.asJson,
      "arbiterMetadata" -> r.arbiterMetadata.asJson
    )
  }

  implicit val encodeTransitionResult: Encoder[TransitionResult] = Encoder.instance { r =>
    Json.obj(
      "inputVector"  -> r.inputVector.asJson,
      "timestamp"    -> Json.fromLong(r.timestamp),
      "totalOutputs" -> r.totalOutputs.asJson
    )
  }

  implicit val encodeRegionMapping: Encoder[RegionMapping] = Encoder.instance { rm =>
    Json.obj("offset" -> Json.fromInt(rm.offset), "length" -> Json.fromInt(rm.length))
  }

  implicit val encodeMachineStepResult: Encoder[MachineStepResult] = Encoder.instance { mr =>
    Json.obj(
      "machineId"        -> Json.fromString(mr.machineId),
      "machineName"      -> Json.fromString(mr.machineName),
      "inputVector"      -> mr.inputVector.asJson,
      "outputVector"     -> mr.outputVector.asJson,
      "inputRegion"      -> mr.inputRegion.asJson,
      "outputRegion"     -> mr.outputRegion.asJson,
      "transitionResult" -> mr.transitionResult.asJson
    )
  }

  implicit val encodeActiveRegion: Encoder[ActiveRegion] = Encoder.instance { ar =>
    Json.obj(
      "offset"    -> Json.fromInt(ar.offset),
      "length"    -> Json.fromInt(ar.length),
      "machineId" -> Json.fromString(ar.machineId),
      "type"      -> Json.fromString(ar.`type`)
    )
  }

  implicit val encodeSimulationStep: Encoder[SimulationStep] = Encoder.instance { ss =>
    Json.obj(
      "stepNumber"      -> Json.fromInt(ss.stepNumber),
      "timestamp"       -> Json.fromLong(ss.timestamp),
      "perceptualSpace" -> ss.perceptualSpace.asJson,
      "machineResults"  -> Json.fromFields(ss.machineResults.view.mapValues(_.asJson).toSeq),
      "activeRegions"   -> ss.activeRegions.asJson
    )
  }

  implicit val encodeMachineCheckpoint: Encoder[MachineCheckpoint] = Encoder.instance { cp =>
    Json.obj(
      "id"          -> Json.fromString(cp.id),
      "machineId"   -> Json.fromString(cp.machineId),
      "machineName" -> Json.fromString(cp.machineName),
      "label"       -> cp.label.asJson,
      "timestamp"   -> Json.fromLong(cp.timestamp)
    )
  }
}
