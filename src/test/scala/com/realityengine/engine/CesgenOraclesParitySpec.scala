package com.realityengine.engine

import com.realityengine.models.SimulationStep
import com.realityengine.services.MachineLoader
import io.circe.Json
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source

/** The cesgen oracle set, run against the Scala engine (RealityEngine_CI#200 follow-up).
  *
  * `RealityEngine_Machines/oracles.json` is derived from the corpus by
  * `RealityEngine_CI/scripts/cesgen-oracles.mjs`: for every vector that emits
  * output, given input pattern P the engine must produce output O at the
  * machine's output region after the right number of steps. Its value is
  * cross-runtime — identical pass-sets over one oracle set is parity evidence
  * that no single-runtime suite can give.
  *
  * It was consumed by C++ alone, so "identical pass-sets" was never actually
  * checked; the generator's own docstring described a contract with a
  * participant that never joined. This is the Scala half, and mirrors
  * `RealityEngine_CPP/tests/cesgen_oracles_parity.cpp` deliberately — same
  * oracle file, same isolation, same comparison rule — because a harness that
  * differs in what it asserts cannot demonstrate parity of what it asserts
  * about.
  *
  * Each oracle runs against a spaceRuntime holding only its own machine. That is
  * the isolation the oracle was generated under: it names one machine's
  * sequences and one machine's output region, and a corpus-wide spaceRuntime would
  * let other machines write the same region.
  */
class CesgenOraclesParitySpec extends AnyFlatSpec with Matchers {

  private val repoRoot     = new File(sys.props.getOrElse("user.dir", "."))
  private val machinesRepo = new File(repoRoot.getParentFile, "RealityEngine_Machines")
  private val oracleFile   = new File(machinesRepo, "oracles.json")
  private val machinesDir  = new File(machinesRepo, "machines")

  /** A machine presents ONE Reality Event per instant: the engine folds its
    * collection of potential outputs at the completion boundary
    * (`RealityEngine_CI/docs/FOLD_PLACEMENT.md`). `mergeBatch` carries one
    * operation per machine, so an individual `outputVector` — which is the unit
    * the oracles are generated in, and the right unit to generate — is no
    * longer separately observable.
    *
    * Under a monotone fold a contribution can only add, so the assertion that
    * survives is that the expectation is subsumed by the folded operation. The
    * other transformations can lower a position, so subsumption would be
    * unsound and the exact match is kept. No corpus machine selects one today,
    * which is why it is written down rather than left implied.
    */
  private val MonotoneFolds = Set("or", "max", "join", "strong-disjunction")

  private def read(f: File): String = {
    val src = Source.fromFile(f, "UTF-8")
    try src.mkString finally src.close()
  }

  private lazy val corpusByBasename: Map[String, File] = {
    import java.nio.file.Files
    import scala.jdk.CollectionConverters._
    if (!machinesDir.isDirectory) Map.empty
    else Files.walk(machinesDir.toPath).iterator().asScala
      .map(_.toFile)
      .filter(f => f.isFile && f.getName.endsWith(".json"))
      .map(f => f.getName -> f)
      .toMap
  }

  private case class Oracle(
    id:             String,
    machineFile:    String,
    sequenceId:     String,
    inputRegion:    (Int, Int),
    inputs:         List[Vector[Double]],
    expectedRegion: (Int, Int),
    expectedValues: Vector[Double],
    transformation: String,
  )

  private def parseOracle(j: Json): Option[Oracle] = {
    val c = j.hcursor
    for {
      id   <- c.get[String]("id").toOption
      file <- c.get[String]("machineFile").toOption
      seq  <- c.get[String]("sequenceId").toOption
      inOff  <- c.downField("inputRegion").get[Int]("offset").toOption
      inLen  <- c.downField("inputRegion").get[Int]("length").toOption
      outOff <- c.downField("expected").downField("region").get[Int]("offset").toOption
      outLen <- c.downField("expected").downField("region").get[Int]("length").toOption
      ins    <- c.get[List[Vector[Double]]]("inputs").toOption
      vals   <- c.downField("expected").get[Vector[Double]]("values").toOption
    } yield Oracle(
      id, file, seq, (inOff, inLen), ins, (outOff, outLen), vals,
      // Absent means "or": both the corpus default and what an oracle file
      // generated before the field existed must still mean.
      c.get[String]("outputMergeTransformation").toOption.map(_.toLowerCase).getOrElse("or"),
    )
  }

  /** Sized to cover the region, exactly as `dense_input` does in the C++
    * harness — not to a fixed dimension.
    *
    * Clamping to 7680 silently wrote nothing for any machine mapped above it,
    * and the corpus has plenty: AgricultureAgx001010Interconnect reads
    * [13031:13061]. The engine then saw an all-zero input, matched whichever
    * sequence declares all-zero elements, and reported that sequence's output.
    * 369 oracles "failed" against a vector the harness never filled in. A
    * harness bug that looks like an engine defect is the worst kind, so the
    * sizing is taken from the oracle rather than assumed.
    */
  private def denseInput(region: (Int, Int), values: Vector[Double]): Vector[Double] = {
    val (offset, length) = region
    val buf = Array.fill(offset + length)(0.0)
    values.take(length).zipWithIndex.foreach { case (v, i) => buf(offset + i) = v }
    buf.toVector
  }

  private def subsumed(expected: Vector[Double], folded: Vector[Double]): Boolean =
    folded.length >= expected.length &&
      expected.indices.forall(i => folded(i) + 1e-9 >= expected(i))

  "the cesgen oracle set" should "pass in full against the Scala engine" in {
    assume(oracleFile.isFile, s"oracle set not found at ${oracleFile.getAbsolutePath}")
    assume(machinesDir.isDirectory, s"corpus not found at ${machinesDir.getAbsolutePath}")

    val doc = parse(read(oracleFile)).getOrElse(
      fail(s"${oracleFile.getAbsolutePath} is not valid JSON"))
    val oracles = doc.hcursor.get[List[Json]]("oracles")
      .getOrElse(fail("oracles.json has no `oracles` array"))
      .flatMap(parseOracle)

    oracles should not be empty

    val rawByFile = scala.collection.mutable.Map.empty[String, String]
    def rawFor(name: String): String =
      rawByFile.getOrElseUpdate(name,
        corpusByBasename.get(name).map(read).getOrElse(
          throw new NoSuchElementException(s"machine not in corpus: $name")))

    var passed = 0
    val failures = scala.collection.mutable.ListBuffer.empty[String]

    oracles.zipWithIndex.foreach { case (o, idx) =>
      try {
        val machine = MachineLoader.loadFromJson(rawFor(o.machineFile), Some(s"oracle-$idx"))
        // Wide enough for both regions; the C++ harness starts at 0 and lets
        // the space grow, which comes to the same thing per oracle.
        val dimension = math.max(o.inputRegion._1 + o.inputRegion._2,
                                 o.expectedRegion._1 + o.expectedRegion._2)
        val sim       = new PerceptualSpaceRuntime(dimension)
        sim.addMachine(machine)

        var last: SimulationStep = null
        o.inputs.foreach { row =>
          last = sim.processImmediate(denseInput(o.inputRegion, row))
        }

        val monotone = MonotoneFolds.contains(o.transformation)
        val hit = Option(last).exists(_.mergeBatch.exists { op =>
          op.region.offset == o.expectedRegion._1 &&
            op.region.length == o.expectedRegion._2 &&
            op.sequenceIds.contains(o.sequenceId) &&
            (if (monotone) subsumed(o.expectedValues, op.values)
             else op.values == o.expectedValues)
        })

        if (hit) passed += 1
        else failures += s"${o.id} — expected ${o.expectedValues.mkString(" ")} not " +
          (if (monotone) "subsumed by" else "present in") +
          s" mergeBatch (${Option(last).map(_.mergeBatch.map(_.values.mkString("[", ",", "]")).mkString(" ")).getOrElse("<no step>")})"
      } catch {
        case e: Exception => failures += s"${o.id} — exception: ${e.getMessage}"
      }
    }

    withClue(
      s"${failures.size} of ${oracles.size} oracles failed against the Scala engine:\n" +
        failures.take(20).map("  " + _).mkString("\n") +
        (if (failures.size > 20) s"\n  ... and ${failures.size - 20} more" else "") + "\n"
    ) {
      failures shouldBe empty
    }
    passed shouldBe oracles.size
  }
}
