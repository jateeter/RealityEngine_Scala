package com.realityengine

import com.realityengine.models._
import com.realityengine.services.MachineLoader
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `GET /api/machines/:id/export` carries the event as it is RUNNING, not only
 * as it was declared.
 *
 * This runtime exported **6** event fields where C++ and LSP export **10**,
 * omitting `isActive`, `matchAlgorithm`, `state` and `wasJustMatched`. The
 * Manager's CES tooltip renders its live activation layer from exactly those,
 * so that layer was inert whenever the active engine was Scala
 * (RealityEngine_Scala#104).
 *
 * It also omitted `outputEvents[].timestamp` and `.provenance` — not named in
 * that issue, which compared event keys and not outputEvent keys. Measured
 * against a live cpp-1: 3 outputEvent fields against C++'s 5.
 *
 * The field LISTS are asserted, not a count. A count passes while the wrong
 * four are present, and the whole point is wire-compatibility with two other
 * runtimes.
 */
class MachineExportFidelitySpec extends AnyFlatSpec with Matchers {

  // The field sets C++ and LSP both emit, measured on a live three-engine
  // universe against `Kleene Star Operator`.
  private val CppEventFields = List(
    "elements", "id", "isActive", "isInitial", "matchAlgorithm", "metadata",
    "nextEventIds", "outputEvents", "state", "wasJustMatched")
  private val CppOutputEventFields = List("id", "metadata", "provenance", "timestamp", "vector")

  private def exported(): (List[String], List[String], io.circe.Json) = {
    val m = new Machine("Export Probe", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(0, 1), RegionMapping(20, 2))), "machine-export")
    val seq = new CriticalEventSequence("Export Seq", "seq-export")
    val ev  = new RealityEvent(Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))),
                               true, "ev-1")
    ev.addOutputVector(OutputVector("out-1", Vector(1.0, 0.0), Map.empty, 1234L, List("in-1")))
    seq.addVector(ev)
    m.addSequence(seq)

    val json  = parse(MachineLoader.saveToJson(m)).toOption.get
    val event = json.hcursor.downField("machine").downField("sequences").downArray
                    .downField("events").downArray
    val eventKeys  = event.keys.map(_.toList.sorted).getOrElse(Nil)
    val outputKeys = event.downField("outputEvents").downArray.keys.map(_.toList.sorted).getOrElse(Nil)
    (eventKeys, outputKeys, json)
  }

  "the exported event" should "carry every field C++ and LSP carry" in {
    val (eventKeys, _, _) = exported()
    eventKeys shouldBe CppEventFields.sorted
  }

  "the exported output event" should "carry every field C++ and LSP carry" in {
    val (_, outputKeys, _) = exported()
    outputKeys shouldBe CppOutputEventFields.sorted
  }

  it should "export the real timestamp and provenance, not placeholders" in {
    // A field present with a hardcoded value is not wire-compatibility. LSP had
    // exactly that defect on the other side of this pair — timestamp always 0
    // (RealityEngine_LSP#104).
    val (_, _, json) = exported()
    val ov = json.hcursor.downField("machine").downField("sequences").downArray
                 .downField("events").downArray.downField("outputEvents").downArray
    ov.get[Long]("timestamp").toOption shouldBe Some(1234L)
    ov.get[List[String]]("provenance").toOption shouldBe Some(List("in-1"))
  }

  "an initial event" should "export as active, with state and isActive agreeing" in {
    // They are the same fact — RealityEvent defines isActive as
    // `state == VectorState.Active` — so exporting one without the other would
    // trade a missing-field divergence for a value-level one.
    val (_, _, json) = exported()
    val event = json.hcursor.downField("machine").downField("sequences").downArray
                    .downField("events").downArray
    event.get[Boolean]("isActive").toOption shouldBe Some(true)
    event.get[String]("state").toOption shouldBe Some("active")
    event.get[Boolean]("wasJustMatched").toOption shouldBe Some(false)
    event.get[String]("matchAlgorithm").toOption shouldBe Some("gte")
  }
}
