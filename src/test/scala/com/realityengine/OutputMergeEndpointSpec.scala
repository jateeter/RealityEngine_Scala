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
 * The runtime face of the merge knob, checked at the route rather than at the
 * object.
 *
 * `/api/machines/:id/output-merge` validates against
 * `OutputMergeTransformation.All` and advertises the same set, so adding the
 * chain family to that set is what makes the five new names settable — and a
 * name the fold implements but the endpoint rejects would be a training
 * variable nobody can reach.
 */
class OutputMergeEndpointSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine     = new RealityEngine(new VectorStore())
  private val spaceRuntime  = new PerceptualSpaceRuntime()
  private val auditCfg   = AuditConfig(enabled = false, level = 0, service = "output-merge-test")
  private val testRoutes = new Routes(engine, spaceRuntime, auditCfg).routes

  private val machineId = "machine-merge"
  engine.addMachine(new Machine("Merge Machine", "", Map.empty,
    ArbiterRule.PASSTHROUGH, None, machineId))

  private def body(json: String) =
    HttpEntity(ContentTypes.`application/json`, json)

  private def field(payload: String, key: String) =
    parse(payload).toOption.get.hcursor.get[String](key).toOption

  "GET /api/machines/:id/output-merge" should "advertise both families" in {
    Get(s"/api/machines/$machineId/output-merge") ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      val available = parse(responseAs[String]).toOption.get
        .hcursor.get[List[String]]("available").toOption.get
      available should have size 10
      available shouldBe OutputMergeTransformation.All.toList.sorted
      available should contain allOf ("or", "meet", "join",
        "strong-conjunction", "strong-disjunction", "discrete-median")
      field(responseAs[String], "outputMergeTransformation") shouldBe Some("or")
    }
  }

  "PUT of a chain transformation" should "be refused while the interlock is on" in {
    // 423 rather than 403: the refusal is about the resource's current state.
    // Retuning a training variable by accident produces a run whose results mean
    // nothing, and the chain family does not get a shortcut past that.
    Put(s"/api/machines/$machineId/output-merge",
      body("""{"outputMergeTransformation":"discrete-median"}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.Locked
    }
  }

  it should "be accepted once unlocked, and carried on the machine" in {
    Put(s"/api/machines/$machineId/output-merge/lock", body("""{"locked":false}""")) ~>
      testRoutes ~> check { status shouldBe StatusCodes.OK }

    Put(s"/api/machines/$machineId/output-merge",
      body("""{"outputMergeTransformation":" Strong-Disjunction "}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      field(responseAs[String], "outputMergeTransformation") shouldBe
        Some(OutputMergeTransformation.StrongDisjunction)
    }
    engine.getMachine(machineId).get.outputMergeTransformation shouldBe
      OutputMergeTransformation.StrongDisjunction
  }

  it should "still reject a name in neither family" in {
    Put(s"/api/machines/$machineId/output-merge",
      body("""{"outputMergeTransformation":"lukasiewicz"}""")) ~> testRoutes ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }
}
