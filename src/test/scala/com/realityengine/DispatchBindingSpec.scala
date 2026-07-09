package com.realityengine

import com.realityengine.services.{DispatchBinding, MachineLoader}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source

class DispatchBindingSpec extends AnyFlatSpec with Matchers {
  private def repoRoot: File =
    Iterator
      .iterate(new File(".").getCanonicalFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(f => new File(f, "build.sbt").isFile && new File(f, "src/main/scala/com/realityengine").isDirectory)
      .getOrElse(new File(".").getCanonicalFile)

  private def machineCorpusDir: File =
    new File(repoRoot.getParentFile, "RealityEngine_Machines/machines")

  private def read(file: File): String = {
    val source = Source.fromFile(file)
    try source.mkString finally source.close()
  }

  // Corpus files may live in domain subdirectories — resolve the flat path
  // first, then fall back to a recursive basename search (filenames are
  // globally unique across the corpus).
  private def resolveMachineFile(filename: String): File = {
    val flat = new File(machineCorpusDir, filename)
    if (flat.isFile) flat
    else collectJsonFiles(machineCorpusDir).find(_.getName == filename).getOrElse(flat)
  }

  private def collectJsonFiles(dir: File): Vector[File] = {
    import java.nio.file.{Files => NioFiles}
    import scala.jdk.CollectionConverters._
    if (!dir.isDirectory) Vector.empty
    else NioFiles.walk(dir.toPath)
      .iterator().asScala
      .filter(p => p.toFile.isFile && p.toString.toLowerCase.endsWith(".json"))
      .map(_.toFile)
      .toVector
      .sortBy(_.getAbsolutePath)
  }

  private def loadMachine(filename: String) =
    MachineLoader.loadFromJson(read(resolveMachineFile(filename)), Some("machine-" + filename.stripSuffix(".json").toLowerCase.replaceAll("[^a-z0-9]+", "-")))

  "DispatchBinding" should "prefer first-class agentBinding over legacy dispatch metadata" in {
    val machine = loadMachine("AGX051_yuma-aqua-maintenance-forecaster.json")
    val binding = DispatchBinding.fromMachine(machine, Vector(1, 0, 0, 0))

    binding.agent shouldBe "aquaculture_predictive_maintenance_agent"
    binding.trigger shouldBe "agriculture-yuma-aqua-maintenance-forecaster-maintenance"
    binding.autonomyMode shouldBe "advise"
    binding.writeBack.hcursor.get[String]("type").toOption shouldBe Some("pe-sensor")
    binding.hasDispatch shouldBe true
  }

  it should "preserve writeBack and autonomy mode in dispatch JSON" in {
    val machine = loadMachine("AGX055_yuma-facility-ai-synthesis-bridge.json")
    val dispatch = DispatchBinding.dispatchJson(machine.metadata, Vector(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    val c = dispatch.hcursor

    c.get[String]("agent").toOption shouldBe Some("agriculture_yield_optimization_ai")
    c.get[String]("trigger").toOption shouldBe Some("ag-yield-optimization-ai-yuma-facility-bridge")
    c.get[String]("autonomyMode").toOption shouldBe Some("advise")
    c.downField("writeBack").get[String]("type").toOption shouldBe Some("pe-sensor")
    c.downField("endpoint").get[String]("mutation").toOption shouldBe Some("updateProcessState")
  }

  it should "fall back to legacy dispatch metadata during migration" in {
    val metadata = Map(
      "dispatchableAgent" -> Json.fromString("legacy-agent"),
      "aiTrigger" -> Json.fromString("LEGACY_TRIGGER"),
      "agentActions" -> Json.arr(Json.fromString("inspect"), Json.fromString("act"))
    )
    val binding = DispatchBinding.fromMetadata(metadata, Vector(0, 1))

    binding.agent shouldBe "legacy-agent"
    binding.trigger shouldBe "LEGACY_TRIGGER"
    binding.action shouldBe "act"
    binding.autonomyMode shouldBe ""
    binding.writeBack shouldBe Json.Null
  }

  it should "see the authoritative corpus as first-class agent-bound" in {
    val dir = machineCorpusDir
    withClue(s"missing authoritative machine corpus at ${dir.getAbsolutePath}") {
      dir.isDirectory shouldBe true
    }

    val machines = collectJsonFiles(dir)
      .map(file => parse(read(file)).toOption.get.hcursor.downField("machine").downField("metadata").focus.getOrElse(Json.obj()))

    val agentBound = machines.count(_.hcursor.downField("agentBinding").focus.exists(_.isObject))
    val triggerBound = machines.count { metadata =>
      val rules = metadata.hcursor.downField("triggerConfig").downField("rules").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val binding = DispatchBinding.fromMetadata(metadata.asObject.map(_.toMap).getOrElse(Map.empty))
      rules.nonEmpty && metadata.hcursor.downField("agentBinding").focus.exists(_.isObject) && binding.hasDispatch
    }

    withClue("authoritative top-level corpus should keep first-class agent bindings; update this floor only with an intentional corpus contraction") {
      agentBound should be >= 895
    }
    triggerBound shouldBe agentBound
  }
}
