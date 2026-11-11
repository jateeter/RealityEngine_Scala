package com.realityengine.perception

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.models._
import com.realityengine.perception.models.PerceptionJsonCodecs._

/** The `POST /api/sources` create body (RealityEngine_CI#755; SURFACE_SPEC
  * "POST /api/sources: the create body").
  *
  * Only the full serialized shape decoded here, so a sparse sensor body that C++
  * and LSP accepted answered 400 (`Missing required field` at `.type`, then at
  * `.lastValue`). `type` or `kind` names the kind, `region` is required, and
  * `sensorId` for a sensor; everything else takes its declared default.
  */
class SourceCreateBodySpec extends AnyFlatSpec with Matchers {

  private def decode(text: String) = parse(text).flatMap(_.as[SourceConfig])

  "a sparse sensor body" should "decode with `kind` and the declared defaults" in {
    val body = """{"id":"s1","kind":"sensor","name":"lifecycle/x","sensorId":"x",
                 |"region":{"offset":10,"length":2},"ttlMs":60000,"origin":"lifecycle","active":false}""".stripMargin
    decode(body) match {
      case Right(s: SensorSourceConfig) =>
        s.id shouldBe "s1"
        s.sensorId shouldBe "x"
        s.lastValue shouldBe Vector.empty
        s.lastUpdated shouldBe None
        s.ttlMs shouldBe 60000L
        s.origin shouldBe Some("lifecycle")
        s.active shouldBe false
      case other => fail(s"expected a sensor source, got $other")
    }
  }

  it should "default ttlMs to 5000 and name to \"source\"" in {
    decode("""{"type":"sensor","sensorId":"y","region":{"offset":0,"length":1}}""") match {
      case Right(s: SensorSourceConfig) =>
        s.ttlMs shouldBe 5000L
        s.name shouldBe "source"
        s.origin shouldBe None
      case other => fail(s"expected a sensor source, got $other")
    }
  }

  "`type`" should "win over `kind` when both are given" in {
    decode("""{"type":"sensor","kind":"test","sensorId":"z","region":{"offset":0,"length":1}}""")
      .map(_.isInstanceOf[SensorSourceConfig]) shouldBe Right(true)
  }

  "a body naming no kind" should "be a simulated source with its defaults" in {
    decode("""{"id":"q","region":{"offset":0,"length":1}}""") match {
      case Right(s: SimulatedSourceConfig) =>
        s.frequency shouldBe 1.0
        s.amplitude shouldBe 1.0
        s.dcOffset shouldBe 0.0
        s.active shouldBe true
      case other => fail(s"expected a simulated source, got $other")
    }
  }

  "a sparse test body" should "default inputs to none and loop to true" in {
    decode("""{"type":"test","region":{"offset":0,"length":1}}""") match {
      case Right(s: TestSourceConfig) =>
        s.inputs shouldBe Vector.empty
        s.loop shouldBe true
      case other => fail(s"expected a test source, got $other")
    }
  }

  "the required fields" should "still be refused when absent" in {
    decode("""{"type":"sensor","sensorId":"x"}""").isLeft shouldBe true                   // no region
    decode("""{"type":"sensor","region":{"offset":0,"length":1}}""").isLeft shouldBe true // no sensorId
  }

  "the full serialized shape" should "decode as before" in {
    val full = """{"type":"sensor","id":"f","name":"n","region":{"offset":3,"length":1},"active":true,
                 |"sensorId":"f","lastValue":[0.5],"lastUpdated":1700000000000,"ttlMs":100}""".stripMargin
    decode(full) match {
      case Right(s: SensorSourceConfig) =>
        s.lastValue shouldBe Vector(0.5)
        s.lastUpdated shouldBe Some(1700000000000L)
        s.ttlMs shouldBe 100L
      case other => fail(s"expected a sensor source, got $other")
    }
  }
}
