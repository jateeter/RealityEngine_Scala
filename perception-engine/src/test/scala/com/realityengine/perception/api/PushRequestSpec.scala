package com.realityengine.perception.api

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Covers `POST /api/push {"compact": true}` — RealityEngine_Scala#30.
  *
  * The route previously took no entity at all, so the flag was silently
  * ignored and Scala returned 12 populated machine results where C++ and LSP
  * returned none. Reading the body is the fix; not breaking the callers who
  * post nothing is the constraint.
  */
class PushRequestSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  "PushRequest.compactFrom" should {
    "read an explicit compact flag" in {
      PushRequest.compactFrom("""{"compact": true}""")  shouldBe true
      PushRequest.compactFrom("""{"compact": false}""") shouldBe false
    }

    "treat everything it cannot read as not-compact" in {
      // Each of these is a body an existing caller may already send.
      PushRequest.compactFrom("")                    shouldBe false
      PushRequest.compactFrom("{}")                  shouldBe false
      PushRequest.compactFrom("not json at all")     shouldBe false
      PushRequest.compactFrom("""{"compact": "yes"}""") shouldBe false
      PushRequest.compactFrom("""{"other": true}""") shouldBe false
    }
  }

  "PushRequest.redactMachineResults" should {
    val populated = Json.obj(
      "stepNumber"     -> Json.fromInt(1),
      "machineResults" -> Json.obj("machine-a" -> Json.obj("outputVector" -> Json.arr(Json.fromInt(1)))),
    )

    "keep the key and empty its contents" in {
      val out = PushRequest.redactMachineResults(populated)
      out.hcursor.downField("machineResults").focus shouldBe Some(Json.obj())
      // present, not removed — this is the LSP shape, not the C++ shape
      out.asObject.map(_.contains("machineResults")) shouldBe Some(true)
    }

    "leave every other field alone" in {
      val out = PushRequest.redactMachineResults(populated)
      out.hcursor.get[Int]("stepNumber") shouldBe Right(1)
    }

    "actually clear it, where deepMerge would not" in {
      // Guards the reason `add` is used: deep-merging an empty object into a
      // populated one is a no-op, so the obvious implementation silently fails.
      val viaDeepMerge = populated.deepMerge(Json.obj("machineResults" -> Json.obj()))
      viaDeepMerge.hcursor.downField("machineResults").focus should not be Some(Json.obj())
      PushRequest.redactMachineResults(populated)
        .hcursor.downField("machineResults").focus shouldBe Some(Json.obj())
    }
  }

  "the push route's entity directive" should {
    // Mirrors the directive the real route uses. The concern is not the flag
    // but the 400 that `as[Json]` would return for the bodies below, every one
    // of which some current caller sends.
    val route = path("api" / "push") {
      post {
        entity(as[String]) { raw =>
          complete(if (PushRequest.compactFrom(raw)) "compact" else "full")
        }
      }
    }

    "accept a request with no entity at all" in {
      Post("/api/push") ~> route ~> check {
        handled shouldBe true
        responseAs[String] shouldBe "full"
      }
    }

    "accept an empty JSON entity" in {
      Post("/api/push", HttpEntity(ContentTypes.`application/json`, "")) ~> route ~> check {
        handled shouldBe true
        responseAs[String] shouldBe "full"
      }
    }

    "accept a plain-text body without a JSON content type" in {
      Post("/api/push", HttpEntity(ContentTypes.`text/plain(UTF-8)`, "ping")) ~> route ~> check {
        handled shouldBe true
        responseAs[String] shouldBe "full"
      }
    }

    "honour the flag when it is sent" in {
      Post("/api/push", HttpEntity(ContentTypes.`application/json`, """{"compact": true}""")) ~> route ~> check {
        handled shouldBe true
        responseAs[String] shouldBe "compact"
      }
    }
  }
}
