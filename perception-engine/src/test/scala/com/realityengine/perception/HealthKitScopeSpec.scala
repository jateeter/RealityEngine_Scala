package com.realityengine.perception

import com.realityengine.perception.healthkit.HealthKitScope
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** HealthKit scope and resync (localHealthkitBridge INGEST_CONTRACT.md). */
class HealthKitScopeSpec extends AnyWordSpec with Matchers {
  private val B  = "healthkit-ios-bridge"
  private val BP = "HKCorrelationTypeIdentifierBloodPressure"
  private val SL = "HKCategoryTypeIdentifierSleepAnalysis"

  "a bridge" should {
    "be open until its first scope declaration" in {
      val s = new HealthKitScope
      s.refusal(B, BP) shouldBe None
      s.change(B, "add", Vector(SL), Some("pim"), 1L).isRight shouldBe true
      s.refusal(B, SL) shouldBe None
      s.refusal(B, BP) shouldBe Some("not-in-scope")
    }
    "refuse locked types as locked and removed ones as not in scope" in {
      val s = new HealthKitScope
      s.change(B, "lock", Vector(SL), None, 1L)
      s.refusal(B, SL) shouldBe Some("locked")
      s.change(B, "remove", Vector(SL), None, 2L)
      s.refusal(B, SL) shouldBe Some("not-in-scope")
    }
    "report the sensors a removed type wrote, so the PE can drop them" in {
      val s = new HealthKitScope
      s.noteSensor(B, BP, "healthkit.bp")
      val Right((resp, removed)) = s.change(B, "remove", Vector(BP), None, 1L)
      removed shouldBe Vector("healthkit.bp")
      resp.hcursor.get[Long]("generation").toOption shouldBe Some(1L)
    }
    "reject an unknown action and an empty type list" in {
      val s = new HealthKitScope
      s.change(B, "resync", Vector(BP), None, 1L).isLeft shouldBe true
      s.change(B, "add", Vector.empty, None, 1L).isLeft shouldBe true
    }
  }

  "resync" should {
    "refuse a locked type with 409 and accept an active one with 202" in {
      val s = new HealthKitScope
      s.change(B, "add", Vector(BP), None, 1L)
      s.change(B, "lock", Vector(SL), None, 2L)
      s.resync(B, Vector(SL), Some("localAIStack"), 3L, k => s"$k-1")._1 shouldBe 409
      val (code, body) = s.resync(B, Vector.empty, Some("localAIStack"), 3L, k => s"$k-1")
      code shouldBe 202
      body.hcursor.downField("request").get[Vector[String]]("types").toOption shouldBe Some(Vector(BP))
    }
    "be fulfilled by an ingest carrying its id" in {
      val s = new HealthKitScope
      s.change(B, "add", Vector(BP), None, 1L)
      s.resync(B, Vector(BP), Some("localAIStack"), 2L, _ => "r1")
      s.fulfil(B, "r1", 3L)
      val req = s.json(B).hcursor.downField("resyncRequests").downArray
      req.get[String]("state").toOption shouldBe Some("fulfilled")
      req.get[Long]("fulfilledAt").toOption shouldBe Some(3L)
    }
    "require requestedBy" in {
      new HealthKitScope().resync(B, Vector.empty, None, 1L, identity)._1 shouldBe 400
    }
  }
}
