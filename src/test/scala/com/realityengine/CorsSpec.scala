package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.VectorStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CorsSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  // ScalatestRouteTest provides implicit system (ActorSystem) and executor (ExecutionContext).
  private val vectorStore = new VectorStore()
  private val engine      = new RealityEngine(vectorStore)
  private val simulator   = new PerceptualSpaceSimulator()
  private val auditCfg    = AuditConfig(enabled = false, level = 0, service = "cors-test")
  private val testRoutes  = new Routes(engine, simulator, auditCfg).routes

  "OPTIONS preflight" should "return 204 with CORS headers" in {
    Options("/api/engine/stats") ~> testRoutes ~> check {
      status shouldBe StatusCodes.NoContent
      header("Access-Control-Allow-Origin").map(_.value) shouldBe Some("*")
      header("Access-Control-Allow-Methods") should be(defined)
      header("Access-Control-Allow-Headers") should be(defined)
    }
  }

  it should "return 204 for any API path" in {
    Options("/api/health") ~> testRoutes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  "GET /api/health" should "carry CORS headers on a normal response" in {
    Get("/api/health") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      header("Access-Control-Allow-Origin").map(_.value) shouldBe Some("*")
      header("Access-Control-Allow-Methods") should be(defined)
      header("Access-Control-Allow-Headers") should be(defined)
    }
  }

  "GET /api/engine/stats" should "carry CORS headers" in {
    Get("/api/engine/stats") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      header("Access-Control-Allow-Origin").map(_.value) shouldBe Some("*")
    }
  }
}
