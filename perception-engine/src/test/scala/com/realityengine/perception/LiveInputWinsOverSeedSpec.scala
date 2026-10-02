package com.realityengine.perception

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** Live inputs always win over the seed on a shared lane (owner decision,
  * 2026-10-02, RealityEngine_CPP#146).
  *
  * A machine's interned test source writes its machine's input region; when that
  * region is a service lane, a live source writes it too. The seed is
  * ISRESeed(n), the base every live input folds over, so assembly writes the
  * seed tier first and live sources after — the live value lands whatever the
  * names. Before the tier, a test source named after the sensor won by sort
  * order and replayed its stimulus over the live reading.
  *
  * The live source registers through `declareSource`, the path every integration
  * (HealthKit included) uses: declared inactive, made active by its first value.
  */
class LiveInputWinsOverSeedSpec extends AnyFlatSpec with Matchers {

  private val lane = Region(4, 4)

  private def seed(region: Region = lane) = TestSourceConfig(
    id           = "test-machine-vitals",
    name         = "Zz Vitals Monitor / 2 sequences", // sorts after the sensor
    region       = region,
    active       = true,
    machineId    = "machine-vitals",
    machineName  = "Zz Vitals Monitor",
    sequenceName = "seq",
    inputs       = Vector(Vector(1.0, 1.0, 1.0, 1.0)),
    loop         = false,
  )

  private def live() = SensorSourceConfig(
    id          = "healthkit-bp",
    name        = "HealthKit Blood Pressure",
    region      = lane,
    active      = true,
    sensorId    = "healthkit.blood-pressure",
    lastValue   = Vector.empty,
    lastUpdated = None,
    ttlMs       = 300000L,
    origin      = Some("healthkit"),
  )

  private def cells(v: Vector[Double], from: Int) = v.slice(from, from + 4).map(x => math.round(x * 100) / 100.0)

  "assembly" should "land the live reading, not the seed, on a shared lane" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(seed())
    engine.declareSource(live())
    engine.updateSensorValue("healthkit.blood-pressure", Vector(0.72, 0.48, 0.24, 0.99))
    cells(engine.assembleVector(), 4) shouldBe Vector(0.72, 0.48, 0.24, 0.99)
  }

  it should "let the live source win regardless of registration order" in {
    val engine = new PerceptionEngine(16)
    engine.declareSource(live())
    engine.addSource(seed())
    engine.updateSensorValue("healthkit.blood-pressure", Vector(0.1, 0.2, 0.3, 0.4))
    cells(engine.assembleVector(), 4) shouldBe Vector(0.1, 0.2, 0.3, 0.4)
  }

  it should "leave a seed-only lane to the seed" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(seed(Region(10, 4)))
    engine.declareSource(live())
    engine.updateSensorValue("healthkit.blood-pressure", Vector(0.5, 0.5, 0.5, 0.5))
    cells(engine.assembleVector(), 10) shouldBe Vector(1.0, 1.0, 1.0, 1.0)
  }

  it should "keep the seed where the live source has not reported" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(seed())
    engine.declareSource(live()) // declared, never fed: inactive, so it does not write
    cells(engine.assembleVector(), 4) shouldBe Vector(1.0, 1.0, 1.0, 1.0)
  }
}
