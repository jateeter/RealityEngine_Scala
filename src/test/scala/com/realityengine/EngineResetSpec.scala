package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.models.PerceptualSpace
import com.realityengine.services.VectorStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** POST /api/engine/reset must clear everything, as it does in C++ and LSP.
  *
  * It used to call resetAllSequences() alone, so the same endpoint meant "reset
  * sequences" on this runtime and "reset everything" on the other two. Measured
  * live against a 12-machine stack, Scala held 17 non-zero perceptual cells and
  * 6 history entries across a reset that took C++ and LSP to zero
  * (RealityEngine_Scala#43).
  *
  * The asymmetry is not contained to this endpoint: every cross-runtime
  * comparison that resets through it was measuring a cleared C++ and LSP
  * against an uncleared Scala, which is how two engine "defects" came to be
  * filed against the wrong runtimes (RealityEngine_CPP#32,
  * RealityEngine_LSP#38). These assertions are what stops that recurring.
  */
class EngineResetSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private def fixture = {
    val vectorStore = new VectorStore()
    val engine      = new RealityEngine(vectorStore)
    val spaceRuntime   = new PerceptualSpaceRuntime()
    val auditCfg    = AuditConfig(enabled = false, level = 0, service = "reset-test")
    (engine, spaceRuntime, new Routes(engine, spaceRuntime, auditCfg).routes)
  }

  private def nonZero(space: PerceptualSpace): Int =
    space.getPerceptualVector.count(_ != 0.0)

  "POST /api/engine/reset" should "zero the spaceRuntime's perceptual space" in {
    val (_, spaceRuntime, routes) = fixture
    spaceRuntime.getPerceptualSpace.updateRegion(10, Vector(1.0, 1.0, 1.0))
    nonZero(spaceRuntime.getPerceptualSpace) should be > 0

    Post("/api/engine/reset") ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
    nonZero(spaceRuntime.getPerceptualSpace) shouldBe 0
  }

  it should "zero the perception engine's perceptual space" in {
    // C++ clears this one via PerceptionMapper::reset(); it is a separate space
    // from the spaceRuntime's, and nothing was clearing it here.
    val (engine, _, routes) = fixture
    engine.perceptionEngine.getPerceptualSpace.updateRegion(20, Vector(1.0, 1.0))
    nonZero(engine.perceptionEngine.getPerceptualSpace) should be > 0

    Post("/api/engine/reset") ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
    nonZero(engine.perceptionEngine.getPerceptualSpace) shouldBe 0
  }

  it should "clear simulation history and the step counter" in {
    val (_, spaceRuntime, routes) = fixture
    spaceRuntime.getPerceptualSpace.updateRegion(0, Vector(1.0))

    Post("/api/engine/reset") ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
    spaceRuntime.getHistory shouldBe empty
    spaceRuntime.getCurrentStep shouldBe 0
  }

  it should "be idempotent" in {
    val (engine, spaceRuntime, routes) = fixture
    spaceRuntime.getPerceptualSpace.updateRegion(5, Vector(1.0, 1.0))

    for (_ <- 1 to 3) Post("/api/engine/reset") ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
    nonZero(spaceRuntime.getPerceptualSpace) shouldBe 0
    nonZero(engine.perceptionEngine.getPerceptualSpace) shouldBe 0
    spaceRuntime.getHistory shouldBe empty
  }
}
