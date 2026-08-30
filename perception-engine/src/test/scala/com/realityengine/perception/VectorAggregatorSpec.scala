package com.realityengine.perception

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Covers the mergeBatch shape — RealityEngine_Scala#33.
  *
  * The payload below is trimmed from a real Reality Engine response captured
  * by the hosted regression suite (run 31229547443), keeping only the fields
  * VectorAggregator reads. Hand-written JSON would encode my idea of the
  * contract; this encodes the contract.
  */
class VectorAggregatorSpec extends AnyWordSpec with Matchers {

  private val machineResults: Json = parse(
    """
    {
      "machine-datacenterdcx001010interconnect": {
        "outputRegion": { "length": 4, "offset": 14157 },
        "outputVector": [0, 0, 0, 1],
        "mergedOutputVector": [0, 0, 0, 1],
        "transitionResult": {
          "arbiterMetadata": { "shouldOutput": true },
          "sequenceResults": {
            "dcx-001-010-domain-family-monitoring":   { "assertedOutputs": [] },
            "dcx-001-010-domain-family-optimization": { "assertedOutputs": [] },
            "dcx-001-010-domain-family-review":       { "assertedOutputs": [] },
            "dcx-001-010-domain-family-stable":       { "assertedOutputs": [ { "vector": [0, 0, 0, 1] } ] }
          }
        }
      },
      "machine-documentsigningworkflowmonitor": {
        "outputRegion": { "length": 4, "offset": 960 },
        "outputVector": [0, 0, 0, 1],
        "mergedOutputVector": [0, 0, 0, 1],
        "transitionResult": {
          "arbiterMetadata": { "shouldOutput": true },
          "sequenceResults": {
            "signing-complete": { "matchedVectors": [ "ds-complete" ], "assertedOutputs": [ { "vector": [0, 0, 0, 1] } ] }
          }
        }
      },
      "machine-notfiring": {
        "outputRegion": { "length": 2, "offset": 10 },
        "outputVector": [1, 0],
        "transitionResult": {
          "arbiterMetadata": { "shouldOutput": false },
          "sequenceResults": {
            "some-sequence": { "assertedOutputs": [ { "vector": [1, 0] } ] }
          }
        }
      }
    }
    """
  ).getOrElse(fail("fixture is not valid JSON"))

  private def batch = VectorAggregator.mergeBatch(machineResults)

  "VectorAggregator.mergeBatch" should {

    "emit one entry per machine per output region" in {
      // FOLD_PLACEMENT.md §1. It emitted one entry per asserted output while the
      // fold sat beside the arbitration path; now the machine presents a single
      // folded output and this reports a single operation for it.
      batch should have size 2
      batch.flatMap(_.hcursor.get[String]("machineId").toOption) shouldBe Vector(
        "machine-datacenterdcx001010interconnect",
        "machine-documentsigningworkflowmonitor",
      )
    }

    "carry the set of sequences that contributed" in {
      // A folded contribution has no single sequenceId, and the scalar is what
      // the cross-runtime parity check used to key on. The evidence for the
      // value is now the set of CESs that completed — sorted and deduplicated.
      batch.flatMap(_.hcursor.get[Vector[String]]("sequenceIds").toOption) shouldBe Vector(
        Vector("dcx-001-010-domain-family-stable"),
        Vector("signing-complete"),
      )
      // The scalar is gone, not merely supplemented — a consumer still reading
      // it must fail loudly rather than silently read the first of a set.
      batch.head.asObject.map(_.contains("sequenceId")) shouldBe Some(false)
    }

    "match the C++ entry shape" in {
      // provenance is unconditional in C++; governance only appears when a
      // trigger rule matched, and no corpus is wired in here. `outputIndex` is
      // gone with the scalar sequenceId — it indexed within one sequence's
      // asserted outputs and is meaningless once one operation covers the
      // machine (§1).
      batch.head.asObject.map(_.keys.toSet) shouldBe
        Some(Set("machineId", "sequenceIds", "region", "values", "provenance"))
    }

    "present the folded value, not one member of the collection" in {
      // `outputVector` is one arbitrarily chosen member and which member it was
      // differed per runtime (RealityEngine_CI#154). Absent a fold, there is no
      // operation at all — see the refusal case below.
      val refused = parse(
        """
        {
          "m": {
            "outputRegion": { "length": 2, "offset": 4 },
            "outputVector": [1, 0],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": {
                "s": { "assertedOutputs": [ { "vector": [1, 0] } ] }
              }
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))
      // No mergedOutputVector: the machine completed a Reality Event but its
      // fold refused (§2), so it contributes nothing rather than a zero vector
      // or a fallback to the arbiter's pick.
      VectorAggregator.mergeBatch(refused) shouldBe empty
    }

    "name the payload values, as C++ and LSP do" in {
      // The C++ PE's trigger dispatch reads op.at("values"); this emitted
      // "vector".
      batch.head.hcursor.get[Vector[Double]]("values") shouldBe Right(Vector(0, 0, 0, 1))
      batch.head.asObject.map(_.contains("vector")) shouldBe Some(false)
    }

    "carry the machine's output region" in {
      batch.head.hcursor.downField("region").get[Int]("offset") shouldBe Right(14157)
      batch.head.hcursor.downField("region").get[Int]("length") shouldBe Right(4)
    }

    "skip machines the arbiter did not gate for output" in {
      // machine-notfiring has an assertedOutput but shouldOutput is false.
      batch.flatMap(_.hcursor.get[String]("machineId").toOption) should not contain "machine-notfiring"
    }

    "collapse multiple asserted outputs of one sequence into one operation" in {
      // Two completed Reality Events on one CES used to be two entries indexed
      // 0 and 1. They are one machine output now, and the sequence appears once
      // in the set rather than twice — `sequenceIds` is deduplicated.
      val twoOutputs = parse(
        """
        {
          "m": {
            "outputRegion": { "length": 2, "offset": 4 },
            "mergedOutputVector": [1, 1],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": {
                "s": { "assertedOutputs": [ { "vector": [1, 0] }, { "vector": [0, 1] } ] }
              }
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))
      val ops = VectorAggregator.mergeBatch(twoOutputs)
      ops should have size 1
      ops.head.hcursor.get[Vector[String]]("sequenceIds") shouldBe Right(Vector("s"))
      ops.head.hcursor.get[Vector[Double]]("values")      shouldBe Right(Vector(1, 1))
    }

    "order by machineId alone" in {
      // §6: with one operation per machine the secondary sort keys the
      // comparator needed — sequenceId and outputIndex — are constant, so
      // machineId already orders the batch totally.
      val unordered = parse(
        """
        {
          "m-b": {
            "outputRegion": { "length": 1, "offset": 0 },
            "mergedOutputVector": [1],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": { "s-1": { "assertedOutputs": [ { "vector": [1] } ] } }
            }
          },
          "m-a": {
            "outputRegion": { "length": 1, "offset": 1 },
            "mergedOutputVector": [1],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": {
                "s-z": { "assertedOutputs": [ { "vector": [1] } ] },
                "s-a": { "assertedOutputs": [ { "vector": [1] } ] }
              }
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))
      val ops = VectorAggregator.mergeBatch(unordered)
      ops.flatMap(_.hcursor.get[String]("machineId").toOption) shouldBe Vector("m-a", "m-b")
      // And the contributing set is sorted within the operation, whatever order
      // the sequenceResults object arrived in.
      ops.head.hcursor.get[Vector[String]]("sequenceIds") shouldBe Right(Vector("s-a", "s-z"))
    }
  }

  /** RealityEngine_Scala#35 — the two fields C++ and LSP report that this
    * runtime dropped.  Both are resolved PE-side; the RE is not consulted. */
  "VectorAggregator.mergeBatch provenance and governance" should {

    val machine = parse(
      """
      {
        "id": "machine-documentsigningworkflowmonitor",
        "name": "Document Signing Workflow Monitor",
        "sequences": [
          { "id": "signing-complete", "name": "Signing Complete",
            "initialVectorIds": ["ds-complete"] }
        ],
        "metadata": {
          "governance": { "ownerTeam": "social-services" },
          "triggerConfig": {
            "rules": [
              {
                "sequenceId": "signing-complete",
                "outputMatches": [0, 0, 0, 1],
                "ragStatusCode": "GREEN",
                "processStatus": "info",
                "description": "SIGNING_COMPLETE: all required documents executed"
              }
            ]
          }
        }
      }
      """
    ).getOrElse(fail("fixture is not valid JSON"))

    val corpus = MachineCorpus.build(Vector(machine))

    def entryFor(id: String) =
      VectorAggregator.mergeBatch(machineResults, corpus)
        .find(_.hcursor.get[String]("machineId").toOption.contains(id))
        .getOrElse(fail(s"no entry for $id"))

    "report provenance as the Reality Events the contributing sequences walked" in {
      // Taken from the step's own `matchedVectors`, not resolved against the
      // corpus. The corpus lookup named a sequence's *entry points*
      // (`initialVectorIds`), which is only the same answer for a single-vector
      // sequence — 63% of the corpus — and wrong for the rest, where C++ and
      // LSP report the whole walked chain (RealityEngine_CI#209).
      //
      // With one operation per machine this is the union over contributors
      // (§1); this machine has one, so it is that sequence's chain.
      entryFor("machine-documentsigningworkflowmonitor")
        .hcursor.get[Vector[String]]("provenance") shouldBe Right(Vector("ds-complete"))
    }

    "emit provenance as an empty array, not omit it, when there is no chain" in {
      // "no provenance" and "this runtime does not report provenance" must
      // stay distinguishable to a consumer.
      val entry = entryFor("machine-datacenterdcx001010interconnect")
      entry.hcursor.get[Vector[String]]("provenance") shouldBe Right(Vector.empty)
      entry.asObject.map(_.contains("provenance")) shouldBe Some(true)
    }

    "attach the resolved governance contract" in {
      val gov = entryFor("machine-documentsigningworkflowmonitor")
        .hcursor.downField("governance")
      gov.get[String]("sequenceId").toOption     shouldBe Some("signing-complete")
      gov.get[String]("ragStatusCode").toOption  shouldBe Some("GREEN")
      gov.get[String]("ownerTeam").toOption      shouldBe Some("social-services")
      gov.get[String]("source").toOption         shouldBe Some("rule-only")
    }

    "omit governance rather than null it when no rule matched" in {
      // The data-center machine is absent from this corpus, so nothing resolves.
      entryFor("machine-datacenterdcx001010interconnect")
        .asObject.map(_.contains("governance")) shouldBe Some(false)
    }

    "join governance over contributors, taking the highest severity whole" in {
      // FOLD_PLACEMENT.md §3. Two CESs contribute; each is resolved against its
      // OWN asserted values, and the RED decision wins the join over the AMBER
      // one. It travels WHOLE — the reported sequenceId is the RED rule's, not
      // a field spliced from the other. Safety-preserving is the point: a
      // RED-governed firing cannot be hidden by folding beside a quieter one.
      val twoRules = parse(
        """
        {
          "id": "m-join",
          "name": "Join Machine",
          "sequences": [
            { "id": "s-amber", "name": "Amber", "initialVectorIds": ["iv-amber"] },
            { "id": "s-red",   "name": "Red",   "initialVectorIds": ["iv-red"] }
          ],
          "metadata": {
            "triggerConfig": {
              "rules": [
                { "sequenceId": "s-amber", "outputMatches": [1, 0], "ragStatusCode": "AMBER" },
                { "sequenceId": "s-red",   "outputMatches": [0, 1], "ragStatusCode": "RED" }
              ]
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))
      val results = parse(
        """
        {
          "m-join": {
            "outputRegion": { "length": 2, "offset": 0 },
            "mergedOutputVector": [1, 1],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": {
                "s-amber": { "matchedVectors": [ "iv-amber" ], "assertedOutputs": [ { "vector": [1, 0] } ] },
                "s-red":   { "matchedVectors": [ "iv-red" ],   "assertedOutputs": [ { "vector": [0, 1] } ] }
              }
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))

      val entry = VectorAggregator.mergeBatch(results, MachineCorpus.build(Vector(twoRules))).head
      val gov   = entry.hcursor.downField("governance")
      gov.get[String]("ragStatusCode").toOption shouldBe Some("RED")
      gov.get[String]("sequenceId").toOption    shouldBe Some("s-red")
      // Provenance is the union over both contributors, in sequenceIds order.
      entry.hcursor.get[Vector[String]]("provenance") shouldBe Right(Vector("iv-amber", "iv-red"))
    }

    "resolve each contributor against its own values, never against the fold" in {
      // The folded value is [1,1] and the only rule declared matches [1,1] — so
      // resolving against the fold would answer RED. A rule written for one
      // CES's output need not match the fold, and changing the matching
      // semantics is not part of this move.
      val foldMatching = parse(
        """
        {
          "id": "m-fold",
          "name": "Fold Machine",
          "sequences": [{ "id": "s-1", "name": "One", "initialVectorIds": [] }],
          "metadata": {
            "triggerConfig": {
              "rules": [
                { "sequenceId": "s-1", "outputMatches": [1, 1], "ragStatusCode": "RED" }
              ]
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))
      val results = parse(
        """
        {
          "m-fold": {
            "outputRegion": { "length": 2, "offset": 0 },
            "mergedOutputVector": [1, 1],
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": {
                "s-1": { "assertedOutputs": [ { "vector": [1, 0] } ] },
                "s-2": { "assertedOutputs": [ { "vector": [0, 1] } ] }
              }
            }
          }
        }
        """
      ).getOrElse(fail("fixture is not valid JSON"))

      VectorAggregator.mergeBatch(results, MachineCorpus.build(Vector(foldMatching)))
        .head.asObject.map(_.contains("governance")) shouldBe Some(false)
    }

    "leave the merged perceptual space untouched by either field" in {
      // Reporting must never change what the engine computes.
      val base = Vector.fill(14162)(0.0)
      VectorAggregator.aggregate(base, machineResults) shouldBe
        VectorAggregator.aggregate(base, machineResults)
      VectorAggregator.mergeBatch(machineResults, corpus).map(_.hcursor.get[Vector[Double]]("values")) shouldBe
        VectorAggregator.mergeBatch(machineResults).map(_.hcursor.get[Vector[Double]]("values"))
    }
  }

  "VectorAggregator.aggregate" should {
    "still merge the arbitrated machine output into the perceptual space" in {
      // Unchanged by the mergeBatch fix, and asserted so it stays that way:
      // the gate for what gets merged selects the same machines either way.
      val base   = Vector.fill(14162)(0.0)
      val merged = VectorAggregator.aggregate(base, machineResults)
      // outputVector [0,0,0,1] written at offset 14157, so only the last
      // slot of the region is set.
      merged(14157) shouldBe 0.0
      merged(14160) shouldBe 1.0
      merged(963) shouldBe 1.0
      // and the machine the arbiter did not gate wrote nothing
      merged(10) shouldBe 0.0
    }
  }
}
