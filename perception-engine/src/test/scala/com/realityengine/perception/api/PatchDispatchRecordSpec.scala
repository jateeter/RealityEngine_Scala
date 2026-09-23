package com.realityengine.perception.api

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** PATCH /api/dispatch/records/:id semantics, settled 3-of-3 in RealityEngine_CI
  * SURFACE_SPEC.md ("Dispatch surface shapes"). This runtime used to deepMerge
  * the whole request body onto the record, so a PATCH could rewrite anything,
  * including the envelope.
  */
class PatchDispatchRecordSpec extends AnyWordSpec with Matchers {

  private val record = parse(
    """{"id":"d1","status":"recorded","attempts":0,"providerReceipt":null,"error":null,
      |"envelope":{"envelopeId":"e1"},"target":"agent"}""".stripMargin).toOption.get

  private def patch(body: String): Json =
    PerceptionRoutes.patchDispatchRecord(record, parse(body).toOption.get, 42L)

  "patchDispatchRecord" should {
    "write status and error, and fold provider details into providerReceipt" in {
      val r = patch("""{"status":"delivered","error":"boom","provider":"ollama","externalRunId":"run-1"}""")
      r.hcursor.get[String]("status").toOption shouldBe Some("delivered")
      r.hcursor.get[String]("error").toOption shouldBe Some("boom")
      r.hcursor.downField("providerReceipt").get[String]("provider").toOption shouldBe Some("ollama")
      r.hcursor.downField("providerReceipt").get[String]("externalRunId").toOption shouldBe Some("run-1")
      r.hcursor.get[Long]("updatedAt").toOption shouldBe Some(42L)
    }

    "merge providerReceipt onto the existing one" in {
      val once  = patch("""{"providerReceipt":{"model":"m"}}""")
      val twice = PerceptionRoutes.patchDispatchRecord(once, parse("""{"adapter":"acp"}""").toOption.get, 43L)
      twice.hcursor.downField("providerReceipt").get[String]("model").toOption shouldBe Some("m")
      twice.hcursor.downField("providerReceipt").get[String]("adapter").toOption shouldBe Some("acp")
    }

    "increment or set attempts, and clear the error" in {
      patch("""{"incrementAttempts":true}""").hcursor.get[Int]("attempts").toOption shouldBe Some(1)
      patch("""{"attempts":5,"incrementAttempts":true}""").hcursor.get[Int]("attempts").toOption shouldBe Some(5)
      val errored = patch("""{"error":"x"}""")
      PerceptionRoutes.patchDispatchRecord(errored, parse("""{"clearError":true}""").toOption.get, 44L)
        .hcursor.downField("error").focus shouldBe Some(Json.Null)
    }

    "ignore every other field, so the envelope cannot be rewritten" in {
      val r = patch("""{"envelope":{"envelopeId":"forged"},"id":"d2","metadata":{"a":1}}""")
      r.hcursor.downField("envelope").get[String]("envelopeId").toOption shouldBe Some("e1")
      r.hcursor.get[String]("id").toOption shouldBe Some("d1")
      r.hcursor.downField("metadata").focus shouldBe None
    }
  }
}
