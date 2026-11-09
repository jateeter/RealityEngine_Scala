package com.realityengine.models

import io.circe.Json

/** Cloneable machines and the clone space (RealityEngine_CI SURFACE_SPEC.md,
  * "Cloneable machines"; RealityEngine_CI#713, localAssetStorageStack#8).
  *
  * Recognition is decided here, by the specification's nine-rule table; nothing
  * in a machine file marks a machine cloneable. The clone space is a band of
  * 64-cell blocks in the nest layout, allocated lowest free first, each with a
  * generation the recycle route must present.
  */
object CloneSpace {
  // Fixed by the specification ("The clone space"); RealityEngine_Machines
  // domains/clone-space.json records the same values.
  val BandOffset       = 20480
  val BandLength       = 65536
  val BlockCells       = 64
  val Quarter          = 16
  val Blocks: Int      = BandLength / BlockCells // 1024
  val BusCells         = 4
  val MaxActiveDefault = 8

  /** `reason` is the first failing rule's code (None when cloneable); `n` the
    * folded fanout cells plus the bus cells, whenever rule 9 was evaluated. */
  final case class Verdict(cloneable: Boolean, reason: Option[String], n: Option[Int], folded: Int)

  private def edges(m: Machine): Vector[Json] =
    m.metadata.get("interconnections").flatMap(_.asArray).getOrElse(Vector.empty)

  private def isProducer(e: Json): Boolean =
    e.hcursor.get[String]("type").toOption.contains("published-domain-bus-producer")

  /** The corpus file name without .json, or None for a machine with no file. */
  private def fileStem(m: Machine): Option[String] =
    m.sourceFile.filter(_.nonEmpty).map(f => if (f.endsWith(".json")) f.dropRight(5) else f)

  private def isOutgoingHandoff(e: Json, stem: Option[String]): Boolean = stem.exists { s =>
    e.hcursor.get[String]("type").toOption.contains("document-workflow-handoff") &&
      e.hcursor.get[String]("sourceMachine").toOption.contains(s)
  }

  /** The file names other machines' published-domain-bus-producer edges target. */
  def busTargetFiles(machines: Iterable[Machine]): Set[String] =
    machines.iterator.flatMap(edges).filter(isProducer)
      .flatMap(_.hcursor.get[String]("targetMachineFile").toOption).filter(_.nonEmpty).toSet

  /** The nine-rule table, in order. */
  def assess(m: Machine, busTargets: Set[String]): Verdict = {
    val stem      = fileStem(m)
    val es        = edges(m)
    val producers = es.filter(isProducer)
    val handoffs  = es.count(e => !isProducer(e) && isOutgoingHandoff(e, stem))
    val folded    = producers.map(_.hcursor.downField("targetInputElements").focus.flatMap(_.asArray).map(_.size).getOrElse(0)).sum
    def fail(reason: String) = Verdict(cloneable = false, Some(reason), None, 0)
    val binding = m.metadata.get("agentBinding").filterNot(_.isNull)
    // 1. It dispatches an agentic action.
    if (binding.isEmpty && handoffs == 0) return fail("no-agentic-action")
    // 2. It has fanout.
    if (producers.isEmpty && handoffs == 0) return fail("no-fanout")
    // 3. It is not a bus target.
    if (m.sourceFile.exists(busTargets.contains)) return fail("bus-target")
    // 4. Not life-safety.
    if (m.metadata.get("severity").flatMap(_.asString).contains("life-safety")) return fail("life-safety")
    // 5. Not a fixture (string tags only).
    if (m.metadata.get("tags").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).exists(_.contains("fixture")))
      return fail("fixture")
    // 6. Not opted out.
    if (m.metadata.get("cloneable").flatMap(_.asBoolean).contains(false)) return fail("opted-out")
    // 7, 8. Its regions fit their quarters; a machine with no mapping fails rule 7.
    m.perceptualMapping match {
      case None => return fail("input-too-long")
      case Some(pm) =>
        if (pm.input.length > Quarter) return fail("input-too-long")
        if (pm.output.length > Quarter) return fail("output-too-long")
    }
    // 9. The cells its fanout folds into fit the fanout quarter.
    val n = folded + BusCells
    if (folded > Quarter) Verdict(cloneable = false, Some("fanout-too-wide"), Some(n), folded)
    else Verdict(cloneable = true, None, Some(n), folded)
  }

  /** GET /api/machines/cloneable: every registered machine except clones,
    * sorted by id, with its verdict. */
  def listing(machines: Iterable[Machine]): Json = {
    val all     = machines.toVector
    val targets = busTargetFiles(all)
    Json.obj("machines" -> Json.arr(all.filter(_.cloneOf.isEmpty).sortBy(_.id).map { m =>
      val v = assess(m, targets)
      Json.obj(
        "id"        -> Json.fromString(m.id),
        "cloneable" -> Json.fromBoolean(v.cloneable),
        "reason"    -> v.reason.fold(Json.Null)(Json.fromString),
        "n"         -> v.n.fold(Json.Null)(Json.fromInt))
    }: _*))
  }

  final case class Clone(
    cloneId: String, machineId: String, engineInstance: String, envelopeId: String, assetId: String,
    block: Int, generation: Long, inputLength: Int, outputLength: Int, fanoutLength: Int
  ) {
    def offset: Int               = BandOffset + BlockCells * block
    def input: RegionMapping      = RegionMapping(offset, inputLength)
    def output: RegionMapping     = RegionMapping(offset + Quarter, outputLength)
    def fanout: RegionMapping     = RegionMapping(offset + 2 * Quarter, fanoutLength)
    def bus: RegionMapping        = RegionMapping(offset + 3 * Quarter, BusCells)
    def blockJson: Json = Json.obj("index" -> Json.fromInt(block), "offset" -> Json.fromInt(offset), "length" -> Json.fromInt(BlockCells))
    /** The 201 body, and each entry of GET /api/engine/clones. */
    def toJson: Json = {
      def region(r: RegionMapping) = Json.obj("offset" -> Json.fromInt(r.offset), "length" -> Json.fromInt(r.length))
      Json.obj(
        "cloneId"        -> Json.fromString(cloneId),
        "machineId"      -> Json.fromString(machineId),
        "engineInstance" -> Json.fromString(engineInstance),
        "envelopeId"     -> Json.fromString(envelopeId),
        "assetId"        -> Json.fromString(assetId),
        "block"          -> blockJson,
        "input"          -> region(input),
        "output"         -> region(output),
        "fanout"         -> region(fanout),
        "bus"            -> region(bus),
        "generation"     -> Json.fromLong(generation))
    }
  }

  /** The template as declared, at its initial state, with its regions moved
    * into the clone's block. Its sequences and Reality Events are its own:
    * `CriticalEventSequence.clone()` deep-copies, and nothing is shared with
    * the template as `Machine.clone()`'s copy-on-write would. */
  def cloneMachine(template: Machine, c: Clone): Machine = {
    val pm = template.perceptualMapping.get
    val m = new Machine(template.name, template.description, template.metadata, template.getArbiter.getRule,
                        Some(pm.copy(input = c.input, output = c.output)), c.cloneId)
    m.matchAlgorithm = template.matchAlgorithm
    m.outputMergeTransformation = template.outputMergeTransformation
    template.getAllSequences.foreach(seq => m.addSequence(seq.clone()))
    m.reset()
    m.cloneOf = Some(c.machineId)
    m.assetId = Some(c.assetId)
    m.envelopeId = Some(c.envelopeId)
    m
  }
}

/** The live clones and every block's generation. A restart loses them all, and
  * every generation starts again at 0. Callers hold the routes' clone lock. */
final class CloneSpace {
  import CloneSpace._
  private val generations = Array.fill[Long](Blocks)(0L)
  private var byBlock     = scala.collection.immutable.TreeMap.empty[Int, Clone]
  @volatile var maxActive: Int = MaxActiveDefault

  def lowestFreeBlock: Option[Int] = (0 until Blocks).find(k => !byBlock.contains(k))
  def liveOf(machineId: String): Int = byBlock.values.count(_.machineId == machineId)
  def byId(cloneId: String): Option[Clone] = byBlock.values.find(_.cloneId == cloneId)
  def byEnvelope(envelopeId: String): Option[Clone] = byBlock.values.find(_.envelopeId == envelopeId)
  def isClone(machineId: String): Boolean = byId(machineId).isDefined
  def generation(block: Int): Long = generations(block)
  def add(c: Clone): Unit = {
    require(!byBlock.contains(c.block), s"clone block ${c.block} already held")
    byBlock += c.block -> c
  }
  /** Frees the clone's block and increments its generation; returns the new one. */
  def recycle(cloneId: String): Long = {
    val c = byId(cloneId).getOrElse(throw new NoSuchElementException(s"no live clone $cloneId"))
    byBlock -= c.block
    generations(c.block) += 1
    generations(c.block)
  }
  /** Live clones in block order. */
  def live: Vector[Clone] = byBlock.values.toVector
}
