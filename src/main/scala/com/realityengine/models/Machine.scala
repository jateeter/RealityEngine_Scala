package com.realityengine.models

import io.circe.Json

/**
 * Machine — a collection of CriticalEventSequences that work together.
 *
 * Implements the 3-phase Reality Engine workflow:
 *   Phase 1: Resolve new input reality vector
 *   Phase 2: Apply input to all active sequences
 *   Phase 3: Resolve output reality vector via arbiter
 */
class Machine(
  val name:             String,
  val description:      String                    = "",
  val metadata:         Map[String, Json]         = Map.empty,
  arbiterRule:          ArbiterRule               = ArbiterRule.PASSTHROUGH,
  var perceptualMapping: Option[PerceptualMapping] = None,
  val id:               String                    = s"machine-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
) {
  var matchAlgorithm: ComparatorType = ComparatorType.GTE
  // How this machine folds its collection of potential outputs into the one
  // output the Reality Engine presents to the PE. Declared per machine
  // (`outputMergeTransformation`, default "or"), read when the machine is
  // interned, and mutable between steps — it is a training variable.
  var outputMergeTransformation: String = OutputMergeTransformation.Or
  // Interlock on the knob above. Initialised LOCKED: the transformation is a
  // training variable, and a run that retunes one by accident is a run whose
  // results mean nothing and which nothing distinguishes from a valid one.
  // Changing it requires unlocking first, deliberately and as a separate act.
  //
  // Runtime state, not a corpus property — a machine's declared transformation
  // travels with it, but whether this deployment is currently allowed to change
  // it does not. Every restart comes up locked.
  var outputMergeLocked: Boolean = true

  private var sequences: Map[String, CriticalEventSequence] = Map.empty
  private val arbiter = new OutputArbiter(arbiterRule)

  // ── COW snapshot support (Fix: clone() is O(1); sequences copied lazily in processInput) ──────

  // When isCow is true this Machine was produced by clone() and shares cowBase with its origin.
  // The first processInput call materialises private copies of each sequence.
  private var cowBase: Map[String, CriticalEventSequence] = Map.empty
  private var isCow: Boolean = false

  // Unified read accessor — transparently handles both normal and COW modes.
  @inline private def effectiveSeqs: Map[String, CriticalEventSequence] =
    if (isCow) cowBase else sequences

  // ── Sequence management ───────────────────────────────────────────────────

  def addSequence(seq: CriticalEventSequence): Unit    = { sequences = sequences + (seq.id -> seq) }
  def removeSequence(seqId: String): Unit              = { sequences = sequences - seqId }
  def getSequence(seqId: String): Option[CriticalEventSequence] = effectiveSeqs.get(seqId)
  /** Sequences in canonical order: (name, id).
    *
    * effectiveSeqs is keyed by sequence id and ids are generated per runtime,
    * so map order varied between engines for the same corpus — the
    * "MEMORY ALERT SET / RESET" divergence in RealityEngine_CI#91. */
  def getAllSequences: List[CriticalEventSequence] =
    effectiveSeqs.values.toList.sortBy(s => (s.name, s.id))
  def getSequenceCount: Int                            = effectiveSeqs.size
  def getTotalVectorCount: Int                         = getAllSequences.map(_.getAllVectors.length).sum
  /** Mirrors getAllSequences so ids and sequences stay positionally aligned. */
  def getSequenceIds: List[String]                     = getAllSequences.map(_.id)
  def hasSequence(seqId: String): Boolean              = effectiveSeqs.contains(seqId)

  def getArbiter: OutputArbiter = arbiter
  def setArbiterRule(r: ArbiterRule): Unit = arbiter.setRule(r)

  // ── Processing ────────────────────────────────────────────────────────────

  /**
   * Process an input vector through all sequences and resolve machine output.
   *
   * COW: if this is a snapshot clone, each sequence is deep-copied before its
   *      first transition so the origin machine's state is never modified.
   * Thread-safety: local buffers ensure no shared mutable state across concurrent calls.
   */
  def processInput(
    inputVector:            Vector[Double],
    matchAlgorithmOverride: Option[ComparatorType] = None,
    audit:                  Boolean                = true
  ): MachineTransitionResult = {
    val seqResultsBuffer = new scala.collection.mutable.HashMap[String, SequenceResult]
    val seqOutputsBuffer = new scala.collection.mutable.HashMap[String, List[OutputVector]]

    for ((seqId, seq) <- effectiveSeqs) {
      // COW materialisation: clone this sequence into our own map before mutating it.
      val ownSeq = if (isCow) {
        val cloned = seq.clone()
        sequences = sequences + (seqId -> cloned)
        cloned
      } else seq

      val sr = ownSeq.transition(inputVector, matchAlgorithmOverride)
      seqResultsBuffer(seqId)  = sr
      seqOutputsBuffer(seqId)  = sr.assertedOutputs
      // Semantic audit (SEMANTIC_AUDIT_CONTRACT.md): one re:SequenceObservation
      // per matched step; determination fields from the first asserted output.
      if (audit && sr.matchedVectors.nonEmpty) {
        val output = sr.assertedOutputs.headOption
        val meta   = output.map(_.metadata).getOrElse(Map.empty)
        sr.matchedVectors.foreach { stepId =>
          com.realityengine.services.SemanticAuditLog.record(
            com.realityengine.services.SemanticAuditLog.Observation(
              at              = System.currentTimeMillis(),
              machineId       = id,
              machineName     = name,
              sequenceId      = seqId,
              stepId          = stepId,
              completed       = output.nonEmpty,
              determinationId = output.map(_.id),
              actionCode      = meta.get("action").flatMap(_.asString),
              ragStatus       = meta.get("ragStatusCode").flatMap(_.asString)
            ))
        }
      }
    }

    // Once all sequences have been materialised the clone is fully owned.
    if (isCow) { isCow = false; cowBase = Map.empty }

    val decision = arbiter.arbitrate(seqOutputsBuffer.toMap, seqResultsBuffer.size)

    MachineTransitionResult(
      inputVector     = inputVector,
      timestamp       = System.currentTimeMillis(),
      sequenceResults = seqResultsBuffer.toMap,
      machineOutput   = decision.machineOutput,
      arbiterMetadata = ArbiterMetadata(
        rule                = ArbiterRule.serialize(decision.rule),
        totalInputs         = decision.totalInputs,
        sequencesWithOutput = decision.sequencesWithOutput,
        shouldOutput        = decision.shouldOutput
      )
    )
  }

  /**
   * Process input extracted from the perceptual space and merge output back.
   */
  def processInputFromPerceptualSpace(perceptualSpace: PerceptualSpace): MachineTransitionResult = {
    val mapping = perceptualMapping.getOrElse(
      throw new IllegalStateException(s"Machine $name does not have a perceptual mapping configured"))
    val machineInput = perceptualSpace.extractMachineInput(mapping)
    val result       = processInput(machineInput)
    result.machineOutput.foreach { ov =>
      perceptualSpace.mergeMachineOutput(ov.vector, mapping)
    }
    result
  }

  // ── Reset ─────────────────────────────────────────────────────────────────

  def reset(): Unit = sequences.values.foreach(_.reset())

  // ── Clone (COW snapshot) ──────────────────────────────────────────────────

  /**
   * Returns a copy-on-write snapshot.  O(1): no sequences or vectors are
   * allocated here.  Individual sequences are deep-copied lazily inside
   * processInput, only when they are about to be mutated.
   */
  override def clone(): Machine = {
    val clonedMapping = perceptualMapping.map(m =>
      PerceptualMapping(
        input          = RegionMapping(m.input.offset,  m.input.length),
        output         = RegionMapping(m.output.offset, m.output.length),
        bitsPerElement = m.bitsPerElement
      )
    )
    val c = new Machine(name, description, metadata, arbiter.getRule, clonedMapping, id)
    c.matchAlgorithm = matchAlgorithm
    c.cowBase = this.effectiveSeqs   // share reference — no deep copy
    c.isCow   = true
    c
  }

  /** A fully independent copy, sequences and Reality Events included.
    *
    * `clone()` is copy-on-write: it shares the origin's sequence objects and
    * only takes its own copies when `processInput` first mutates them. That is
    * right for a clone that is about to be processed — `whatIf` does exactly
    * that — but wrong for anything that must *hold* a state.
    *
    * A checkpoint is the second case. Stored as a COW clone it shared the live
    * machine's Reality Events, so as the machine advanced the "snapshot"
    * advanced with it, and restoring it handed back the current state: the
    * restore answered 200 and changed nothing (#51). A snapshot that tracks its
    * subject is not a snapshot.
    *
    * `CriticalEventSequence.clone()` deep-copies its vectors and
    * `RealityVector.clone()` carries `state`, so cloning each sequence here is
    * enough to capture the active RE list — which is what a back-step restores.
    */
  def deepClone(): Machine = {
    val clonedMapping = perceptualMapping.map(m =>
      PerceptualMapping(
        input          = RegionMapping(m.input.offset,  m.input.length),
        output         = RegionMapping(m.output.offset, m.output.length),
        bitsPerElement = m.bitsPerElement
      )
    )
    val c = new Machine(name, description, metadata, arbiter.getRule, clonedMapping, id)
    c.matchAlgorithm = matchAlgorithm
    effectiveSeqs.values.foreach(seq => c.addSequence(seq.clone()))
    c
  }

  // ── Serialisation ─────────────────────────────────────────────────────────

  /** metadata.domain, or "" when absent. */
  def domain: String =
    metadata.get("domain").flatMap(_.asString).getOrElse("")

  def toJson: Json = {
    import io.circe.syntax._
    val mappingJson = perceptualMapping.map { m =>
      Json.obj(
        "input"          -> Json.obj("offset" -> Json.fromInt(m.input.offset),  "length" -> Json.fromInt(m.input.length)),
        "output"         -> Json.obj("offset" -> Json.fromInt(m.output.offset), "length" -> Json.fromInt(m.output.length)),
        "bitsPerElement" -> Json.fromInt(m.bitsPerElement)
      )
    }.getOrElse(Json.Null)

    Json.obj(
      "id"               -> Json.fromString(id),
      "name"             -> Json.fromString(name),
      "description"      -> Json.fromString(description),
      "matchAlgorithm"   -> Json.fromString(ComparatorType.serialize(matchAlgorithm)),
      "outputMergeTransformation" -> Json.fromString(outputMergeTransformation),
      "outputMergeLocked" -> Json.fromBoolean(outputMergeLocked),
      "arbiterRule"      -> Json.fromString(ArbiterRule.serialize(arbiter.getRule)),
      "sequenceCount"    -> Json.fromInt(getSequenceCount),
      "totalVectors"     -> Json.fromInt(getTotalVectorCount),
      "sequenceIds"      -> Json.arr(getSequenceIds.map(Json.fromString): _*),
      // initialVectorIds travels with the shallow summary because the
      // Perception Engine owns the mapping from a fired sequence to the next
      // Reality Event vector, and the merge batch reports those ids as the
      // audit trail behind the assertion.  The PE cannot derive them — the
      // corpus names them arbitrarily (signing-complete asserts ds-complete)
      // — and fetching each machine's full detail to read one field would be
      // a request per machine.  Reporting a corpus fact the engine already
      // holds is not the same as owning the merge.
      "sequences"        -> Json.arr(getAllSequences.map(seq =>
        Json.obj(
          "id"               -> Json.fromString(seq.id),
          "name"             -> Json.fromString(seq.name),
          "initialVectorIds" -> Json.arr(seq.getInitialVectorIds.map(Json.fromString): _*),
        )
      ): _*),
      "metadata"         -> metadata.asJson,
      "perceptualMapping" -> mappingJson
    )
  }

  /** Full serialization including sequence internals. */
  def toFullJson: Json = {
    val base = toJson.asObject.get
    val withSequences = base.add("sequences", Json.arr(getAllSequences.map(_.toJson): _*))
    Json.fromJsonObject(withSequences)
  }
}

object Machine {

  /** Canonical ordering for machine collections, shared by every runtime.
    *
    * Machines are stored in maps keyed by id and ids are generated per runtime,
    * so iteration order differed between C++, LSP and Scala and the same corpus
    * serialized to different bytes.  Ordering by content rather than by
    * identity is what makes the comparison meaningful.
    *
    * `(domain, name, id)` — the trailing id keeps the order total when two
    * machines share a domain and name, and metadata.domain is absent on a
    * handful of corpus machines, which sort first under an empty key.
    */
  val canonicalOrder: Ordering[Machine] =
    Ordering.by(m => (m.domain, m.name, m.id))

  /** Machines in canonical order. */
  def inCanonicalOrder(machines: Iterable[Machine]): List[Machine] =
    machines.toList.sorted(canonicalOrder)

  def fromFullJson(json: Json): Machine = {
    val c           = json.hcursor
    val id          = c.get[String]("id").getOrElse(s"machine-${System.currentTimeMillis()}")
    val name        = c.get[String]("name").getOrElse("unnamed")
    val description = c.get[String]("description").getOrElse("")
    val algoStr     = c.get[String]("matchAlgorithm").toOption
    val arbiterStr  = c.get[String]("arbiterRule").toOption
    val metadata    = c.downField("metadata").as[Map[String, Json]].getOrElse(Map.empty)

    val mapping = c.downField("perceptualMapping").as[Json].toOption.flatMap { mj =>
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
          PerceptualMapping(RegionMapping(iOff, iLen), RegionMapping(oOff, oLen), bpe)
        }
      }
    }

    val arbiterRule = arbiterStr.map(ArbiterRule.fromString).getOrElse(ArbiterRule.PASSTHROUGH)
    val machine = new Machine(name, description, metadata, arbiterRule, mapping, id)
    machine.matchAlgorithm = algoStr.map(ComparatorType.fromString).getOrElse(ComparatorType.GTE)

    c.downField("sequences").as[Vector[Json]].getOrElse(Vector.empty).foreach { sj =>
      machine.addSequence(CriticalEventSequence.fromJson(sj))
    }
    machine
  }
}
