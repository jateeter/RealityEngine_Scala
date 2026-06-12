package com.realityengine

import akka.actor.ActorSystem
import com.realityengine.engine.{PerceptualSpaceSimulator, RealityEngine}
import com.realityengine.services.{MachineLoader, VectorStore}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{File, OutputStream, PrintStream}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Failure, Success, Try}

class MachineCorpusRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("machine-corpus-registry-spec")
  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()
  }

  private val silentOut = new PrintStream(new OutputStream {
    override def write(b: Int): Unit = ()
  })

  private def repoRoot: File =
    Iterator
      .iterate(new File(".").getCanonicalFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(f => new File(f, "build.sbt").isFile && new File(f, "src/main/scala/com/realityengine").isDirectory)
      .getOrElse(new File(".").getCanonicalFile)

  private def machineCorpusDir: File =
    new File(repoRoot.getParentFile, "RealityEngine_Machines/machines")

  private def machineIdFromFile(file: File): String =
    "machine-" + file.getName.stripSuffix(".json").toLowerCase.replaceAll("[^a-z0-9]+", "-")

  "Authoritative RealityEngine_Machines corpus" should "load into the Scala engine registry" in {
    val dir = machineCorpusDir
    withClue(s"missing authoritative machine corpus at ${dir.getAbsolutePath}") {
      dir.isDirectory shouldBe true
    }

    val files = Option(dir.listFiles((_, name) => name.toLowerCase.endsWith(".json")))
      .getOrElse(Array.empty[File])
      .sortBy(_.getName)
      .toVector
    files should not be empty

    val engine = new RealityEngine(new VectorStore(vectorDimension = 7680), universalDimension = 7680)
    val simulator = new PerceptualSpaceSimulator(7680)

    val loaded = Console.withOut(silentOut) {
      files.map { file =>
        val text = {
          val src = Source.fromFile(file)
          try src.mkString finally src.close()
        }
        Try {
          val machine = MachineLoader.loadFromJson(text, Some(machineIdFromFile(file)))
          engine.addMachine(machine)
          machine.perceptualMapping.foreach(_ => simulator.addMachine(machine))
          machine
        } match {
          case Success(machine) => Right(file.getName -> machine)
          case Failure(error)   => Left(file.getName -> error.getMessage)
        }
      }
    }

    val failures = loaded.collect { case Left(err) => err }
    withClue(failures.take(20).mkString("corpus load failures:\n", "\n", "")) {
      failures shouldBe empty
    }

    val machines = loaded.collect { case Right((_, machine)) => machine }
    engine.getAllMachines.map(_.id).toSet should contain allOf (
      "machine-agx051-yuma-aqua-maintenance-forecaster",
      "machine-agx052-yuma-do-probe-reliability-tracker",
      "machine-agx053-yuma-vpd-hvac-service-planner",
      "machine-agx054-yuma-co2-safety-compliance-officer",
      "machine-agx055-yuma-facility-ai-synthesis-bridge"
    )
    engine.getAllMachines.size shouldBe files.size
    simulator.getMachines.size shouldBe machines.count(_.perceptualMapping.isDefined)
  }
}
