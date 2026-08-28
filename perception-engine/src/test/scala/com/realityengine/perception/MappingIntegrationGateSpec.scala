package com.realityengine.perception

import com.realityengine.perception.api.PerceptionRoutes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A source mapping is only declared when its integration is enabled (#63).
  *
  * The reported failure: a run started with no HealthKit bridge left cpp-1 and
  * lsp-1 holding 12 PE sources and scala-1 holding 15 — the extra three being
  * `healthkit.bp`, `healthkit.exercise` and `healthkit.sleep`, declared from
  * INTEGRATIONS_CONFIG because the mapping registry was present, not because
  * the integration was running.
  *
  * They were inactive and contributed no values, so this is not a stimulus
  * defect. It is a membership defect, and membership is compared: three
  * runtimes disagreeing about the source set diverge before any stimulus.
  *
  * Registration is an integration declaring its sources. An integration that is
  * not running has not registered, so its sources are not part of the set.
  */
class MappingIntegrationGateSpec extends AnyFlatSpec with Matchers {

  private def enabled(origin: Option[String],
                      healthkit: Boolean = false,
                      carekit: Boolean = false,
                      acp: Boolean = false) =
    PerceptionRoutes.integrationEnabled(origin, healthkit, carekit, acp)

  "a healthkit mapping" should "not be declared when the bridge is disabled" in {
    enabled(Some("healthkit"), healthkit = false) shouldBe false
  }

  it should "be declared when the bridge is enabled" in {
    enabled(Some("healthkit"), healthkit = true) shouldBe true
  }

  "a carekit mapping" should "follow its own flag, not another integration's" in {
    enabled(Some("carekit"), carekit = false, healthkit = true) shouldBe false
    enabled(Some("carekit"), carekit = true) shouldBe true
  }

  "an acp mapping" should "follow its own flag" in {
    enabled(Some("acp"), acp = false) shouldBe false
    enabled(Some("acp"), acp = true) shouldBe true
  }

  "an origin with no enable flag" should "still be declared" in {
    // Deliberate: this gates the integrations that have a flag. Anything else
    // keeps its existing behaviour rather than being silently suppressed by a
    // default this function chose.
    enabled(Some("mqtt")) shouldBe true
    enabled(Some("localai")) shouldBe true
    enabled(None) shouldBe true
  }

  it should "match origin case-insensitively" in {
    // `origin` is derived from a mapping id prefix, which is not
    // case-normalised anywhere upstream.
    enabled(Some("HealthKit"), healthkit = false) shouldBe false
    enabled(Some("HEALTHKIT"), healthkit = false) shouldBe false
  }

  "the origin derivation" should "match what the declaration records" in {
    // The gate and the recorded `origin` field must not disagree about which
    // integration owns a mapping, so both take the id prefix up to the first
    // ':' or '-'. These are the three ids that actually reached the declarable
    // branch in RealityEngine_CI/config/integrations.json.
    val ids = Seq(
      "healthkit:HKCorrelationTypeIdentifierBloodPressure",
      "healthkit:HKWorkoutTypeIdentifierWorkout",
      "healthkit:HKCategoryTypeIdentifierSleepAnalysis",
    )
    ids.foreach { id =>
      val origin = id.takeWhile(ch => ch != ':' && ch != '-')
      origin shouldBe "healthkit"
      enabled(Some(origin), healthkit = false) shouldBe false
    }
  }
}
