package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.VectorStore
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `GET /api/engine/config` lists its controls sorted by name (SURFACE_SPEC
 * "/api/engine/config"), as C++ serves them. Declaration order put phaseDetail
 * before the include* controls, so the compared list differed from C++'s by
 * order alone: a parity error.
 */
class EngineConfigOrderSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val testRoutes = new Routes(new RealityEngine(new VectorStore()), new PerceptualSpaceRuntime(),
    AuditConfig(enabled = false, level = 0, service = "engine-config-order-test")).routes

  "GET /api/engine/config" should "list the controls sorted by name" in {
    Get("/api/engine/config") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val names = parse(responseAs[String]).toOption.get.hcursor.downField("controls").values.get
        .map(_.hcursor.get[String]("name").toOption.get).toList
      names shouldBe List("arbitrationRetention", "arbitrationWindow", "clone.maxActive", "historyLimit",
        "includeActiveRegions", "includeMachineResults", "includePerceptualSpace", "phaseDetail",
        "transitionsInhibited")
    }
  }
}
