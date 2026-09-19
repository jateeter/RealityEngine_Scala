package com.realityengine

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.models._
import com.realityengine.services.VectorStore
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `POST /api/perceive` activates the `only` subset selector by its **presence**,
 * not by its contents.
 *
 * `only: {}` used to fall through to the unfiltered step, so a selector that
 * named nothing returned the whole universe. That is the behaviour
 * RealityEngine_CI#367 rules out in the same breath as the unknown-sequence-id
 * case, and for the same reason: a caller building `sequenceIds` from a list
 * that happened to be empty was handed everything and had no way to tell a
 * filter that matched nothing from a filter that was ignored.
 *
 * The runtimes disagreed here — C++ activated on presence, LSP and this engine
 * returned the universe — which is a 3-of-3 quorum failure rather than a
 * majority for either answer (`docs/QUORUM_CONTRACT.md`). Presence won.
 *
 * The assertions below are all *comparisons against the unfiltered response*
 * rather than literals. A test asserting a fixed count would pass against a
 * selector that was ignored whenever the corpus happened to be that size, which
 * is precisely the condition under which this defect was invisible.
 */
class PerceiveSelectorPresenceSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val engine       = new RealityEngine(new VectorStore())
  private val spaceRuntime = new PerceptualSpaceRuntime()
  private val auditCfg     = AuditConfig(enabled = false, level = 0, service = "perceive-selector-test")
  private val testRoutes   = new Routes(engine, spaceRuntime, auditCfg).routes

  spaceRuntime.addMachine(new Machine(
    "Selector Fixture A", "", Map.empty, ArbiterRule.PASSTHROUGH,
    Some(PerceptualMapping(RegionMapping(0, 4), RegionMapping(64, 2))),
    "machine-selector-a"))
  spaceRuntime.addMachine(new Machine(
    "Selector Fixture B", "", Map.empty, ArbiterRule.PASSTHROUGH,
    Some(PerceptualMapping(RegionMapping(8, 4), RegionMapping(72, 2))),
    "machine-selector-b"))

  private def perceive(only: Option[String]): Json = {
    val selector = only.map(s => s""", "only": $s""").getOrElse("")
    val body = s"""{"vector": [${List.fill(16)("0.0").mkString(",")}]$selector}"""
    Post("/api/perceive", HttpEntity(ContentTypes.`application/json`, body)) ~> testRoutes ~> check {
      status shouldBe StatusCodes.OK
      parse(responseAs[String]).toOption.get
    }
  }

  private def machineResultCount(step: Json): Int =
    step.hcursor.downField("machineResults").focus
      .flatMap(_.asObject).map(_.keys.size).getOrElse(-1)

  "POST /api/perceive" should "report every machine when no selector is supplied" in {
    // The premise of every assertion below. If this is not greater than zero
    // the fixture never ran and the filter tests prove nothing.
    machineResultCount(perceive(None)) should be > 0
  }

  it should "select nothing for a selector that names nothing" in {
    val unfiltered = machineResultCount(perceive(None))
    val selected   = machineResultCount(perceive(Some("{}")))

    withClue(s"`only: {}` was ignored rather than applied ($selected of $unfiltered kept): ") {
      selected should not be unfiltered
    }
    selected shouldBe 0
  }

  it should "select nothing for a sequence id that matches nothing" in {
    machineResultCount(perceive(Some("""{"sequenceIds": ["no-such-sequence-id"]}"""))) shouldBe 0
  }

  it should "select nothing for a machine name that matches nothing" in {
    machineResultCount(perceive(Some("""{"machineNames": ["No Such Machine"]}"""))) shouldBe 0
  }

  it should "keep a machine the selector names, and only that machine" in {
    val unfiltered = machineResultCount(perceive(None))
    val selected   = perceive(Some("""{"machineNames": ["Selector Fixture A"]}"""))

    machineResultCount(selected) shouldBe 1
    machineResultCount(selected) should be < unfiltered
    selected.hcursor.downField("machineResults").focus
      .flatMap(_.asObject).get.values.head
      .hcursor.get[String]("machineName").toOption shouldBe Some("Selector Fixture A")
  }

  it should "return the unfiltered step when `only` is absent or is not an object" in {
    // Several regression stages compare this wire exactly, so a non-object
    // `only` must be no selector at all rather than a selector matching
    // nothing — otherwise a malformed request silently empties the response.
    val unfiltered = machineResultCount(perceive(None))
    machineResultCount(perceive(Some("null"))) shouldBe unfiltered
    machineResultCount(perceive(Some("""["a"]"""))) shouldBe unfiltered
  }
}
