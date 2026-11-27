package com.realityengine.engine

import com.realityengine.services.MachineLoader
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** K-line capture (RealityEngine_CI#603, RealityEngine_Scala#198): the same
  * cases as RealityEngine_CPP tests/kline_tests.cpp and RealityEngine_LSP
  * tests/kline-tests.lisp, on a runtime stepped through processImmediate. */
class KlineSpec extends AnyFlatSpec with Matchers {

  private val instance = "01a218ae-9950-76ed-aded-d4cf80228cf4"

  private def event(id: String, values: Seq[Int], initial: Boolean, next: Seq[String], output: Boolean): String = {
    val els = values.map(v => s"""{"value":$v,"threshold":0.5}""").mkString(",")
    val nx = if (next.isEmpty) "" else next.map(n => s""""$n"""").mkString(""","nextEventIds":[""", ",", "]")
    val out = if (output) s""","outputEvents":[{"id":"$id-out","vector":[1,1]}]""" else ""
    s"""{"id":"$id","elements":[$els],"isInitial":$initial$nx$out}"""
  }
  private def machine(name: String, in: Int, out: Int, seq: String, events: Seq[String]): String =
    s"""{"version":"1.0.0","machine":{"name":"$name","description":"kline test","arbiterRule":"PASSTHROUGH","matchAlgorithm":"gte","perceptualMapping":{"input":{"offset":$in,"length":2},"output":{"offset":$out,"length":2}},"sequences":[{"id":"$seq","name":"$seq","events":[${events.mkString(",")}]}]}}"""

  private val linear = machine("Linear", 0, 10, "lin", Seq(
    event("e0", Seq(1, 0), initial = true, Seq("e1"), output = false),
    event("e1", Seq(0, 1), initial = false, Seq("e2"), output = false),
    event("e2", Seq(1, 1), initial = false, Nil, output = true)))

  private final class Rig(machines: Seq[String], store: KlineStore = new KlineStore(None)) {
    val rt = new PerceptualSpaceRuntime(32)
    private var lamport = 1000L
    val tracker = new KlineTracker(instance, store, () => { lamport += 1; lamport })
    tracker.capture = true
    rt.useKlineTracker(tracker)
    machines.foreach(j => rt.addMachine(MachineLoader.loadFromJson(j)))
    def step(cells: (Int, Double)*): Unit = {
      val v = Array.fill(32)(0.0)
      cells.foreach { case (i, x) => v(i) = x }
      rt.processImmediate(v.toVector)
    }
    def records: Vector[Json] = rt.klineList(None, None, None, 0, 1000).hcursor.get[Vector[Json]]("klines").getOrElse(Vector.empty)
  }

  private def find(recs: Seq[Json], machine: String, outcome: String, openStep: Long): Option[Json] = recs.find { r =>
    val c = r.hcursor
    c.downField("focal").get[String]("machine").contains(machine) && c.get[String]("outcome").contains(outcome) &&
      c.downField("open").get[Long]("step").contains(openStep)
  }
  private def path(r: Json): Seq[String] =
    r.hcursor.get[Vector[Json]]("path").getOrElse(Vector.empty).map(p => s"${p.hcursor.get[String]("event").getOrElse("")}@${p.hcursor.get[Long]("step").getOrElse(-1L)}")

  "JCS and SHA-256" should "match the other voters" in {
    val escaped = "\\u0001"  // the six characters \u0001
    Kline.jcs(parse("{\"b\":1,\"a\":[true,null,\"x" + escaped + "\"],\"c\":1.5e-7}").toOption.get) shouldBe
      "{\"a\":[true,null,\"x" + escaped + "\"],\"b\":1,\"c\":1.5e-7}"
    Kline.sha256Hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  "A linear sequence" should "complete, sustain, end its sustain, and abandon" in {
    val r = new Rig(Seq(linear))
    r.step(0 -> 1); r.step(1 -> 1); r.step(0 -> 1, 1 -> 1); r.step(0 -> 1, 1 -> 1); r.step(); r.step(0 -> 1); r.step(0 -> 1, 1 -> 1)
    val c = find(r.records, "Linear", "completed", 0)
    c shouldBe defined
    path(c.get) shouldBe Seq("e0@0", "e1@1", "e2@2")
    c.get.hcursor.downField("sustain").get[Long]("through") shouldBe Right(3L)
    val a = find(r.records, "Linear", "abandoned", 5)
    a shouldBe defined
    a.get.hcursor.downField("difference").get[Int]("elementIndex") shouldBe Right(0)
    a.get.hcursor.downField("difference").get[String]("comparator") shouldBe Right("gte")
  }

  "A one-event sequence" should "open and complete on one step, and sustain on consecutive matches" in {
    val r = new Rig(Seq(machine("One", 2, 12, "one", Seq(event("o0", Seq(1, 1), initial = true, Nil, output = true)))))
    r.step(2 -> 1, 3 -> 1); r.step(2 -> 1, 3 -> 1); r.step(); r.step(2 -> 1, 3 -> 1)
    r.records.length shouldBe 2
    find(r.records, "One", "completed", 3) shouldBe defined
  }

  "A merge" should "subsume the younger K-line" in {
    val r = new Rig(Seq(machine("Merge", 6, 16, "mg", Seq(
      event("m0", Seq(1, 0), initial = true, Seq("m1"), output = false),
      event("m1", Seq(1, 0), initial = false, Seq("m2"), output = false),
      event("m2", Seq(1, 0), initial = false, Nil, output = true)))))
    (1 to 3).foreach(_ => r.step(6 -> 1))
    val older = find(r.records, "Merge", "completed", 0)
    val younger = find(r.records, "Merge", "subsumed", 1)
    older shouldBe defined
    younger shouldBe defined
    younger.get.hcursor.get[String]("subsumedBy") shouldBe older.get.hcursor.get[String]("id")
  }

  "A reset" should "close open K-lines interrupted" in {
    val r = new Rig(Seq(linear))
    r.step(0 -> 1)
    r.rt.reset()
    find(r.records, "Linear", "interrupted", 0).flatMap(_.hcursor.get[String]("reason").toOption) shouldBe Some("reset")
  }

  "The store" should "survive a restart and close the checkpoint as shutdown" in {
    val dir = Files.createTempDirectory("kline-spec")
    val r = new Rig(Seq(linear), new KlineStore(Some(dir)))
    r.step(0 -> 1); r.step(1 -> 1); r.step(0 -> 1, 1 -> 1); r.step(0 -> 1)
    var lamport = 5000L
    val t = new KlineTracker(instance, new KlineStore(Some(dir)), () => { lamport += 1; lamport })
    t.capture = true
    t.recover()
    val recs = t.list(None, None, None, 0, 1000).hcursor.get[Vector[Json]]("klines").getOrElse(Vector.empty)
    recs.length shouldBe 2
    find(recs, "Linear", "interrupted", 3).flatMap(_.hcursor.get[String]("reason").toOption) shouldBe Some("shutdown")
  }
}
