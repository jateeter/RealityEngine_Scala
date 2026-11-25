package com.realityengine.engine

import com.realityengine.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.io.Source

/** Arbitration retention keyed by step, under the instance Lamport clock
  * (RealityEngine_CI#296). Each of n = 0, 1, 2 proves something the others
  * cannot: 0 is an empty window, not "latest only"; 1 is a window of one; 2 is
  * oldest-first and short when fewer steps exist. */
class ArbitrationRetentionSpec extends AnyFlatSpec with Matchers {

  private val Dimension = 32

  /** A machine reading cell 0 and writing `value` to cell 20. Two of them
    * contend for cell 20, so a step that fires them emits one record. */
  private def writer(id: String, value: Double): Machine = {
    val mapping = PerceptualMapping(RegionMapping(0, 1), RegionMapping(20, 1), 8, None)
    val m = new Machine(id, "retention test", Map.empty, ArbiterRule.PASSTHROUGH, Some(mapping), id)
    val seq = new CriticalEventSequence(s"seq-$id", s"seq-$id")
    val v = new RealityEvent(
      Vector(VectorElement(1.0, Some(ComparatorType.GTE), Some(0.5))), isInitial = true, id = s"start-$id")
    v.addOutputVector(OutputVector(s"out-$id", Vector(value), Map.empty, System.currentTimeMillis()))
    seq.addVector(v)
    m.addSequence(seq)
    m
  }

  private def contended(): PerceptualSpaceRuntime = {
    val sim = new PerceptualSpaceRuntime(Dimension)
    sim.addMachine(writer("m-b", 1.0))
    sim.addMachine(writer("m-a", 1.0))
    sim
  }

  private val fire  = Vector.tabulate(Dimension)(i => if (i == 0) 1.0 else 0.0)
  private val quiet = Vector.fill(Dimension)(0.0)
  private def steps(sim: PerceptualSpaceRuntime) = sim.retainedArbitration.map(_._1)

  "arbitration retention" should "be off by default, keep nothing, and refuse ?step" in {
    val sim = contended()
    sim.getArbitrationRetention shouldBe false
    sim.getArbitrationWindow shouldBe 1
    sim.processImmediate(fire)
    sim.getLastArbitration should have size 1      // legacy still answers the latest step
    sim.retainedArbitration shouldBe empty
    sim.arbitrationAt(0) shouldBe Left(409)
    sim.setArbitrationWindow(2)
    sim.processImmediate(fire)
    sim.retainedArbitration shouldBe empty          // the window is ignored while off
  }

  it should "answer n = 0, 1 and 2, oldest first, short when fewer steps exist" in {
    val sim = contended()
    sim.setArbitrationRetention(true)
    sim.setArbitrationWindow(0)
    sim.processImmediate(fire)
    sim.retainedArbitration shouldBe empty          // n = 0 is []
    sim.setArbitrationWindow(1)
    sim.processImmediate(fire)
    steps(sim) shouldBe List(1L)                    // n = 1 is [current]
    sim.setArbitrationWindow(2)
    steps(sim) shouldBe List(1L)                    // widening never pads
    sim.processImmediate(quiet)
    steps(sim) shouldBe List(1L, 2L)                // n = 2 is [previous, current]
    sim.retainedArbitration.map(_._3.size) shouldBe List(1, 0)
    sim.processImmediate(fire)
    steps(sim) shouldBe List(2L, 3L)                // the oldest is evicted first
    sim.arbitrationAt(3).map(_._2.size) shouldBe Right(1)
    sim.arbitrationAt(1) shouldBe Left(410)         // evicted
    sim.arbitrationAt(9) shouldBe Left(404)         // not resolved
    sim.setArbitrationWindow(1)
    steps(sim) shouldBe List(3L)                    // narrowing applies at once
    sim.arbitrationAt(2) shouldBe Left(410)
  }

  it should "toggle both ways without a restart, and drop what it kept when off" in {
    val sim = contended()
    sim.setArbitrationRetention(true)
    sim.processImmediate(fire)
    steps(sim) shouldBe List(0L)
    sim.setArbitrationRetention(false)
    sim.setArbitrationRetention(true)
    sim.retainedArbitration shouldBe empty          // on starts empty
    sim.processImmediate(fire)
    steps(sim) shouldBe List(1L)                    // and retains from the next step
  }

  "a reset" should "clear the latest records and the retained steps (finding B), never the clock" in {
    val sim = contended()
    sim.setArbitrationRetention(true)
    sim.processImmediate(fire)
    sim.processImmediate(fire)
    val (instance, lamport, commit, step) = sim.clockNow
    (lamport, commit, step) shouldBe ((2L, 2L, 1L))
    sim.reset()
    sim.getLastArbitration shouldBe empty
    sim.retainedArbitration shouldBe empty
    sim.clockNow shouldBe ((instance, 2L, 2L, -1L)) // the step count restarts; lamport and commit do not
    sim.processImmediate(fire)
    sim.arbitrationAt(0).map(_._1) shouldBe Right((3L, 3L)) // step 0 again, at a new tick
  }

  "the instance clock" should "mint a canonical v7 UUID per instance when none is allocated" in {
    val a = InstanceClock.minted()
    InstanceClock.canonicalUuid(a.instance) shouldBe true
    a.instance.charAt(14) shouldBe '7'
    InstanceClock.minted().instance should not be a.instance
    InstanceClock.canonicalUuid("cpp-1") shouldBe false
    InstanceClock.boot(Map.empty).lamport shouldBe 0L
  }

  it should "resume past every tick it issued after a restart" in {
    val dir  = Files.createTempDirectory("re-scala-lamport")
    val file = dir.resolve("0192f3a0-0000-7000-8000-0000000002f3.lamport")
    val uuid = "0192f3a0-0000-7000-8000-0000000002f3"
    val first = InstanceClock.persisted(uuid, file)
    List(first.commitStep(), first.commitStep(), first.commitStep()).map(_._2) shouldBe List(1L, 2L, 3L)
    InstanceClock.readReservation(file) shouldBe InstanceClock.Reservation
    InstanceClock.readReservation(dir.resolve(s"$uuid.commit")) shouldBe InstanceClock.Reservation
    first.close()
    val second = InstanceClock.persisted(uuid, file)
    second.commitStep() shouldBe ((InstanceClock.Reservation + 1, InstanceClock.Reservation + 1)) // never reissued
    var last = (1 to 1100).map(_ => second.send()).last
    InstanceClock.readReservation(file) should be >= last  // persisted before issued
    val jumped = second.receive(Some(last + 10 * InstanceClock.Reservation))
    jumped shouldBe last + 10 * InstanceClock.Reservation + 1 // max(local, received) + 1
    InstanceClock.readReservation(file) should be >= jumped   // durable before it is used
    last = jumped
    second.close()
    val third = InstanceClock.persisted(uuid, file)
    third.send() should be > last
    third.commit should be < third.lamport                    // the marks are kept apart
    third.close()
  }

  it should "refuse a second live process presenting the same UUID" in {
    val dir  = Files.createTempDirectory("re-scala-lock")
    val uuid = "0192f3a0-0000-7000-8000-0000000002f4"
    val file = dir.resolve(s"$uuid.lamport")
    val lock = dir.resolve(s"$uuid.lock")
    val holder = new ProcessBuilder("python3", "-c",
      "import fcntl,sys,time\nf=open(sys.argv[1],'a'); fcntl.lockf(f, fcntl.LOCK_EX|fcntl.LOCK_NB)\nprint('locked', flush=True); time.sleep(60)",
      lock.toString).start()
    try {
      Source.fromInputStream(holder.getInputStream).getLines().next() shouldBe "locked"
      val refused = intercept[IllegalStateException](InstanceClock.persisted(uuid, file))
      refused.getMessage should include("already live")
    } finally {
      holder.destroy()
      holder.waitFor()
    }
    val after = InstanceClock.persisted(uuid, file)          // released when the holder exits
    after.commitStep() shouldBe ((1L, 1L))
    after.close()
  }

  it should "tick lamport alone on a send or receive, and commit and lamport on a step (#600)" in {
    val c = InstanceClock.minted()
    c.commitStep() shouldBe ((1L, 1L))
    c.send() shouldBe 2L
    c.commit shouldBe 1L
    c.receive(None) shouldBe 3L                       // no header: + 1
    c.receive(Some(100L)) shouldBe 101L               // max(local, received) + 1
    c.receive(Some(5L)) shouldBe 102L                 // a value behind moves it by one
  }

  it should "seed both marks from a directory written before #600" in {
    val dir  = Files.createTempDirectory("re-scala-migrate")
    val uuid = "0192f3a0-0000-7000-8000-0000000003aa"
    Files.write(dir.resolve(s"$uuid.lamport"), "500\n".getBytes)
    val migrated = InstanceClock.persisted(uuid, dir.resolve(s"$uuid.lamport"))
    (migrated.lamport, migrated.commit) shouldBe ((500L, 500L))
    migrated.commitStep()._2 shouldBe 501L
    migrated.close()
  }

  it should "parse the RE-Clock header exactly as the specification states it" in {
    val u = "0192f3a0-0000-7000-8000-0000000002d9"
    InstanceClock.parseHeader(s"instance=$u; lamport=42") shouldBe Some(42L)
    InstanceClock.parseHeader(s"instance=$u;lamport=0") shouldBe Some(0L)
    InstanceClock.parseHeader(s"instance=$u ;  lamport=9007199254740991") shouldBe Some(9007199254740991L)
    List("", s"lamport=1; instance=$u", s"instance=$u", s"instance=$u; lamport=", s"instance=$u; lamport=-1",
         s"instance=$u; lamport=01", s"instance=$u; lamport=9007199254740992", s"instance=$u; lamport=1; x=2",
         "instance=0192F3A0-0000-7000-8000-0000000002D9; lamport=1", "instance=cpp-1; lamport=1")
      .foreach(bad => InstanceClock.parseHeader(bad) shouldBe None)
    val c = InstanceClock.minted()
    InstanceClock.parseHeader(c.header(7)) shouldBe Some(7L)
  }

  it should "refuse a corrupt or unwritable clock rather than reissue ticks" in {
    val dir = Files.createTempDirectory("re-scala-bad")
    val bad: Path = dir.resolve("0192f3a0-0000-7000-8000-0000000002f5.lamport")
    Files.write(bad, "not-a-number".getBytes)
    an[IllegalStateException] should be thrownBy InstanceClock.persisted("0192f3a0-0000-7000-8000-0000000002f5", bad)
    an[Exception] should be thrownBy
      InstanceClock.persisted("0192f3a0-0000-7000-8000-0000000002f6", Path.of("/dev/null/cannot/x.lamport"))
  }
}
