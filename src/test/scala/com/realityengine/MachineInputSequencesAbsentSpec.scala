package com.realityengine

import com.realityengine.services.MachineLoader
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A machine imported without `inputSequences` keeps it absent, as on C++ and
  * LSP. The loader defaulted it to `[]`, inventing a key the other runtimes do
  * not carry on every such machine (localAI's topology machines), and
  * overwriting a value the machine held in its own metadata.
  */
class MachineInputSequencesAbsentSpec extends AnyFlatSpec with Matchers {

  private def machine(extra: String) =
    s"""{"version": "1.0.0", "machine": {"name": "Topology Fixture", "description": "d", "arbiterRule": "PASSTHROUGH",
       |  "perceptualMapping": {"input": {"offset": 0, "length": 2}, "output": {"offset": 8, "length": 2}},
       |  "sequences": [{"id": "s", "name": "S", "events": [{"id": "v1", "name": "A", "isInitial": true,
       |                 "elements": [{"value": 1}, {"value": 0}]}]}]$extra}}""".stripMargin

  "MachineLoader" should "not invent inputSequences for a machine that declares none" in {
    MachineLoader.loadFromJson(machine("")).metadata.contains("inputSequences") shouldBe false
  }

  it should "carry a top-level inputSequences into metadata" in {
    val m = MachineLoader.loadFromJson(machine(""", "inputSequences": [{"name": "x", "events": [[1, 0]]}]"""))
    m.metadata.get("inputSequences").flatMap(_.asArray).map(_.size) shouldBe Some(1)
  }

  it should "keep an inputSequences the machine carries in its own metadata" in {
    val m = MachineLoader.loadFromJson(machine(""", "metadata": {"inputSequences": [{"name": "y", "events": [[0, 1]]}]}"""))
    m.metadata.get("inputSequences").flatMap(_.asArray).map(_.size) shouldBe Some(1)
  }
}
