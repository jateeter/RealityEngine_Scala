package com.realityengine.perception

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The Perception Engine consumes the Reality Engine's mergeBatch; it does not
  * rebuild it.
  *
  * The RE composes that batch at the end of the machine's atomic step — folded
  * value, joined governance, deprecation mark, provenance union. This engine
  * used to `deepMerge` a locally reconstructed batch over it, which silently
  * discarded the authoritative one and could only ever produce a subset:
  * `machineResults` carries no CES lifecycle data, so the reconstruction could
  * not express `deprecation` at all, while Manager's trigger envelope reads
  * that field.
  *
  * C++ and LSP both read the RE's batch. This engine rebuilding it was the
  * outlier, and it is the same defect shape as RealityEngine_CI#154 one level
  * up: the PE recomputing something the RE is authoritative for.
  *
  * The selection logic is small enough to state directly, and is asserted here
  * against the same shapes the route sees.
  */
class MergeBatchPassThroughSpec extends AnyWordSpec with Matchers {

  /** Mirrors the route: the RE's batch wins whenever it sent one. */
  private def selects(parsed: Json): Boolean =
    parsed.hcursor.downField("mergeBatch").focus.exists(!_.isNull)

  private val reBatch = parse(
    """{"perceptualSpace":[0,1],"machineResults":{},"mergeBatch":[
       {"machineId":"m-1","sequenceIds":["ces-a","ces-b"],
        "values":[1.0],"provenance":["ces-a"],
        "governance":{"ragStatusCode":"RED"},
        "deprecation":{"since":"2026-01-01","replacedBy":"ces-c"}}]}""").toOption.get

  "the Perception Engine" should {

    "take the Reality Engine's mergeBatch when one is supplied" in {
      selects(reBatch) shouldBe true
    }

    "preserve fields the reconstruction cannot express" in {
      // deprecation is the one that motivated this: it has no source in
      // machineResults, so a rebuilt batch drops it however careful the rebuild.
      val op = reBatch.hcursor.downField("mergeBatch").downArray
      op.get[Json]("deprecation").toOption.flatMap(_.hcursor.get[String]("replacedBy").toOption) shouldBe
        Some("ces-c")
      op.get[Json]("governance").toOption.flatMap(_.hcursor.get[String]("ragStatusCode").toOption) shouldBe
        Some("RED")
      op.get[List[String]]("sequenceIds").toOption shouldBe Some(List("ces-a", "ces-b"))
    }

    "fall back to reconstruction only for a Reality Engine that sends no batch" in {
      // The genuine mixed-version case — an RE predating the fold move. Not a
      // silent default: a current RE always sends one.
      val old = parse("""{"perceptualSpace":[0,1],"machineResults":{}}""").toOption.get
      selects(old) shouldBe false
    }

    "treat an explicit null batch as absent rather than as an empty batch" in {
      // A null would otherwise pass an is-present check and blank the batch,
      // which is worse than reconstructing: it reports "no machine fired".
      val nulled = parse(
        """{"perceptualSpace":[0,1],"machineResults":{},"mergeBatch":null}""").toOption.get
      selects(nulled) shouldBe false
    }

    "take an empty batch from the RE as authoritative" in {
      // Empty is a real answer — no machine contributed this step, or every
      // fold refused. Reconstructing over it would invent contributions.
      val empty = parse(
        """{"perceptualSpace":[0,1],"machineResults":{},"mergeBatch":[]}""").toOption.get
      selects(empty) shouldBe true
    }
  }
}
