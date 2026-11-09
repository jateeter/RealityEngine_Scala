package com.realityengine.models

import com.realityengine.services.MachineLoader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Cloneable machines (SURFACE_SPEC.md, "Cloneable machines"): each rule of the
  * nine-rule table by the reason code a machine failing only that rule gets;
  * the listing; the block space (lowest free first, a generation per block);
  * and that a clone shares no Reality Event with its template. The same cases
  * as RealityEngine_CPP `verify_clone_space` and RealityEngine_LSP
  * `test-clone-space`. */
class CloneSpaceSpec extends AnyFlatSpec with Matchers {
  import CloneSpace._

  private val producer = """{"type": "published-domain-bus-producer", "targetMachineFile": "Bus.json", "targetInputElements": [0, 1, 2]}"""
  private val agent    = """"agentBinding": {"agent": "a"}"""
  private def md(extra: String, edges: String) =
    s"""{$agent${if (extra.isEmpty) "" else s", $extra"}, "interconnections": [$edges]}"""

  private def fixture(file: String, metadata: String, in: Int = 4, out: Int = 4, mapping: Boolean = true): Machine = {
    val stem = file.stripSuffix(".json")
    val pm = if (mapping) s""""perceptualMapping": {"input": {"offset": 0, "length": $in}, "output": {"offset": 100, "length": $out}},""" else ""
    val json = s"""{"version": "1.0.0", "machine": {"name": "$stem", $pm "metadata": $metadata,
      "sequences": [{"id": "s", "name": "S", "events": [
        {"id": "v1", "isInitial": true, "elements": [{"value": 1}], "nextEventIds": ["v2"]},
        {"id": "v2", "isInitial": false, "elements": [{"value": 1}]}]}]}}"""
    val m = MachineLoader.loadFromJson(json, Some(s"machine-${stem.toLowerCase}"))
    m.sourceFile = Some(file)
    m
  }

  private val machines = Vector(
    fixture("Ok.json", md("", producer)),
    fixture("NoAgent.json", s"""{"interconnections": [$producer]}"""),
    fixture("Handoff.json", """{"interconnections": [{"type": "document-workflow-handoff", "sourceMachine": "Handoff"}]}"""),
    fixture("HandoffElsewhere.json", """{"interconnections": [{"type": "document-workflow-handoff", "sourceMachine": "Other"}]}"""),
    fixture("NoFanout.json", md("", "")),
    fixture("Bus.json", md("", producer)),
    fixture("Safety.json", md(""""severity": "life-safety"""", producer)),
    fixture("Fixture.json", md(""""tags": ["x", "test-fixture"]""", producer)),
    fixture("Out.json", md(""""cloneable": false""", producer)),
    fixture("WideIn.json", md("", producer), in = 17),
    fixture("WideOut.json", md("", producer), in = 16, out = 17),
    fixture("NoMapping.json", md("", producer), mapping = false),
    fixture("WideFan.json", md("", Vector.fill(6)(producer).mkString(", ")))
  )
  private val byId    = machines.map(m => m.id -> m).toMap
  private val targets = busTargetFiles(machines)
  private def verdict(id: String) = assess(byId(id), targets)

  "the nine-rule table" should "give each machine the first rule it fails" in {
    targets shouldBe Set("Bus.json")
    verdict("machine-ok") shouldBe Verdict(cloneable = true, None, Some(7), 3)
    verdict("machine-handoff") shouldBe Verdict(cloneable = true, None, Some(4), 0)
    Map(
      "machine-noagent" -> "no-agentic-action", "machine-handoffelsewhere" -> "no-agentic-action",
      "machine-nofanout" -> "no-fanout", "machine-bus" -> "bus-target", "machine-safety" -> "life-safety",
      "machine-fixture" -> "fixture", "machine-out" -> "opted-out", "machine-widein" -> "input-too-long",
      "machine-wideout" -> "output-too-long", "machine-nomapping" -> "input-too-long"
    ).foreach { case (id, reason) => withClue(id) { verdict(id).reason shouldBe Some(reason) } }
    verdict("machine-widefan") shouldBe Verdict(cloneable = false, Some("fanout-too-wide"), Some(22), 18)
    verdict("machine-bus").n shouldBe None
  }

  it should "never let a machine without a file be a handoff source or a bus target" in {
    val bare = fixture("Handoff.json", """{"interconnections": [{"type": "document-workflow-handoff", "sourceMachine": "Handoff"}]}""")
    bare.sourceFile = None
    assess(bare, Set.empty).reason shouldBe Some("no-agentic-action")
  }

  "the listing" should "sort by id and leave clones out" in {
    val c     = Clone("machine-ok.scala-1.e1", "machine-ok", "scala-1", "e1", "a", 0, 0, 4, 4, 3)
    val clone = cloneMachine(byId("machine-ok"), c)
    val listed = listing(machines :+ clone).hcursor.downField("machines").focus.get.asArray.get
    val ids = listed.flatMap(_.hcursor.get[String]("id").toOption)
    ids shouldBe ids.sorted
    ids should not contain clone.id
    ids.size shouldBe machines.size
  }

  "a clone" should "step its own events, in its own block, leaving the template at its initial state" in {
    val template = byId("machine-ok")
    val clone = cloneMachine(template, Clone("machine-ok.scala-1.e2", "machine-ok", "scala-1", "e2", "a", 0, 0, 4, 4, 3))
    clone.processInput(Vector(1.0))
    def active(m: Machine) = m.getAllSequences.flatMap(_.getActiveVectors.map(_.id)).toSet
    active(template) shouldBe Set("v1")
    active(clone) should contain("v2")
    clone.perceptualMapping.get.input shouldBe RegionMapping(20480, 4)
    clone.perceptualMapping.get.output shouldBe RegionMapping(20496, 4)
    template.perceptualMapping.get.output shouldBe RegionMapping(100, 4)
    clone.toJson.hcursor.get[String]("cloneOf").toOption shouldBe Some("machine-ok")
    template.toJson.hcursor.downField("cloneOf").focus shouldBe None
  }

  "the block space" should "allocate lowest free first and count generations" in {
    val space = new CloneSpace()
    def mk(id: String, block: Int, gen: Long) = Clone(id, "machine-ok", "scala-1", s"e-$id", "a", block, gen, 4, 3, 3)
    space.lowestFreeBlock shouldBe Some(0)
    space.add(mk("a", 0, 0)); space.add(mk("b", 1, 0))
    space.lowestFreeBlock shouldBe Some(2)
    space.liveOf("machine-ok") shouldBe 2
    space.recycle("a") shouldBe 1
    space.lowestFreeBlock shouldBe Some(0)
    an[NoSuchElementException] should be thrownBy space.recycle("a")
    val j = mk("c", 1, 0).toJson.hcursor
    j.downField("block").get[Int]("offset").toOption shouldBe Some(20544)
    j.downField("fanout").get[Int]("offset").toOption shouldBe Some(20576)
    j.downField("bus").get[Int]("offset").toOption shouldBe Some(20592)
    j.downField("bus").get[Int]("length").toOption shouldBe Some(4)
  }
}
