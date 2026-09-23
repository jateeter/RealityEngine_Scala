package com.realityengine.perception

import com.realityengine.perception.triggers.TriggerDispatcher
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Trigger dispatch (RealityEngine_Scala#149): the rules C++ and LSP already
  * agree on, held 3-of-3 by RealityEngine_CI SURFACE_SPEC.md ("Dispatch
  * surface shapes").
  */
class TriggerDispatcherSpec extends AnyWordSpec with Matchers {

  private def j(s: String): Json = parse(s).toOption.get

  private val machine = j(
    """{"id":"machine-m1","name":"M One","metadata":{
      |  "machineCode":"M1",
      |  "triggerConfig":{"processId":"P1","processName":"Proc One"},
      |  "agentBinding":{"agent":"agent_a","trigger":"trig_a","mode":"advise",
      |    "allowedActions":["act0","act1"],"writeBack":{"type":"pe-sensor"}}}}""".stripMargin)

  private val op = j(
    """{"machineId":"machine-m1","sequenceIds":["seq-x"],"values":[0,1],
      |  "region":{"offset":4,"length":2},"provenance":["seq-w"],"deprecation":null,
      |  "governance":{"ragStatusCode":"RED","processStatus":"error","actionCode":"dispatch-agent"}}""".stripMargin)

  private def dispatcher(catalog: Map[String, Json], refreshedAt: Long, enabled: Boolean = true) =
    new TriggerDispatcher(
      enabled = enabled, mode = "dry-run", graphqlEndpoint = "http://ai/graphql",
      realityEngineUrl = "http://re", catalog = () => (catalog, refreshedAt),
      semanticsBase = n => if (n == "M One") Some("https://x/machines/m1") else None,
      now = () => 42L, newId = k => s"$k-1")

  "dispatchStep" should {
    "build one record with exactly the agreed keys" in {
      val d = dispatcher(Map("machine-m1" -> machine), 1L)
      val recs = d.dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}"""))
      recs should have size 1
      recs.head.asObject.get.keys.toSet shouldBe Set(
        "id", "envelopeId", "correlationId", "status", "mode", "target", "machineId", "sequenceIds",
        "ragStatusCode", "processStatus", "attempts", "createdAt", "updatedAt", "providerReceipt",
        "envelope", "error", "semantics", "replayOf")
      val r = recs.head.hcursor
      r.get[String]("target").toOption shouldBe Some("agent_a")
      r.get[Vector[String]]("sequenceIds").toOption shouldBe Some(Vector("seq-x"))
      r.downField("error").focus shouldBe Some(Json.Null)
      r.downField("replayOf").focus shouldBe Some(Json.Null)
      d.envelopesCreated shouldBe 1
    }

    "select the action at the first non-zero cell, and label the cells" in {
      val env = dispatcher(Map("machine-m1" -> machine), 1L)
        .dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")).head.hcursor.downField("envelope")
      env.downField("dispatch").get[String]("action").toOption shouldBe Some("act1")
      env.downField("outputVector").get[String]("assertedLabel").toOption shouldBe Some("cell_1")
      env.downField("dispatch").downField("endpoint").get[String]("kind").toOption shouldBe Some("dry-run")
      env.downField("dispatch").downField("endpoint").get[String]("url").toOption shouldBe Some("")
    }

    "derive semantics from the manifest base and the sole sequence" in {
      val sem = dispatcher(Map("machine-m1" -> machine), 1L)
        .dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")).head.hcursor.downField("semantics")
      sem.get[String]("machineIri").toOption shouldBe Some("https://x/machines/m1#machine")
      sem.get[String]("sequenceIri").toOption shouldBe Some("https://x/machines/m1#seq-seq-x")
      sem.get[String]("actionCode").toOption shouldBe Some("dispatch-agent")
    }

    "count a merge entry without governance as droppedNoGovernance" in {
      val d = dispatcher(Map("machine-m1" -> machine), 1L)
      d.dispatchStep(j("""{"mergeBatch":[{"machineId":"machine-m1"}]}""")) shouldBe empty
      d.droppedNoGovernance shouldBe 1
    }

    "tell a cold catalog apart from a machine it does not hold" in {
      val cold = dispatcher(Map.empty, 0L)
      cold.dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")) shouldBe empty
      cold.droppedCatalogCold shouldBe 1
      cold.droppedNoDispatch shouldBe 0

      val warm = dispatcher(Map.empty, 1L)
      warm.dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")) shouldBe empty
      warm.droppedNoDispatch shouldBe 1
      warm.droppedCatalogCold shouldBe 0
    }

    "drop a machine that declares no agent or trigger" in {
      val bare = j("""{"id":"machine-m1","name":"M One","metadata":{}}""")
      val d = dispatcher(Map("machine-m1" -> bare), 1L)
      d.dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")) shouldBe empty
      d.droppedNoDispatch shouldBe 1
    }

    "do nothing when trigger dispatch is disabled" in {
      val d = dispatcher(Map("machine-m1" -> machine), 1L, enabled = false)
      d.dispatchStep(j(s"""{"mergeBatch":[${op.noSpaces}]}""")) shouldBe empty
      d.envelopesCreated shouldBe 0
    }
  }

  "binding" should {
    "fall back to the legacy aliases when there is no agentBinding" in {
      val b = TriggerDispatcher.binding(
        j("""{"dispatchableAgent":"legacy","aiTrigger":"t","agentActions":["only"]}"""), j("[0,0]"))
      b.agent shouldBe "legacy"
      b.trigger shouldBe "t"
      b.action shouldBe "only"
      b.writeBack shouldBe Json.Null
    }
  }
}
