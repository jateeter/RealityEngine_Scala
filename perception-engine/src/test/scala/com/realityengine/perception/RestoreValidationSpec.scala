package com.realityengine.perception

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** The store caches run state — value and activity — and never membership
  * (RealityEngine_CI#163 point 5). So a restored source brings its recorded
  * `active` flag back, and restore *validates* that claim instead of
  * overriding it.
  *
  * This replaces RestoreDormancySpec, which pinned the blanket "restore always
  * inactive" rule from #55. That rule reached the right answer for the case it
  * was written against — scala-1 emitting `localai` cells at step 0 that C++
  * and LSP did not — but by assumption rather than on the evidence, and it
  * discarded activity an interrupted dispatch needs to resume against.
  */
class RestoreValidationSpec extends AnyFlatSpec with Matchers {

  private def persistedTest(id: String, active: Boolean = true) = TestSourceConfig(
    id               = id,
    name             = id,
    region           = Region(40, 4),
    active           = active,
    machineId        = "m-1",
    machineName      = "Machine One",
    sequenceName     = "seq",
    inputs           = Vector(Vector(1.0, 1.0, 1.0, 1.0)),
    loop             = true,
    sequenceMetadata = Json.Null,
    testSequence     = Json.Null,
  )

  private def persistedSensor(id: String, active: Boolean, ageMs: Long, ttlMs: Long) =
    SensorSourceConfig(
      id          = id,
      name        = id,
      region      = Region(60, 4),
      active      = active,
      sensorId    = id,
      lastValue   = Vector(1.0, 1.0, 1.0, 1.0),
      lastUpdated = Some(System.currentTimeMillis() - ageMs),
      ttlMs       = ttlMs,
      origin      = Some("localai"),
    )

  // ── Membership ────────────────────────────────────────────────────────────

  "restoreSource" should "register the source, so the restart does not forget it" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedTest("persisted"))
    engine.getSources.map(_.id) should contain("persisted")
  }

  // ── Validation of the restored activity claim ─────────────────────────────

  it should "deactivate a restored sensor whose cached value is outside its TTL" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedSensor("stale", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.getSource("stale").map(_.active) shouldBe Some(false)
  }

  it should "contribute nothing for a restored sensor that failed validation" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedSensor("stale", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.assembleVector().forall(_ == 0.0) shouldBe true
  }

  it should "keep a restored sensor active when its cached value is still live" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedSensor("live", active = true, ageMs = 0L, ttlMs = 600000L))
    engine.getSource("live").map(_.active) shouldBe Some(true)
    engine.assembleVector().slice(60, 64) shouldBe Vector(1.0, 1.0, 1.0, 1.0)
  }

  it should "not activate a source the store recorded inactive" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedSensor("declared", active = false, ageMs = 0L, ttlMs = 600000L))
    engine.getSource("declared").map(_.active) shouldBe Some(false)
  }

  it should "not restore a test source active — the store cached no value to support the claim" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedTest("armed", active = true))
    engine.restoreSource(persistedTest("idle",  active = false))
    engine.getSource("armed").map(_.active) shouldBe Some(false)
    engine.getSource("idle").map(_.active)  shouldBe Some(false)
    engine.assembleVector().forall(_ == 0.0) shouldBe true
  }

  it should "leave re-arming a restored test source to whoever is entitled to" in {
    // Nobody is entitled to re-arm a source no integration registered this run.
    // Reset returns the engine to the clean step 0 condition, so a restored
    // source nothing has claimed is dropped rather than armed (#61) — which is
    // strictly stronger than the inactive this used to assert.
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedTest("armed", active = true))
    engine.getSource("armed").map(_.active) shouldBe Some(false)
    engine.reset()
    engine.getSource("armed") shouldBe None
  }

  it should "re-arm a restored test source once an integration re-declares it" in {
    // The other half: re-registration claims the source, and from then on it is
    // this run's membership, so reset validates it back to active exactly as it
    // does for any registered test source.
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedTest("armed", active = true))
    engine.declareSource(persistedTest("armed", active = false))
    engine.reset()
    engine.getSource("armed").map(_.active) shouldBe Some(true)
  }

  // ── The half #55 could not express: retry state ───────────────────────────

  it should "let an interrupted dispatch resume against a source known to have been live" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(persistedSensor("in-flight", active = true, ageMs = 0L, ttlMs = 600000L))
    // The retry lands after the restart.
    engine.updateSensorValue("in-flight", Vector(0.5, 0.5, 0.5, 0.5)) shouldBe true
    engine.getSource("in-flight").map(_.active) shouldBe Some(true)
  }
}
