package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.models._
import com.realityengine.services.{SemanticAuditLog, VectorStore}
import io.circe.Json
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

// GET /api/audit/semantics — re:SequenceObservation records per
// RealityEngine_Machines docs/SEMANTIC_AUDIT_CONTRACT.md (milestone M5).
class SemanticAuditSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with BeforeAndAfterEach {

  private val root = Files.createTempDirectory("semantic-audit-spec")
  private val machinesDir = root.resolve("machines")
  private val base = "https://realityengine.example.org/machines/test/AuditMachine"

  Files.createDirectories(machinesDir)
  Files.createDirectories(root.resolve("semantics"))
  Files.writeString(
    root.resolve("semantics/abox-manifest.json"),
    s"""{"version":"1.0.0","ontology":"semantics/ontology/re-core.ttl","machines":{
       |"test/AuditMachine":{"name":"Audit Machine","iri":"$base#machine",
       |"sourceFile":"machines/domains/test/AuditMachine.json","sha256":"${"ab" * 32}"}}}
       |""".stripMargin
  )

  override def beforeEach(): Unit = SemanticAuditLog.clear()

  private def buildMachine(): Machine = {
    val machine = new Machine("Audit Machine", "two-step CES", Map.empty,
      ArbiterRule.PASSTHROUGH, None, "machine-audit")
    val seq = new CriticalEventSequence("Escalation", "seq-a")
    val v1 = new RealityVector(
      Vector(VectorElement(1.0, Some(ComparatorType.Equals), Some(0.5))),
      isInitial = true, "v1")
    v1.addNextVector("v2")
    val v2 = new RealityVector(
      Vector(VectorElement(2.0, Some(ComparatorType.Equals), Some(0.5))),
      isInitial = false, "v2")
    v2.addOutputVector(OutputVector("out-red", Vector(4.0), Map(
      "action" -> Json.fromString("emergency-dispatch"),
      "ragStatusCode" -> Json.fromString("RED")
    ), System.currentTimeMillis()))
    seq.addVector(v1)
    seq.addVector(v2)
    machine.addSequence(seq)
    machine
  }

  private val vectorStore = new VectorStore()
  private val engine      = new RealityEngine(vectorStore)
  private val simulator   = new PerceptualSpaceSimulator()
  private val auditCfg    = AuditConfig(enabled = false, level = 0, service = "semantic-audit-test")
  private val testRoutes  = new Routes(engine, simulator, auditCfg, machinesDir.toString).routes

  "machine processing" should "emit IRI-joined sequence observations served by /api/audit/semantics" in {
    val machine = buildMachine()
    machine.processInput(Vector(1.0))
    machine.processInput(Vector(2.0))

    Get("/api/audit/semantics") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val doc = io.circe.parser.parse(responseAs[String]).getOrElse(Json.Null).hcursor
      // Scoped to this spec's own machine. SemanticAuditLog is a process-global
      // ring buffer written by any machine processing anywhere, and sbt runs
      // suites in parallel — `beforeEach` clearing it is not enough, because a
      // concurrent suite can write between the clear and this read. Asserting a
      // global count made that a race: this began reporting "58 was not equal
      // to 2" once CesgenOraclesParitySpec started processing 4966 machines,
      // while still passing in isolation. The property under test was always
      // "this machine emitted two observations", so that is what it now says.
      val records = doc.downField("records").as[Vector[Json]].getOrElse(Vector.empty)
        .filter(_.hcursor.get[String]("machineId").toOption.contains("machine-audit"))
      records.length shouldBe 2

      val first = records.head.hcursor
      first.get[String]("type") shouldBe Right("re:SequenceObservation")
      first.get[String]("stepIri") shouldBe Right(s"$base#step-v1")
      first.get[Boolean]("completed") shouldBe Right(false)

      val terminal = records.last.hcursor
      terminal.get[String]("machineIri") shouldBe Right(s"$base#machine")
      terminal.get[String]("sequenceIri") shouldBe Right(s"$base#seq-seq-a")
      terminal.get[String]("stepIri") shouldBe Right(s"$base#step-v2")
      terminal.get[Boolean]("completed") shouldBe Right(true)
      terminal.get[String]("determinationIri") shouldBe Right(s"$base#out-out-red")
      terminal.get[String]("actionCode") shouldBe Right("emergency-dispatch")
      terminal.get[String]("ragStatus") shouldBe Right("RED")
    }
  }

  it should "not record what-if evaluations" in {
    val machine = buildMachine()
    engine.addMachine(machine)
    engine.processWhatIf(machine.id, Vector(1.0))
    // Scoped to this spec's own machine, for the same reason as the assertion
    // above: SemanticAuditLog is a process-global ring buffer written by any
    // machine processing anywhere, and sbt runs suites in parallel, so
    // `beforeEach` clearing it does not stop a concurrent suite writing between
    // the clear and this read. `SemanticAuditLog.size shouldBe 0` was a global
    // count and began failing with "1 was not equal to 0" once
    // CesgenOraclesParitySpec started processing 4966 machines.
    //
    // The property under test is that a what-if records nothing *for this
    // machine*, which is what it now says.
    SemanticAuditLog.recent(SemanticAuditLog.Capacity)
      .count(_.machineId == machine.id) shouldBe 0
    engine.removeMachine(machine.id)
  }
}
