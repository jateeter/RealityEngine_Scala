package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.models._
import com.realityengine.services.VectorStore
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The perceptual space is read-only downward.
 *
 * `PUT /api/config/dimension` bound its parameter, echoed it back, and assigned
 * nothing at all — not to the space, not to any field. It reported
 * `success: true` for a write that did not exist, and defaulted the parameter
 * from `VECTOR_DIMENSION`, so a caller omitting it was told the dimension had
 * been set to the launch seed (RealityEngine_CI#425).
 *
 * The floor is `max(requiredDimension, current width)`. The second half is the
 * one worth stating: a request below the width held but above the corpus
 * requirement violates no corpus constraint, so a requirement-only check accepts
 * it and answers 200 while the space does not move — reporting success for a
 * write that did not take effect, which is the defect this exists to remove.
 */
class DimensionWriteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime(64)
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "dimension-write-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  // Output beyond input deliberately: the requirement folded over input
  // mappings alone, which under-reports for exactly this shape.
  spaceRuntime.addMachine(new Machine(
    "Width Probe", "", Map.empty, ArbiterRule.PASSTHROUGH,
    Some(PerceptualMapping(RegionMapping(0, 4), RegionMapping(100, 4))),
    "machine-width-probe"))

  private def width: Int = spaceRuntime.getPerceptualSpace.getPerceptualVector.length

  private def put(d: String) =
    Put(s"/api/config/dimension?dimension=$d") ~> testRoutes ~> check {
      (status, responseAs[String])
    }

  "requiredDimension" should "cover output regions, not only input" in {
    // Input ends at 4, output at 104. A fold over inputs alone reports 4.
    spaceRuntime.requiredDimension shouldBe 104
  }

  it should "be reported as the corpus requirement, not the width held" in {
    // /api/runtime/vector-space folded in the current width, so it reported
    // max(width, requirement) — a different quantity under the same name, and
    // one that made this runtime disagree with cpp and lsp on the same corpus:
    // measured live, cpp and lsp reported 7504 while this reported 7680, the
    // width it happened to hold.
    //
    // Its own runtime and routes. The cases in this suite share one mutable
    // space and run in declaration order, so a test that widens the space
    // changes what every later test sees — which is exactly what happened when
    // this was first written against the shared fixture.
    val ownRuntime = new PerceptualSpaceRuntime(64)
    val ownRoutes  = new Routes(new RealityEngine(new VectorStore()), ownRuntime,
                                AuditConfig(enabled = false, level = 0, service = "vector-space-test")).routes
    ownRuntime.addMachine(new Machine(
      "Report Probe", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(0, 4), RegionMapping(100, 4))),
      "machine-report-probe"))

    Put("/api/config/dimension?dimension=4096") ~> ownRoutes ~> check {
      status shouldBe StatusCodes.OK
    }
    ownRuntime.requiredDimension shouldBe 104
    ownRuntime.getPerceptualSpace.getPerceptualVector.length shouldBe 4096

    Get("/api/runtime/vector-space") ~> ownRoutes ~> check {
      status shouldBe StatusCodes.OK
      val c = parse(responseAs[String]).toOption.get.hcursor
      c.get[Int]("dimension").toOption shouldBe Some(4096)
      withClue("requiredDimension reported the width held, not the corpus requirement: ") {
        c.get[Int]("requiredDimension").toOption shouldBe Some(104)
      }
    }
  }

  "PUT /api/config/dimension" should "refuse a request below the corpus requirement" in {
    val before = width
    val (status, body) = put("64")
    status shouldBe StatusCodes.BadRequest
    body should include("the 104 the resident corpus requires")
    withClue("a refused write mutated the space: ") { width shouldBe before }
  }

  it should "refuse a request below the width already held, even above the requirement" in {
    put("512")
    width shouldBe 512
    spaceRuntime.requiredDimension shouldBe 104

    val (status, body) = put("256")
    status shouldBe StatusCodes.BadRequest
    body should include("the 512 this engine already holds")
    withClue("a refused write mutated the space: ") { width shouldBe 512 }
  }

  it should "accept a request equal to the width already held" in {
    // Re-asserting the width you have is not a lowering, and has asked for
    // nothing impossible.
    val (status, _) = put(width.toString)
    status shouldBe StatusCodes.OK
  }

  it should "apply a widening and report the width it actually has" in {
    val (status, body) = put("2048")
    status shouldBe StatusCodes.OK
    width shouldBe 2048
    val json = parse(body).toOption.get.hcursor
    json.get[Int]("dimension").toOption shouldBe Some(2048)
    json.get[Int]("requiredDimension").toOption shouldBe Some(104)
  }

  it should "follow the requirement as machines are added" in {
    spaceRuntime.addMachine(new Machine(
      "Far Probe", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(9000, 4), RegionMapping(9100, 4))),
      "machine-far-probe"))
    spaceRuntime.requiredDimension shouldBe 9104
    // The floor is recomputed, not captured at construction.
    val (status, body) = put("2048")
    status shouldBe StatusCodes.BadRequest
    body should include("9104")
  }

  it should "require the parameter rather than defaulting it from the environment" in {
    // It defaulted to VECTOR_DIMENSION, so an omitted parameter was answered as
    // a successful write of the launch seed.
    Put("/api/config/dimension") ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include("required")
    }
  }
}
