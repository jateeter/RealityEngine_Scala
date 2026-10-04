package com.realityengine.perception

import com.realityengine.perception.engine.{FoldArbitration, PerceptionEngine}
import com.realityengine.perception.models._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The fold on a declared cell (ARBITER_CONTRACT.md §4.4b, amended 2026-10-04,
  * RealityEngine_CI#525): the arbitration registry's rule governs, so under
  * PRECEDENCE {acp:1, machine:3} a machine at 0 beats an agent at 1 -- the one
  * pair where T_M ('or' = max) would let the generated value win. Every fold is
  * recorded and counted; undeclared cells keep T_M. */
class FoldDeclaredRuleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private def near(a: Double, b: Double) = math.abs(a - b) < 1e-12

  override def afterEach(): Unit = FoldArbitration.install(Map.empty)

  "the provider of a source" should "be the first origin segment through the surface aliases" in {
    import FoldArbitration.sourceProvider
    sourceProvider(Some("acp.openclaw.target.assessment"), "sensor") shouldBe "acp"
    sourceProvider(Some("openclaw"), "sensor") shouldBe "acp"
    sourceProvider(Some("ollama"), "sensor") shouldBe "localai"
    sourceProvider(Some("localai.x-mcp-y"), "sensor") shouldBe "localai" // never a substring
    sourceProvider(Some("mqtt"), "sensor") shouldBe "mqtt"
    sourceProvider(None, "sensor") shouldBe "sensor"
    sourceProvider(Some("signal"), "test") shouldBe "synthetic"
    FoldArbitration.determinismRank(sourceProvider(Some("somesurface.x"), "sensor")) shouldBe 1 // generated
  }

  "the fold" should "apply a declared PRECEDENCE, keep T_M elsewhere, and record every fold" in {
    FoldArbitration.install(Map(
      50 -> FoldArbitration.Entry("PRECEDENCE", Map("acp" -> 1, "machine" -> 3)),
      52 -> FoldArbitration.Entry("PRECEDENCE", Map("acp" -> 3, "machine" -> 3)),
      53 -> FoldArbitration.Entry("PRECEDENCE", Map("acp" -> 1, "machine" -> 3))))
    val engine = new PerceptionEngine(64)
    engine.updateFromPerceptualSpace(Vector.tabulate(64)(i => if (i == 51) 0.2 else 0.0))
    engine.addSource(SensorSourceConfig(
      id = "agent", name = "agent assessment", region = Region(50, 3), active = false,
      sensorId = "agent", lastValue = Vector.empty, lastUpdated = None, ttlMs = 300000L,
      origin = Some("acp.openclaw.target.assessment")))
    engine.updateSensorValue("agent", Vector(1.0, 1.0, 1.0)) shouldBe true
    // A seed the cell does not name, on declared cell 53: it keeps T_M.
    engine.addSource(TestSourceConfig(
      id = "seed", name = "unnamed seed", region = Region(53, 1), active = true,
      machineId = "m", machineName = "m", sequenceName = "s", inputs = Vector(Vector(1.0)), loop = true))
    engine.setOsreFoldCells(Map(50 -> ("Peer", "or"), 51 -> ("Peer", "or"), 52 -> ("Peer", "or"), 53 -> ("Peer", "or")))

    val (v, folds) = engine.assembleWithFolds()
    near(v(50), 0.0) shouldBe true // PRECEDENCE: the machine's 0 beats the agent's 1 (5a)
    near(v(51), 1.0) shouldBe true // undeclared: T_M = max(1, 0.2)
    near(v(52), 1.0) shouldBe true // equal ranks fall back to T_M
    near(v(53), 1.0) shouldBe true // an unnamed provider keeps T_M on a declared cell
    folds.map(_.hcursor.get[Int]("cell").toOption.get) shouldBe Vector(50, 51, 52, 53)
    def str(i: Int, k: String) = folds(i).hcursor.get[String](k).toOption
    def side(i: Int, s: String, k: String) = folds(i).hcursor.downField(s).get[String](k).toOption
    str(0, "resolution") shouldBe Some("declared-rule")
    str(0, "rule") shouldBe Some("PRECEDENCE")
    str(0, "kept") shouldBe Some("osre")
    side(0, "source", "provider") shouldBe Some("acp")
    side(0, "osre", "machine") shouldBe Some("Peer")
    str(1, "resolution") shouldBe Some("osre-fold")
    str(1, "operator") shouldBe Some("or")
    str(1, "kept") shouldBe Some("source")
    str(2, "declaredRule") shouldBe Some("PRECEDENCE")
    str(3, "review") shouldBe Some("provider-unranked")
    side(3, "source", "provider") shouldBe Some("synthetic")

    engine.recordContention()
    val json = engine.contentionJson
    json.hcursor.downField("folds").focus.flatMap(_.asArray).map(_.size) shouldBe Some(4)
    val counter = json.hcursor.downField("counters").downN(0)
    counter.get[Long]("contended") shouldBe Right(1L)
    counter.get[Long]("suppressed") shouldBe Right(1L)
    engine.assembleVector() // a read assembles, but never records or counts
    engine.contentionJson.hcursor.downField("counters").downN(0).get[Long]("contended") shouldBe Right(1L)
    engine.reset()
    engine.contentionJson.hcursor.downField("folds").focus.flatMap(_.asArray).map(_.size) shouldBe Some(0)
  }
}
