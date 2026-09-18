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
 * `/api/config` reports the perceptual space as it stands, not the launch seed.
 *
 * The value passed at launch seeds the space; it grows during machine loading
 * to fit every declared mapping. This route read `sys.env("VECTOR_DIMENSION")`
 * — the seed, and not a runtime fact at all — so it would have reported 7680
 * for a space of any size, including one this process never had.
 *
 * That is why the assertion here is a comparison against the space and never a
 * literal. A test asserting a number would pass against a hardcoded env read
 * whenever the two happened to coincide, which is exactly the condition under
 * which the defect was invisible: on a launch that names the right dimension,
 * the seed and the space agree and nothing looks wrong.
 *
 * C++ had the same defect against its own seed member; LSP was correct. A
 * default launch therefore read cpp=7680, scala=7680, lsp=16944, and that 2-1
 * split was investigated as an engine disagreement about a machine mapped at
 * [14364:14384] which was resident and live throughout (RealityEngine_CI#422).
 */
class ConfigDimensionSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime()
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "config-dimension-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  private def reportedDimension: Int =
    Get("/api/config") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      parse(responseAs[String]).toOption.get
        .hcursor.get[Int]("eventDimension").toOption.get
    }

  private def liveSpaceWidth: Int =
    spaceRuntime.getPerceptualSpace.getPerceptualVector.length

  "GET /api/config" should "report the live perceptual space width" in {
    reportedDimension shouldBe liveSpaceWidth
  }

  it should "follow the space when a machine mapped beyond it is loaded" in {
    val before = liveSpaceWidth
    val far    = before + 4096

    spaceRuntime.addMachine(new Machine(
      "Far Machine", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(far, 4), RegionMapping(far + 8, 2))),
      "machine-far"))

    // The growth obligation itself. If this fails the runtime is not growing,
    // which is a different and larger defect than the one below it.
    withClue("the perceptual space did not grow to fit a machine mapped beyond it: ") {
      liveSpaceWidth should be >= (far + 10)
    }

    // And the report follows it. This is the assertion that was false: the
    // route answered from the environment, so it returned `before` no matter
    // how far the space had grown.
    withClue("/api/config did not follow the space it grew to: ") {
      reportedDimension shouldBe liveSpaceWidth
    }
    reportedDimension should be > before
  }
}
