package com.realityengine.engine

import com.realityengine.services.{CesCoverageRegistry, MachineLoader}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import scala.io.Source

/** ces_unfired_sequences must fall as sequences fire (RealityEngine_CI#218).
  *
  * The metric previously counted sequences with no active vectors, which
  * CriticalEventSequence's invariant makes impossible, so it read 0 forever.
  * This pins the replacement to observable behaviour: drive a machine with its
  * own interned inputSequences and the unfired count must strictly decrease.
  */
class UnfiredCoverageSpec extends AnyFlatSpec with Matchers {
  private val machinesRepo = new File(new File(sys.props.getOrElse("user.dir",".")).getParentFile, "RealityEngine_Machines")

  // inputSequences is a corpus-JSON field, not a Machine member: it is interned
  // as a test source at load time. It sits under `machine` and each entry is a
  // named scenario carrying its rows under `events` — or `vectors` in a corpus
  // written before RealityEngine_CI#220 layer 1b. Both are read, because the
  // `assume` below turns a missed read into a *cancelled* test rather than a
  // failing one: the spec would go quiet instead of going red.
  private def inputRows(raw: String): List[Vector[Double]] =
    io.circe.parser.parse(raw).toOption.toList.flatMap { j =>
      j.hcursor.downField("machine").downField("inputSequences").values.toList.flatten
        .flatMap(e => e.hcursor.get[List[Vector[Double]]]("events")
          .orElse(e.hcursor.get[List[Vector[Double]]]("vectors")).toOption.toList.flatten)
    }

  private def firstMappedMachine: Option[(com.realityengine.models.Machine, String, List[Vector[Double]])] = {
    import java.nio.file.Files
    import scala.jdk.CollectionConverters._
    val dir = new File(machinesRepo, "machines")
    if (!dir.isDirectory) return None
    Files.walk(dir.toPath).iterator().asScala.map(_.toFile)
      .filter(f => f.isFile && f.getName.endsWith(".json")).toList.sortBy(_.getName)
      .view.flatMap { f =>
        val src = Source.fromFile(f, "UTF-8"); val raw = try src.mkString finally src.close()
        scala.util.Try(MachineLoader.loadFromJson(raw)).toOption
          .filter(_.perceptualMapping.isDefined)
          .map(m => (m, f.getName, inputRows(raw)))
          .filter(_._3.nonEmpty)
      }.headOption
  }

  private def unfired(m: com.realityengine.models.Machine, cov: CesCoverageRegistry): Int = {
    val base = m.id + "\t" + m.name
    m.getAllSequences.count(sq => !cov.outputsSnap.contains(base + "\t" + sq.id))
  }

  "unfired sequence coverage" should "decrease as the machine's own sequences fire" in {
    val picked = firstMappedMachine
    assume(picked.isDefined, s"no mapped machine with inputSequences under ${machinesRepo.getAbsolutePath}")
    val (machine, file, rows) = picked.get
    val mapping = machine.perceptualMapping.get
    val dim = math.max(mapping.input.offset + mapping.input.length, mapping.output.offset + mapping.output.length)
    val cov = new CesCoverageRegistry()
    val sim = new PerceptualSpaceRuntime(dim)
    sim.setCoverageRegistry(cov)
    sim.addMachine(machine)

    val before = unfired(machine, cov)
    withClue(s"$file declares no sequences") { machine.getAllSequences should not be empty }
    before shouldBe machine.getAllSequences.size

    for (_ <- 1 to 12; row <- rows) {
      val buf = Array.fill(dim)(0.0)
      row.take(mapping.input.length).zipWithIndex.foreach { case (v, i) =>
        if (mapping.input.offset + i < dim) buf(mapping.input.offset + i) = v }
      sim.processImmediate(buf.toVector)
    }

    val after = unfired(machine, cov)
    withClue(s"$file: unfired stayed at $after of $before after driving its own inputSequences; " +
             s"outputs recorded=${cov.outputsSnap.size} matched=${cov.matchedSnap.size} ") {
      after should be < before
    }
  }
}
