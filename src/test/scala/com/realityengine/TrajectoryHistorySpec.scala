package com.realityengine

import com.realityengine.engine._
import com.realityengine.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** ISRE/OSRE trajectory histories — SURFACE_SPEC.md, "Trajectory histories".
  *
  * Nothing proved the claim the multi-engine deployment rests on: that every
  * engine given the same seed evolves the same way. Single-step comparisons
  * cannot — two engines can agree at every step examined in isolation and still
  * be on different trajectories (RealityEngine_CI#148). These are the
  * observation points that make the trajectory observable at all, so what they
  * record has to be pinned here rather than only across a live universe.
  */
class TrajectoryHistorySpec extends AnyFlatSpec with Matchers {

  /** One machine reading cell 0 and writing 1.0 to cell 20 on its first match. */
  private def outputMachine(id: String): Machine = {
    val mapping  = PerceptualMapping(RegionMapping(0, 1), RegionMapping(20, 1))
    val machine  = new Machine(id, "trajectory test", Map.empty, ArbiterRule.PASSTHROUGH, Some(mapping), id)
    val sequence = new CriticalEventSequence("Immediate output", s"seq-$id")
    val start    = new RealityEvent(
      Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))), isInitial = true, id = s"start-$id")
    start.addOutputVector(OutputVector(s"out-$id", Vector(1.0), Map.empty, System.currentTimeMillis()))
    sequence.addVector(start)
    machine.addSequence(sequence)
    machine
  }

  private def fixture: PerceptualSpaceRuntime = {
    val sim = new PerceptualSpaceRuntime(32)
    sim.addMachine(outputMachine("machine-traj"))
    sim
  }

  private val input: Vector[Double] = Vector.tabulate(32)(i => if (i == 0) 1.0 else 0.0)

  "ISRE" should "record the space as presented to the corpus" in {
    val sim = fixture
    sim.processImmediate(input)

    val isre = sim.getIsreHistory()
    isre should have size 1
    isre.head.stepNumber shouldBe 0
    isre.head.length shouldBe 32
    isre.head.nonZero shouldBe List(TrajectoryCell(0, 1.0))
  }

  "OSRE" should "record what the corpus produced, not what it read" in {
    val sim = fixture
    sim.processImmediate(input)

    val osre = sim.getOsreHistory()
    osre should have size 1
    osre.head.stepNumber shouldBe 0
    osre.head.nonZero shouldBe List(TrajectoryCell(20, 1.0))
  }

  it should "record nothing where the corpus produced nothing" in {
    val sim = fixture
    sim.processImmediate(Vector.fill(32)(0.0))

    sim.getOsreHistory().head.nonZero shouldBe empty
    // The ISRE is still recorded: a step where nothing fired is a step, and a
    // history that skipped it would misalign against an engine that did not.
    sim.getIsreHistory() should have size 1
  }

  "the histories" should "be ordered oldest first" in {
    val sim = fixture
    sim.processImmediate(input)
    sim.processImmediate(input)
    sim.processImmediate(input)

    // Compared by index across engines, so a newest-first history would report
    // every step as the first divergence.
    sim.getIsreHistory().map(_.stepNumber) shouldBe List(0, 1, 2)
    sim.getOsreHistory().map(_.stepNumber) shouldBe List(0, 1, 2)
  }

  it should "be appended together, one entry each per step" in {
    val sim = fixture
    for (_ <- 1 to 4) sim.processImmediate(input)

    // The pair is recorded in one action at the end of the step. Unequal
    // lengths would mean an observer can read a step whose trajectories are
    // half-written, which is what makes the history authoritative or not.
    sim.getIsreHistory().length shouldBe sim.getOsreHistory().length
    sim.getIsreHistory() should have size 4
  }

  it should "window by step, not by recency" in {
    val sim = fixture
    for (_ <- 1 to 5) sim.processImmediate(input)

    sim.getIsreHistory(from = 3).map(_.stepNumber) shouldBe List(3, 4)
    sim.getIsreHistory(from = 1, limit = 2).map(_.stepNumber) shouldBe List(1, 2)
  }

  it should "restart at step zero after a reset" in {
    val sim = fixture
    sim.processImmediate(input)
    sim.processImmediate(input)
    sim.reset()

    sim.getIsreHistory() shouldBe empty
    sim.getOsreHistory() shouldBe empty

    // The step counter used to survive the reset that cleared the histories,
    // so a reset engine's first entry was stepNumber 2 here and 0 on LSP —
    // and these histories are compared by stepNumber.
    sim.processImmediate(input)
    sim.getIsreHistory().head.stepNumber shouldBe 0
    sim.getOsreHistory().head.stepNumber shouldBe 0
  }

  "the step" should "carry mergeBatch" in {
    val sim  = fixture
    val step = sim.processImmediate(input)

    // Required of every runtime by SURFACE_SPEC.md and previously absent here:
    // C++ and LSP emitted it and this did not, so a consumer walking the step
    // saw a different shape depending on which engine answered.
    step.mergeBatch should have size 1
    val op = step.mergeBatch.head
    op.machineId shouldBe "machine-traj"
    // One operation per machine per output region since the fold moved into the
    // machine's step (FOLD_PLACEMENT.md §1). The scalar `sequenceId` a folded
    // contribution cannot supply is now the contributing set, and `outputIndex`
    // is gone with it. This machine has one contributing sequence, so §8 applies
    // and the values are the ones the batch carried before.
    op.sequenceIds shouldBe List("seq-machine-traj")
    op.values shouldBe Vector(1.0)
    op.region shouldBe RegionMapping(20, 1)
  }
}
