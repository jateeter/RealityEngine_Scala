package com.realityengine.perception

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.{ContendedCell, PerceptionEngine}
import com.realityengine.perception.models._

/** Two sources on one cell: the incumbent writer keeps it (ARBITER_CONTRACT.md
  * §4.4b, owner decision 2026-10-02).
  *
  * Two sources writing a cell in one transition violates the single transition
  * time constraint. Within a tier the source activated earliest keeps the cell;
  * equal activation instants — every seed interned at boot — fall back to
  * canonical (name, id), first in order winning. Before this the last name won.
  */
class SttIncumbentSourceSpec extends AnyFlatSpec with Matchers {

  private def seed(id: String, name: String, region: Region, value: Double) = TestSourceConfig(
    id           = id,
    name         = name,
    region       = region,
    active       = true,
    machineId    = s"machine-$id",
    machineName  = name,
    sequenceName = "seq",
    inputs       = Vector(Vector.fill(region.length)(value)),
    loop         = true,
  )

  private def sensor(id: String, sensorId: String, region: Region) = SensorSourceConfig(
    id          = id,
    name        = id,
    region      = region,
    active      = true,
    sensorId    = sensorId,
    lastValue   = Vector.empty,
    lastUpdated = None,
    ttlMs       = 300000L,
    origin      = None,
  )

  private def cell(cells: Vector[ContendedCell], n: Int) = cells.find(_.cell == n).get

  "assembly" should "give equal instants to the first source in (name, id) order, not the last" in {
    val engine = new PerceptionEngine(64)
    engine.addSource(seed("seed-b", "Beta seed", Region(10, 2), 0.25))
    engine.addSource(seed("seed-a", "Alpha seed", Region(10, 2), 0.75))
    engine.assembleVector()(10) shouldBe 0.75
    val cells = engine.sourceContention()
    cells.map(_.cell) shouldBe Vector(10, 11)
    val c = cell(cells, 10)
    c.resolution shouldBe "incumbent"
    c.winner.id shouldBe "seed-a"
    c.suppressed.map(_.id) shouldBe Vector("seed-b")
  }

  it should "let a newcomer lose however its name sorts, and forfeit a cell given up" in {
    val engine = new PerceptionEngine(64)
    engine.addSource(seed("seed-m", "Middle seed", Region(20, 1), 0.5))
    engine.advance() // transition 1
    engine.addSource(seed("seed-z", "Aardvark seed", Region(20, 1), 1.0))
    engine.assembleVector()(20) shouldBe 0.5

    engine.deactivateSource("seed-m") shouldBe true
    engine.advance() // transition 2
    engine.updateSource("seed-m", engine.getSource("seed-m").get.withActive(true))
    engine.assembleVector()(20) shouldBe 1.0 // the former incumbent is now the newcomer
    cell(engine.sourceContention(), 20).winner.activatedAt shouldBe 1L

    // A patch of a source that stays active keeps its claim.
    engine.updateSource("seed-z", engine.getSource("seed-z").get.asInstanceOf[TestSourceConfig].copy(name = "Renamed"))
    cell(engine.sourceContention(), 20).winner.id shouldBe "seed-z"
    cell(engine.sourceContention(), 20).winner.activatedAt shouldBe 1L
  }

  it should "decide live over seed by tier, and live against live by incumbency" in {
    val engine = new PerceptionEngine(64)
    engine.addSource(seed("seed-hk", "Zz HealthKit seed", Region(30, 1), 1.0))
    engine.advance() // transition 1
    engine.declareSource(sensor("live-early", "hk.early", Region(30, 1)))
    engine.updateSensorValue("hk.early", Vector(0.25))
    engine.advance() // transition 2
    engine.declareSource(sensor("live-late", "hk.late", Region(30, 1)))
    engine.updateSensorValue("hk.late", Vector(0.75))
    engine.assembleVector()(30) shouldBe 0.25
    val c = cell(engine.sourceContention(), 30)
    c.resolution shouldBe "incumbent"
    c.winner.id shouldBe "live-early"
    c.winner.activatedAt shouldBe 1L
    c.suppressed.map(_.id).toSet shouldBe Set("live-late", "seed-hk")

    engine.deactivateSource("live-late")
    val t = cell(engine.sourceContention(), 30)
    t.resolution shouldBe "live-over-seed"
    t.winner.id shouldBe "live-early"
  }

  "contention" should "be counted only by the push, and cleared by reset" in {
    val engine = new PerceptionEngine(64)
    engine.addSource(seed("seed-a", "Alpha seed", Region(40, 1), 1.0))
    engine.advance()
    engine.addSource(seed("seed-b", "Beta seed", Region(40, 1), 0.5))
    engine.sourceContention()
    engine.contentionJson.hcursor.downField("counters").values.get shouldBe empty

    engine.recordContention()
    engine.recordContention()
    val j        = engine.contentionJson.hcursor
    val counters = j.downField("counters").values.get.toVector.map(_.hcursor)
    j.downField("cells").values.get.size shouldBe 1
    counters.map(_.get[String]("id").toOption.get) shouldBe Vector("seed-a", "seed-b")
    counters(0).get[Long]("contended").toOption.get shouldBe 2L
    counters(0).get[Long]("suppressed").toOption.get shouldBe 0L
    counters(1).get[Long]("suppressed").toOption.get shouldBe 2L

    engine.reset()
    cell(engine.sourceContention(), 40).suppressed.head.activatedAt shouldBe 0L
    engine.contentionJson.hcursor.downField("counters").values.get shouldBe empty
    engine.contentionJson.hcursor.downField("cells").values.get shouldBe empty

    engine.recordContention()
    engine.removeSource("seed-b") shouldBe true
    engine.contentionJson.hcursor.downField("counters").values.get.size shouldBe 1
  }
}
