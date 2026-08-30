package com.realityengine.perception

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A caller may not assert a sensor into activity it has not earned (#199).
  *
  * Registration declares a source completely and inactive (#163 point 2a);
  * activity is earned by the first value (point 2b). The integration paths
  * already honoured this — they go through `declareSource` — but
  * `POST /api/sources` reaches `addSource`, which is a constructor and takes
  * the flag it is given. An external caller could therefore do what no
  * integration could.
  *
  * The invariant is enforced at the HTTP surface rather than inside
  * `addSource`, which is documented as honouring the activity it is given
  * ("it is not a registration", SourceDeclarationSpec) and is used to build
  * fixtures that need a specific stored flag. These exercise
  * `deriveSensorActivity`, which is what the route applies.
  */
class SensorActivationSurfaceSpec extends AnyFlatSpec with Matchers {

  private val Now = 1_000_000L

  private def sensor(active: Boolean,
                     lastUpdated: Option[Long] = None,
                     ttlMs: Long = 60000L) =
    SensorSourceConfig(
      id = "s1", name = "s1", region = Region(40, 4), active = active,
      sensorId = "s.1", lastValue = Vector.empty, lastUpdated = lastUpdated, ttlMs = ttlMs)

  private def derived(s: SourceConfig) =
    new PerceptionEngine(256).deriveSensorActivity(s, Now).active

  "a sensor asking to be active" should "not be granted it without a value" in {
    derived(sensor(active = true)) shouldBe false
  }

  it should "keep the stored flag for a sensor that was fed and then lapsed" in {
    // Fed once, now outside its TTL. The stored flag stays — expiry is a
    // read-time question, and `reported` is `stored AND validated` (#175). That
    // separation is load-bearing: a later value revives the source without
    // anything having to re-establish the flag. Writing the demotion back here
    // would break it, which ReportedActivitySpec asserts against directly.
    derived(sensor(active = true, lastUpdated = Some(Now - 5000L), ttlMs = 10L)) shouldBe true
  }

  "a sensor constructed while delivering a value" should "come out active" in {
    // The MQTT auto-provision and signal-ingest shape: the value is in hand
    // before the source is stored, so the flag is earned at construction. The
    // rule is a conjunction, so the path must still ask for it — what it cannot
    // do is ask without having delivered anything.
    derived(sensor(active = true, lastUpdated = Some(Now))) shouldBe true
  }

  it should "still refuse when the path asks but delivers nothing" in {
    derived(sensor(active = true, lastUpdated = None)) shouldBe false
  }

  "a caller clearing the flag" should "be honoured whatever the value says" in {
    // Deactivation is free: the conjunction makes a requested false final.
    derived(sensor(active = false, lastUpdated = Some(Now))) shouldBe false
  }

  "a non-sensor source" should "be left alone" in {
    // The rule is about integration sources whose activity is traceable to an
    // ingress event. A simulated source generates unconditionally.
    val sim = SimulatedSourceConfig(
      id = "sim", name = "sim", region = Region(80, 2), active = true,
      pattern = SimPattern.Constant, frequency = 1.0, amplitude = 1.0, dcOffset = 0.0)
    new PerceptionEngine(256).deriveSensorActivity(sim, Now).active shouldBe true
  }

  "deactivation" should "be honoured where activation is not" in {
    // Activation is earned; deactivation is not, and the two directions do not
    // need the same rule. Clearing the flag asserts nothing about ingress — the
    // source keeps its value and its TTL, and the next value re-earns activity.
    // Derivation applied to both directions would leave a live sensor with no
    // way to be paused (RealityEngine_CPP#43).
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor(active = true, lastUpdated = Some(System.currentTimeMillis())))
    engine.getSource("s1").map(_.active) shouldBe Some(true)

    engine.deactivateSource("s1") shouldBe true
    engine.getSource("s1").map(_.active) shouldBe Some(false)

    // Still declared, still holding its value — paused, not withdrawn.
    engine.getSource("s1") should not be empty
  }

  it should "report false for an unknown id rather than pretending" in {
    new PerceptionEngine(256).deactivateSource("nope") shouldBe false
  }
}
