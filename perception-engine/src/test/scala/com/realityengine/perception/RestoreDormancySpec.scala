package com.realityengine.perception

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** A restored source must not claim to be carrying live data.
  *
  * scala-1 is the only runtime that persists PE sources; C++ and LSP start
  * empty. Restoring one active made a freshly booted engine perceive values
  * before this run produced any, which for the localAI windows is impossible —
  * everything there is a response to a dispatch that has not happened yet.
  */
class RestoreDormancySpec extends AnyFlatSpec with Matchers {

  private def persistedActive(id: String) = TestSourceConfig(
    id               = id,
    name             = id,
    region           = Region(40, 4),
    active           = true,
    machineId        = "m-1",
    machineName      = "Machine One",
    sequenceName     = "seq",
    inputs           = Vector(Vector(1.0, 1.0, 1.0, 1.0)),
    loop             = true,
    sequenceMetadata = Json.Null,
    testSequence     = Json.Null,
  )

  "restoreSource" should "restore inactive however the store recorded it" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedActive("persisted-active"))
    engine.getSource("persisted-active").map(_.active) shouldBe Some(false)
  }

  it should "contribute nothing before this run activates it" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedActive("persisted-active"))
    engine.assembleVector().forall(_ == 0.0) shouldBe true
  }

  it should "stay dormant across a reset" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedActive("persisted-active"))
    engine.reset()
    engine.getSource("persisted-active").map(_.active) shouldBe Some(false)
  }

  it should "still register the source, so the restart does not forget it" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedActive("persisted-active"))
    engine.getSources.map(_.id) should contain("persisted-active")
  }
}
