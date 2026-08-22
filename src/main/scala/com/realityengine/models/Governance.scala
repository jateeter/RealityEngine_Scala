package com.realityengine.models

import io.circe.Json

/** The paging contract a fired output resolves to — who is told, how loudly, and
  * on what clock.
  *
  * Carried on the `MergeOperation` so a listener reads the contract from the
  * same record as the asserted values, rather than re-deriving it from the CES
  * JSON and possibly disagreeing. The CES JSON remains the sole source of truth;
  * this is a resolution of it, not a second copy.
  *
  * This runtime had no `PagingDecision` in its step path at all — governance was
  * resolved only by the `/api/governance/route` handler, and `mergeBatch`
  * emitted no `governance` key, so a consumer reading `governance.ragStatusCode`
  * got nothing from Scala while getting a full object from C++ and LSP
  * (FOLD_PLACEMENT.md A2). Field-for-field with `PagingDecision` in
  * `RealityEngine_CPP/include/reality/reality.hpp`, which is the canonical
  * definition; C++ uses empty-string sentinels where this uses `Option`, and the
  * two encode identically.
  *
  * Deliberately NOT the shape `api/Routes.scala` emits: that handler synthesises
  * a `description` of its own ("Governance resolved for machine …") instead of
  * reporting the rule's. It serves a different consumer and is left alone.
  */
case class PagingDecision(
  machineId:            String,
  machineName:          String,
  sequenceId:           String,
  ragStatusCode:        Option[String],
  processStatus:        Option[String],
  ownerTeam:            String,
  slaSeconds:           Option[Int],
  runbook:              Option[String],
  escalationPolicy:     Option[String],
  contactPrimary:       Option[String],
  contactSecondary:     Option[String],
  description:          Option[String],
  source:               String,
  hasMachineGovernance: Boolean,
)

/** Attached to a merge operation when a contributing sequence carries
  * `deprecatedAt`. Listeners and dashboards surface stale CESs from this rather
  * than re-deriving it from the corpus JSON. Mirrors C++'s `DeprecationMark`.
  */
case class DeprecationMark(
  since:      String,
  replacedBy: Option[String],
  ageDays:    Long,
)

object Governance {

  /** Resolve the paging contract for ONE fired output of ONE sequence, or None
    * when the machine declares no rule matching both.
    *
    * Mirrors `resolve_governance` in `RealityEngine_CPP/src/reality.cpp`, which
    * is the canonical definition (RealityEngine_CI#91): match a
    * `triggerConfig.rules` entry on `sequenceId` AND an exact `outputMatches`
    * comparison against the asserted values, then layer rule-level governance
    * over machine-level defaults.
    *
    * The match is exact rather than epsilon-tolerant, matching C++'s
    * `values_match`. Paging is opt-in per (sequenceId, values), and a tolerance
    * would silently widen which outputs page someone.
    *
    * `values` must be the sequence's OWN asserted output, never a folded vector
    * — see `Arbiter.joinGovernance`.
    */
  def resolve(machine: Machine, sequenceId: String, values: Vector[Double]): Option[PagingDecision] = {
    val rules = machine.metadata.get("triggerConfig")
      .flatMap(_.hcursor.downField("rules").as[Vector[Json]].toOption)
      .getOrElse(Vector.empty)

    rules.find { rule =>
      val c = rule.hcursor
      c.get[String]("sequenceId").toOption.contains(sequenceId) &&
        c.downField("outputMatches").as[Vector[Double]].toOption.contains(values)
    }.map { rule =>
      val rc         = rule.hcursor
      val machineGov = machine.metadata.get("governance").filter(_.isObject)
      val ruleGov    = rc.downField("governance").focus.filter(_.isObject)

      // Precedence throughout: rule override → machine default → absent.
      def pick(field: String): Option[String] =
        ruleGov.flatMap(_.hcursor.get[String](field).toOption)
          .orElse(machineGov.flatMap(_.hcursor.get[String](field).toOption))
          .filter(_.nonEmpty)

      val processStatus = rc.get[String]("processStatus").toOption.filter(_.nonEmpty)

      // The rule's own value wins; otherwise the machine's per-status table keyed
      // by THIS rule's processStatus. Corpus tables carry explicit nulls for
      // statuses with no SLA, which must stay unset rather than become 0.
      val slaSeconds = ruleGov.flatMap(_.hcursor.get[Int]("slaSeconds").toOption)
        .orElse(processStatus.flatMap { status =>
          machineGov.flatMap(_.hcursor.downField("sla").get[Int](status).toOption)
        })

      def contact(field: String): Option[String] =
        ruleGov.flatMap(_.hcursor.downField("contact").get[String](field).toOption)
          .orElse(machineGov.flatMap(_.hcursor.downField("contact").get[String](field).toOption))
          .filter(_.nonEmpty)

      PagingDecision(
        machineId            = machine.id,
        machineName          = machine.name,
        sequenceId           = sequenceId,
        ragStatusCode        = rc.get[String]("ragStatusCode").toOption.filter(_.nonEmpty),
        processStatus        = processStatus,
        ownerTeam            = pick("ownerTeam").getOrElse("unrouted"),
        slaSeconds           = slaSeconds,
        runbook              = pick("runbook"),
        escalationPolicy     = pick("escalationPolicy"),
        contactPrimary       = contact("primary"),
        contactSecondary     = contact("secondary"),
        description          = rc.get[String]("description").toOption.filter(_.nonEmpty),
        // Matches C++'s labelling exactly, including the case where a rule
        // matched but carries no governance override of its own.
        source               = if (ruleGov.isDefined) "rule-with-override"
                               else if (machineGov.isDefined) "rule-only"
                               else "machine-fallback",
        hasMachineGovernance = machineGov.isDefined,
      )
    }
  }
}
