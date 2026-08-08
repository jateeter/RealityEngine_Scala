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
        "transitionResult": {
          "arbiterMetadata": { "shouldOutput": true },
          "sequenceResults": {
            "signing-complete": { "assertedOutputs": [ { "vector": [0, 0, 0, 1] } ] }
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

    "emit one entry per asserted output, not one per machine" in {
      // The old rule emitted one per machine, which is why the runtime
      // reported 5 entries against C++'s 3 for the same input.
      batch should have size 2
      batch.flatMap(_.hcursor.get[String]("machineId").toOption) shouldBe Vector(
        "machine-datacenterdcx001010interconnect",
        "machine-documentsigningworkflowmonitor",
      )
    }

    "carry the sequenceId that fired" in {
      // The whole of the parity mismatch: the cross-runtime check keys on
      // machineId + sequenceId, and entries had no sequenceId at all.
      batch.flatMap(_.hcursor.get[String]("sequenceId").toOption) shouldBe Vector(
        "dcx-001-010-domain-family-stable",
        "signing-complete",
      )
    }

    "match the C++ entry shape" in {
      batch.head.asObject.map(_.keys.toSet) shouldBe
        Some(Set("machineId", "sequenceId", "outputIndex", "region", "values"))
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

    "index multiple asserted outputs of one sequence" in {
      val twoOutputs = parse(
        """
        {
          "m": {
            "outputRegion": { "length": 2, "offset": 4 },
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
      ops should have size 2
      ops.flatMap(_.hcursor.get[Int]("outputIndex").toOption) shouldBe Vector(0, 1)
    }

    "order by (machineId, sequenceId, outputIndex), as C++ sorts" in {
      val unordered = parse(
        """
        {
          "m-b": {
            "outputRegion": { "length": 1, "offset": 0 },
            "transitionResult": {
              "arbiterMetadata": { "shouldOutput": true },
              "sequenceResults": { "s-1": { "assertedOutputs": [ { "vector": [1] } ] } }
            }
          },
          "m-a": {
            "outputRegion": { "length": 1, "offset": 1 },
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
      VectorAggregator.mergeBatch(unordered).flatMap { op =>
        for {
          m <- op.hcursor.get[String]("machineId").toOption
          s <- op.hcursor.get[String]("sequenceId").toOption
        } yield s"$m/$s"
      } shouldBe Vector("m-a/s-a", "m-a/s-z", "m-b/s-1")
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
