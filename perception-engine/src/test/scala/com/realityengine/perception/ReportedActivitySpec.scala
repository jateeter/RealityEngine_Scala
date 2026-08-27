package com.realityengine.perception

import io.circe.Json
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import com.realityengine.perception.models.PerceptionJsonCodecs._

/** Activity expiry is continuous, not an event (RealityEngine_CI#175).
  *
  * `reset` validating activity fixed the reported flag *at* a reset and nowhere
  * else, so it went stale again immediately: nothing runs when a sensor's TTL
  * lapses, and until the next reset `/api/sources` and `/api/state` kept
  * advertising `active: true` for a source `assembleVector` was already zeroing.
  * The reported value is now derived at every read:
  *
  *     reported_active = stored_active AND validated_active(kind)
  *
  * Both halves matter, and the tests below pin each. `stored_active` is why an
  * operator pause and an exhausted non-looping test source still read inactive;
  * `validated_active` is why a lapsed TTL does. Validation can only take
  * activity away — it never grants it — and it never writes back.
  */
class ReportedActivitySpec extends AnyFlatSpec with Matchers {

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

  private def test(id: String, active: Boolean, inputs: Vector[Vector[Double]], loop: Boolean = true) =
    TestSourceConfig(
      id               = id,
      name             = id,
      region           = Region(40, 4),
      active           = active,
      machineId        = s"m-$id",
      machineName      = id,
      sequenceName     = "seq",
      inputs           = inputs,
      loop             = loop,
      sequenceMetadata = Json.Null,
      testSequence     = Json.Null,
    )

  private def reported(engine: PerceptionEngine, id: String): Option[Boolean] =
    engine.reportedSources.find(_.id == id).map(_.active)

  /** `active` as it lands on the wire, through the encoder that serves
    * `/api/sources` — the field under byte comparison, not just the flag. */
  private def serializedActive(sources: Vector[SourceConfig], id: String): Option[Boolean] =
    sources.asJson.asArray.flatMap(
      _.find(_.hcursor.get[String]("id").contains(id))
        .flatMap(_.hcursor.get[Boolean]("active").toOption))

  // ── validated_active: a lapsed TTL demotes on sight ───────────────────────

  "a source read" should "report a sensor whose TTL lapsed as inactive, with no reset in between" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s", active = false, ageMs = 0L, ttlMs = 30L)
      .copy(lastValue = Vector.empty, lastUpdated = None))
    engine.updateSensorValue("s", Vector(1.0, 1.0, 1.0, 1.0))
    reported(engine, "s") shouldBe Some(true)

    Thread.sleep(60L)

    // No reset, no push, nothing ran. The TTL simply lapsed.
    reported(engine, "s") shouldBe Some(false)
  }

  it should "report a sensor still inside its TTL as active" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("fresh", active = true, ageMs = 0L, ttlMs = 600000L))
    reported(engine, "fresh") shouldBe Some(true)
  }

  it should "report a sensor that has never held a value as inactive" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("never", active = true, ageMs = 0L, ttlMs = 600000L)
      .copy(lastValue = Vector.empty, lastUpdated = None))
    reported(engine, "never") shouldBe Some(false)
  }

  it should "agree with what the stale sensor actually contributes" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    // The pair that used to split between resets: reported live, contributing zeros.
    reported(engine, "expired") shouldBe Some(false)
    engine.assembleVector().slice(60, 64) shouldBe Vector(0.0, 0.0, 0.0, 0.0)
  }

  // ── stored_active: validation takes away, never grants ────────────────────

  it should "keep a paused source inactive — validation cannot resurrect it" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("paused", active = true, Vector(Vector(1.0, 1.0, 1.0, 1.0))))
    // What PATCH /api/sources/:id {"active": false} leaves behind.
    engine.updateSource("paused", engine.getSource("paused").get.withActive(false))
    // Its sequence is non-empty, so it validates active — and still reads inactive.
    reported(engine, "paused") shouldBe Some(false)
  }

  it should "keep a paused sensor holding a live value inactive" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("paused", active = false, ageMs = 0L, ttlMs = 600000L))
    reported(engine, "paused") shouldBe Some(false)
  }

  it should "report a finished non-looping test source as inactive once its sequence is exhausted" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("once", active = true,
      Vector(Vector(1.0, 1.0, 1.0, 1.0), Vector(0.5, 0.5, 0.5, 0.5)), loop = false))
    reported(engine, "once") shouldBe Some(true)
    engine.advance()
    reported(engine, "once") shouldBe Some(true)
    engine.advance() // sequence exhausted, and it does not loop
    reported(engine, "once") shouldBe Some(false)
  }

  it should "report a test source with nothing interned as inactive" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("empty", active = true, Vector.empty))
    reported(engine, "empty") shouldBe Some(false)
  }

  it should "leave a simulated source alone — it generates from globalStep" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(SimulatedSourceConfig(
      id = "sim", name = "sim", region = Region(80, 4), active = true,
      pattern = SimPattern.Sine, frequency = 1.0, amplitude = 0.1, dcOffset = 0.5))
    reported(engine, "sim") shouldBe Some(true)
  }

  // ── A read is a read ──────────────────────────────────────────────────────

  it should "not write the demotion back to the stored flag" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.reportedSources.map(_.active) shouldBe Vector(false)

    // Still stored active — serializing must not be an event in the source's life.
    engine.getSource("expired").map(_.active) shouldBe Some(true)

    // And so the ingress path finds the state it left: a fresh reading is not
    // fighting a demotion that a GET wrote into storage.
    engine.updateSensorValue("expired", Vector(0.5, 0.5, 0.5, 0.5))
    reported(engine, "expired") shouldBe Some(true)
    engine.assembleVector().slice(60, 64) shouldBe Vector(0.5, 0.5, 0.5, 0.5)
  }

  it should "change no source's membership" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(test("t", active = true, Vector(Vector(1.0, 1.0, 1.0, 1.0))))
    engine.addSource(sensor("s", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.reportedSources.map(_.id) shouldBe engine.getSources.map(_.id)
  }

  // ── Every path that serializes a source ───────────────────────────────────

  it should "report the same activity on /api/state as on /api/sources" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.addSource(sensor("fresh", active = true, ageMs = 0L, ttlMs = 600000L))

    val state = engine.getState(None, AutoConfig(running = false, intervalMs = 1000L))
    state.sources.map(s => s.id -> s.active).toMap shouldBe
      engine.reportedSources.map(s => s.id -> s.active).toMap
    state.sources.find(_.id == "expired").map(_.active) shouldBe Some(false)
    state.sources.find(_.id == "fresh").map(_.active)   shouldBe Some(true)
  }

  it should "carry the reported value onto the wire, not the stored one" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    engine.addSource(sensor("fresh", active = true, ageMs = 0L, ttlMs = 600000L))

    serializedActive(engine.reportedSources, "expired") shouldBe Some(false)
    serializedActive(engine.reportedSources, "fresh")   shouldBe Some(true)
    // The stored view is what the store persists, and it is untouched.
    serializedActive(engine.getSources, "expired") shouldBe Some(true)
  }

  it should "report the same activity for a single source as for the listing" in {
    val engine = new PerceptionEngine(256)
    val src = engine.addSource(sensor("expired", active = true, ageMs = 60000L, ttlMs = 1000L))
    // The one-source responses (POST /api/sources, PATCH /api/sources/:id, the
    // ingest and bootstrap echoes) must not answer differently from the listing.
    engine.reported(src).active shouldBe false
    engine.reported(src).active shouldBe reported(engine, "expired").get
  }

  // ── One clock read per pass ───────────────────────────────────────────────

  it should "validate identically configured sensors identically" in {
    val engine = new PerceptionEngine(256)
    val at = System.currentTimeMillis()
    // Sat exactly on the TTL boundary, where a per-source clock read would split
    // the set the moment the pass crossed a millisecond.
    (1 to 200).foreach { i =>
      engine.addSource(sensor(f"s$i%03d", active = true, ageMs = 0L, ttlMs = 0L)
        .copy(lastUpdated = Some(at)))
    }
    engine.reportedSources.map(_.active).distinct.length shouldBe 1
  }
}
