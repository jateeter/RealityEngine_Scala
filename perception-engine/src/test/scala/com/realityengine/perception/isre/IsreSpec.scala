package com.realityengine.perception.isre

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** The PE half of K-lines (RealityEngine_CI#603): the taxonomy derivation, the
  * runs and the store, as RealityEngine_CPP tests/isre_tests.cpp. */
class IsreSpec extends AnyFlatSpec with Matchers {
  import Isre._

  private val dir = Files.createTempDirectory("isre-spec")
  Files.write(dir.resolve("re-source-kinds-2.json"),
    """{"format":"re-source-kinds","version":2,"purpose":"t","classes":["deterministic","measured","generated"],"providerMapping":{"openclaw":"acp"},"kinds":[{"kind":"test","meaning":"t","class":"deterministic"},{"kind":"sensor","meaning":"s","providers":[{"provider":"healthkit","class":"measured"},{"provider":"signal","class":"measured"},{"provider":"acp","class":"generated"}]},{"kind":"simulated","meaning":"m","class":"measured"},{"kind":"feedback","meaning":"f","class":"deterministic"},{"kind":"none","meaning":"n","class":null}],"unknownProvider":{"provider":"unattributed","class":"generated"},"mappingFromPrevious":[]}"""
      .getBytes(UTF_8))
  private val tx = loadTaxonomy(dir).get

  "the taxonomy" should "load only the version asked for" in {
    loadTaxonomy(dir, 3) shouldBe None
    tx.name shouldBe "re-source-kinds/2"
  }

  it should "derive a sensor's provider from its origin" in {
    tx.sourceEntry("sensor", "openclaw.agent.x") shouldBe Entry("sensor", Some("acp"), "generated")
    tx.sourceEntry("sensor", "") shouldBe Entry("sensor", Some("signal"), "measured")
    tx.sourceEntry("sensor", "Mystery") shouldBe Entry("sensor", Some("unattributed"), "generated")
    tx.sourceEntry("simulated", "") shouldBe Entry("simulated", None, "measured")
  }

  "sources" should "be runs of one winner, split by id and causedBy" in {
    def cb(n: Long) = Json.obj("instance" -> Json.fromString("x"), "lamport" -> Json.fromLong(n))
    val runs = sourcesJson(Seq(
      Winner(11, feedback = false, "a", "test"), Winner(10, feedback = false, "a", "test"), Winner(12, feedback = false, "b", "test"),
      Winner(20, feedback = true), Winner(21, feedback = true),
      Winner(30, feedback = false, "h", "sensor", "healthkit", cb(1)), Winner(31, feedback = false, "h", "sensor", "healthkit", cb(2))), tx)
      .asArray.get
    runs.length shouldBe 5
    jcs(runs(0).hcursor.downField("cells").focus.get) shouldBe "[10,11]"
    runs(2).hcursor.get[String]("kind") shouldBe Right("feedback")
    runs(2).hcursor.downField("provider").focus shouldBe None
  }

  "the recorder" should "page by commit and export a verifiable bundle" in {
    val store = Files.createTempDirectory("isre-store")
    val r = new IsreRecorder("01a2aaaa-0000-7000-8000-0000000c0076", new IsreStore(Some(store)), Some(tx))
    r.capture = true
    (1 to 5).foreach { c =>
      r.record(Json.obj("instance" -> Json.fromString("re"), "lamport" -> Json.fromLong(c.toLong), "commit" -> Json.fromLong(c.toLong),
        "step" -> Json.fromLong(c.toLong)), Json.obj("instance" -> Json.fromString("pe"), "lamport" -> Json.fromLong(c.toLong)),
        Seq(Winner(1, feedback = true)), Vector.empty)
    }
    val page = r.list(0, -1, 2)
    page.hcursor.downField("records").focus.get.asArray.get.length shouldBe 2
    page.hcursor.get[Long]("next") shouldBe Right(3L)
    val bundle = r.exportBundle(2, 4)
    val (header, body) = bundle.splitAt(bundle.indexOf('\n') + 1)
    val h = io.circe.parser.parse(header).toOption.get
    h.hcursor.get[Int]("count") shouldBe Right(3)
    h.hcursor.get[String]("sha256") shouldBe Right(sha256Hex(body))
    // A reopened store sees the same records.
    new IsreStore(Some(store)).metas.length shouldBe 5
  }
}
