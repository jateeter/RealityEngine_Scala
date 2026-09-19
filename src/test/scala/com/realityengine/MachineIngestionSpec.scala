package com.realityengine

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.models._
import com.realityengine.services.VectorStore
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `POST /api/machines` always ingests; a name conflict is versioned and
 * reallocated.
 *
 * The route minted a fresh id and kept the requested name, so two resident
 * machines answered to one name — and a caller's bad retry became corrupted
 * engine state that went unnoticed until a count mismatch
 * (RealityEngine_CI#357, SURFACE_SPEC "POST /api/machines always ingests").
 *
 * "Resident" is runtime state: previously ingested and still held in THIS
 * engine's machine corpus. DELETE frees the name.
 */
class MachineIngestionSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime(64)
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "ingestion-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  private val declaredIn  = 0
  private val declaredOut = 100

  private val body =
    s"""{
       |  "name": "Ingest Probe",
       |  "description": "conflict versioning",
       |  "arbiterRule": "PASSTHROUGH",
       |  "perceptualMapping": {"input": {"offset": $declaredIn, "length": 4},
       |                        "output": {"offset": $declaredOut, "length": 2}},
       |  "sequences": [{"id": "seq-ingest", "name": "Ingest Seq",
       |                 "vectors": [{"id": "v1", "name": "A", "isInitial": true,
       |                              "values": [1, 0, 0, 0]}]}]
       |}""".stripMargin

  private def post(): (String, Boolean, Option[PerceptualMapping]) =
    Post("/api/machines", HttpEntity(ContentTypes.`application/json`, body)) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val c = parse(responseAs[String]).toOption.get.hcursor
      val name      = c.downField("machine").get[String]("name").toOption.get
      val versioned = c.get[Boolean]("versioned").toOption.getOrElse(false)
      (name, versioned, engine.getAllMachines.find(_.name == name).flatMap(_.perceptualMapping))
    }

  private def regionsOf(pm: PerceptualMapping): List[(Int, Int)] =
    List((pm.input.offset, pm.input.offset + pm.input.length),
         (pm.output.offset, pm.output.offset + pm.output.length))

  private def overlaps(a: (Int, Int), b: (Int, Int)): Boolean = a._1 < b._2 && b._1 < a._2

  "the first POST of a name" should "be ingested as declared" in {
    val (name, versioned, pm) = post()
    name shouldBe "Ingest Probe"
    versioned shouldBe false
    withClue("a non-conflicting POST must honour the declared mapping: ") {
      pm.map(_.input.offset) shouldBe Some(declaredIn)
      pm.map(_.output.offset) shouldBe Some(declaredOut)
    }
  }

  "a conflicting POST" should "be ingested under a versioned name with new regions" in {
    val (name, versioned, pm) = post()
    name shouldBe "Ingest Probe v2"
    versioned shouldBe true

    // The declared mapping is discarded, not reused.
    withClue("the conflicted machine kept the declared mapping: ") {
      pm.map(_.input.offset) should not be Some(declaredIn)
    }

    // And overlaps nothing resident. This is the assertion that matters — a
    // versioned name over an overlapping region would still corrupt the space.
    val mine = regionsOf(pm.get)
    for {
      other  <- engine.getAllMachines.filter(_.name != name)
      theirs <- other.perceptualMapping.toList.flatMap(regionsOf)
      region <- mine
    } withClue(s"allocated $region overlaps ${other.name} at $theirs: ") {
      overlaps(region, theirs) shouldBe false
    }
  }

  it should "leave the resident machine untouched" in {
    val original = engine.getAllMachines.find(_.name == "Ingest Probe")
    original shouldBe defined
    original.flatMap(_.perceptualMapping).map(_.input.offset) shouldBe Some(declaredIn)
    original.flatMap(_.perceptualMapping).map(_.output.offset) shouldBe Some(declaredOut)
  }

  it should "take the lowest unused version on a further conflict" in {
    val (name, versioned, _) = post()
    name shouldBe "Ingest Probe v3"
    versioned shouldBe true
    engine.getAllMachines.map(_.name).toSet should contain allOf
      ("Ingest Probe", "Ingest Probe v2", "Ingest Probe v3")
  }

  it should "continue the version sequence rather than nesting suffixes" in {
    // A caller re-posting what it received must not drift: appending to the
    // requested name verbatim gives "Ingest Probe v2 v2", then
    // "Ingest Probe v2 v2 v2". The base is recovered so one sequence serves the
    // machine however the caller addresses it.
    val versioned = Post("/api/machines",
      HttpEntity(ContentTypes.`application/json`,
                 body.replaceFirst("\"Ingest Probe\"", "\"Ingest Probe v2\""))) ~>
      testRoutes ~> check {
        status shouldBe StatusCodes.OK
        parse(responseAs[String]).toOption.get.hcursor
          .downField("machine").get[String]("name").toOption.get
      }
    versioned shouldBe "Ingest Probe v4"
    versioned should not include "v2 v"
  }

  it should "take a versioned name as requested when it is not resident" in {
    // The base is consulted only to number a conflict, never to rewrite a name
    // that has none.
    Post("/api/machines",
         HttpEntity(ContentTypes.`application/json`,
                    body.replaceFirst("\"Ingest Probe\"", "\"Ingest Probe v9\""))) ~>
      testRoutes ~> check {
        status shouldBe StatusCodes.OK
        val c = parse(responseAs[String]).toOption.get.hcursor
        c.downField("machine").get[String]("name").toOption shouldBe Some("Ingest Probe v9")
        c.get[Boolean]("versioned").toOption shouldBe Some(false)
      }
  }

  "DELETE" should "free the name, so the next POST is not a conflict" in {
    // Residency is runtime state. Versioning answers a collision at the moment
    // of ingestion; it is not a permanent mark on a name. This is the half a
    // conflict check written against an append-only set would fail.
    for (victim <- List("Ingest Probe", "Ingest Probe v2", "Ingest Probe v3",
                        "Ingest Probe v4", "Ingest Probe v9")) {
      engine.getAllMachines.find(_.name == victim).foreach { m =>
        Delete(s"/api/machines/${m.id}") ~> testRoutes ~> check { status shouldBe StatusCodes.OK }
      }
    }
    engine.getAllMachines.map(_.name) should not contain "Ingest Probe"

    val (name, versioned, pm) = post()
    name shouldBe "Ingest Probe"
    versioned shouldBe false
    withClue("a freed name must restore the declared mapping: ") {
      pm.map(_.input.offset) shouldBe Some(declaredIn)
    }
  }
}
