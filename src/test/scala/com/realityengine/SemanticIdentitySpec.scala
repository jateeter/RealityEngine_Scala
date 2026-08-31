package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.VectorStore
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

// GET /api/machines/semantics/:name and the semanticsIri/semanticsHash
// fields on /api/machines/json/list, backed by the corpus OWL semantics
// manifest (RealityEngine_Machines semantics/abox-manifest.json).
class SemanticIdentitySpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val root = Files.createTempDirectory("semantics-spec")
  private val machinesDir = root.resolve("machines")
  private val hash = "ab" * 32

  Files.createDirectories(machinesDir.resolve("domains/test"))
  Files.writeString(
    machinesDir.resolve("domains/test/TestMachine.json"),
    """{"version":"1.0.0","machine":{"name":"Test Machine","description":"spec machine","metadata":{},"arbiterRule":"PASSTHROUGH","perceptualMapping":{"input":{"offset":0,"length":2},"output":{"offset":2,"length":2}},"sequences":[]}}"""
  )
  Files.createDirectories(root.resolve("semantics"))
  Files.writeString(
    root.resolve("semantics/abox-manifest.json"),
    s"""{
       |  "version": "1.0.0",
       |  "generator": "scripts/generate-owl.py",
       |  "ontology": "semantics/ontology/re-core.ttl",
       |  "machines": {
       |    "test/TestMachine": {
       |      "name": "Test Machine",
       |      "iri": "https://realityengine.example.org/machines/test/TestMachine#machine",
       |      "sourceFile": "machines/domains/test/TestMachine.json",
       |      "sha256": "$hash"
       |    }
       |  }
       |}
       |""".stripMargin
  )

  private val vectorStore = new VectorStore()
  private val engine      = new RealityEngine(vectorStore)
  private val spaceRuntime   = new PerceptualSpaceRuntime()
  private val auditCfg    = AuditConfig(enabled = false, level = 0, service = "semantics-test")
  private val testRoutes  = new Routes(engine, spaceRuntime, auditCfg, machinesDir.toString).routes

  "GET /api/machines/semantics/:name" should "return the manifest identity for a known machine" in {
    Get("/api/machines/semantics/Test%20Machine") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val c = io.circe.parser.parse(responseAs[String]).getOrElse(Json.Null).hcursor
      c.get[String]("name") shouldBe Right("Test Machine")
      c.get[String]("machineKey") shouldBe Right("test/TestMachine")
      c.get[String]("semanticsIri") shouldBe Right("https://realityengine.example.org/machines/test/TestMachine#machine")
      c.get[String]("semanticsHash") shouldBe Right(hash)
      c.get[String]("sourceFile") shouldBe Right("machines/domains/test/TestMachine.json")
      c.get[String]("ontology") shouldBe Right("semantics/ontology/re-core.ttl")
    }
  }

  it should "404 for a machine absent from the manifest" in {
    Get("/api/machines/semantics/Nope") ~> testRoutes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  "GET /api/machines/json/list" should "carry semanticsIri and semanticsHash per machine" in {
    Get("/api/machines/json/list") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val machines = io.circe.parser.parse(responseAs[String]).getOrElse(Json.Null)
        .hcursor.downField("machines").as[Vector[Json]].getOrElse(Vector.empty)
      machines should have size 1
      val c = machines.head.hcursor
      c.get[String]("name") shouldBe Right("Test Machine")
      c.get[String]("semanticsIri").isRight shouldBe true
      c.get[String]("semanticsHash") shouldBe Right(hash)
    }
  }
}
