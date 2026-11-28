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

  "The store" should "be off when asked, off for qdrant (phase 3), and keep files by hex id" in {
    Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "off")).enabled shouldBe false
    Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> "qdrant:https://example.invalid")).enabled shouldBe false
    val dir = Files.createTempDirectory("snap")
    val st = Snapshot.Store.fromEnvironment(Map("RE_SNAPSHOT_STORE" -> s"file:$dir"))
    val id = "b" * 64
    st.put(id, Json.obj("digest" -> Json.fromString(s"sha256:$id")))
    st.get(id) shouldBe defined
    st.get("../etc") shouldBe None
    st.remove(id) shouldBe true
  }
}
