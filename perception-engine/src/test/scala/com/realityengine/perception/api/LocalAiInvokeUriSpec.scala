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

  // The paths of the curated allow-list (RealityEngine_CI
  // config/integrations.example.json, localai.allowedOperations).
  private val allowed = Seq(
    "/health", "/graph/schema", "/graph/rag", "/graph/agent", "/rag/query",
    "/rag/ingest/text", "/chat", "/graphql", "/graphql/events"
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

  "PerceptionRoutes.localAiOperationId" should {
    import io.circe.Json
    import io.circe.syntax._
    val ops = Vector(
      Json.obj("id" -> "health".asJson, "method" -> "GET".asJson, "path" -> "/health".asJson),
      Json.obj("id" -> "graph_rag".asJson, "method" -> "POST".asJson, "path" -> "/graph/rag".asJson)
    )
    "resolve an allowed (method, path) to its id" in {
      PerceptionRoutes.localAiOperationId(ops, "POST", "/graph/rag") shouldBe Some("graph_rag")
      PerceptionRoutes.localAiOperationId(ops, "get", "/health") shouldBe Some("health")
    }
    "match the method, not only the path" in {
      PerceptionRoutes.localAiOperationId(ops, "GET", "/graph/rag") shouldBe None
    }
    "neither prefix-match nor treat / as a wildcard" in {
      PerceptionRoutes.localAiOperationId(ops, "GET", "/health/deep") shouldBe None
      PerceptionRoutes.localAiOperationId(ops, "GET", "/") shouldBe None
    }
    "allow nothing when nothing is configured" in {
      PerceptionRoutes.localAiOperationId(Vector.empty, "GET", "/health") shouldBe None
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
