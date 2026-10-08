package com.realityengine

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.realityengine.api.Routes
import com.realityengine.engine._
import com.realityengine.logging.AuditConfig
import com.realityengine.services.{MachineLoader, VectorStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source

/** GET /api/metrics counts each coverage event once, and renders every counter
  * the step path records (RealityEngine_Scala#185).
  *
  * The per-machine baselines carried each machine's total while the event-keyed
  * series carried the same counts under the same machine labels, so any sum over
  * machine counted Scala twice: over 24 h live, scala-1 reported 3,068 matches
  * against 1,472 (cpp-1) and 1,467 (lsp-1). Paging decisions and deprecated fires
  * were recorded and never rendered; an invented GREEN zero stood in for them.
  */
class MetricsExpositionSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {
  private val machinesRepo = new File(new File(sys.props.getOrElse("user.dir", ".")).getParentFile, "RealityEngine_Machines")

  // Same corpus pick as UnfiredCoverageSpec: a mapped machine with interned
  // inputSequences, so driving it fires real coverage rather than a fixture's.
  private def inputRows(raw: String): List[Vector[Double]] =
    io.circe.parser.parse(raw).toOption.toList.flatMap { j =>
      j.hcursor.downField("machine").downField("inputSequences").values.toList.flatten
        .flatMap(e => e.hcursor.get[List[Vector[Double]]]("events")
          .orElse(e.hcursor.get[List[Vector[Double]]]("vectors")).toOption.toList.flatten)
    }

  private def firstMappedMachine: Option[(com.realityengine.models.Machine, List[Vector[Double]])] = {
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
          .map(m => (m, inputRows(raw)))
          .filter(_._2.nonEmpty)
      }.headOption
  }

  private case class Sample(name: String, labels: Map[String, String], value: Double)

  private val SampleLine = """^([a-z_]+)\{(.*)\} (\S+)$""".r
  private val Label      = """([a-z_]+)="((?:[^"\\]|\\.)*)"""".r

  private def samples(body: String): List[Sample] =
    body.linesIterator.collect { case SampleLine(n, ls, v) =>
      Sample(n, Label.findAllMatchIn(ls).map(m => m.group(1) -> m.group(2)).toMap, v.toDouble)
    }.toList

  private def scrape(routes: akka.http.scaladsl.server.Route): List[Sample] =
    Get("/api/metrics") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      samples(responseAs[String])
    }

  private def drivenFixture = {
    val picked = firstMappedMachine
    assume(picked.isDefined, s"no mapped machine with inputSequences under ${machinesRepo.getAbsolutePath}")
    val (machine, rows) = picked.get
    val mapping = machine.perceptualMapping.get
    val dim = math.max(mapping.input.offset + mapping.input.length, mapping.output.offset + mapping.output.length)
    val engine       = new RealityEngine(new VectorStore())
    val spaceRuntime = new PerceptualSpaceRuntime(dim)
    spaceRuntime.setCoverageRegistry(engine.coverage)
    engine.addMachine(machine)
    spaceRuntime.addMachine(machine)
    for (_ <- 1 to 12; row <- rows) {
      val buf = Array.fill(dim)(0.0)
      row.take(mapping.input.length).zipWithIndex.foreach { case (v, i) =>
        if (mapping.input.offset + i < dim) buf(mapping.input.offset + i) = v }
      spaceRuntime.processImmediate(buf.toVector)
    }
    val routes = new Routes(engine, spaceRuntime, AuditConfig(enabled = false, level = 0, service = "metrics-test")).routes
    (engine, machine, routes)
  }

  private val Counters = List("ces_vector_matched_total", "ces_vector_activated_total", "ces_sequence_outputs_total")

  "GET /api/metrics" should "emit the per-machine counter baselines at zero" in {
    val (engine, _, routes) = drivenFixture
    engine.coverage.matchedSnap should not be empty
    val baselines = scrape(routes).filter(s => Counters.contains(s.name) && s.labels.keySet == Set("runtime", "machine", "machine_id"))
    baselines should not be empty
    all(baselines.map(_.value)) shouldBe 0.0
  }

  it should "count each coverage event once when summed over machine" in {
    val (engine, machine, routes) = drivenFixture
    val got = scrape(routes)
    def summed(name: String) = got.filter(s => s.name == name && s.labels.get("machine_id").contains(machine.id)).map(_.value).sum
    summed("ces_vector_matched_total")   shouldBe engine.coverage.matchedSnap.values.sum.toDouble
    summed("ces_vector_activated_total") shouldBe engine.coverage.activatedSnap.values.sum.toDouble
    summed("ces_sequence_outputs_total") shouldBe engine.coverage.outputsSnap.values.sum.toDouble
  }

  it should "render recorded paging decisions and deprecated fires" in {
    val (engine, machine, routes) = drivenFixture
    engine.coverage.recordPagingDecision("grid-ops", "open", "RED", machine.id)
    engine.coverage.recordPagingDecision("grid-ops", "open", "RED", machine.id)
    engine.coverage.recordDeprecatedFire(machine.id, machine.name, "seq-old", "seq-new")
    val got = scrape(routes)

    // Driving the machine records its own governance decisions too; every one
    // rendered must be one the registry holds, and the invented stand-in is gone.
    val paging = got.filter(_.name == "ces_paging_decisions_total")
    paging.map(_.value).sum shouldBe engine.coverage.pagingDecisionsSnap.values.sum.toDouble
    paging.filter(_.labels.get("owner_team").contains("unknown")) shouldBe empty
    paging.filter(_.labels.get("owner_team").contains("grid-ops"))
      .map(s => (s.labels - "runtime", s.value)) shouldBe List((Map(
        "owner_team" -> "grid-ops", "process_status" -> "open", "rag_status_code" -> "RED",
        "machine_id" -> machine.id, "machine" -> machine.name), 2.0))

    got.filter(s => s.name == "ces_deprecated_fires_total" && s.labels.contains("replaced_by"))
      .map(s => (s.labels("sequence"), s.labels("replaced_by"), s.value)) shouldBe List(("seq-old", "seq-new", 1.0))
  }
}
