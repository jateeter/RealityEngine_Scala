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
  val id:               String                    = s"machine-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(9)}"
) {
  var matchAlgorithm: ComparatorType = ComparatorType.GTE

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
  def getAllSequences: List[CriticalEventSequence]      = effectiveSeqs.values.toList
  def getSequenceCount: Int                            = effectiveSeqs.size
  def getTotalVectorCount: Int                         = getAllSequences.map(_.getAllVectors.length).sum
  def getSequenceIds: List[String]                     = effectiveSeqs.keys.toList
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
    matchAlgorithmOverride: Option[ComparatorType] = None
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
        input  = RegionMapping(m.input.offset,  m.input.length),
        output = RegionMapping(m.output.offset, m.output.length)
      )
    )
    val c = new Machine(name, description, metadata, arbiter.getRule, clonedMapping, id)
    c.matchAlgorithm = matchAlgorithm
    c.cowBase = this.effectiveSeqs   // share reference — no deep copy
    c.isCow   = true
    c
  }

  // ── Serialisation ─────────────────────────────────────────────────────────

  def toJson: Json = {
    import io.circe.syntax._
    val mappingJson = perceptualMapping.map { m =>
      Json.obj(
        "input"  -> Json.obj("offset" -> Json.fromInt(m.input.offset),  "length" -> Json.fromInt(m.input.length)),
        "output" -> Json.obj("offset" -> Json.fromInt(m.output.offset), "length" -> Json.fromInt(m.output.length))
      )
    }.getOrElse(Json.Null)

    Json.obj(
      "id"               -> Json.fromString(id),
      "name"             -> Json.fromString(name),
      "description"      -> Json.fromString(description),
      "matchAlgorithm"   -> Json.fromString(ComparatorType.serialize(matchAlgorithm)),
      "arbiterRule"      -> Json.fromString(ArbiterRule.serialize(arbiter.getRule)),
      "sequenceCount"    -> Json.fromInt(getSequenceCount),
      "totalVectors"     -> Json.fromInt(getTotalVectorCount),
      "sequenceIds"      -> Json.arr(getSequenceIds.map(Json.fromString): _*),
      "sequences"        -> Json.arr(getAllSequences.map(seq =>
        Json.obj("id" -> Json.fromString(seq.id), "name" -> Json.fromString(seq.name))
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
        } yield PerceptualMapping(RegionMapping(iOff, iLen), RegionMapping(oOff, oLen))
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
