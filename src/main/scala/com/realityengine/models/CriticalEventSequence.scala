package com.realityengine.models

import java.util.UUID
import io.circe.Json

/**
 * CriticalEventSequence — a sequence of RealityVectors that impacts reality.
 *
 * Key characteristics:
 *  - At least one vector asserts an OutputVector (has outputs).
 *  - At least one InitialRealityVector (isInitial = true) is always Active.
 *  - Deferred activation: successor vectors are activated AFTER the full active-
 *    vector loop, preventing same-cycle cascade.
 */
class CriticalEventSequence(
  val name: String,
  val id:   String = UUID.randomUUID().toString
) {
  private var vectors:        Map[String, RealityVector] = Map.empty
  private var initialVectorIds: Set[String]              = Set.empty
  private var outputVectorIds:  Set[String]              = Set.empty
  var metadata:      Map[String, Json] = Map.empty
  var schemaVersion: Option[String]    = None
  var deprecatedAt:  Option[String]    = None
  var replacedBy:    Option[String]    = None

  // Pre-allocated transition buffers — cleared and reused each transition() call
  // to avoid allocating new ListBuffers on every hot-path invocation.
  private val _matchedBuf     = new scala.collection.mutable.ArrayBuffer[String](8)
  private val _outputsBuf     = new scala.collection.mutable.ArrayBuffer[OutputVector](4)
  private val _activatedBuf   = new scala.collection.mutable.ArrayBuffer[String](8)
  private val _pendingBuf     = new scala.collection.mutable.HashSet[String]()

  // ── Mutations ────────────────────────────────────────────────────────────

  def addVector(vector: RealityVector): Unit = {
    vectors = vectors + (vector.id -> vector)
    if (vector.isInitial) initialVectorIds = initialVectorIds + vector.id
    if (vector.getOutputVectors.nonEmpty) outputVectorIds = outputVectorIds + vector.id
  }

  // ── Accessors ────────────────────────────────────────────────────────────

  def getVector(vectorId: String): Option[RealityVector] = vectors.get(vectorId)
  def getAllVectors: List[RealityVector]                  = vectors.values.toList
  def getInitialVectors: List[RealityVector]             = initialVectorIds.flatMap(vectors.get).toList
  def getActiveVectors: List[RealityVector]              = vectors.values.filter(_.isActive).toList

  // ── Validation ───────────────────────────────────────────────────────────

  def validate(): (Boolean, List[String]) = {
    var errors = List.empty[String]
    if (initialVectorIds.isEmpty) errors = errors :+ "CriticalEventSequence must have at least one initial vector"
    if (outputVectorIds.isEmpty)  errors = errors :+ "CriticalEventSequence must have at least one vector with output"
    (errors.isEmpty, errors)
  }

  // ── Transition ───────────────────────────────────────────────────────────

  /**
   * Process one input cycle over all currently active vectors.
   *
   * Rules:
   *  1. Clear per-cycle flags (wasJustMatched, lastOutputVector) on every vector.
   *  2. Match every active vector against the input — order irrelevant.
   *  3. For matched vectors: collect outputs, queue successor IDs.
   *  4. Apply all queued activations AFTER the full loop (deferred / atomic).
   */
  def transition(
    inputVector:             Vector[Double],
    matchAlgorithmOverride:  Option[ComparatorType] = None
  ): SequenceResult = {
    // Clear per-cycle flags.
    vectors.values.foreach { v =>
      v.clearWasJustMatched()
      v.clearLastOutputVector()
    }

    // Reuse pre-allocated buffers — no allocation per call.
    _matchedBuf.clear()
    _outputsBuf.clear()
    _activatedBuf.clear()
    _pendingBuf.clear()

    // Match every currently active vector.
    for (vector <- getActiveVectors) {
      val (matched, nextIds, outputs, _) = vector.transition(inputVector, matchAlgorithmOverride)
      if (matched) {
        _matchedBuf += vector.id

        if (vector.getOutputVectors.nonEmpty) {
          vector.setWasJustMatched()
          outputs.headOption.foreach(ov => vector.setLastOutputVector(Some(ov)))
        }

        nextIds.foreach(_pendingBuf += _)
        outputs.foreach(_outputsBuf += _)
      }
    }

    // Deferred activation — apply after all deactivations have settled.
    for (id <- _pendingBuf) {
      vectors.get(id).foreach { nv =>
        if (!nv.isActive) {
          nv.setActive()
          _activatedBuf += id
        }
      }
    }

    SequenceResult(
      matchedVectors   = _matchedBuf.toList,
      activatedVectors = _activatedBuf.toList,
      assertedOutputs  = _outputsBuf.toList
    )
  }

  // ── Reset ─────────────────────────────────────────────────────────────────

  def reset(): Unit = vectors.values.foreach { v =>
    if (v.isInitial) v.setActive() else v.clearActive()
  }

  // ── Stats ─────────────────────────────────────────────────────────────────

  def getStats: Map[String, Int] = Map(
    "totalVectors"   -> vectors.size,
    "activeVectors"  -> getActiveVectors.length,
    "initialVectors" -> initialVectorIds.size,
    "outputVectors"  -> outputVectorIds.size
  )

  // ── Clone ─────────────────────────────────────────────────────────────────

  override def clone(): CriticalEventSequence = {
    val c = new CriticalEventSequence(name, id)
    vectors.values.foreach(v => c.addVector(v.clone()))
    c.metadata      = metadata
    c.schemaVersion = schemaVersion
    c.deprecatedAt  = deprecatedAt
    c.replacedBy    = replacedBy
    c
  }

  // ── Serialisation ─────────────────────────────────────────────────────────

  def toJson: Json = {
    import io.circe.syntax._
    val lifecycleFields: Seq[(String, Json)] = Seq(
      schemaVersion.map("schemaVersion" -> Json.fromString(_)),
      deprecatedAt.map("deprecatedAt"   -> Json.fromString(_)),
      replacedBy.map("replacedBy"       -> Json.fromString(_))
    ).flatten
    Json.fromFields(Seq(
      "id"               -> Json.fromString(id),
      "name"             -> Json.fromString(name),
      "vectors"          -> Json.arr(getAllVectors.map(_.toJson): _*),
      "initialVectorIds" -> Json.arr(initialVectorIds.toList.map(Json.fromString): _*),
      "outputVectorIds"  -> Json.arr(outputVectorIds.toList.map(Json.fromString): _*),
      "metadata"         -> metadata.asJson
    ) ++ lifecycleFields)
  }
}

object CriticalEventSequence {
  def fromJson(json: Json): CriticalEventSequence = {
    val c         = json.hcursor
    val id        = c.get[String]("id").getOrElse(UUID.randomUUID().toString)
    val name      = c.get[String]("name").getOrElse("unnamed")
    val seq       = new CriticalEventSequence(name, id)
    val vectorsJs = c.downField("vectors").as[Vector[Json]].getOrElse(Vector.empty)
    vectorsJs.foreach(vj => seq.addVector(RealityVector.fromJson(vj)))
    seq.metadata      = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    seq.schemaVersion = c.get[String]("schemaVersion").toOption
    seq.deprecatedAt  = c.get[String]("deprecatedAt").toOption
    seq.replacedBy    = c.get[String]("replacedBy").toOption
    seq
  }
}
