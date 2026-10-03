package com.realityengine.perception

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._

/** PATCH /api/sources/:id {"active": true} cannot activate a sensor that has
  * never reported. The route now applies `deriveSensorActivity`, as POST does.
  *
  * Without it the PE Manager's "All On" stored unfed localAI sensors active.
  * The read validated them back to inactive, but assembly uses the stored flag,
  * so each wrote zeros over its lane's seed — scala alone, against cpp and lsp
  * which refuse the activation (RealityEngine_CI tree-to-pe-manager-equivalence).
  */
class PatchCannotActivateUnfedSensorSpec extends AnyFlatSpec with Matchers {

  private val lane = Region(4, 2)

  private val seed = TestSourceConfig(
    id = "seed-rag", name = "localai/rag_corrective_cycle / 4 sequences", region = lane, active = true,
    machineId = "machine-rag", machineName = "localai/rag_corrective_cycle", sequenceName = "seq",
    inputs = Vector(Vector(0.5, 0.7)), loop = true)

  private val unfed = SensorSourceConfig(
    id = "sensor-rag", name = "localai/rag_retrieval", region = lane, active = false,
    sensorId = "localai.rag_retrieval", lastValue = Vector.empty, lastUpdated = None,
    ttlMs = 10000L, origin = Some("localai"))

  /** What the PATCH route does with a body of {"active": true}. */
  private def patchActiveTrue(engine: PerceptionEngine, id: String): Unit = {
    val existing = engine.getSource(id).get
    engine.updateSource(id, engine.deriveSensorActivity(existing.withActive(true), System.currentTimeMillis()))
  }

  "PATCH active:true" should "leave a sensor that has never reported stored inactive" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(seed)
    engine.addSource(unfed)
    patchActiveTrue(engine, "sensor-rag")
    engine.getSource("sensor-rag").get.active shouldBe false
  }

  it should "leave the seed's value on the lane, not the unfed sensor's zeros" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(seed)
    engine.addSource(unfed)
    patchActiveTrue(engine, "sensor-rag")
    engine.assembleVector().slice(4, 6) shouldBe Vector(0.5, 0.7)
  }

  it should "still activate a sensor that has reported" in {
    val engine = new PerceptionEngine(16)
    engine.addSource(unfed)
    engine.updateSensorValue("localai.rag_retrieval", Vector(0.2, 0.3))
    engine.deactivateSource("sensor-rag")
    patchActiveTrue(engine, "sensor-rag")
    engine.getSource("sensor-rag").get.active shouldBe true
  }
}
