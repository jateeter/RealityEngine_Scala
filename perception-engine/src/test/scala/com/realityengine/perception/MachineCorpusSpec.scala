package com.realityengine.perception

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec


/** Covers the PE-side resolution of the two merge-batch fields the machine
  * results do not carry — RealityEngine_Scala#35 — and the single source
  * builder both seeding paths go through — #36.
  *
  * The machine fixture is trimmed from the real corpus entry
  * (`machines/domains/data-center/DataCenterDCX001010Interconnect.json`) and
  * the expected governance object is the one C++ actually emitted for
  * `event-1` of hosted regression run 31263877721.  Hand-written expectations
  * would encode my reading of the contract; these encode the contract.
  */
class MachineCorpusSpec extends AnyWordSpec with Matchers {

  private val dataCenterMachine: Json = parse(
    """
    {
      "id": "machine-datacenterdcx001010interconnect",
      "name": "Data Center DCX-001-010 Interconnect",
      "perceptualMapping": { "input": { "offset": 14127, "length": 30 } },
      "metadata": {
        "governance": {
          "ownerTeam": "data-center-ops",
          "runbook": "https://runbooks.example.org/data-center/dcx-001-010",
          "escalationPolicy": "pagerduty:data-center-ops",
          "contact": {
            "primary": "data-center-ops-primary@example.org",
            "secondary": "platform-ops-primary@example.org"
          },
          "sla": { "ok": null, "info": null, "warning": 86400, "error": 3600 }
        },
        "triggerConfig": {
          "rules": [
            {
              "sequenceId": "dcx-001-010-domain-family-stable",
              "outputMatches": [0, 0, 0, 1],
              "ragStatusCode": "GREEN",
              "processStatus": "info",
              "description": "Data Center DCX-001-010 domain family stable at [14157:14161]"
            },
            {
              "sequenceId": "dcx-001-010-domain-family-review",
              "outputMatches": [1, 0, 0, 0],
              "ragStatusCode": "RED",
              "processStatus": "error",
              "description": "Data Center DCX-001-010 domain family review at [14157:14161]"
            }
          ]
        },
        "inputSequences": [
          { "name": "Interconnect Saturation Drift", "vectors": [[0.66, 0.63], [0.64, 0.61]] }
        ]
      }
    }
    """
  ).getOrElse(fail("fixture is not valid JSON"))

  private val machineId  = "machine-datacenterdcx001010interconnect"
  private val stableSeq  = "dcx-001-010-domain-family-stable"

  private val corpus = MachineCorpus.build(Vector(dataCenterMachine))

  private def fields(j: Json) = j.asObject.getOrElse(fail("not an object")).toMap

  "MachineCorpus.governance" should {

    "reproduce the contract C++ emits for a matching rule" in {
      val expected = parse(
        """
        {
          "contact": { "primary": "data-center-ops-primary@example.org",
                       "secondary": "platform-ops-primary@example.org" },
          "description": "Data Center DCX-001-010 domain family stable at [14157:14161]",
          "escalationPolicy": "pagerduty:data-center-ops",
          "hasMachineGovernance": true,
          "machineId": "machine-datacenterdcx001010interconnect",
          "machineName": "Data Center DCX-001-010 Interconnect",
          "ownerTeam": "data-center-ops",
          "processStatus": "info",
          "ragStatusCode": "GREEN",
          "runbook": "https://runbooks.example.org/data-center/dcx-001-010",
          "sequenceId": "dcx-001-010-domain-family-stable",
          "slaSeconds": null,
          "source": "rule-only"
        }
        """
      ).getOrElse(fail("expectation is not valid JSON"))

      val resolved = corpus.governance(machineId, stableSeq, Vector(0, 0, 0, 1))
      resolved.map(fields) shouldBe Some(fields(expected))
    }

    "not resolve when the sequence fired with values no rule matches" in {
      // Same sequence, different asserted output — C++ compares outputMatches
      // exactly, so a near miss is a miss.
      corpus.governance(machineId, stableSeq, Vector(1, 0, 0, 0)) shouldBe None
    }

    "not resolve for a sequence with no rule" in {
      corpus.governance(machineId, "dcx-001-010-domain-family-monitoring", Vector(0, 0, 0, 1)) shouldBe None
    }

    "not resolve for an unknown machine" in {
      corpus.governance("machine-absent", stableSeq, Vector(0, 0, 0, 1)) shouldBe None
    }

    "take slaSeconds from the machine table keyed by the rule's processStatus" in {
      // processStatus "error" → 3600 in the machine's sla table.
      val g = corpus.governance(machineId, "dcx-001-010-domain-family-review", Vector(1, 0, 0, 0))
        .getOrElse(fail("expected a resolved contract"))
      g.hcursor.get[Int]("slaSeconds").toOption shouldBe Some(3600)
    }

    "leave slaSeconds null when the machine table holds an explicit null" in {
      // "info": null must stay unset rather than collapse to 0.
      val g = corpus.governance(machineId, stableSeq, Vector(0, 0, 0, 1))
        .getOrElse(fail("expected a resolved contract"))
      g.hcursor.downField("slaSeconds").focus shouldBe Some(Json.Null)
    }

    "let a rule-level override win over the machine default, and relabel the source" in {
      val withOverride = dataCenterMachine.hcursor
        .downField("metadata").downField("triggerConfig").downField("rules")
        .withFocus(_.mapArray(rules =>
          rules.updated(0, rules(0).deepMerge(Json.obj(
            "governance" -> Json.obj(
              "ownerTeam"  -> Json.fromString("interconnect-oncall"),
              "slaSeconds" -> Json.fromInt(900),
            )
          )))
        )).top.getOrElse(fail("could not rebuild the fixture"))

      val g = MachineCorpus.build(Vector(withOverride))
        .governance(machineId, stableSeq, Vector(0, 0, 0, 1))
        .getOrElse(fail("expected a resolved contract"))

      g.hcursor.get[String]("ownerTeam").toOption  shouldBe Some("interconnect-oncall")
      g.hcursor.get[Int]("slaSeconds").toOption    shouldBe Some(900)
      // Machine defaults still show through for fields the rule did not set.
      g.hcursor.get[String]("runbook").toOption    shouldBe
        Some("https://runbooks.example.org/data-center/dcx-001-010")
      g.hcursor.get[String]("source").toOption     shouldBe Some("rule-with-override")
    }

    "fall back to machine-fallback with no machine governance block" in {
      val noGov = dataCenterMachine.hcursor.downField("metadata")
        .withFocus(_.mapObject(_.remove("governance"))).top
        .getOrElse(fail("could not rebuild the fixture"))

      val g = MachineCorpus.build(Vector(noGov))
        .governance(machineId, stableSeq, Vector(0, 0, 0, 1))
        .getOrElse(fail("expected a resolved contract"))

      g.hcursor.get[String]("source").toOption               shouldBe Some("machine-fallback")
      g.hcursor.get[Boolean]("hasMachineGovernance").toOption shouldBe Some(false)
      g.hcursor.get[String]("ownerTeam").toOption             shouldBe Some("unrouted")
      g.hcursor.downField("contact").focus                    shouldBe Some(Json.obj())
    }

    "omit description rather than null it when the rule carries none" in {
      val noDesc = dataCenterMachine.hcursor
        .downField("metadata").downField("triggerConfig").downField("rules")
        .withFocus(_.mapArray(rules =>
          rules.updated(0, rules(0).mapObject(_.remove("description")))
        )).top.getOrElse(fail("could not rebuild the fixture"))

      val g = MachineCorpus.build(Vector(noDesc))
        .governance(machineId, stableSeq, Vector(0, 0, 0, 1))
        .getOrElse(fail("expected a resolved contract"))

      g.asObject.map(_.keys.toVector.contains("description")) shouldBe Some(false)
    }
  }

  "MachineCorpus.provenance" should {

    // Shaped as `GET /api/machines` serves it — Machine.toJson carries
    // initialVectorIds on each sequence summary.  MachineSequenceJsonSpec in
    // the Reality Engine build asserts the producing end against the real
    // corpus, so the two together cover the whole chain.
    val signing = parse(
      """
      {
        "id": "machine-documentsigningworkflowmonitor",
        "name": "Document Signing Workflow Monitor",
        "sequences": [
          { "id": "signing-complete",            "name": "Signing Complete",
            "initialVectorIds": ["ds-complete"] },
          { "id": "signing-blocked-escalation",  "name": "Signing Blocked",
            "initialVectorIds": ["ds-active"] },
          { "id": "signing-multi",               "name": "Multiple initials",
            "initialVectorIds": ["ds-a", "ds-b"] }
        ]
      }
      """
    ).getOrElse(fail("fixture is not valid JSON"))

    val signingCorpus = MachineCorpus.build(Vector(signing))
    val signingId     = "machine-documentsigningworkflowmonitor"

    "return the fired sequence's initial reality event vector ids" in {
      // C++ emitted exactly ["ds-complete"] for signing-complete in run
      // 31263877721 — note it is not derivable from the sequenceId.
      signingCorpus.provenance(signingId, "signing-complete") shouldBe Vector("ds-complete")
      signingCorpus.provenance(signingId, "signing-blocked-escalation") shouldBe Vector("ds-active")
    }

    "carry every initial vector when a CES declares more than one" in {
      // A CES must have at least one initial event; it may have more.
      signingCorpus.provenance(signingId, "signing-multi") shouldBe Vector("ds-a", "ds-b")
    }

    "return empty rather than fail when the machine or sequence is unknown" in {
      // A missing audit trail must not stop the merge itself.
      signingCorpus.provenance(signingId, "no-such-sequence") shouldBe Vector.empty
      signingCorpus.provenance("machine-absent", "signing-complete") shouldBe Vector.empty
      corpus.provenance(machineId, stableSeq) shouldBe Vector.empty
    }
  }

  "MachineCorpus.testSourceFor" should {

    "build one source per machine, not one per input sequence" in {
      val twoSeqs = dataCenterMachine.hcursor.downField("metadata")
        .downField("inputSequences")
        .withFocus(_.mapArray(_ :+ Json.obj(
          "name"    -> Json.fromString("Thermal Excursion"),
          "vectors" -> Json.arr(Json.arr(Json.fromDouble(0.9).get, Json.fromDouble(0.9).get)),
        ))).top.getOrElse(fail("could not rebuild the fixture"))

      val src = MachineCorpus.testSourceFor(twoSeqs).getOrElse(fail("expected a source"))

      src.id            shouldBe s"test-$machineId"
      src.inputs        should have size 3   // 2 from the first sequence + 1 from the second
      src.sequenceName  shouldBe "2 sequences"
      src.sequenceMetadata.hcursor.downField("segments").as[Vector[Json]]
        .getOrElse(fail("expected segments")).map(_.hcursor.get[String]("name").toOption) shouldBe
        Vector(Some("Interconnect Saturation Drift"), Some("Thermal Excursion"))
    }

    "register inactive, because no corpus sequence opts in" in {
      // The corpus has no `active` property on an input sequence — the schema
      // does not define one and no entry sets it — so scenario stimulus stays
      // out of the shared reality vector until an operator activates it (#36).
      MachineCorpus.testSourceFor(dataCenterMachine).map(_.active) shouldBe Some(false)
    }

    "honour an explicit opt-in if a corpus ever declares one" in {
      val optedIn = dataCenterMachine.hcursor.downField("metadata")
        .downField("inputSequences")
        .withFocus(_.mapArray(seqs =>
          seqs.updated(0, seqs(0).deepMerge(Json.obj("active" -> Json.True)))
        )).top.getOrElse(fail("could not rebuild the fixture"))

      MachineCorpus.testSourceFor(optedIn).map(_.active) shouldBe Some(true)
    }

    "skip a machine with no input sequences" in {
      val noSeqs = dataCenterMachine.hcursor.downField("metadata")
        .withFocus(_.mapObject(_.remove("inputSequences"))).top
        .getOrElse(fail("could not rebuild the fixture"))

      MachineCorpus.testSourceFor(noSeqs) shouldBe None
    }

    "skip a machine with no input region" in {
      val noMapping = dataCenterMachine.mapObject(_.remove("perceptualMapping"))
      MachineCorpus.testSourceFor(noMapping) shouldBe None
    }
  }
}
