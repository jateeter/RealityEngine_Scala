package com.realityengine

import com.realityengine.engine.PerceptualSpaceRuntime
import com.realityengine.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The configured simulation's settled behaviour (RealityEngine_CI
  * SURFACE_SPEC.md, "Already-settled instances", #489). */
class PerceptualSimulationSpec extends AnyFlatSpec with Matchers {

  private def configured(maxSteps: Option[Int]) = {
    val rt = new PerceptualSpaceRuntime(16)
    rt.configure(SimulationConfig(
      Vector(Vector(1.0, 0.0), Vector(0.0, 1.0), Vector(1.0, 1.0)), RegionMapping(3, 2), 100L, maxSteps))
    rt
  }

  "An unconfigured step" should "be a caller error the route answers 400" in {
    // IllegalArgumentException is what Routes' exception handler answers 400;
    // it was IllegalStateException, answered 500.
    an[IllegalArgumentException] should be thrownBy new PerceptualSpaceRuntime(16).step()
  }

  "A live run" should "end on the step that reaches maxSteps" in {
    val rt = configured(Some(2))
    rt.start()
    rt.step() shouldBe defined
    rt.getIsRunning shouldBe true
    rt.step() shouldBe defined
    rt.getIsRunning shouldBe false
    rt.step() shouldBe empty
    rt.getCurrentStep shouldBe 2
  }

  it should "end on the step that finishes the sequence" in {
    val rt = configured(None)
    rt.start()
    (1 to 3).foreach(_ => rt.step() shouldBe defined)
    rt.getIsRunning shouldBe false
  }
}
