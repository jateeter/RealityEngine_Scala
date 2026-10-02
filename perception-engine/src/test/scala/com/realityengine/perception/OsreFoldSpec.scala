package com.realityengine.perception

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.{OsreFold, PerceptionEngine}
import com.realityengine.perception.models._

/** A source on an OSRE cell is folded with the OSRE value by the writing
  * machine's declared operator over [0..1] (ARBITER_CONTRACT.md §4.4b). */
class OsreFoldSpec extends AnyFlatSpec with Matchers {

  private def near(a: Double, b: Double) = math.abs(a - b) < 1e-12

  "OsreFold" should "apply each declared operator's [0..1] form" in {
    near(OsreFold("or", 0.3, 0.6), 0.6) shouldBe true
    near(OsreFold("join", 0.3, 0.6), 0.6) shouldBe true
    near(OsreFold("and", 0.3, 0.6), 0.3) shouldBe true
    near(OsreFold("meet", 0.3, 0.6), 0.3) shouldBe true
    near(OsreFold("discrete-median", 0.3, 0.6), 0.3) shouldBe true
    near(OsreFold("strong-disjunction", 0.7, 0.6), 1.0) shouldBe true
    near(OsreFold("strong-conjunction", 0.7, 0.6), 0.3) shouldBe true
    near(OsreFold("strong-conjunction", 0.2, 0.3), 0.0) shouldBe true
    near(OsreFold("xor", 0.25, 1.0), 0.75) shouldBe true
    near(OsreFold("nor", 0.25, 0.5), 0.5) shouldBe true
    near(OsreFold("nand", 0.25, 0.5), 0.75) shouldBe true
    near(OsreFold("not-a-name", 0.25, 0.5), 0.5) shouldBe true
  }

  it should "map mergeBatch output cells to their machine's operator, first by name on shared cells" in {
    val step = parse("""{"mergeBatch":[
      {"machineId":"m-z","region":{"offset":10,"length":2}},
      {"machineId":"m-a","region":{"offset":11,"length":2}}]}""").toOption.get
    val ops = Map("m-z" -> ("Zeta", "and"), "m-a" -> ("Alpha", "strong-disjunction"))
    OsreFold.cells(step, ops.get) shouldBe Map(10 -> "and", 11 -> "strong-disjunction", 12 -> "strong-disjunction")
  }

  "assembly" should "fold a source on an OSRE cell and leave OSRE-only cells alone" in {
    val engine = new PerceptionEngine(64)
    val ps = Vector.tabulate(64)(i => if (i >= 50 && i <= 52) 0.6 else 0.0)
    engine.updateFromPerceptualSpace(ps)
    engine.addSource(TestSourceConfig(
      id = "seed-osre", name = "OSRE lane seed", region = Region(50, 2), active = true,
      machineId = "m", machineName = "m", sequenceName = "s", inputs = Vector(Vector(0.3, 0.3)), loop = true))
    near(engine.assembleVector()(50), 0.3) shouldBe true
    engine.setOsreFold(Map(50 -> "or", 51 -> "and", 52 -> "or"))
    val v = engine.assembleVector()
    near(v(50), 0.6) shouldBe true
    near(v(51), 0.3) shouldBe true
    near(v(52), 0.6) shouldBe true
    engine.reset()
    near(engine.assembleVector()(50), 0.3) shouldBe true
  }
}
