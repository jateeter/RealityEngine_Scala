package com.realityengine.engine

import com.realityengine.models._
import com.realityengine.services.MachineLoader
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** Runtime snapshots (RealityEngine_CI#287): capture, the digest, a round trip
  * through restore, and the store's modes. The same checks as
  * RealityEngine_CPP tests/snapshot_tests.cpp. */
class SnapshotSpec extends AnyFlatSpec with Matchers {

  private val linear =
    """{"version":"1.0.0","machine":{"name":"Linear","description":"snapshot test","arbiterRule":"PASSTHROUGH","matchAlgorithm":"gte","perceptualMapping":{"input":{"offset":0,"length":2},"output":{"offset":4,"length":2}},"sequences":[{"id":"s","name":"s","events":[{"id":"e1","elements":[{"value":1,"threshold":0.5},{"value":0,"threshold":0.5}],"isInitial":true,"nextEventIds":["e2"]},{"id":"e2","elements":[{"value":1,"threshold":0.5},{"value":1,"threshold":0.5}],"isInitial":false,"outputEvents":[{"id":"o","vector":[1,1]}]}]}]}}"""

  private def rig(): PerceptualSpaceRuntime = {
    val rt = new PerceptualSpaceRuntime(16)
    rt.addMachine(MachineLoader.loadFromJson(linear))
    rt
  }
  private def step(rt: PerceptualSpaceRuntime, a: Double, b: Double): Unit = {
    val v = Array.fill(16)(0.0); v(0) = a; v(1) = b
    rt.processImmediate(v.toVector)
  }
  private def snap(rt: PerceptualSpaceRuntime, from: Option[Long] = None, to: Option[Long] = None) =
    rt.captureSnapshot(None, from, to, Json.obj(), Json.obj(), Json.obj(), Json.Null)

  "A snapshot" should "be a re-snapshot/1 body over the newest step, digested without the clocks' lamport" in {
    val rt = rig(); step(rt, 1, 0); step(rt, 0, 0)
    val s = snap(rt).toOption.get
    s.hcursor.get[String]("format") shouldBe Right("re-snapshot/1")
    s.hcursor.downField("kline").downField("span").get[Long]("to") shouldBe Right(1L)
    s.hcursor.get[String]("digest") shouldBe Right(Snapshot.digest(s))
    val other = s.hcursor.downField("kline").downField("clocks").downField("to").downField("lamport")
      .withFocus(_ => Json.fromLong(1)).top.get
    Snapshot.digest(other) shouldBe Snapshot.digest(s)
    val first = snap(rt, Some(0L), Some(0L)).toOption.get
    first.hcursor.downField("kline").downField("activeBefore").as[List[Json]].getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("event").toOption) should contain("e1")
    snap(rt, Some(0L), Some(5L)).left.map(_._1) shouldBe Left(410)
  }

  it should "restore the active list and the step count" in {
    val rt = rig(); step(rt, 1, 0); step(rt, 0, 0)
    val s = snap(rt).toOption.get
    val after = Kline.jcs(s.hcursor.downField("kline").downField("activeAfter").focus.get)
    rt.reset()
    rt.restoreSnapshot(s) shouldBe 1L
    Kline.jcs(Kline.activesJson(Snapshot.allActives(rt.getMachines))) shouldBe after
    step(rt, 1, 1)
    rt.getIsreHistory().last.stepNumber shouldBe 2
  }

  "The store" should "be off when asked, Qdrant for an https URL only (#900), and keep files by hex id" in {
    Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "off")).enabled shouldBe false
    Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "qdrant:https://example.invalid")).mode shouldBe "qdrant"
    val plain = Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "qdrant:http://qdrant.invalid:4333")) // insecure-transport: negative-test
    plain.enabled shouldBe false
    plain.offReason shouldBe defined
    Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "redis:x")).enabled shouldBe false
    // Nothing listens on port 1: StoreUnavailable (503), never a boot failure.
    an[Snapshot.StoreUnavailable] should be thrownBy
      Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "qdrant:https://127.0.0.1:1")).list
    val dir = Files.createTempDirectory("snap")
    val st = Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> s"file:$dir"))
    val id = "b" * 64
    st.put(id, Json.obj("digest" -> Json.fromString(s"sha256:$id")))
    st.get(id) shouldBe defined
    st.get("../etc") shouldBe None
    st.remove(id) shouldBe true
  }

  "nearest (#900)" should "score the state at span.to by dot product, above 0, highest first, then id" in {
    def body(c: Char, lamport: Int, cells: Seq[(Int, Double)]): Json = {
      def nz(xs: Seq[(Int, Double)]) = Json.arr(xs.map { case (i, v) =>
        Json.obj("index" -> Json.fromInt(i), "value" -> Json.fromDoubleOrNull(v)) }: _*)
      Json.obj("digest" -> Json.fromString("sha256:" + c.toString * 64), "corpus" -> Json.obj("machines" -> Json.fromInt(1)),
        "kline" -> Json.obj("scope" -> Json.fromString("corpus"),
          "span" -> Json.obj("from" -> Json.fromInt(2), "to" -> Json.fromInt(3)),
          "clocks" -> Json.obj("to" -> Json.obj("lamport" -> Json.fromInt(lamport))),
          "pairs" -> Json.arr(
            Json.obj("stepNumber" -> Json.fromInt(3), "isre" -> Json.obj("length" -> Json.fromInt(8), "nonZero" -> nz(cells))),
            Json.obj("stepNumber" -> Json.fromInt(2), "isre" -> Json.obj("length" -> Json.fromInt(8), "nonZero" -> nz(Seq(7 -> 9.0)))))))
    }
    val st = Snapshot.Store.fromEnvironment(Map.empty)
    val a = body('a', 3, Seq(1 -> 1.0, 2 -> 1.0))
    val all = Seq(a, body('b', 2, Seq(1 -> 1.0, 2 -> 1.0)), body('c', 1, Seq(1 -> 0.5)), body('d', 4, Seq(5 -> 1.0)))
    all.foreach(b => st.put(Snapshot.idOf(b), b))
    Snapshot.stateOf(a) shouldBe Map(1L -> 1.0, 2L -> 1.0)
    val q = Snapshot.parseState(io.circe.parser.parse("""{"length":8,"nonZero":[{"index":1,"value":1},{"index":2,"value":1}]}""").toOption.get).get
    Snapshot.parseState(io.circe.parser.parse("""{"nonZero":"x"}""").toOption.get) shouldBe None
    val n = st.nearest(q, 5).hcursor.downField("snapshots").focus.get.asArray.get
    n.map(_.hcursor.get[String]("id").toOption.get.head) shouldBe Vector('a', 'b', 'c')
    n.map(_.hcursor.get[Double]("score").toOption.get) shouldBe Vector(2.0, 2.0, 0.5)
    st.nearest(q, 1).hcursor.downField("snapshots").focus.get.asArray.get.size shouldBe 1
    st.list.hcursor.downField("snapshots").focus.get.asArray.get.map(_.hcursor.get[String]("id").toOption.get.head) shouldBe
      Vector('c', 'b', 'a', 'd')
  }
}
