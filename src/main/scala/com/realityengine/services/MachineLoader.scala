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

    val version = c.get[String]("version").getOrElse(throw new RuntimeException("Missing version field"))
    val major   = version.split('.').headOption.flatMap(_.toIntOption).getOrElse(0)
    val curMajor = MACHINE_JSON_VERSION.split('.').headOption.flatMap(_.toIntOption).getOrElse(0)
    if (major != curMajor)
      throw new RuntimeException(s"Incompatible machine JSON version: $version (current: $MACHINE_JSON_VERSION)")

    val m = c.downField("machine")

    val name         = m.get[String]("name").getOrElse(throw new RuntimeException("Missing machine.name"))
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
        } yield PerceptualMapping(RegionMapping(iOff, iLen), RegionMapping(oOff, oLen))
      }
    }

    val metadataBase = m.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    val inputSeqsJson = m.downField("inputSequences").as[Json].getOrElse(Json.arr())
    val metadata     = metadataBase + ("inputSequences" -> inputSeqsJson)

    val machine = new Machine(name, description, metadata, arbiterRule, mapping,
      id.getOrElse(s"machine-${System.currentTimeMillis()}-${UUID.randomUUID().toString.take(9)}"))
    machine.matchAlgorithm = matchAlgo

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
    seq.metadata = sc.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)

    val vectorsJson = sc.downField("vectors").as[Vector[Json]].getOrElse(Vector.empty)

    // First pass — create all vectors
    val vectorOrder = scala.collection.mutable.ListBuffer.empty[String]
    val vectorMap   = scala.collection.mutable.Map.empty[String, RealityVector]

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

      val vector = new RealityVector(elements, isInitial, vectorId)
      vector.matchAlgorithm = matchAlgo
      vector.metadata = vc.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)

      vc.downField("outputVectors").as[Vector[Json]].getOrElse(Vector.empty).foreach { oj =>
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
      vc.downField("nextVectorIds").as[Vector[String]].getOrElse(Vector.empty).foreach { nextId =>
        vectorMap.get(vectorId).foreach(_.addNextVector(nextId))
      }
    }

    seq
  }

  def saveToJson(machine: Machine, pretty: Boolean = true): String = {
    val seqs = machine.getAllSequences.map { seq =>
      Json.obj(
        "id"       -> Json.fromString(seq.id),
        "name"     -> Json.fromString(seq.name),
        "metadata" -> seq.metadata.asJson,
        "vectors"  -> Json.arr(seq.getAllVectors.map { vec =>
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
            "nextVectorIds" -> Json.arr(vec.getNextVectorIds.map(Json.fromString): _*),
            "outputVectors" -> Json.arr(vec.getOutputVectors.map { ov =>
              Json.obj(
                "id"       -> Json.fromString(ov.id),
                "vector"   -> ov.vector.asJson,
                "metadata" -> ov.metadata.asJson
              )
            }: _*)
          )
        }: _*)
      )
    }

    val metaWithoutInputSeqs = machine.metadata - "inputSequences"
    val inputSeqs = machine.metadata.getOrElse("inputSequences", Json.arr())

    val mappingJson = machine.perceptualMapping.map { m =>
      Json.obj(
        "input"  -> Json.obj("offset" -> Json.fromInt(m.input.offset), "length" -> Json.fromInt(m.input.length)),
        "output" -> Json.obj("offset" -> Json.fromInt(m.output.offset), "length" -> Json.fromInt(m.output.length))
      )
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
        sc.downField("vectors").as[Vector[Json]].getOrElse(Vector.empty).zipWithIndex.foreach { case (vj, vi) =>
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
