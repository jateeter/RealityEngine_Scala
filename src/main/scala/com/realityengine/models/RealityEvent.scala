package com.realityengine.models

import java.util.UUID
import io.circe.Json

/**
 * RealityEvent — core unit of the Reality Engine.
 *
 * Mutable state (active, wasJustMatched) mirrors the
 * TypeScript implementation exactly.  Callers are responsible for
 * synchronising access when vectors are shared across threads.
 */
class RealityEvent(
  val elements:  Vector[VectorElement],
  val isInitial: Boolean,
  val id:        String = UUID.randomUUID().toString
) {
  var matchAlgorithm: ComparatorType = ComparatorType.GTE

  private var state:            VectorState          = if (isInitial) VectorState.Active else VectorState.Inactive
  private var _nextVectorIds:   List[String]         = Nil
  private var _outputVectors:   List[OutputVector]   = Nil
  private var _wasJustMatched:  Boolean              = false
  // The Reality Events walked to reach this one, in order. An initial event has
  // none. A successor inherits its activator's chain, so the evidence trail for
  // a firing is the whole path rather than only the step that completed it.
  // Mirrors `predecessorChain` in RealityEngine_CPP (reality.cpp:511).
  private var _predecessorChain: List[String]         = Nil
  var metadata: Map[String, Json] = Map.empty

  // ── Accessors ────────────────────────────────────────────────────────────

  def getVector: Vector[Double]         = elements.map(_.value)
  def getElements: Vector[VectorElement] = elements

  def isActive: Boolean = state == VectorState.Active
  def setActive(predecessorChain: List[String] = Nil): Unit = {
    state = VectorState.Active
    _predecessorChain = predecessorChain
  }
  def clearActive(): Unit = {
    if (!isInitial) {
      state = VectorState.Inactive
      _predecessorChain = Nil
    }
  }

  /** predecessorChain + this id — the evidence for a Reality Event this vector
    * completes. Mirrors `RealityEvent::provenance_chain()` in C++. */
  def provenanceChain: List[String] = _predecessorChain :+ id

  def setWasJustMatched(): Unit  = { _wasJustMatched = true  }
  def clearWasJustMatched(): Unit = { _wasJustMatched = false }
  def wasJustMatched: Boolean    = _wasJustMatched

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
        case ComparatorType.Equals | ComparatorType.Exact =>
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

  override def clone(): RealityEvent = {
    val c = new RealityEvent(elements, isInitial, id)
    c.matchAlgorithm   = matchAlgorithm
    c.state            = state
    c._nextVectorIds   = _nextVectorIds
    c._outputVectors   = _outputVectors
    c._wasJustMatched  = _wasJustMatched
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
    "metadata"         -> Json.fromFields(metadata.toSeq)
  )

  private def outputVectorToJson(ov: OutputVector): Json = Json.obj(
    "id"        -> Json.fromString(ov.id),
    "vector"    -> Json.arr(ov.vector.map(Json.fromDoubleOrNull): _*),
    "metadata"  -> Json.fromFields(ov.metadata.toSeq),
    "timestamp" -> Json.fromLong(ov.timestamp)
  )
}

object RealityEvent {
  def fromJson(json: Json): RealityEvent = {
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
    val v = new RealityEvent(elements, isInitial, id)
    v.matchAlgorithm  = c.get[String]("matchAlgorithm").toOption.map(ComparatorType.fromString).getOrElse(ComparatorType.GTE)
    v.state           = if (c.get[String]("state").toOption.contains("active")) VectorState.Active else VectorState.Inactive
    v._nextVectorIds  = c.downField("nextVectorIds").as[List[String]].getOrElse(Nil)
    v._outputVectors  = c.downField("outputVectors").as[Vector[io.circe.Json]].getOrElse(Vector.empty).toList.map(parseOutputVector)
    v._wasJustMatched = c.get[Boolean]("wasJustMatched").getOrElse(false)
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
