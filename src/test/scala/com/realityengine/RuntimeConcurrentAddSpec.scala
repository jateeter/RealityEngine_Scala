package com.realityengine

import com.realityengine.engine.PerceptualSpaceRuntime
import com.realityengine.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/**
 * Concurrent addMachine never loses a machine.
 *
 * Machines reach the runtime from Akka HTTP's request threads, several at once
 * when a caller loads in parallel (Manager's Load Machines posts 8 at a time).
 * The runtime's machine set was an immutable Map in a var updated without a
 * lock, so concurrent adds lost updates: the engine's own registry held a
 * machine that the runtime, the perceptual simulation and the machine graph did
 * not. Found by the RealityEngine release install (2026-10-18), where it showed
 * as GET /api/machine-graph and /api/perceptual-simulation/state disagreeing
 * across the quorum.
 */
class RuntimeConcurrentAddSpec extends AnyFlatSpec with Matchers {

  private def machine(i: Int): Machine =
    new Machine(
      s"Concurrent Add $i", "", Map.empty, ArbiterRule.PASSTHROUGH,
      Some(PerceptualMapping(RegionMapping(i * 8, 4), RegionMapping(i * 8 + 4, 2))),
      s"machine-concurrent-add-$i")

  "the perceptual space runtime" should "hold every machine added concurrently" in {
    val count   = 200
    val threads = 16
    for (round <- 1 to 5) {
      val runtime = new PerceptualSpaceRuntime(64)
      val pool    = Executors.newFixedThreadPool(threads)
      val start   = new CountDownLatch(1)
      (0 until count).foreach { i =>
        pool.submit(new Runnable { def run(): Unit = { start.await(); runtime.addMachine(machine(i)) } })
      }
      start.countDown()
      pool.shutdown()
      pool.awaitTermination(60, TimeUnit.SECONDS) shouldBe true
      withClue(s"round $round: ") {
        runtime.getMachines.map(_.id).toSet shouldBe (0 until count).map(i => s"machine-concurrent-add-$i").toSet
      }
    }
  }

  it should "hold exactly the survivors of concurrent adds and removes" in {
    val runtime = new PerceptualSpaceRuntime(64)
    (0 until 100).foreach(i => runtime.addMachine(machine(i)))
    val pool  = Executors.newFixedThreadPool(16)
    val start = new CountDownLatch(1)
    (0 until 100).foreach { i =>
      pool.submit(new Runnable { def run(): Unit = {
        start.await()
        if (i % 2 == 0) runtime.removeMachine(s"machine-concurrent-add-$i")
        else runtime.addMachine(machine(i + 100))
      } })
    }
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS) shouldBe true
    val expected = (0 until 100).filter(_ % 2 == 1).flatMap(i => Seq(i, i + 100)).map(i => s"machine-concurrent-add-$i").toSet
    runtime.getMachines.map(_.id).toSet shouldBe expected
  }
}
