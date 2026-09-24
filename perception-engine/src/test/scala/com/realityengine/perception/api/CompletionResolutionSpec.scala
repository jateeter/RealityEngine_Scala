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
  * C++ `ingest_completion` is the reference behaviour these cases pin.
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
}
