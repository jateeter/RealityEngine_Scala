package com.realityengine.perception

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** Sources are declared by integrations; integrations register; registration
  * declares (RealityEngine_CI#163 points 1, 2a, 2b, 3).
  *
  * The two halves are on different events and must stay that way:
  * *membership* moves only on register and deregister, *activity* is earned by
  * the first value. MQTT and the completion/HealthKit ingest paths used to
  * collapse both into one — conjuring the source record `active = true` when
  * its first value arrived — so no declared-inactive state existed for those
  * integrations and nothing could observe the set before it was live.
  */
class SourceDeclarationSpec extends AnyFlatSpec with Matchers {

  private def sensor(sensorId: String, active: Boolean = true) = SensorSourceConfig(
    id          = sensorId,
    name        = s"mqtt:$sensorId",
    region      = Region(40, 4),
    active      = active,
    sensorId    = sensorId,
    lastValue   = Vector.empty,
    lastUpdated = None,
    ttlMs       = 60000L,
    origin      = Some("mqtt"),
  )

  "declareSource" should "declare the source inactive, whatever activity the registration asked for" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1", active = true))
    engine.getSource("s1").map(_.active) shouldBe Some(false)
  }

  it should "put the source on GET /api/sources before any traffic arrives" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1"))
    engine.getSources.map(_.id) should contain("s1")
  }

  it should "contribute nothing until it has been fed" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1"))
    engine.assembleVector().forall(_ == 0.0) shouldBe true
  }

  it should "be idempotent — re-registration is the same event, not a second source" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1"))
    engine.updateSensorValue("s1", Vector(1.0, 1.0, 1.0, 1.0))
    engine.declareSource(sensor("s1"))
    engine.getSources.count(_.id == "s1") shouldBe 1
    engine.getSource("s1").map(_.active) shouldBe Some(true)
  }

  it should "leave a live source's cached value alone when its integration re-registers" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1"))
    engine.updateSensorValue("s1", Vector(1.0, 1.0, 1.0, 1.0))
    engine.declareSource(sensor("s1"))
    engine.assembleVector().slice(40, 44) shouldBe Vector(1.0, 1.0, 1.0, 1.0)
  }

  it should "dedupe on the logical sensorId, not only the generated id" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1").copy(id = ""))
    engine.declareSource(sensor("s1").copy(id = ""))
    engine.getSources.collect { case s: SensorSourceConfig if s.sensorId == "s1" => s } should have length 1
  }

  "the first value" should "be what earns a declared source its activity" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(sensor("s1"))
    engine.getSource("s1").map(_.active) shouldBe Some(false)
    engine.updateSensorValue("s1", Vector(1.0, 1.0, 1.0, 1.0)) shouldBe true
    engine.getSource("s1").map(_.active) shouldBe Some(true)
    engine.assembleVector().slice(40, 44) shouldBe Vector(1.0, 1.0, 1.0, 1.0)
  }

  "addSource" should "still honour the activity it was given — it is not a registration" in {
    val engine = new PerceptionEngine(256)
    engine.addSource(sensor("s1", active = true))
    engine.getSource("s1").map(_.active) shouldBe Some(true)
  }
}
