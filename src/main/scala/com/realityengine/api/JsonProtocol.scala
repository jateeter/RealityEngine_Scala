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
      "matchedEvents"   -> sr.matchedVectors.asJson,
      "activatedEvents" -> sr.activatedVectors.asJson,
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
      "inputEvent"     -> r.inputVector.asJson,
      "timestamp"       -> Json.fromLong(r.timestamp),
      "sequenceResults" -> Json.fromFields(r.sequenceResults.view.mapValues(_.asJson).toSeq),
      "machineOutput"   -> r.machineOutput.asJson,
      "arbiterMetadata" -> r.arbiterMetadata.asJson
    )
  }

  implicit val encodeTransitionResult: Encoder[TransitionResult] = Encoder.instance { r =>
    Json.obj(
      "inputEvent"  -> r.inputVector.asJson,
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
      "inputEvent"      -> mr.inputVector.asJson,
      "outputVector"     -> mr.outputVector.asJson,
      "mergedOutputVector"        -> mr.mergedOutputVector.asJson,
      "outputMergeTransformation" -> mr.outputMergeTransformation.asJson,
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

  // Key order follows C++'s to_json(SimulationStep) mergeBatch object, which
  // SURFACE_SPEC.md governs. Ordering is part of the contract, not a rendering
  // preference — an observer must see the same object however it was produced.
  //
  // `sequenceIds` (array) replaces `sequenceId` (string) and `outputIndex` is
  // gone (FOLD_PLACEMENT.md §7): one operation now covers the machine, so there
  // is no single firing sequence to name and no index to name it within. RE and
  // PE move together — a PE reading `sequenceId` from a new RE gets nothing, and
  // the reverse is worse.
  //
  // Key order follows C++'s to_json(PagingDecision) exactly, including
  // `description` sitting between `contact` and `source`. Ordering is part of
  // the contract: an observer must see the same object however it was produced.
  //
  // Empty-valued fields are nulled rather than dropped — C++ carries
  // empty-string sentinels and encodes them as null — EXCEPT `description`,
  // which C++ omits entirely when the rule carries none, so a consumer can tell
  // "the rule said nothing" from "the rule said nothing useful".
  implicit val encodePagingDecision: Encoder[PagingDecision] = Encoder.instance { d =>
    val contact = Json.fromFields(
      d.contactPrimary.map("primary" -> Json.fromString(_)).toList ++
      d.contactSecondary.map("secondary" -> Json.fromString(_)).toList)
    val base = List(
      "machineId"        -> Json.fromString(d.machineId),
      "machineName"      -> Json.fromString(d.machineName),
      "sequenceId"       -> Json.fromString(d.sequenceId),
      "ragStatusCode"    -> d.ragStatusCode.fold(Json.Null)(Json.fromString),
      "processStatus"    -> d.processStatus.fold(Json.Null)(Json.fromString),
      "ownerTeam"        -> Json.fromString(d.ownerTeam),
      "slaSeconds"       -> d.slaSeconds.fold(Json.Null)(Json.fromInt),
      "runbook"          -> d.runbook.fold(Json.Null)(Json.fromString),
      "escalationPolicy" -> d.escalationPolicy.fold(Json.Null)(Json.fromString),
      "contact"          -> contact)
    val withDescription =
      base ++ d.description.map("description" -> Json.fromString(_)).toList
    Json.fromFields(withDescription ++ List(
      "source"               -> Json.fromString(d.source),
      "hasMachineGovernance" -> Json.fromBoolean(d.hasMachineGovernance)))
  }

  // `replacedBy` is omitted when the corpus names no successor, like C++.
  implicit val encodeDeprecationMark: Encoder[DeprecationMark] = Encoder.instance { m =>
    val base = List(
      "since"   -> Json.fromString(m.since),
      "ageDays" -> Json.fromLong(m.ageDays))
    Json.fromFields(base ++ m.replacedBy.filter(_.nonEmpty).map("replacedBy" -> Json.fromString(_)).toList)
  }

  // `governance` and `deprecation` are conditional, like C++: present only when
  // a trigger rule matched, and only when a contributor is deprecated. This
  // runtime emitted neither key at all before — a consumer reading
  // `governance.ragStatusCode` got an object from C++ and LSP and nothing here
  // (FOLD_PLACEMENT.md A2).
  implicit val encodeMergeOperation: Encoder[MergeOperation] = Encoder.instance { op =>
    val base = Json.obj(
      "region"      -> op.region.asJson,
      "machineId"   -> Json.fromString(op.machineId),
      "sequenceIds" -> op.sequenceIds.asJson,
      "values"      -> op.values.asJson,
      "provenance"  -> op.provenance.asJson
    )
    val withGov = op.governance.fold(base)(g => base.mapObject(_.add("governance", g.asJson)))
    op.deprecation.fold(withGov)(d => withGov.mapObject(_.add("deprecation", d.asJson)))
  }

  implicit val encodeTrajectoryCell: Encoder[TrajectoryCell] = Encoder.instance { c =>
    Json.obj("index" -> Json.fromInt(c.index), "value" -> Json.fromDoubleOrNull(c.value))
  }

  implicit val encodeTrajectoryEntry: Encoder[TrajectoryEntry] = Encoder.instance { e =>
    Json.obj(
      "stepNumber" -> Json.fromInt(e.stepNumber),
      "length"     -> Json.fromInt(e.length),
      "nonZero"    -> e.nonZero.asJson
    )
  }

  implicit val encodeEventBusWrite: Encoder[EventBusWrite] = Encoder.instance { w =>
    Json.obj(
      "producerMachineId"   -> Json.fromString(w.producerMachineId),
      "producerSequenceId"  -> Json.fromString(w.producerSequenceId),
      "subscriberMachineId" -> Json.fromString(w.subscriberMachineId),
      "bitOffset"           -> Json.fromInt(w.bitOffset),
      "value"               -> Json.fromDoubleOrNull(w.value)
    )
  }

  implicit val encodeSimulationStep: Encoder[SimulationStep] = Encoder.instance { ss =>
    Json.obj(
      "stepNumber"      -> Json.fromInt(ss.stepNumber),
      "timestamp"       -> Json.fromLong(ss.timestamp),
      "perceptualSpace" -> ss.perceptualSpace.asJson,
      "machineResults"  -> Json.fromFields(ss.machineResults.view.mapValues(_.asJson).toSeq),
      "activeRegions"   -> ss.activeRegions.asJson,
      // All three required of every runtime by SURFACE_SPEC.md and previously
      // absent here, which made this runtime's step a different shape from
      // C++'s and LSP's for an identical computation.
      "mergeBatch"      -> ss.mergeBatch.asJson,
      "eventBus"        -> ss.eventBus.asJson,
      "perceptualSpaceIsDebugProjection" -> Json.True
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
