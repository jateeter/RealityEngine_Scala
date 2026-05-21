package com.realityengine.models

import java.util.UUID
import io.circe.Json

/**
 * RealityVector — core unit of the Reality Engine.
 *
 * Mutable state (active, wasJustMatched, lastOutputVector) mirrors the
 * TypeScript implementation exactly.  Callers are responsible for
 * synchronising access when vectors are shared across threads.
 */
class RealityVector(
  val elements:  Vector[VectorElement],
  val isInitial: Boolean,
  val id:        String = UUID.randomUUID().toString
) {
  var matchAlgorithm: ComparatorType = ComparatorType.GTE

  private var state:            VectorState          = if (isInitial) VectorState.Active else VectorState.Inactive
  private var _nextVectorIds:   List[String]         = Nil
  private var _outputVectors:   List[OutputVector]   = Nil
  private var _wasJustMatched:  Boolean              = false
  private var _lastOutputVector: Option[OutputVector] = None
  var metadata: Map[String, Json] = Map.empty

  // ── Accessors ────────────────────────────────────────────────────────────

  def getVector: Vector[Double]         = elements.map(_.value)
  def getElements: Vector[VectorElement] = elements

  def isActive: Boolean = state == VectorState.Active
  def setActive():  Unit = { state = VectorState.Active }
  def clearActive(): Unit = { if (!isInitial) state = VectorState.Inactive }

  def setWasJustMatched(): Unit  = { _wasJustMatched = true  }
  def clearWasJustMatched(): Unit = { _wasJustMatched = false }
  def wasJustMatched: Boolean    = _wasJustMatched

  def setLastOutputVector(ov: Option[OutputVector]): Unit = { _lastOutputVector = ov }
  def clearLastOutputVector(): Unit = { _lastOutputVector = None }
  def lastOutputVector: Option[OutputVector] = _lastOutputVector

  def addNextVector(vectorId: String): Unit = {
    if (!_nextVectorIds.contains(vectorId))
      _nextVectorIds = _nextVectorIds :+ vectorId
  }
  def getNextVectorIds: List[String] = _nextVectorIds

  def addOutputVector(ov: OutputVector): Unit = { _outputVectors = _outputVectors :+ ov }
  def getOutputVectors: List[OutputVector]    = _outputVectors

  // ── Match ────────────────────────────────────────────────────────────────

  def match_(inputVector: Vector[Double], matchAlgorithmOverride: Option[ComparatorType] = None): MatchResult = {
    if (inputVector.length != elements.length)
      return MatchResult(matched = false, metadata = Map("error" -> Json.fromString("Vector dimension mismatch")))

    var totalScore = 0.0
    var i = 0
    while (i < elements.length) {
      val elem         = elements(i)
      val inputValue   = inputVector(i)
      val effectiveType = matchAlgorithmOverride.orElse(elem.comparatorType).getOrElse(matchAlgorithm)
      // Pre-extract threshold Option once before the match arm — avoids repeated field resolution.
      val thr          = elem.threshold

      // Inline comparison: returns (matched, score) without allocating a MatchResult per element.
      val (elemMatched, elemScore): (Boolean, Double) = effectiveType match {
        case ComparatorType.Equals =>
          val m = elem.value == inputValue
          (m, if (m) 1.0 else 0.0)

        case ComparatorType.Threshold =>
          val t    = thr.getOrElse(0.1)
          val diff = math.abs(elem.value - inputValue)
          val m    = diff <= t
          (m, if (m) 1.0 - diff / t else 0.0)

        case ComparatorType.Pattern =>
          val sim  = 1.0 - math.abs(elem.value - inputValue)
          val pthr = thr.getOrElse(0.5)
          (sim >= pthr, sim)

        case ComparatorType.Custom =>
          // No custom comparator in JVM context; fall back to Equals
          val m = elem.value == inputValue
          (m, if (m) 1.0 else 0.0)

        case ComparatorType.GTE =>
          val gt        = thr.getOrElse(0.5)
          val inputHigh = inputValue >= gt
          val valueHigh = elem.value  >= gt
          if (inputHigh != valueHigh) (false, 0.0)
          else {
            val score = if (inputHigh) {
              if (gt < 1.0) (inputValue - gt) / (1.0 - gt) else 1.0
            } else {
              if (gt > 0.0) (gt - inputValue) / gt else 1.0
            }
            (true, math.max(0.0, math.min(1.0, score)))
          }
      }

      if (!elemMatched)
        return MatchResult(matched = false, score = totalScore / elements.length,
          metadata = Map("failedAtIndex" -> Json.fromInt(i)))
      totalScore += elemScore
      i += 1
    }
    MatchResult(matched = true, score = totalScore / elements.length)
  }

  // ── Transition ───────────────────────────────────────────────────────────

  /**
   * Returns (matched, nextVectorIds, outputVectors, matchResult).
   * Deactivates transitional vectors (non-initial, no outputs) after a match.
   */
  def transition(inputVector: Vector[Double],
                 matchAlgorithmOverride: Option[ComparatorType] = None
                ): (Boolean, List[String], List[OutputVector], MatchResult) = {
    val mr = match_(inputVector, matchAlgorithmOverride)
    if (!mr.matched) {
      if (!isInitial) clearActive()
      (false, Nil, Nil, mr)
    } else {
      val isFinal        = _outputVectors.nonEmpty
      val isTransitional = !isInitial && !isFinal
      if (isTransitional && _nextVectorIds.nonEmpty) clearActive()
      (true, _nextVectorIds, _outputVectors, mr)
    }
  }

  // ── Clone ────────────────────────────────────────────────────────────────

  override def clone(): RealityVector = {
    val c = new RealityVector(elements, isInitial, id)
    c.matchAlgorithm   = matchAlgorithm
    c.state            = state
    c._nextVectorIds   = _nextVectorIds
    c._outputVectors   = _outputVectors
    c._wasJustMatched  = _wasJustMatched
    c._lastOutputVector = _lastOutputVector
    c.metadata         = metadata
    c
  }

  // ── Serialisation ────────────────────────────────────────────────────────

  def toJson: Json = Json.obj(
    "id"               -> Json.fromString(id),
    "matchAlgorithm"   -> Json.fromString(ComparatorType.serialize(matchAlgorithm)),
    "elements"         -> Json.arr(elements.map(e => Json.obj(
                            "value"          -> Json.fromDoubleOrNull(e.value),
                            "comparatorType" -> e.comparatorType.map(ct => Json.fromString(ComparatorType.serialize(ct))).getOrElse(Json.Null),
                            "threshold"      -> e.threshold.map(Json.fromDoubleOrNull).getOrElse(Json.Null)
                          )): _*),
    "state"            -> Json.fromString(if (state == VectorState.Active) "active" else "inactive"),
    "isActive"         -> Json.fromBoolean(isActive),
    "nextVectorIds"    -> Json.arr(_nextVectorIds.map(Json.fromString): _*),
    "outputVectors"    -> Json.arr(_outputVectors.map(outputVectorToJson): _*),
    "isInitial"        -> Json.fromBoolean(isInitial),
    "wasJustMatched"   -> Json.fromBoolean(_wasJustMatched),
    "lastOutputVector" -> _lastOutputVector.map(outputVectorToJson).getOrElse(Json.Null),
    "metadata"         -> Json.fromFields(metadata.toSeq)
  )

  private def outputVectorToJson(ov: OutputVector): Json = Json.obj(
    "id"        -> Json.fromString(ov.id),
    "vector"    -> Json.arr(ov.vector.map(Json.fromDoubleOrNull): _*),
    "metadata"  -> Json.fromFields(ov.metadata.toSeq),
    "timestamp" -> Json.fromLong(ov.timestamp)
  )
}

object RealityVector {
  def fromJson(json: Json): RealityVector = {
    val c = json.hcursor
    val id        = c.get[String]("id").getOrElse(UUID.randomUUID().toString)
    val isInitial = c.get[Boolean]("isInitial").getOrElse(false)
    val elements  = c.downField("elements").as[Vector[io.circe.Json]].getOrElse(Vector.empty).map { ej =>
      val ec = ej.hcursor
      VectorElement(
        value          = ec.get[Double]("value").getOrElse(0.0),
        comparatorType = ec.get[String]("comparatorType").toOption.map(ComparatorType.fromString),
        threshold      = ec.get[Double]("threshold").toOption
      )
    }
    val v = new RealityVector(elements, isInitial, id)
    v.matchAlgorithm  = c.get[String]("matchAlgorithm").toOption.map(ComparatorType.fromString).getOrElse(ComparatorType.GTE)
    v.state           = if (c.get[String]("state").toOption.contains("active")) VectorState.Active else VectorState.Inactive
    v._nextVectorIds  = c.downField("nextVectorIds").as[List[String]].getOrElse(Nil)
    v._outputVectors  = c.downField("outputVectors").as[Vector[io.circe.Json]].getOrElse(Vector.empty).toList.map(parseOutputVector)
    v._wasJustMatched = c.get[Boolean]("wasJustMatched").getOrElse(false)
    v._lastOutputVector = c.downField("lastOutputVector").as[io.circe.Json].toOption.flatMap { j =>
      if (j.isNull) None else Some(parseOutputVector(j))
    }
    v.metadata = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    v
  }

  private def parseOutputVector(j: Json): OutputVector = {
    val c = j.hcursor
    OutputVector(
      id        = c.get[String]("id").getOrElse(UUID.randomUUID().toString),
      vector    = c.downField("vector").as[Vector[Double]].getOrElse(Vector.empty),
      metadata  = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty),
      timestamp = c.get[Long]("timestamp").getOrElse(System.currentTimeMillis())
    )
  }
}
