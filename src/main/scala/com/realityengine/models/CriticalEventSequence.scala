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
  private var vectors:        Map[String, RealityEvent] = Map.empty
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
  private val _provenanceBuf  = new scala.collection.mutable.ArrayBuffer[String](8)

  // ── Mutations ────────────────────────────────────────────────────────────

  def addVector(vector: RealityEvent): Unit = {
    vectors = vectors + (vector.id -> vector)
    if (vector.isInitial) initialVectorIds = initialVectorIds + vector.id
    if (vector.getOutputVectors.nonEmpty) outputVectorIds = outputVectorIds + vector.id
  }

  // ── Accessors ────────────────────────────────────────────────────────────

  def getVector(vectorId: String): Option[RealityEvent] = vectors.get(vectorId)
  def getAllVectors: List[RealityEvent]                  = vectors.values.toList
  def getInitialVectors: List[RealityEvent]             = initialVectorIds.flatMap(vectors.get).toList

  /** Ids of this sequence's Initial Reality Event vectors — at least one, by
    * the CES invariant `validate` enforces.
    *
    * Sorted, because these are held in a Set and serialized: C++ iterates its
    * vectors in a map keyed by id, so ordering by id is what makes the two
    * runtimes emit the same bytes for the same sequence. */
  def getInitialVectorIds: List[String]                  = initialVectorIds.toList.sorted
  def getOutputVectorIds: List[String]                   = outputVectorIds.toList.sorted
  def getActiveVectors: List[RealityEvent]              = vectors.values.filter(_.isActive).toList

  // ── Lifecycle ────────────────────────────────────────────────────────────

  /** A sequence is deprecated once it carries a `deprecatedAt`. Mirrors C++'s
    * `is_deprecated`, which tests the same field for non-emptiness. */
  def isDeprecated: Boolean = deprecatedAt.exists(_.nonEmpty)

  /** Days elapsed since deprecation; 0 when unset or unparseable.
    *
    * Accepts "YYYY-MM-DD" and "YYYY-MM-DDTHH:MM:SSZ" by reading the leading
    * date, which is what C++'s `std::get_time(&tm, "%Y-%m-%d")` does — it stops
    * at the format and ignores the rest. Anything more exotic returns 0, the
    * same conservative fallback all three runtimes take: a stale-CES dashboard
    * showing 0 days is visibly wrong, where a parse guess would be plausibly
    * wrong.
    *
    * Measured from local midnight, matching C++'s `mktime`, so the three
    * runtimes agree when they share a timezone and disagree by at most a day
    * when they do not. That is a property of the field's precision, not of the
    * arithmetic — the corpus declares a date, not an instant.
    */
  def daysSinceDeprecation: Long =
    deprecatedAt.flatMap { raw =>
      scala.util.Try {
        val date = java.time.LocalDate.parse(raw.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(
          date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
          java.time.Instant.now())
      }.toOption
    }.getOrElse(0L)

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
   *  1. Clear the per-cycle wasJustMatched flag on every vector.
   *  2. Match every active vector against the input — order irrelevant.
   *  3. For matched vectors: collect outputs, queue successor IDs.
   *  4. Apply all queued activations AFTER the full loop (deferred / atomic).
   */
  def transition(
    inputVector:             Vector[Double],
    matchAlgorithmOverride:  Option[ComparatorType] = None
  ): SequenceResult = {
    // Clear per-cycle flags.
    vectors.values.foreach(_.clearWasJustMatched())

    // Reuse pre-allocated buffers — no allocation per call.
    _matchedBuf.clear()
    _outputsBuf.clear()
    _activatedBuf.clear()
    _pendingBuf.clear()
    _provenanceBuf.clear()

    // Match every currently active vector.
    // Pair each pending successor with the chain its activator carried, so the
    // successor inherits the full evidence trail rather than starting a new
    // one. First activator wins, matching C++'s `pending` map
    // (reality.cpp:687): a successor armed by two matched predecessors in the
    // same step records the first in canonical order, deterministically.
    val pendingChains = scala.collection.mutable.LinkedHashMap.empty[String, List[String]]

    for (vector <- getActiveVectors) {
      // Read the chain BEFORE transitioning. RealityEvent.transition
      // deactivates a transitional vector on a match (RealityEvent.scala:142),
      // and clearActive() drops the predecessor chain with the activation — so
      // reading provenanceChain afterwards returns just the vector's own id and
      // the trail is lost one hop at a time. That truncated dlx-017 to
      // [step-3, step-4] where C++ and LSP report all four.
      //
      // C++ has no such hazard because it takes the chain from the transition
      // result (`tr.provenanceChain`), captured at match time.
      val chain = vector.provenanceChain
      val (matched, nextIds, outputs, _) = vector.transition(inputVector, matchAlgorithmOverride)
      if (matched) {
        _matchedBuf += vector.id

        if (vector.getOutputVectors.nonEmpty) vector.setWasJustMatched()
        nextIds.foreach { nid =>
          _pendingBuf += nid
          if (!pendingChains.contains(nid)) pendingChains(nid) = chain
        }
        // Only a vector that asserted output contributes evidence: the chain
        // is the trail behind a Reality Event that completed, not behind every
        // match. Mirrors C++, which stamps the chain onto the asserted outputs
        // (reality.cpp:599-601) rather than onto every matched vector.
        if (outputs.nonEmpty) chain.foreach(_provenanceBuf += _)
        outputs.foreach(_outputsBuf += _)
      }
    }

    // Deferred activation — apply after all deactivations have settled.
    for (id <- _pendingBuf) {
      vectors.get(id).foreach { nv =>
        if (!nv.isActive) {
          nv.setActive(pendingChains.getOrElse(id, Nil))
          _activatedBuf += id
        }
      }
    }

    SequenceResult(
      matchedVectors   = _matchedBuf.toList,
      activatedVectors = _activatedBuf.toList,
      assertedOutputs  = _outputsBuf.toList,
      provenance       = _provenanceBuf.toList.distinct
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
      "initialVectorIds" -> Json.arr(getInitialVectorIds.map(Json.fromString): _*),
      "outputVectorIds"  -> Json.arr(getOutputVectorIds.map(Json.fromString): _*),
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
    vectorsJs.foreach(vj => seq.addVector(RealityEvent.fromJson(vj)))
    seq.metadata      = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)
    seq.schemaVersion = c.get[String]("schemaVersion").toOption
    seq.deprecatedAt  = c.get[String]("deprecatedAt").toOption
    seq.replacedBy    = c.get[String]("replacedBy").toOption
    seq
  }
}
