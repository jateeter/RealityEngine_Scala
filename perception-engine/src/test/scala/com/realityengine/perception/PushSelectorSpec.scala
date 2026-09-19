package com.realityengine.perception

import com.realityengine.perception.api.PushRequest
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** `POST /api/push` honours the `only` subset selector (RealityEngine_CI#367).
  *
  * The selector exists because a step answers ~1.6 MB at full corpus and the
  * transient allocation during serialization exhausted two runtimes' heaps
  * mid-sweep. `machineResults` is 1422 KB of that 1637 KB, so a selector that
  * narrows everything *except* `machineResults` satisfies the shape of the
  * contract and none of its purpose — which is exactly what one runtime shipped.
  *
  * The selector is applied to the reply and never to the request this engine
  * makes of the Reality Engine. The RE filters `machineResults` like every
  * other field, and `VectorAggregator.aggregate` reads that field to build the
  * next InputSpaceVector, so forwarding it would make what the caller asked to
  * be *shown* decide what the engine *computes*. That is covered end-to-end by
  * `RealityEngine_CI/scripts/regression-perceive-selector.py`; what is covered
  * here is the filter itself.
  */
class PushSelectorSpec extends AnyWordSpec with Matchers {

  // Two machines. `m-keep` is named by the selector; `m-seq` is not, but
  // produces an operation carrying a requested sequence id. `m-drop` is
  // neither and must not survive any selection.
  private val step: Json = parse(
    """{
      |  "stepNumber": 7,
      |  "machineResults": {
      |    "m-keep": {"machineId": "m-keep", "machineName": "Kept Machine"},
      |    "m-seq":  {"machineId": "m-seq",  "machineName": "Sequence Machine"},
      |    "m-drop": {"machineId": "m-drop", "machineName": "Dropped Machine"}
      |  },
      |  "mergeBatch": [
      |    {"machineId": "m-keep", "sequenceIds": ["seq-keep"]},
      |    {"machineId": "m-seq",  "sequenceIds": ["seq-wanted"]},
      |    {"machineId": "m-drop", "sequenceIds": ["seq-other"]}
      |  ],
      |  "eventBus": [
      |    {"producerSequenceId": "seq-wanted", "producerMachineId": "m-seq",  "subscriberMachineId": "m-drop"},
      |    {"producerSequenceId": "seq-other",  "producerMachineId": "m-drop", "subscriberMachineId": "m-drop"}
      |  ],
      |  "activeRegions": [
      |    {"machineId": "m-keep", "offset": 0, "length": 2},
      |    {"machineId": "m-drop", "offset": 4, "length": 2}
      |  ],
      |  "perceptualSpace": [0.0, 1.0, 2.0]
      |}""".stripMargin).getOrElse(Json.Null)

  private def ids(j: Json, field: String): Set[String] =
    j.hcursor.downField(field).focus.flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def len(j: Json, field: String): Int =
    j.hcursor.downField(field).focus.flatMap(_.asArray).map(_.length).getOrElse(-1)

  private def selector(s: String): Option[Json] = PushRequest.onlyFrom(s"""{"only": $s}""")

  "PushRequest.applySelector" should {

    "return the step untouched when no selector was supplied" in {
      // Several regression stages compare this wire exactly, so the default
      // must not move — not even to a re-serialized equivalent.
      PushRequest.applySelector(step, None) shouldBe step
    }

    "keep a machine the selector names" in {
      val out = PushRequest.applySelector(step, selector("""{"machineNames": ["Kept Machine"]}"""))
      ids(out, "machineResults") shouldBe Set("m-keep")
      len(out, "mergeBatch") shouldBe 1
      len(out, "activeRegions") shouldBe 1
    }

    "keep a machine that produced an operation carrying a requested sequence id" in {
      // Attribution is by sequence id, never by region: a region can have more
      // than one writer, a sequence id cannot.
      val out = PushRequest.applySelector(step, selector("""{"sequenceIds": ["seq-wanted"]}"""))
      ids(out, "machineResults") shouldBe Set("m-seq")
      len(out, "mergeBatch") shouldBe 1
      len(out, "eventBus") shouldBe 1
    }

    "keep an entry matching either list when both are given" in {
      val out = PushRequest.applySelector(
        step, selector("""{"machineNames": ["Kept Machine"], "sequenceIds": ["seq-wanted"]}"""))
      ids(out, "machineResults") shouldBe Set("m-keep", "m-seq")
    }

    "select nothing for a sequence id that matches nothing" in {
      // Not an error, and above all not the universe. A filter that silently
      // widens on a miss cannot be told apart from one that was ignored, and
      // every assertion about the entries the caller wanted still passes.
      val out = PushRequest.applySelector(step, selector("""{"sequenceIds": ["no-such-sequence"]}"""))
      ids(out, "machineResults") shouldBe empty
      len(out, "mergeBatch") shouldBe 0
      len(out, "eventBus") shouldBe 0
      len(out, "activeRegions") shouldBe 0
    }

    "select nothing for a selector that names nothing" in {
      // `only: {}` is present, so the selector is active, and naming nothing
      // then selects nothing. This is the same unfalsifiability argument as the
      // case above: a caller building `sequenceIds` from a list that happened to
      // be empty must not be handed the universe.
      val out = PushRequest.applySelector(step, selector("{}"))
      ids(out, "machineResults") shouldBe empty
      len(out, "mergeBatch") shouldBe 0
    }

    "leave fields the step does not carry absent rather than emptying them" in {
      // `includeActiveRegions: false` omits the key. Emitting `[]` instead would
      // claim no regions were active, which is a different statement from "not
      // asked for" — and the parity stage compares key sets.
      val trimmed = step.mapObject(_.remove("activeRegions").remove("eventBus"))
      val out = PushRequest.applySelector(trimmed, selector("""{"machineNames": ["Kept Machine"]}"""))
      out.hcursor.downField("activeRegions").focus shouldBe None
      out.hcursor.downField("eventBus").focus shouldBe None
    }

    "leave everything outside the selectable fields alone" in {
      val out = PushRequest.applySelector(step, selector("""{"machineNames": ["Kept Machine"]}"""))
      out.hcursor.get[Int]("stepNumber").toOption shouldBe Some(7)
      out.hcursor.get[Vector[Double]]("perceptualSpace").toOption shouldBe Some(Vector(0.0, 1.0, 2.0))
    }
  }

  "PushRequest.onlyFrom" should {
    "read a selector that is present" in {
      PushRequest.onlyFrom("""{"only": {"sequenceIds": ["a"]}}""") should not be empty
    }
    "treat a selector naming nothing as present" in {
      PushRequest.onlyFrom("""{"only": {}}""") should not be empty
    }
    "report no selector when the key is absent, or the body is not JSON" in {
      // The push route historically took no entity at all, so callers post an
      // empty body or something that is not JSON. Neither is a request to
      // filter, and neither is an error.
      PushRequest.onlyFrom("""{"compact": true}""") shouldBe None
      PushRequest.onlyFrom("") shouldBe None
      PushRequest.onlyFrom("not json") shouldBe None
    }
    "report no selector when `only` is present but is not an object" in {
      PushRequest.onlyFrom("""{"only": ["a"]}""") shouldBe None
      PushRequest.onlyFrom("""{"only": null}""") shouldBe None
    }
  }
}
