package com.realityengine.perception.api

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
// Imported for the same reason PerceptionRoutes imports it, and it must stay:
// it puts a FromEntityUnmarshaller for every Decoder into implicit scope, and
// that is precisely what broke the first attempt at this route. A test route
// assembled without it exercises a different implicit scope than production
// and will pass while production returns 400.
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

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

    "omit the key entirely" in {
      // This asserted the key stayed present with an empty object — "the LSP
      // shape, not the C++ shape". SURFACE_SPEC.md now specifies one shape for
      // all three: `compact` omits machineResults. An empty object is not an
      // absent key to a consumer walking the response, and it made a compact
      // push from this runtime a different shape from C++'s and LSP's.
      val out = PushRequest.redactMachineResults(populated)
      out.asObject.map(_.contains("machineResults")) shouldBe Some(false)
      out.hcursor.downField("machineResults").focus shouldBe None
    }

    "leave every other field alone" in {
      val out = PushRequest.redactMachineResults(populated)
      out.hcursor.get[Int]("stepNumber") shouldBe Right(1)
    }

    "actually remove it, where deepMerge would not" in {
      // Guards the reason an explicit remove is used: deep-merging an empty
      // object into a populated one is a no-op, so the obvious implementation
      // silently leaves the payload in place.
      val viaDeepMerge = populated.deepMerge(Json.obj("machineResults" -> Json.obj()))
      viaDeepMerge.asObject.map(_.contains("machineResults")) shouldBe Some(true)
      PushRequest.redactMachineResults(populated)
        .asObject.map(_.contains("machineResults")) shouldBe Some(false)
    }
  }

  "the push route's entity directive" should {
    // Mirrors the real route, including its implicit scope — see the
    // FailFastCirceSupport import above. The first version of this used
    // entity(as[String]) and passed here while production answered 400,
    // because this file did not import the circe support and so resolved
    // as[String] to akka-http's own unmarshaller rather than circe's.
    val route = path("api" / "push") {
      post {
        extractStrictEntity(3.seconds) { strict =>
          complete(if (PushRequest.compactFrom(strict.data.utf8String)) "compact" else "full")
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

    "reject the request if written with entity(as[String]) — the trap this avoids" in {
      // Documents why extractStrictEntity is used. With FailFastCirceSupport
      // in scope, as[String] means "decode a JSON string", so the very body
      // the flag lives in is rejected. This shipped once and returned
      // HTTP 400 from every push the regression suite made.
      val trap = path("api" / "push") {
        post { entity(as[String]) { raw =>
          complete(if (PushRequest.compactFrom(raw)) "compact" else "full")
        } }
      }
      Post("/api/push", HttpEntity(ContentTypes.`application/json`, """{"compact": true}""")) ~> trap ~> check {
        rejection shouldBe a[akka.http.scaladsl.server.MalformedRequestContentRejection]
      }
    }
  }
}
