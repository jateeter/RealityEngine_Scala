package com.realityengine.perception.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.client3._

/** Covers the URI `POST /api/integrations/localai/invoke` sends to —
  * RealityEngine_Scala#126.
  *
  * Every invocation failed with `POST http://localhost`: the port and path were
  * gone. The ledger added in #125 recorded the failure; nothing before it did,
  * so the constructed URI is asserted here rather than inferred from a
  * provider's response.
  */
class LocalAiInvokeUriSpec extends AnyWordSpec with Matchers {

  private val base = "http://localhost:4000"

  // The allow-list in the invoke handler. Every entry must reach the provider.
  private val allowed = Seq(
    "/v1/chat/completions", "/v1/completions", "/v1/embeddings", "/v1/models",
    "/v1/images/generations", "/v1/audio/transcriptions", "/graphql", "/api/predict"
  )

  "PerceptionRoutes.localAiInvokeUri" should {
    "keep the base URL's port and append the path, for every allowed endpoint" in {
      allowed.foreach { p =>
        val u = PerceptionRoutes.localAiInvokeUri(base, p)
        u.toString shouldBe s"$base$p"
        u.host shouldBe Some("localhost")
        u.port shouldBe Some(4000)
      }
    }

    "not double the slash when the base URL ends with one" in {
      PerceptionRoutes.localAiInvokeUri("http://localhost:4000/", "/graphql").toString shouldBe
        "http://localhost:4000/graphql"
    }

    "keep a path prefix carried by the base URL" in {
      // A localAI stack behind a reverse proxy is configured with one.
      PerceptionRoutes.localAiInvokeUri("https://ai.example:8443/localai", "/graphql").toString shouldBe
        "https://ai.example:8443/localai/graphql"
    }
  }

  "the interpolator form it replaces" should {
    "lose the port and path — the defect in #126" in {
      // Pins the sttp behaviour that made `uri"$base$path"` wrong, so a reader
      // can see why the helper parses a joined string instead. If an sttp
      // upgrade changes this, the helper is still correct; delete this case.
      val targetPath = "/graphql"
      val broken = uri"$base$targetPath"
      broken.toString should not be s"$base$targetPath"
    }
  }
}
