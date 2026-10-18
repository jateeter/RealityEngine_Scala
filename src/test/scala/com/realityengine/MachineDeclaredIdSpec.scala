package com.realityengine

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.VectorStore
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `POST /api/machines` keeps a declared id that no resident machine holds
 * (SURFACE_SPEC, "POST /api/machines keeps a declared id"; owner decision
 * 2026-10-18).
 *
 * The route minted an id for every POST while LSP kept the declared one, so a
 * machine loaded at runtime had a different identity on each engine and the
 * byte-equivalence surfaces (GET /api/machine-graph) diverged. A held id is
 * never reused: that would replace the resident machine.
 */
class MachineDeclaredIdSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime(64)
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "declared-id-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  private def body(id: Option[String], enveloped: Boolean): String = {
    val idField = id.map(i => s""""id": "$i",""").getOrElse("")
    val machine =
      s"""{ $idField
         |  "name": "Declared Id Probe",
         |  "arbiterRule": "PASSTHROUGH",
         |  "perceptualMapping": {"input": {"offset": 0, "length": 4},
         |                        "output": {"offset": 100, "length": 2}},
         |  "sequences": [{"id": "seq-declared", "name": "Declared Seq",
         |                 "events": [{"id": "v1", "name": "A", "isInitial": true,
         |                              "elements": [{"value": 1}, {"value": 0}, {"value": 0}, {"value": 0}]}]}]
         |}""".stripMargin
    if (enveloped) s"""{"version": "1.0.0", "machine": $machine}""" else machine
  }

  private def post(json: String): String =
    Post("/api/machines", HttpEntity(ContentTypes.`application/json`, json)) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      parse(responseAs[String]).toOption.get.hcursor.downField("machine").get[String]("id").toOption.get
    }

  "a POST that declares an id no resident machine holds" should "be ingested under that id" in {
    post(body(Some("declared-probe"), enveloped = true)) shouldBe "declared-probe"
    engine.getMachine("declared-probe").map(_.name) shouldBe Some("Declared Id Probe")
  }

  "a POST that declares an id a resident machine holds" should "get a minted id and leave the resident untouched" in {
    val id = post(body(Some("declared-probe"), enveloped = true))
    id should not be "declared-probe"
    id should startWith("machine-")
    engine.getMachine(id).map(_.name) shouldBe Some("Declared Id Probe v2")
    engine.getMachine("declared-probe").map(_.name) shouldBe Some("Declared Id Probe")
  }

  "a bare Machine object that declares an id" should "keep it too" in {
    post(body(Some("declared-bare"), enveloped = false)) shouldBe "declared-bare"
  }

  "a POST that declares no id" should "get a minted id" in {
    post(body(None, enveloped = true)) should startWith("machine-")
  }
}
