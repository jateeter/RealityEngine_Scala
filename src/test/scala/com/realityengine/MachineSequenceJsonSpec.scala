package com.realityengine

import com.realityengine.services.MachineLoader
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File
import scala.io.Source
import scala.util.Using

/** `Machine.toJson` carries each sequence's `initialVectorIds`, which is how
  * the Perception Engine resolves merge-batch provenance without a second view
  * of the corpus (RealityEngine_Scala#35).
  *
  * The PE side is covered by `MachineCorpusSpec` in the perception-engine
  * build; this covers the producing end, against the corpus as it ships.
  */
class MachineSequenceJsonSpec extends AnyWordSpec with Matchers {

  private def findCorpus(from: File): Option[File] =
    Option(from).flatMap { d =>
      val candidate = new File(d, "RealityEngine_Machines/machines")
      if (candidate.isDirectory) Some(candidate) else findCorpus(d.getParentFile)
    }

  private def corpusDir: Option[File] =
    sys.env.get("MACHINES_DIR").map(new File(_, "machines")).filter(_.isDirectory)
      .orElse(findCorpus(new File(".").getAbsoluteFile))

  private def machineFile(dir: File, name: String): Option[File] = {
    def walk(f: File): Vector[File] =
      if (f.isDirectory) Option(f.listFiles()).map(_.toVector.flatMap(walk)).getOrElse(Vector.empty)
      else if (f.getName == name) Vector(f)
      else Vector.empty
    walk(dir).headOption
  }

  "Machine.toJson" should {

    "expose each sequence's initial reality event vector ids" in {
      val dir = corpusDir
      assume(dir.isDefined, "machine corpus not found in any parent directory")

      val file = dir.flatMap(machineFile(_, "DocumentSigningWorkflowMonitor.json"))
      assume(file.isDefined, "DocumentSigningWorkflowMonitor.json not in the corpus")

      val raw     = Using(Source.fromFile(file.get, "UTF-8"))(_.mkString).get
      val machine = MachineLoader.loadFromJson(raw)

      val sequences = machine.toJson.hcursor.downField("sequences").as[Vector[io.circe.Json]]
        .getOrElse(fail("sequences missing from Machine.toJson"))

      val initialsBySequence = sequences.flatMap { s =>
        for {
          id       <- s.hcursor.get[String]("id").toOption
          initials <- s.hcursor.get[Vector[String]]("initialEventIds").toOption
        } yield id -> initials
      }.toMap

      // The value C++ emitted as mergeBatch provenance for this sequence in
      // hosted regression run 31263877721.  Note it is not derivable from the
      // sequence id — the corpus names the vector independently.
      initialsBySequence.get("signing-complete") shouldBe Some(Vector("ds-complete"))

      // Every CES carries at least one initial event vector, so no sequence
      // may report an empty list.
      initialsBySequence should not be empty
      initialsBySequence.foreach { case (sequenceId, initials) =>
        withClue(s"sequence $sequenceId reported no initial vector: ") {
          initials should not be empty
        }
      }
    }

    "order initial vector ids deterministically" in {
      // They are held in a Set. C++ iterates its vectors in a map keyed by id,
      // so sorting by id is what keeps the two runtimes byte-identical.
      val machine = MachineLoader.loadFromJson(
        """
        { "version": "1.0.0",
          "machine": {
            "name": "Ordering Probe",
            "perceptualMapping": { "input":  { "offset": 0, "length": 2 },
                                   "output": { "offset": 2, "length": 2 } },
            "sequences": [
              { "id": "s", "name": "s", "vectors": [
                  { "id": "v-z", "isInitial": true,  "elements": [ { "value": 1 } ] },
                  { "id": "v-a", "isInitial": true,  "elements": [ { "value": 1 } ] },
                  { "id": "v-m", "isInitial": false, "elements": [ { "value": 1 } ] }
              ] }
            ] } }
        """
      )

      machine.toJson.hcursor.downField("sequences").downArray
        .get[Vector[String]]("initialEventIds") shouldBe Right(Vector("v-a", "v-z"))
    }
  }
}
