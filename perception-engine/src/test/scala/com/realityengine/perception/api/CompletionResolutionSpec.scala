package com.realityengine.perception.api

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Covers `POST /api/integrations/completions` resolution — RealityEngine_Scala#157.
  *
  * A completion that names no sourceMappingId used to be filed under the
  * ACP/OpenClaw mapping: the MCP smoke's `region: [4200:4204]` landed on
  * [4210:4214], this runtime only, and sat in the OpenClaw seed's window.
  * These cases pin the resolution SURFACE_SPEC.md declares for completion
  * ingest, which all three runtimes must agree on.
  */
class CompletionResolutionSpec extends AnyWordSpec with Matchers {
  private def body(s: String): Json = parse(s).fold(throw _, identity)

  private val acp = body("""{"id":"acp-openclaw-completion","sensorIdTemplate":"acp.openclaw.{agent}.completion",
    "name":"openclaw/acp/completion","region":{"offset":4210,"length":4},"ttlMs":300000}""")
  private val mappings: String => Option[Json] = Map("acp-openclaw-completion" -> acp).get

  "CompletionResolution.resolve" should {
    "honour the caller's region and sensorId when no mapping is named" in {
      val t = CompletionResolution.resolve(body("""{"provider":"mcp-smoke","agent":"deployment-smoke",
        "sensorId":"mcp.smoke.provider.completion","region":{"offset":4200,"length":4},
        "values":[1,0,0.75,0],"ttlMs":60000}"""), mappings).toOption.get
      t.region   shouldBe Some((4200, 4))
      t.sensorId shouldBe "mcp.smoke.provider.completion"
      t.name     shouldBe "agent:mcp-smoke/deployment-smoke/completion"
      t.smId     shouldBe None
      t.ttlMs    shouldBe 60000L
      t.values   shouldBe Vector(1.0, 0.0, 0.75, 0.0)
    }

    "apply a named mapping: its region, name and template" in {
      val t = CompletionResolution.resolve(body("""{"provider":"openclaw","agent":"Hello Agent",
        "sourceMappingId":"acp-openclaw-completion","values":[1,0,0.95,0]}"""), mappings).toOption.get
      t.region   shouldBe Some((4210, 4))
      t.name     shouldBe "openclaw/acp/completion"
      t.sensorId shouldBe "acp.openclaw.hello-agent.completion"
      t.smId     shouldBe Some("acp-openclaw-completion")
    }

    "let the body's region win over a named mapping's" in {
      CompletionResolution.resolve(body("""{"sourceMappingId":"acp-openclaw-completion",
        "region":{"offset":4200,"length":4}}"""), mappings).toOption.get.region shouldBe Some((4200, 4))
    }

    "refuse an unknown mapping rather than fall back" in {
      CompletionResolution.resolve(body("""{"sourceMappingId":"nope"}"""), mappings) shouldBe
        Left("""Unknown sourceMappingId "nope"""")
    }

    "fall back to agent.<agent>.completion, with no region, when nothing names one" in {
      val t = CompletionResolution.resolve(body("""{"agent":"Planner"}"""), mappings).toOption.get
      t.sensorId shouldBe "agent.planner.completion"
      t.region   shouldBe None
      t.provider shouldBe "external"
    }
  }

  "CompletionResolution.sourceIdPart" should {
    "slug as C++ source_id_part does" in {
      CompletionResolution.sourceIdPart("Hello Agent!") shouldBe "hello-agent"
      CompletionResolution.sourceIdPart("--x--") shouldBe "x"
      CompletionResolution.sourceIdPart("") shouldBe "unnamed"
    }
  }

  // RealityEngine_Scala#162: the reply is the completion envelope C++ and LSP
  // return, not the stored record.
  "CompletionResolution.envelope" should {
    val request = body("""{"provider":"openclaw","agent":"hello-world",
      "sourceMappingId":"acp-openclaw-completion","values":[1,0,0.95,0],
      "correlationId":"trigger-correlation-1","envelopeId":"trigger-envelope-1",
      "completionId":"openclaw-e2e-completion-1"}""")
    val target = CompletionResolution.resolve(request, mappings).toOption.get
    val source = Json.obj("id" -> Json.fromString(target.sensorId))
    val reply  = CompletionResolution.envelope(request, target, source, 1790287326296L)
    val c      = reply.hcursor

    "echo the caller's correlation fields under completion" in {
      // Exactly what test-openclaw-integration.sh asserts.
      c.downField("completion").get[String]("correlationId") shouldBe Right("trigger-correlation-1")
      c.downField("completion").get[String]("envelopeId")    shouldBe Right("trigger-envelope-1")
      c.downField("completion").get[String]("completionId")  shouldBe Right("openclaw-e2e-completion-1")
    }

    "carry the C++/LSP key sets at every level" in {
      reply.asObject.get.keys.toSet shouldBe Set("success", "completion", "signal")
      c.downField("completion").focus.get.asObject.get.keys.toSet shouldBe
        Set("provider", "agent", "sensorId", "sourceMappingId",
            "correlationId", "envelopeId", "completionId", "receivedAt")
      c.downField("signal").focus.get.asObject.get.keys.toSet shouldBe
        Set("success", "source", "push", "timestamp")
    }

    "describe the resolved target, not the raw body" in {
      c.get[Boolean]("success") shouldBe Right(true)
      c.downField("completion").get[String]("sensorId")        shouldBe Right("acp.openclaw.hello-world.completion")
      c.downField("completion").get[String]("sourceMappingId") shouldBe Right("acp-openclaw-completion")
      c.downField("completion").get[Long]("receivedAt")        shouldBe Right(1790287326296L)
      c.downField("signal").downField("source").focus          shouldBe Some(source)
      c.downField("signal").downField("push").focus            shouldBe Some(Json.Null)
    }

    "render absent correlation fields as null, and fall back from completionId to id" in {
      val bare  = body("""{"agent":"Planner","id":"compl-7"}""")
      val t     = CompletionResolution.resolve(bare, mappings).toOption.get
      val comp  = CompletionResolution.envelope(bare, t, Json.Null, 1L).hcursor.downField("completion")
      comp.downField("correlationId").focus shouldBe Some(Json.Null)
      comp.downField("envelopeId").focus    shouldBe Some(Json.Null)
      comp.get[String]("completionId")      shouldBe Right("compl-7")
      comp.get[String]("sourceMappingId")   shouldBe Right("")
    }
  }
}
