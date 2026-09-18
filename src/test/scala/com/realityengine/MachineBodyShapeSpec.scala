package com.realityengine

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.{MachineLoader, VectorStore}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `POST /api/machines` accepts the schema its own published document declares.
 *
 * `docs/openapi/scala-re.yaml` declares the request body as
 * `$ref: '#/components/schemas/Machine'` — the bare machine object. This
 * runtime rejected it with `400 Missing machine.name`, so a client generated
 * from that document could not add a machine, and the error sent the caller to
 * add a name they had already supplied (RealityEngine_CI#419).
 *
 * Both shapes are accepted, disambiguated by an object-valued `machine` key —
 * the rule LSP already implements (`src/loader.lisp:248`). Safe across the
 * corpus: all 1328 files carry the envelope and none has an inner machine with
 * its own object-valued `machine` key.
 */
class MachineBodyShapeSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime()
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "machine-body-shape-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  private val bareMachine =
    """{
      |  "name": "Shape Fixture",
      |  "description": "either-shape acceptance",
      |  "arbiterRule": "PASSTHROUGH",
      |  "perceptualMapping": {"input": {"offset": 0, "length": 2},
      |                        "output": {"offset": 8, "length": 2}},
      |  "sequences": [{"id": "seq-shape", "name": "Shape Seq",
      |                 "vectors": [{"id": "v1", "name": "A", "isInitial": true,
      |                              "values": [1, 0]}]}]
      |}""".stripMargin

  private def body(json: String) = HttpEntity(ContentTypes.`application/json`, json)

  "POST /api/machines" should "accept the bare Machine object the OpenAPI document declares" in {
    Post("/api/machines", body(bareMachine)) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val machine = parse(responseAs[String]).toOption.get.hcursor.downField("machine")
      // Every field must survive. A name defaulting to something and zero
      // sequences is what this looked like on the runtime that accepted the
      // shape and silently discarded its contents.
      machine.get[String]("name").toOption shouldBe Some("Shape Fixture")
      machine.downField("perceptualMapping").downField("input")
        .get[Int]("offset").toOption shouldBe Some(0)
    }
  }

  it should "accept the corpus file envelope" in {
    // A DISTINCT name, because the previous case already ingested "Shape
    // Fixture" into this same engine and `POST /api/machines` versions a
    // resident name (RealityEngine_CI#357). Reusing it here would assert
    // `Shape Fixture` and receive `Shape Fixture v2` — a failure about naming
    // in a spec whose subject is body SHAPE. The two rules are independent and
    // the fixtures should not couple them.
    val enveloped = bareMachine.replaceFirst("\"Shape Fixture\"", "\"Shape Fixture Envelope\"")
    Post("/api/machines", body(s"""{"version": "1.0.0", "machine": $enveloped}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      parse(responseAs[String]).toOption.get.hcursor.downField("machine")
        .get[String]("name").toOption shouldBe Some("Shape Fixture Envelope")
    }
  }

  it should "read both shapes into the same machine" in {
    val fromBare = MachineLoader.loadFromJson(bareMachine, Some("machine-bare"))
    val fromEnv  = MachineLoader.loadFromJson(s"""{"version": "1.0.0", "machine": $bareMachine}""",
                                              Some("machine-env"))
    fromBare.name shouldBe fromEnv.name
    fromBare.getAllSequences.size shouldBe fromEnv.getAllSequences.size
    fromBare.getAllSequences.size shouldBe 1
    fromBare.perceptualMapping.map(_.input.offset) shouldBe fromEnv.perceptualMapping.map(_.input.offset)
  }

  it should "name the field the caller actually has to add" in {
    // "Missing machine.name" for a body that IS the machine sends the caller to
    // a path that does not exist in what they sent.
    Post("/api/machines", body("""{"description": "no name here"}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
      val error = parse(responseAs[String]).toOption.get.hcursor.get[String]("error").toOption.get
      error should include("Missing name")
      error should not include "machine.name"
    }
  }

  it should "still require a version inside the envelope" in {
    // Every corpus file carries one; loosening this would let a file of the
    // wrong major version load silently.
    Post("/api/machines", body(s"""{"machine": $bareMachine}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include("Missing version field")
    }
  }

  it should "reject a wrong major version in either shape" in {
    Post("/api/machines", body(s"""{"version": "9.0.0", "machine": $bareMachine}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include("Incompatible machine JSON version")
    }
    // A version a caller supplies on the bare shape is checked, not ignored
    // because of which shape it arrived in.
    val bareWithVersion = bareMachine.replaceFirst("\\{", """{"version": "9.0.0",""")
    Post("/api/machines", body(bareWithVersion)) ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include("Incompatible machine JSON version")
    }
  }
}
