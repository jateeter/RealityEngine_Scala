package com.realityengine.engine

import com.realityengine.models._
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The output fold as the machine's single contribution into arbitration —
  * RealityEngine_CI `docs/FOLD_PLACEMENT.md`, implementation contract §1–§8.
  *
  * The fold used to sit beside the arbitration path rather than in it: the
  * merged value was reported to the Perception Engine and every asserted output
  * still reached the arbiter separately, so a machine with seven completed
  * Reality Events handed seven values to the same cell and the CELL arbiter
  * resolved contention that belonged to the machine. FallDetection is the case
  * that exposed it — its seven sequences assert 0/1/2/3/4/4/0 on output index 0,
  * and the sweep resolved 2.0 on C++ and LSP against 0.0 here, neither the
  * maximum nor the minimum, because only a subset fires on any step and the
  * answer depended on which subset plus how each runtime broke ties among
  * same-machine contributions.
  *
  * These are the properties a live parity sweep cannot establish, because a
  * sweep sees the resolved value and not why it resolved: that the collapse is
  * to exactly one operation, that governance joins over contributors rather than
  * being picked from one, that a refusal withholds an operation instead of
  * asserting zeros, and that the event bus still sees every sequence that fired.
  */
class FoldPlacementSpec extends AnyFlatSpec with Matchers {

  // Output region for every machine built here. Width 2 so a fold that collapses
  // cells independently is distinguishable from one that collapses the vector.
  private val OutRegion = RegionMapping(20, 2)
  private val Dimension = 32

  /** A machine whose sequences all read cell 0 and all fire together.
    *
    * Each entry of `asserts` becomes one sequence asserting one output vector,
    * so the machine completes one Reality Event per entry on every step and the
    * collection reaching the fold is exactly `asserts`.
    */
  private def machine(id: String,
                      asserts:        Seq[(String, Vector[Double])],
                      transformation: String            = OutputMergeTransformation.Or,
                      chainTop:       Option[Int]       = None,
                      metadata:       Map[String, Json] = Map.empty): Machine = {
    val mapping = PerceptualMapping(RegionMapping(0, 1), OutRegion, 8, chainTop)
    val m = new Machine(id, "fold placement test", metadata, ArbiterRule.PASSTHROUGH, Some(mapping), id)
    m.outputMergeTransformation = transformation
    asserts.foreach { case (seqId, out) =>
      val seq = new CriticalEventSequence(seqId, seqId)
      val v   = new RealityVector(
        Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))), isInitial = true, id = s"start-$seqId")
      v.addOutputVector(OutputVector(s"out-$seqId", out, Map.empty, System.currentTimeMillis()))
      seq.addVector(v)
      m.addSequence(seq)
    }
    m
  }

  /** A machine with no sequences — it never asserts, so it never contributes.
    * Present only to carry a compose subscription. */
  private def subscriber(id: String, producerId: String, producerSeqId: String,
                         bitOffset: Int, outOffset: Int): Machine = {
    val meta = Map("compose" -> Json.obj("subscriptions" -> Json.arr(
      Json.obj(
        "producerMachineId"  -> Json.fromString(producerId),
        "producerSequenceId" -> Json.fromString(producerSeqId),
        "bitOffset"          -> Json.fromInt(bitOffset)))))
    val mapping = PerceptualMapping(RegionMapping(bitOffset, 1), RegionMapping(outOffset, 1))
    new Machine(id, "compose subscriber", meta, ArbiterRule.PASSTHROUGH, Some(mapping), id)
  }

  private def simulatorWith(ms: Machine*): PerceptualSpaceRuntime = {
    val sim = new PerceptualSpaceRuntime(Dimension)
    ms.foreach(sim.addMachine)
    sim
  }

  /** Cell 0 asserted, everything else clear — fires every sequence built above. */
  private val input: Vector[Double] = Vector.tabulate(Dimension)(i => if (i == 0) 1.0 else 0.0)

  private def ragRule(seqId: String, matches: Vector[Double], rag: String): Json =
    Json.obj(
      "sequenceId"    -> Json.fromString(seqId),
      "outputMatches" -> Json.arr(matches.map(Json.fromDoubleOrNull): _*),
      "ragStatusCode" -> Json.fromString(rag))

  private def triggerConfig(rules: Json*): Map[String, Json] =
    Map("triggerConfig" -> Json.obj("rules" -> Json.arr(rules: _*)))

  // ── §1: one operation per machine per output region ────────────────────────

  "the merge batch" should "carry one operation per machine per output region" in {
    // Three completed Reality Events, one operation. This is the whole move: the
    // batch used to carry one entry per asserted output, so this machine reached
    // the arbiter as three contributions per cell and now reaches it as one.
    val sim  = simulatorWith(machine("m-fold", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0),
      "s-3" -> Vector(1.0, 1.0))))
    val step = sim.processImmediate(input)

    step.mergeBatch should have size 1
    val op = step.mergeBatch.head
    op.machineId   shouldBe "m-fold"
    op.region      shouldBe OutRegion
    // Sorted and deduplicated — the evidence for the folded value is the SET of
    // CESs that completed, and a folded contribution has no single sequenceId.
    op.sequenceIds shouldBe List("s-1", "s-2", "s-3")
    // `or` over the collection, not one arbitrarily chosen member of it.
    op.values      shouldBe Vector(1.0, 1.0)
  }

  it should "leave the cell arbiter nothing to resolve within one machine" in {
    // Constraint A in FOLD_PLACEMENT.md: two CESs share an output position only
    // when the constructor intends that position to be identical across every
    // fold configuration, so intra-machine contention was never contention — it
    // was the machine's own composition, and the arbiter resolving it was the
    // defect. One contribution per cell means no rule, tie-break or shard
    // ordering can make the runtimes differ on it.
    val sim = simulatorWith(machine("m-fold", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0))))
    sim.processImmediate(input)

    sim.getLastArbitration shouldBe empty
    sim.getPerceptualSpace.getPerceptualVector(20) shouldBe 1.0
    sim.getPerceptualSpace.getPerceptualVector(21) shouldBe 1.0
  }

  // ── §6: canonical ordering by machineId alone ──────────────────────────────

  it should "order by machineId alone" in {
    // machineId is unique per operation now, so the secondary sort keys the
    // comparator used to need — sequenceId and outputIndex — are gone and the
    // ordering is still total.
    val sim = new PerceptualSpaceRuntime(Dimension)
    // Added out of order, and each given its own output region so the machines
    // do not contend with one another — this is about batch ordering, not
    // resolution.
    Seq("m-c" -> 20, "m-a" -> 22, "m-b" -> 24).foreach { case (id, offset) =>
      val m = machine(id, Seq(s"s-$id" -> Vector(1.0, 0.0)))
      m.perceptualMapping = Some(PerceptualMapping(RegionMapping(0, 1), RegionMapping(offset, 2)))
      sim.addMachine(m)
    }
    val step = sim.processImmediate(input)

    step.mergeBatch.map(_.machineId) shouldBe List("m-a", "m-b", "m-c")
  }

  // ── §8: the single-contributor byte-identity property ──────────────────────

  "a machine with one contributing sequence" should "be byte-identical where the fold is the identity on a singleton" in {
    // §8 originally claimed this of ALL single-contributor machines. That is
    // false, and it is corrected in FOLD_PLACEMENT.md A1: byte-identity holds
    // exactly where the fold is the identity on a one-element collection.
    //
    // It is, for `or`/`and`/`xor` over {0,1} and for all five chain
    // transformations. The Boolean three are counted over asserted cells, so at
    // n=1 each returns the contributor unchanged; the chain five each return one
    // of their inputs, and with one input that is it.
    val asserted = Vector(1.0, 0.0)
    val identityAtOne = Seq(
      OutputMergeTransformation.Or,
      OutputMergeTransformation.And,
      OutputMergeTransformation.Xor,
      OutputMergeTransformation.Meet,
      OutputMergeTransformation.Join,
      OutputMergeTransformation.DiscreteMedian,
      OutputMergeTransformation.StrongDisjunction,
      OutputMergeTransformation.StrongConjunction)

    identityAtOne.foreach { t =>
      // The Łukasiewicz pair needs a chain top to fold at all; every other
      // transformation ignores it.
      val sim  = simulatorWith(machine("m-solo", Seq("s-only" -> asserted),
                                       transformation = t, chainTop = Some(4)))
      val step = sim.processImmediate(input)

      withClue(s"transformation $t: ") {
        step.mergeBatch should have size 1
        val op = step.mergeBatch.head
        // A one-element array, and the values the batch carried before the move.
        op.sequenceIds shouldBe List("s-only")
        op.values      shouldBe asserted
        // And the committed cells are those values — arbitration for a cell with
        // a single contributing machine is untouched.
        sim.getPerceptualSpace.getPerceptualVector(20) shouldBe 1.0
        sim.getPerceptualSpace.getPerceptualVector(21) shouldBe 0.0
      }
    }
  }

  it should "NOT be byte-identical under nor or nand, which invert a lone contributor" in {
    // The first half of what §8 got wrong. Before the move the raw asserted
    // value reached the arbiter and the fold was only reported; now the fold IS
    // the contribution, so an inverting gate inverts what the space commits.
    // Pinned rather than described, because it is the case that would otherwise
    // be discovered as a parity divergence.
    Seq(OutputMergeTransformation.Nor, OutputMergeTransformation.Nand).foreach { t =>
      val sim  = simulatorWith(machine("m-solo", Seq("s-only" -> Vector(1.0, 0.0)), transformation = t))
      val step = sim.processImmediate(input)

      withClue(s"transformation $t: ") {
        step.mergeBatch.head.values shouldBe Vector(0.0, 1.0)
        sim.getPerceptualSpace.getPerceptualVector(20) shouldBe 0.0
        sim.getPerceptualSpace.getPerceptualVector(21) shouldBe 1.0
      }
    }
  }

  it should "NOT be byte-identical when a Boolean gate binarises an ordinal contributor" in {
    // The second half. A machine ranking severity over {0..4} and declaring no
    // transformation gets the default `or`, which reads a cell as asserted or
    // not — so 3 becomes 1 and the ladder is gone (RealityEngine_CI#158). The
    // magnitude used to survive to arbitration because the fold was only
    // reported.
    val ordinal = simulatorWith(machine("m-ord", Seq("s-only" -> Vector(3.0, 0.0))))
    ordinal.processImmediate(input).mergeBatch.head.values shouldBe Vector(1.0, 0.0)

    // And the corpus fix that keeps this safe: FallDetection and
    // FallSensorMotionPreaggregator declare `join`, which is a chain
    // transformation and preserves the rung.
    val declared = simulatorWith(machine("m-ord", Seq("s-only" -> Vector(3.0, 0.0)),
                                         transformation = OutputMergeTransformation.Join))
    declared.processImmediate(input).mergeBatch.head.values shouldBe Vector(3.0, 0.0)
  }

  it should "carry that sequence's own governance, unjoined" in {
    val meta = triggerConfig(ragRule("s-only", Vector(1.0, 0.0), "RED"))
    val sim  = simulatorWith(machine("m-solo", Seq("s-only" -> Vector(1.0, 0.0)), metadata = meta))
    val step = sim.processImmediate(input)

    step.mergeBatch.head.governance.flatMap(_.ragStatusCode) shouldBe Some("RED")
    step.mergeBatch.head.governance.map(_.sequenceId)        shouldBe Some("s-only")
  }

  // ── §3: governance is the join over contributors ───────────────────────────

  "governance for a folded contribution" should "take the highest severity rank among contributors" in {
    // severity_rank is an ordered chain — GREEN/absent 0 < AMBER 1 < RED 2 <
    // lifeSafety 3 — so the join is the same lattice operation the fold
    // vocabulary defines. Safety-preserving: a RED-governed firing cannot be
    // hidden by a GREEN one that folded alongside it, which is exactly what
    // SEVERITY arbitration exists to guarantee.
    val meta = triggerConfig(
      ragRule("s-1", Vector(1.0, 0.0), "AMBER"),
      ragRule("s-2", Vector(0.0, 1.0), "RED"),
      ragRule("s-3", Vector(1.0, 1.0), "GREEN"))
    val sim  = simulatorWith(machine("m-gov", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0),
      "s-3" -> Vector(1.0, 1.0)), metadata = meta))
    val step = sim.processImmediate(input)

    val decision = step.mergeBatch.head.governance.getOrElse(fail("no governance resolved"))
    decision.ragStatusCode shouldBe Some("RED")
    // Taken WHOLE from the winning contributor — the reported sequenceId is the
    // RED rule's, not a field spliced in from the AMBER or GREEN one.
    decision.sequenceId    shouldBe "s-2"
  }

  it should "resolve each contributor against its OWN asserted values, not the fold" in {
    // The sharp edge of §3. These two contributors fold under `or` to [1,1], and
    // the only rule declared matches [1,1] — so resolving against the folded
    // value would answer RED. A rule written for one CES's output need not match
    // the fold, and changing the matching semantics is not part of this move.
    val meta = triggerConfig(ragRule("s-1", Vector(1.0, 1.0), "RED"))
    val sim  = simulatorWith(machine("m-gov", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0)), metadata = meta))
    val step = sim.processImmediate(input)

    step.mergeBatch.head.values     shouldBe Vector(1.0, 1.0)
    step.mergeBatch.head.governance shouldBe None
  }

  it should "break a rank tie on the lexicographically smallest sequenceId" in {
    // An unrecognised code ranks 0, the same rank as GREEN, so this is a genuine
    // tie between two DIFFERENT decisions — the only shape in which the
    // tie-break is observable while the decision is a single RAG code. "s-alpha"
    // sorts before "s-beta", so its decision is the one taken.
    val meta = triggerConfig(
      ragRule("s-alpha", Vector(1.0, 0.0), "BLUE"),
      ragRule("s-beta",  Vector(0.0, 1.0), "GREEN"))
    val m = machine("m-tie", Seq(
      "s-alpha" -> Vector(1.0, 0.0),
      "s-beta"  -> Vector(0.0, 1.0)), metadata = meta)
    val contributors = Seq(
      "s-alpha" -> Vector(1.0, 0.0),
      "s-beta"  -> Vector(0.0, 1.0))

    Arbiter.joinGovernance(m, contributors).map(_.sequenceId) shouldBe Some("s-alpha")
    // Symmetric: max over a set does not depend on enumeration order, and the
    // tie-break must not either. The machine loop sorts its contributors, but
    // the join has to hold the property on its own.
    Arbiter.joinGovernance(m, contributors.reverse).map(_.sequenceId) shouldBe Some("s-alpha")
  }

  it should "be None when no contributor resolved a rule" in {
    val sim  = simulatorWith(machine("m-gov", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0))))
    sim.processImmediate(input).mergeBatch.head.governance shouldBe None
  }

  // ── A2: governance reaches the wire as a full object ───────────────────────

  it should "reach the wire as a complete PagingDecision, not a bare code" in {
    // This runtime emitted no `governance` key at all, so a consumer reading
    // `governance.ragStatusCode` got an object from C++ and LSP and nothing
    // here. Field-for-field with C++'s to_json(PagingDecision), including
    // `description` sitting between `contact` and `source`.
    import com.realityengine.api.JsonProtocol._
    import io.circe.syntax._

    val meta = Map(
      "governance" -> Json.obj(
        "ownerTeam" -> Json.fromString("platform-oncall"),
        "runbook"   -> Json.fromString("https://runbook/thermal"),
        "sla"       -> Json.obj("warning" -> Json.fromInt(900)),
        "contact"   -> Json.obj("primary" -> Json.fromString("pager-a"))),
      "triggerConfig" -> Json.obj("rules" -> Json.arr(
        Json.obj(
          "sequenceId"    -> Json.fromString("s-only"),
          "outputMatches" -> Json.arr(Json.fromInt(1), Json.fromInt(0)),
          "ragStatusCode" -> Json.fromString("RED"),
          "processStatus" -> Json.fromString("warning"),
          "description"   -> Json.fromString("THERMAL: coolant loop above threshold")))))

    val sim  = simulatorWith(machine("m-gov", Seq("s-only" -> Vector(1.0, 0.0)), metadata = meta))
    val gov  = sim.processImmediate(input).mergeBatch.head.asJson.hcursor.downField("governance")

    gov.get[String]("machineId").toOption       shouldBe Some("m-gov")
    gov.get[String]("sequenceId").toOption      shouldBe Some("s-only")
    gov.get[String]("ragStatusCode").toOption   shouldBe Some("RED")
    gov.get[String]("processStatus").toOption   shouldBe Some("warning")
    // Rule declares no ownerTeam, so the machine default carries.
    gov.get[String]("ownerTeam").toOption       shouldBe Some("platform-oncall")
    // No rule slaSeconds, so the machine's per-status table keyed by "warning".
    gov.get[Int]("slaSeconds").toOption         shouldBe Some(900)
    gov.get[String]("runbook").toOption         shouldBe Some("https://runbook/thermal")
    gov.downField("contact").get[String]("primary").toOption shouldBe Some("pager-a")
    gov.get[String]("description").toOption     shouldBe Some("THERMAL: coolant loop above threshold")
    // The rule carries no governance block of its own, so "rule-only".
    gov.get[String]("source").toOption          shouldBe Some("rule-only")
    gov.get[Boolean]("hasMachineGovernance").toOption shouldBe Some(true)
    // Unresolved fields are nulled, not dropped — except description, which C++
    // omits when the rule carries none.
    gov.downField("escalationPolicy").focus     shouldBe Some(Json.Null)
  }

  it should "omit the governance key rather than null it when no rule matched" in {
    import com.realityengine.api.JsonProtocol._
    import io.circe.syntax._
    val sim = simulatorWith(machine("m-plain", Seq("s-only" -> Vector(1.0, 0.0))))
    sim.processImmediate(input).mergeBatch.head.asJson
      .asObject.map(_.contains("governance")) shouldBe Some(false)
  }

  // ── A3: cesId is the joined set, and an opaque key ─────────────────────────

  "the arbiter contribution's cesId" should "be the comma-joined sorted set" in {
    // A3. Contended so a record is emitted at all — a single contributor
    // resolves to itself and needs none. The second machine writes the same
    // cell from a different region offset.
    val a = machine("m-a", Seq("s-2" -> Vector(1.0, 0.0), "s-1" -> Vector(1.0, 0.0)))
    val b = machine("m-b", Seq("s-x" -> Vector(0.0, 0.0)))
    val sim = simulatorWith(a, b)
    sim.processImmediate(input)

    val record = sim.getLastArbitration.find(_.cell == 20).getOrElse(fail("cell 20 uncontended"))
    // No spaces, sorted, deduplicated. A one-element set renders as the bare id,
    // which is what keeps single-contributor machines unchanged on the wire.
    record.contributors.flatMap(_.cesId).toSet shouldBe Set("s-1,s-2", "s-x")
  }

  // ── §4 / A4: deprecation ───────────────────────────────────────────────────

  "deprecation" should "mark the lexicographically smallest deprecated contributor" in {
    val m = machine("m-dep", Seq(
      "s-a" -> Vector(1.0, 0.0),
      "s-b" -> Vector(0.0, 1.0),
      "s-c" -> Vector(1.0, 1.0)))
    // s-b and s-c both deprecated; s-a is not. Smallest deprecated is s-b.
    m.getSequence("s-b").get.deprecatedAt = Some("2026-01-01")
    m.getSequence("s-b").get.replacedBy   = Some("s-successor")
    m.getSequence("s-c").get.deprecatedAt = Some("2025-01-01")

    val op = simulatorWith(m).processImmediate(input).mergeBatch.head
    val mark = op.deprecation.getOrElse(fail("no deprecation mark"))
    mark.since      shouldBe "2026-01-01"
    mark.replacedBy shouldBe Some("s-successor")
    mark.ageDays    should be > 0L
    // The identity set is untouched by deprecation — every contributor is still
    // evidence for the folded value.
    op.sequenceIds shouldBe List("s-a", "s-b", "s-c")
  }

  it should "be absent when no contributor is deprecated" in {
    val sim = simulatorWith(machine("m-live", Seq("s-1" -> Vector(1.0, 0.0))))
    sim.processImmediate(input).mergeBatch.head.deprecation shouldBe None
  }

  it should "count one fire per asserted output, not one per deprecated sequence" in {
    // FOLD_PLACEMENT.md A4. `s-multi` asserts twice in this step, so it fired
    // twice, and the counter has always counted firings. Collapsing it to the
    // deduplicated sequenceIds would silently change what the metric means.
    val m = machine("m-dep", Seq("s-multi" -> Vector(1.0, 0.0)))
    // A second asserted output on the same sequence.
    val extra = new RealityVector(
      Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))), isInitial = true, id = "start-extra")
    extra.addOutputVector(OutputVector("out-extra", Vector(0.0, 1.0), Map.empty, System.currentTimeMillis()))
    m.getSequence("s-multi").get.addVector(extra)
    m.getSequence("s-multi").get.deprecatedAt = Some("2026-01-01")

    val registry = new com.realityengine.services.CesCoverageRegistry
    val sim = new PerceptualSpaceRuntime(Dimension)
    sim.setCoverageRegistry(registry)
    sim.addMachine(m)
    val op = sim.processImmediate(input).mergeBatch.head

    // Two asserted outputs from one deprecated sequence.
    op.sequenceIds shouldBe List("s-multi")
    registry.deprecatedFiresSnap.values.sum shouldBe 2L
  }

  it should "record the joined paging decision once per operation" in {
    // §3: one record per operation, carrying the joined decision — not one per
    // firing. This runtime recorded nothing at all before.
    val meta = triggerConfig(
      ragRule("s-1", Vector(1.0, 0.0), "AMBER"),
      ragRule("s-2", Vector(0.0, 1.0), "RED"))
    val registry = new com.realityengine.services.CesCoverageRegistry
    val sim = new PerceptualSpaceRuntime(Dimension)
    sim.setCoverageRegistry(registry)
    sim.addMachine(machine("m-gov", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0)), metadata = meta))
    sim.processImmediate(input)

    val snap = registry.pagingDecisionsSnap
    snap.values.sum shouldBe 1L
    // And it is the joined decision that was recorded, not the first contributor's.
    snap.keys.head should include ("RED")
  }

  // ── §5: the event bus sees every contributing sequence ─────────────────────

  "the event bus" should "fire for every contributing sequence" in {
    // The one consumer where behaviour must be identical rather than analogous:
    // it writes into the perceptual space, and it is how compose/meta machines
    // observe their producers. Subscriptions key on
    // machineId + "|" + sequenceId, so collapsing to one operation would have
    // left them matching one arbitrarily chosen sequence — or none.
    val producer = machine("m-prod", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0),
      "s-3" -> Vector(1.0, 1.0)))
    val sim = simulatorWith(
      producer,
      subscriber("m-sub-1", "m-prod", "s-1", 24, 27),
      subscriber("m-sub-2", "m-prod", "s-2", 25, 28),
      subscriber("m-sub-3", "m-prod", "s-3", 26, 29))

    val step = sim.processImmediate(input)

    step.eventBus.map(_.producerSequenceId).sorted shouldBe List("s-1", "s-2", "s-3")
    step.eventBus.map(_.bitOffset).sorted          shouldBe List(24, 25, 26)
    // Latched into the space, which is the part that is not merely reporting.
    val space = sim.getPerceptualSpace.getPerceptualVector
    space(24) shouldBe 1.0
    space(25) shouldBe 1.0
    space(26) shouldBe 1.0
  }

  it should "fire even when the fold refuses, since the Reality Events still completed" in {
    // A refusal withdraws the machine's VALUE from arbitration. It does not
    // retract the CESs that fired, and a meta machine subscribed to one of them
    // is observing the firing, not the value. Driving the bus from the merge
    // operation would have silently taken those subscribers off the air.
    val producer = machine("m-prod",
      Seq("s-1" -> Vector(1.0, 0.0), "s-2" -> Vector(0.0, 1.0)),
      transformation = OutputMergeTransformation.StrongDisjunction)
    val sim  = simulatorWith(producer, subscriber("m-sub-1", "m-prod", "s-1", 24, 27))
    val step = sim.processImmediate(input)

    step.mergeBatch shouldBe empty
    step.eventBus.map(_.producerSequenceId) shouldBe List("s-1")
    sim.getPerceptualSpace.getPerceptualVector(24) shouldBe 1.0
  }

  // ── §2: a fold refusal contributes no operation ────────────────────────────

  "a fold refusal" should "contribute no operation rather than a zero vector" in {
    // strong-disjunction is min(k, Sum x) and has no defined value without k, so
    // with no declared chain top the fold presents nothing. Contributing zeros
    // instead would be indistinguishable, in the arbitrated space, from a
    // machine that genuinely asserted nothing — and a machine presenting a
    // plausible wrong value is not diagnosable where a silent one is
    // (RealityEngine_CI#158).
    val sim  = simulatorWith(machine("m-refuse",
      Seq("s-1" -> Vector(1.0, 0.0), "s-2" -> Vector(0.0, 1.0)),
      transformation = OutputMergeTransformation.StrongDisjunction))
    val step = sim.processImmediate(input)

    step.mergeBatch shouldBe empty
    step.machineResults("m-refuse").mergedOutputVector shouldBe None
    // Not written, as opposed to written as zero — the cells are untouched.
    sim.getPerceptualSpace.getPerceptualVector(20) shouldBe 0.0
    sim.getPerceptualSpace.getPerceptualVector(21) shouldBe 0.0
    sim.getLastArbitration shouldBe empty
  }

  it should "fold and contribute once the machine declares a chain top" in {
    // The same machine with k declared. `outputAlphabetTop` is the machine's
    // alphabet and deliberately not `bitsPerElement`, which is the representable
    // range: folding FallDetection's {0..4} ladder at its 4-bit k=15 makes the
    // truncated sum yield 14, outside the alphabet entirely.
    val sim = simulatorWith(machine("m-refuse",
      Seq("s-1" -> Vector(2.0, 0.0), "s-2" -> Vector(1.0, 1.0)),
      transformation = OutputMergeTransformation.StrongDisjunction,
      chainTop       = Some(4)))
    val step = sim.processImmediate(input)

    step.mergeBatch should have size 1
    // min(4, 2+1) = 3 on the first cell, min(4, 0+1) = 1 on the second.
    step.mergeBatch.head.values shouldBe Vector(3.0, 1.0)
    step.mergeBatch.head.sequenceIds shouldBe List("s-1", "s-2")
  }

  // ── The merged value is one computation, not two ───────────────────────────

  "the merged value" should "be the same computation the PE is shown" in {
    // mergedOutputVector and the merge operation were built from two separately
    // assembled collections. Two computations that are supposed to agree are a
    // standing invitation to drift, and the drift would show up as the PE being
    // told one thing and the space another.
    val sim  = simulatorWith(machine("m-one", Seq(
      "s-1" -> Vector(1.0, 0.0),
      "s-2" -> Vector(0.0, 1.0)), transformation = OutputMergeTransformation.Meet))
    val step = sim.processImmediate(input)

    step.machineResults("m-one").mergedOutputVector shouldBe Some(Vector(0.0, 0.0))
    step.mergeBatch.head.values shouldBe Vector(0.0, 0.0)
    step.machineResults("m-one").mergedOutputVector shouldBe Some(step.mergeBatch.head.values)
  }
}
