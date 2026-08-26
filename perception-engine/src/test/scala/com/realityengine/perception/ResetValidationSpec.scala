package com.realityengine.perception

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** `POST /api/reset` validates activity rather than assigning it
  * (RealityEngine_CI#163 point 3, RealityEngine_CI#166).
  *
  * All four PE runtimes used to force `active = true` on every test source and
  * leave every other kind's flag exactly as it was, so a sensor whose TTL had
  * expired before the reset was still reported active after it. The assembled
  * vector was already right — an expired sensor contributes zeros at assembly —
  * but `active` is on the byte-compared source payload, so the reported state
  * is a parity surface in its own right.
  */
class ResetValidationSpec extends AnyFlatSpec with Matchers {

  private def sensor(id: String, active: Boolean, ageMs: Long, ttlMs: Long) =
    SensorSourceConfig(
      id          = id,
      name        = id,
      region      = Region(60, 4),
      active      = active,
      sensorId    = id,
      lastValue   = Vector(1.0, 1.0, 1.0, 1.0),
      lastUpdated = Some(System.currentTimeMillis() - ageMs),
      ttlMs       = ttlMs,
      origin      = Some("mqtt"),
    )

  private def test(id: String, active: Boolean, inputs: Vector[Vector[Double]]) =
    TestSourceConfig(
      id               = id,
      name             = id,
      region           = Region(40, 4),
      active           = active,
      machineId        = s"m-$id",
      machineName      = id,
      sequenceName     = "seq",
      inputs           = inputs,
      loop             = true,
      sequenceMetadata = Json.Null,
      testSequence     = Json.Null,
    )

  // ── Sensor: active iff holding a value inside its TTL ─────────────────────

  "reset" should "deactivate a sensor whose TTL expired before it" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.reset()
    engine.getSource("expired").map(_.active) shouldBe Some(false)
  }

  it should "leave a sensor active while its value is still inside its TTL" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("live", active = true, ageMs = 0L, ttlMs = 600000L))
    engine.reset()
    engine.getSource("live").map(_.active) shouldBe Some(true)
  }

  it should "deactivate a sensor that has never held a value" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("never", active = true, ageMs = 0L, ttlMs = 600000L)
      .copy(lastValue = Vector.empty, lastUpdated = None))
    engine.reset()
    engine.getSource("never").map(_.active) shouldBe Some(false)
  }

  it should "not clear the evidence it validates against" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("live", active = true, ageMs = 0L, ttlMs = 600000L))
    engine.reset()
    engine.getSource("live") match {
      case Some(s: SensorSourceConfig) =>
        s.lastValue   shouldBe Vector(1.0, 1.0, 1.0, 1.0)
        s.lastUpdated should not be None
      case other => fail(s"expected a sensor source, got $other")
    }
  }

  it should "keep the reported flag and the contributed values agreeing" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.reset()
    // Reported inactive, and contributing nothing — the pair that used to split.
    engine.getSource("expired").map(_.active) shouldBe Some(false)
    engine.assembleVector().slice(60, 64) shouldBe Vector(0.0, 0.0, 0.0, 0.0)
  }

  // ── Acceptance path from the issue ────────────────────────────────────────

  it should "report a fed-then-expired sensor inactive, end to end" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s", active = false, ageMs = 0L, ttlMs = 30L)
      .copy(lastValue = Vector.empty, lastUpdated = None))
    engine.updateSensorValue("s", Vector(1.0, 1.0, 1.0, 1.0))
    engine.getSource("s").map(_.active) shouldBe Some(true)
    Thread.sleep(60L)
    engine.reset()
    engine.getSource("s").map(_.active) shouldBe Some(false)
  }

  it should "let a later reading bring a validated-off sensor back" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("s", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.reset()
    engine.getSource("s").map(_.active) shouldBe Some(false)
    engine.updateSensorValue("s", Vector(0.5, 0.5, 0.5, 0.5))
    engine.getSource("s").map(_.active) shouldBe Some(true)
    engine.assembleVector().slice(60, 64) shouldBe Vector(0.5, 0.5, 0.5, 0.5)
  }

  // ── Test: active iff its interned sequence is non-empty ───────────────────

  it should "rewind a test source's playback cursor to step 0" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("playing", active = true, Vector(Vector(1.0, 1.0, 1.0, 1.0), Vector(0.0, 0.0, 0.0, 0.0))))
    engine.advance()
    engine.getTestProgress("playing").map(_.current) shouldBe Some(1)
    engine.reset()
    engine.getTestProgress("playing").map(_.current) shouldBe Some(0)
    engine.getSource("playing").map(_.active)        shouldBe Some(true)
  }

  it should "arm a test source that has a sequence to replay" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("armed", active = false, Vector(Vector(1.0, 1.0, 1.0, 1.0))))
    engine.reset()
    engine.getSource("armed").map(_.active) shouldBe Some(true)
  }

  it should "not report a test source with nothing interned as active" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("empty", active = true, Vector.empty))
    engine.reset()
    engine.getSource("empty").map(_.active) shouldBe Some(false)
  }

  // ── Simulated: generates from the zeroed globalStep ───────────────────────

  it should "activate a simulated source and re-seed its walk state" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(SimulatedSourceConfig(
      id = "sim", name = "sim", region = Region(80, 4), active = false,
      pattern = SimPattern.RandomWalk, frequency = 1.0, amplitude = 0.1, dcOffset = 0.5))
    engine.advance()
    engine.reset()
    engine.getSource("sim").map(_.active) shouldBe Some(true)
    engine.assembleVector().slice(80, 84) shouldBe Vector(0.5, 0.5, 0.5, 0.5)
  }

  // ── Membership neutrality and run state ───────────────────────────────────

  it should "change no source's membership" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("t", active = true, Vector(Vector(1.0, 1.0, 1.0, 1.0))))
    engine.addSource(sensor("s", active = true, ageMs = 60000L, ttlMs = 1000L))
    val before = engine.getSources.map(_.id)
    engine.reset()
    engine.getSources.map(_.id) shouldBe before
  }

  it should "rewind globalStep and the persistent vector" in {
    val engine = new PerceptionEngine(256)
    engine.updateFromPerceptualSpace(Vector.fill(256)(1.0))
    engine.advance()
    engine.globalStep should be > 0L
    engine.reset()
    engine.globalStep shouldBe 0L
    engine.assembleVector().forall(_ == 0.0) shouldBe true
  }
}
