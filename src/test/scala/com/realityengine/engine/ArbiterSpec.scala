package com.realityengine.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.Json

/**
 * Conformance checks for RealityEngine_Machines docs/ARBITER_CONTRACT.md.
 *
 * The properties here are the ones a live probe cannot establish: that the
 * resolution does not depend on the order contributions arrive in, nor on how
 * the resolve phase is sharded. Those are the whole basis for parallelising the
 * arbiter, and a violation would be invisible in any single run.
 */
class ArbiterSpec extends AnyFlatSpec with Matchers {

  import Arbiter._

  private def c(cell: Int, value: Double, provider: String, origin: String,
                rag: Option[String] = None, ces: Option[String] = Some("seq")) =
    Contribution(cell, value, provider, origin, ces, Some("ov"), rag)

  "determinism classification" should "put unregistered surfaces in generated" in {
    determinismOf("machine") shouldBe Deterministic
    determinismOf("mqtt") shouldBe Measured
    determinismOf("acp") shouldBe Generated
    // A surface nobody registered must not be able to outrank a reading by
    // default; generated is the safe assumption.
    determinismOf("some-future-surface") shouldBe Generated
  }

  "PRECEDENCE" should "never let a generated contribution override a deterministic one" in {
    val entry = Some(RegistryEntry(1, "PRECEDENCE", None, Map.empty))
    // The generated value is larger, so a MAX-based merge would pick it.
    val (v, rec) = resolve(1, 0, List(
      c(1, 0.0, "machine", "m1"),
      c(1, 1.0, "acp", "agent-1")), entry)
    v shouldBe 0.0
    rec.get.suppressed.map(_.provider) shouldBe List("acp")
  }

  it should "apply withinRank among the winning class rather than falling back to MAX" in {
    val entry = Some(RegistryEntry(1, "PRECEDENCE", Some("SEVERITY"), Map.empty))
    // Two machine determinations plus an agent. RED asserts 0, AMBER asserts 1.
    // MAX would take 1; SEVERITY within the machine class must take 0.
    val (v, _) = resolve(1, 0, List(
      c(1, 1.0, "machine", "m-amber", Some("AMBER")),
      c(1, 0.0, "machine", "m-red", Some("RED")),
      c(1, 1.0, "acp", "agent-1")), entry)
    v shouldBe 0.0
  }

  "SEVERITY" should "resolve by RAG rank before value" in {
    val entry = Some(RegistryEntry(1, "SEVERITY", None, Map.empty))
    val (v, _) = resolve(1, 0, List(
      c(1, 1.0, "machine", "a", Some("AMBER")),
      c(1, 0.0, "machine", "b", Some("RED"))), entry)
    v shouldBe 0.0
  }

  "resolution" should "not depend on contribution order" in {
    // Acceptance criterion 2/4: shuffling arrival order changes nothing. This is
    // the externally visible form of the commutative-monoid requirement, and the
    // property most likely to be violated once sources arrive asynchronously.
    val entry = Some(RegistryEntry(1, "PRECEDENCE", Some("SEVERITY"), Map.empty))
    val contributions = List(
      c(1, 1.0, "machine", "m-amber", Some("AMBER")),
      c(1, 0.0, "machine", "m-red", Some("RED")),
      c(1, 0.7, "acp", "agent-1"),
      c(1, 0.3, "mqtt", "sensor-1"))
    val results = contributions.permutations.map { p => resolve(1, 0, p, entry)._1 }.toSet
    results.size shouldBe 1
  }

  "a single contributor" should "resolve to itself and emit no record" in {
    val (v, rec) = resolve(1, 0, List(c(1, 0.42, "acp", "agent-1")), None)
    v shouldBe 0.42
    rec shouldBe None
  }

  "the arbitration record" should "account for every contribution" in {
    val entry = Some(RegistryEntry(1, "PRECEDENCE", None, Map.empty))
    val cs = List(c(1, 0.0, "machine", "m1"), c(1, 1.0, "acp", "a1"), c(1, 0.5, "mcp", "a2"))
    val (_, rec) = resolve(1, 0, cs, entry)
    val r = rec.get
    // contributors ∪ suppressed must equal the full contribution set — a
    // discarded agent answer has to stay attributable.
    (r.contributors.toSet ++ r.suppressed.toSet) shouldBe cs.toSet
    r.suppressed.map(_.provider).toSet shouldBe Set("acp", "mcp")
  }

  "the trigger-rule join" should "read severity from triggerConfig, not the output vector" in {
    // ragStatusCode is absent from 5104 of 5128 output vectors; it lives here.
    val meta = Map("triggerConfig" -> Json.obj(
      "rules" -> Json.arr(
        Json.obj("sequenceId" -> Json.fromString("s1"),
                 "outputMatches" -> Json.arr(Json.fromInt(1), Json.fromInt(0)),
                 "ragStatusCode" -> Json.fromString("RED")),
        Json.obj("sequenceId" -> Json.fromString("s1"),
                 "outputMatches" -> Json.arr(Json.fromInt(0), Json.fromInt(1)),
                 "ragStatusCode" -> Json.fromString("GREEN")))))
    joinSeverity(meta, "s1", Vector(1.0, 0.0)) shouldBe Some("RED")
    joinSeverity(meta, "s1", Vector(0.0, 1.0)) shouldBe Some("GREEN")
    joinSeverity(meta, "s2", Vector(1.0, 0.0)) shouldBe None
  }

  it should "take the maximum severity when rules disagree" in {
    // One corpus machine does this (AIHardwareResilience, tracked separately).
    // Max keeps the join a commutative monoid and errs toward the emergency.
    val meta = Map("triggerConfig" -> Json.obj(
      "rules" -> Json.arr(
        Json.obj("sequenceId" -> Json.fromString("s"),
                 "outputMatches" -> Json.arr(Json.fromInt(1)),
                 "ragStatusCode" -> Json.fromString("GREEN")),
        Json.obj("sequenceId" -> Json.fromString("s"),
                 "outputMatches" -> Json.arr(Json.fromInt(1)),
                 "ragStatusCode" -> Json.fromString("RED")))))
    joinSeverity(meta, "s", Vector(1.0)) shouldBe Some("RED")
  }

  "MEAN" should "be order-independent under its canonical ordering" in {
    // Floating-point addition is not associative, so MEAN is only admissible
    // with the contract's canonical contributor order.
    val entry = Some(RegistryEntry(1, "MEAN", None, Map.empty))
    val cs = List(c(1, 0.1, "machine", "c"), c(1, 0.2, "machine", "a"), c(1, 0.7, "machine", "b"))
    val results = cs.permutations.map(p => resolve(1, 0, p, entry)._1).toSet
    results.size shouldBe 1
  }
}
