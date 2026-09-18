package com.realityengine.services

import com.realityengine.models._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import java.util.UUID

/**
 * MachineLoader — loads and saves Machines from/to JSON format.
 * Mirrors the TypeScript MachineLoader exactly.
 */
object MachineLoader {
  val MACHINE_JSON_VERSION = "1.0.0"


  def loadFromJson(jsonString: String, id: Option[String] = None): Machine = {
    val root = parse(jsonString).getOrElse(throw new RuntimeException("JSON parse error"))
    val c    = root.hcursor

    // Two accepted shapes, disambiguated by an object-valued `machine` key:
    //
    //   {"version": "1.0.0", "machine": {…}}   the corpus file envelope
    //   {"name": …, "perceptualMapping": …}    the bare Machine object
    //
    // The second is what docs/openapi/scala-re.yaml declares for
    // POST /api/machines — $ref: '#/components/schemas/Machine' — and this
    // rejected it, so the runtime refused the schema its own published document
    // describes. A client generated from that document could not add a machine.
    //
    // The rejection also misled: a bare machine HAS a name, and it was reported
    // as "Missing machine.name" because this looked for body.machine.name in a
    // body that is itself the machine. A caller reading that message would add a
    // name it had already supplied (RealityEngine_CI#419).
    //
    // The rule is LSP's — src/loader.lisp:248 — which had it right. Adopting the
    // existing correct implementation rather than inventing a third reading.
    // Safe across the corpus: all 1328 files carry the envelope and none has an
    // inner machine with its own object-valued `machine` key, so no machine
    // reads differently under the two.
    val enveloped = c.downField("machine").focus.exists(_.isObject)
    val m         = if (enveloped) c.downField("machine") else c

    // Version belongs to the envelope, not to the machine. Required and
    // validated there — every corpus file carries it, and loosening that would
    // let a file of the wrong major version load silently. The bare Machine
    // schema does not declare `version`, so it is optional for that shape and
    // validated only when a caller supplies one.
    val versionOpt = c.get[String]("version").toOption.orElse(m.get[String]("version").toOption)
    if (enveloped && versionOpt.isEmpty)
      throw new RuntimeException("Missing version field")
    versionOpt.foreach { version =>
      val major    = version.split('.').headOption.flatMap(_.toIntOption).getOrElse(0)
      val curMajor = MACHINE_JSON_VERSION.split('.').headOption.flatMap(_.toIntOption).getOrElse(0)
      if (major != curMajor)
        throw new RuntimeException(s"Incompatible machine JSON version: $version (current: $MACHINE_JSON_VERSION)")
    }

    // Name the field the caller actually has to add. Saying "machine.name" for a
    // body that IS the machine sends them to a path that does not exist in what
    // they sent.
    val name = m.get[String]("name").getOrElse(
      throw new RuntimeException(if (enveloped) "Missing machine.name" else "Missing name"))
    val description  = m.get[String]("description").getOrElse("")
    val arbiterStr   = m.get[String]("arbiterRule").getOrElse("PASSTHROUGH")
    val arbiterRule  = parseArbiterRule(arbiterStr)
    val algoStr      = m.get[String]("matchAlgorithm").toOption
    val matchAlgo    = algoStr.map(ComparatorType.fromString).getOrElse(ComparatorType.GTE)

    val mapping = m.downField("perceptualMapping").as[Json].toOption.flatMap { mj =>
      if (mj.isNull) None
      else {
        val mc = mj.hcursor
        for {
          iOff <- mc.downField("input").get[Int]("offset").toOption
          iLen <- mc.downField("input").get[Int]("length").toOption
          oOff <- mc.downField("output").get[Int]("offset").toOption
          oLen <- mc.downField("output").get[Int]("length").toOption
        } yield {
          val bpe = mc.get[Int]("bitsPerElement").toOption
            .filter(Set(1, 2, 4, 8).contains)
            .getOrElse(8)
          // k for the chain fold. Filtered at >= 1 because a top below that is
          // not a chain; the fold applies the same filter, so a malformed
          // declaration reads as undeclared in both places rather than in one.
          val top = mc.get[Int]("outputAlphabetTop").toOption.filter(_ >= 1)
          PerceptualMapping(RegionMapping(iOff, iLen), RegionMapping(oOff, oLen), bpe, top)
        }
      }
    }

    val metadataBase = m.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    val inputSeqsJson = m.downField("inputSequences").as[Json].getOrElse(Json.arr())
    val metadata     = metadataBase + ("inputSequences" -> inputSeqsJson)

    val machine = new Machine(name, description, metadata, arbiterRule, mapping,
      id.getOrElse(s"machine-${System.currentTimeMillis()}-${UUID.randomUUID().toString.take(8)}"))
    machine.matchAlgorithm = matchAlgo
    // Read at intern time so the machine carries it from the moment it loads.
    // Absent means "or", which is what every runtime already does, so no corpus
    // file needs to declare it for behaviour to stay as it is.
    machine.outputMergeTransformation =
      OutputMergeTransformation.normalise(m.get[String]("outputMergeTransformation").toOption)

    m.downField("sequences").as[Vector[Json]].getOrElse(Vector.empty).foreach { sj =>
      machine.addSequence(loadSequenceFromJson(sj, matchAlgo))
    }

    machine
  }

  private def loadSequenceFromJson(sj: Json, matchAlgo: ComparatorType): CriticalEventSequence = {
    val sc   = sj.hcursor
    val seqId = sc.get[String]("id").getOrElse(UUID.randomUUID().toString)
    val name  = sc.get[String]("name").getOrElse("unnamed")
    val seq   = new CriticalEventSequence(name, seqId)
    seq.metadata      = sc.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    seq.schemaVersion = sc.get[String]("schemaVersion").toOption
    seq.deprecatedAt  = sc.get[String]("deprecatedAt").toOption
    seq.replacedBy    = sc.get[String]("replacedBy").toOption

    val vectorsJson = sc.downField("events").as[Vector[Json]].getOrElse(Vector.empty)

    // First pass — create all vectors
    val vectorOrder = scala.collection.mutable.ListBuffer.empty[String]
    val vectorMap   = scala.collection.mutable.Map.empty[String, RealityEvent]

    for (vj <- vectorsJson) {
      val vc        = vj.hcursor
      val vectorId  = vc.get[String]("id").getOrElse(UUID.randomUUID().toString)
      val isInitial = vc.get[Boolean]("isInitial").getOrElse(false)
      val elements  = vc.downField("elements").as[Vector[Json]].getOrElse(Vector.empty).map { ej =>
        val ec = ej.hcursor
        VectorElement(
          value          = ec.get[Double]("value").getOrElse(0.0),
          comparatorType = ec.get[String]("comparatorType").toOption.map(ComparatorType.fromString),
          threshold      = ec.get[Double]("threshold").toOption
        )
      }

      val vector = new RealityEvent(elements, isInitial, vectorId)
      vector.matchAlgorithm = matchAlgo
      vector.metadata = vc.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)

      vc.downField("outputEvents").as[Vector[Json]].getOrElse(Vector.empty).foreach { oj =>
        val oc = oj.hcursor
        vector.addOutputVector(OutputVector(
          id        = oc.get[String]("id").getOrElse(UUID.randomUUID().toString),
          vector    = oc.downField("vector").as[Vector[Double]].getOrElse(Vector.empty),
          metadata  = oc.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty),
          timestamp = System.currentTimeMillis()
        ))
      }

      vectorMap(vectorId) = vector
      vectorOrder += vectorId
      seq.addVector(vector)
    }

    // Second pass — wire up nextVectorIds
    vectorsJson.zip(vectorOrder.toList).foreach { case (vj, vectorId) =>
      val vc = vj.hcursor
      vc.downField("nextEventIds").as[Vector[String]].getOrElse(Vector.empty).foreach { nextId =>
        vectorMap.get(vectorId).foreach(_.addNextVector(nextId))
      }
    }

    seq
  }

  def saveToJson(machine: Machine, pretty: Boolean = true): String = {
    val seqs = machine.getAllSequences.map { seq =>
      val lifecycleFields: Seq[(String, Json)] = Seq(
        seq.schemaVersion.map("schemaVersion" -> Json.fromString(_)),
        seq.deprecatedAt.map("deprecatedAt"   -> Json.fromString(_)),
        seq.replacedBy.map("replacedBy"       -> Json.fromString(_))
      ).flatten
      Json.fromFields(Seq(
        "id"       -> Json.fromString(seq.id),
        "name"     -> Json.fromString(seq.name),
        "metadata" -> seq.metadata.asJson
      ) ++ lifecycleFields ++ Seq(
        "events"   -> Json.arr(seq.getAllVectors.map { vec =>
          val elements = vec.getElements.map { elem =>
            val base = Map("value" -> Json.fromDoubleOrNull(elem.value))
            val withComp = elem.comparatorType match {
              case Some(ct) if ct != machine.matchAlgorithm =>
                base + ("comparatorType" -> Json.fromString(ComparatorType.serialize(ct)))
              case _ => base
            }
            val withThresh = elem.threshold.map(t => withComp + ("threshold" -> Json.fromDoubleOrNull(t))).getOrElse(withComp)
            Json.fromFields(withThresh.toSeq)
          }
          Json.obj(
            "id"           -> Json.fromString(vec.id),
            "elements"     -> Json.arr(elements: _*),
            "isInitial"    -> Json.fromBoolean(vec.isInitial),
            "metadata"     -> vec.metadata.asJson,
            "nextEventIds"  -> Json.arr(vec.getNextVectorIds.map(Json.fromString): _*),
            "outputEvents"  -> Json.arr(vec.getOutputVectors.map { ov =>
              Json.obj(
                "id"       -> Json.fromString(ov.id),
                "vector"   -> ov.vector.asJson,
                "metadata" -> ov.metadata.asJson
              )
            }: _*)
          )
        }: _*)
      ))
    }

    val metaWithoutInputSeqs = machine.metadata - "inputSequences"
    val inputSeqs = machine.metadata.getOrElse("inputSequences", Json.arr())

    val mappingJson = machine.perceptualMapping.map { m =>
      Json.obj(
        "input"          -> Json.obj("offset" -> Json.fromInt(m.input.offset), "length" -> Json.fromInt(m.input.length)),
        "output"         -> Json.obj("offset" -> Json.fromInt(m.output.offset), "length" -> Json.fromInt(m.output.length)),
        "bitsPerElement" -> Json.fromInt(m.bitsPerElement)
      ).deepMerge(Machine.chainTopJson(m))
    }

    val machineFields = Seq(
      "name"          -> Json.fromString(machine.name),
      "description"   -> Json.fromString(machine.description),
      "metadata"      -> metaWithoutInputSeqs.asJson,
      "arbiterRule"   -> Json.fromString(ArbiterRule.serialize(machine.getArbiter.getRule).toUpperCase),
      "matchAlgorithm" -> Json.fromString(ComparatorType.serialize(machine.matchAlgorithm)),
      "sequences"     -> Json.arr(seqs: _*),
      "inputSequences" -> inputSeqs
    ) ++ mappingJson.map("perceptualMapping" -> _).toSeq

    val root = Json.obj(
      "version" -> Json.fromString(MACHINE_JSON_VERSION),
      "machine" -> Json.fromFields(machineFields)
    )

    if (pretty) root.spaces2 else root.noSpaces
  }

  def validate(jsonString: String): (Boolean, List[String]) = {
    var errors = List.empty[String]
    try {
      val root = parse(jsonString).getOrElse(throw new RuntimeException("JSON parse error"))
      val c    = root.hcursor
      if (c.get[String]("version").isLeft) errors = errors :+ "Missing required field: version"
      val m = c.downField("machine")
      if (!root.hcursor.downField("machine").succeeded) {
        errors = errors :+ "Missing required field: machine"
        return (false, errors)
      }
      if (m.get[String]("name").isLeft)        errors = errors :+ "Missing required field: machine.name"
      if (m.get[String]("description").isLeft) errors = errors :+ "Missing required field: machine.description"
      if (m.get[String]("arbiterRule").isLeft) errors = errors :+ "Missing required field: machine.arbiterRule"
      m.downField("sequences").as[Vector[Json]].getOrElse(Vector.empty).zipWithIndex.foreach { case (sj, si) =>
        val sc = sj.hcursor
        if (sc.get[String]("name").isLeft) errors = errors :+ s"Sequence $si: Missing required field: name"
        sc.downField("events").as[Vector[Json]].getOrElse(Vector.empty).zipWithIndex.foreach { case (vj, vi) =>
          val vc = vj.hcursor
          if (vc.downField("elements").as[Vector[Json]].isLeft) errors = errors :+ s"Sequence $si, Vector $vi: Missing or invalid field: elements"
          if (vc.get[Boolean]("isInitial").isLeft) errors = errors :+ s"Sequence $si, Vector $vi: Missing required field: isInitial"
        }
      }
    } catch { case e: Exception => errors = errors :+ s"JSON parse error: ${e.getMessage}" }
    (errors.isEmpty, errors)
  }

  private def parseArbiterRule(s: String): ArbiterRule = s.toUpperCase match {
    case "PASSTHROUGH" => ArbiterRule.PASSTHROUGH
    case "AND"         => ArbiterRule.AND
    case "OR"          => ArbiterRule.OR
    case other         => throw new RuntimeException(s"Unknown arbiter rule: $other. Valid: PASSTHROUGH, AND, OR")
  }
}
