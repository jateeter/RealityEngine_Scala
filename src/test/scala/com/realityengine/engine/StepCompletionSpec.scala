package com.realityengine.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

/** The step completion point (RealityEngine_CI#375): a step's (ISRE, OSRE)
  * pair is published on the runtime's step monitor once it is committed, and
  * awaitStepPair waits on it within a window. Steps are numbered from 0.
  */
class StepCompletionSpec extends AnyFlatSpec with Matchers {

  "awaitStepPair" should "end at its window when no step has committed" in {
    val rt = new PerceptualSpaceRuntime(16)
    val t0 = System.nanoTime()
    rt.awaitStepPair(0, 100) shouldBe Left(408)
    (System.nanoTime() - t0) / 1000000L should be >= 90L
  }

  it should "be woken by the commit of the step it waits for, with that step's pair" in {
    val rt     = new PerceptualSpaceRuntime(16)
    val result = new AtomicReference[Either[Int, (com.realityengine.models.TrajectoryEntry, com.realityengine.models.TrajectoryEntry)]]()
    val waiter = new Thread(() => result.set(rt.awaitStepPair(0, 5000)))
    waiter.start()
    Thread.sleep(100)
    rt.processImmediate(Vector.fill(16)(0.0))
    waiter.join(5000)
    val Right((isre, osre)) = result.get(): @unchecked
    isre.stepNumber shouldBe 0
    osre.stepNumber shouldBe 0
    isre shouldBe rt.getIsreHistory().head
    osre shouldBe rt.getOsreHistory().head
  }

  it should "answer a committed step at once, keep later steps waiting, and restart after a reset" in {
    val rt = new PerceptualSpaceRuntime(16)
    rt.processImmediate(Vector.fill(16)(0.0))
    rt.awaitStepPair(0, 0).isRight shouldBe true
    rt.awaitStepPair(1, 50) shouldBe Left(408)
    rt.reset()
    rt.awaitStepPair(0, 50) shouldBe Left(408)
  }
}
