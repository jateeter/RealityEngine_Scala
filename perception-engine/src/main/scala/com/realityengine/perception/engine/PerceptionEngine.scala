package com.realityengine.perception.engine

import com.fasterxml.uuid.Generators
import com.realityengine.perception.models._

import scala.util.Random

/**
 * PerceptionEngine — Scala port of the TypeScript PerceptionEngine.
 *
 * All public methods are synchronized: the auto-push Akka scheduler and
 * inbound HTTP requests both touch engine state concurrently.
 *
 * Signal generation lives in the companion object as pure functions.
 */
class PerceptionEngine(initialDimension: Int = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)) {
  private val uuidGen = Generators.timeBasedReorderedGenerator()

  private var sources: Map[String, SourceConfig]        = Map.empty
  private var testStep: Map[String, Int]                = Map.empty
  private var walkState: Map[String, Vector[Double]]    = Map.empty
  // Sources restored from the store that nothing in this run has touched yet.
  // reset() re-arms test sources so this run's interned sequences replay from
  // the top; a restored source has no sequence this run interned, so re-arming
  // it replays the previous run's. Membership ends the moment this run adds,
  // updates or feeds the source — at which point it is an ordinary source.
  private var restoredDormant: Set[String]              = Set.empty
  // Persistent perceptual space — carries machine outputs forward.  Grows
  // dynamically as sources with higher offsets are added so machines whose
  // perceptualMapping extends beyond the initial dimension still receive
  // input on every push.  Without this, sources whose region starts at or
  // beyond vectorDimension are silently skipped in assembleVector and the
  // corresponding machines never see their inputs change.
  @volatile private var _vectorDimension: Int           = initialDimension
  private var persistentVector: Array[Double]           = new Array[Double](initialDimension)
  def vectorDimension: Int = _vectorDimension

  /** Expand persistentVector and vectorDimension to cover [0, requiredEnd). */
  private def ensureCapacity(requiredEnd: Int): Unit = {
    if (requiredEnd > _vectorDimension) {
      val previous = _vectorDimension
      val grown = new Array[Double](requiredEnd)
      System.arraycopy(persistentVector, 0, grown, 0, previous)
      persistentVector  = grown
      _vectorDimension  = requiredEnd
      System.err.println(s"[PerceptionEngine] perceptionDimension grew $previous → $requiredEnd")
    }
  }

  var globalStep: Long = 0L
  var matchAlgorithm: MatchAlgorithm = MatchAlgorithm.Gte

  def setMatchAlgorithm(algo: MatchAlgorithm): Unit = synchronized {
    matchAlgorithm = algo
  }

  // ── Source CRUD ───────────────────────────────────────────────────────────

  /** Add a source, keeping a caller-supplied id and generating one only when
    * the config does not carry one.
    *
    * This matches C++ (`if (source.id.empty()) source.id = make_id("source")`),
    * which is the canonical definition. Overwriting unconditionally meant the
    * deterministic `test-<machineId>` id that bootstrapSourcesFromMachines
    * builds was discarded, so Scala served random UUIDs where C++ and LSP
    * served `test-machine-dcmemoryalertff` — the last thing keeping
    * /api/sources and /api/state off three-way byte equality
    * (RealityEngine_CI#91).
    *
    * The decoders default an absent `id` to "", so sources created through
    * POST /api/sources are unaffected unless the caller supplies one.
    */
  def addSource(config: SourceConfig): SourceConfig = synchronized {
    val id  = if (config.id.nonEmpty) config.id else uuidGen.generate().toString
    val src = applyId(config, id)
    ensureCapacity(src.region.offset + src.region.length)
    sources = sources + (id -> src)
    restoredDormant = restoredDormant - id
    initRuntimeState(id, src)
    src
  }

  /** Restore a previously persisted source preserving its original ID.
    *
    * Restored ALWAYS INACTIVE, whatever the store recorded. An active source
    * contributes its region to every vector this PE assembles, so restoring one
    * active makes the engine perceive data before anything in this run produced
    * it. For the localAI machines that is not merely early, it is impossible:
    * every value in those windows is a *response* to a dispatch this process has
    * not yet made, so a value present at step 0 is last run's answer to a
    * question nobody asked.
    *
    * That is how it surfaced. On a freshly booted one-machine universe, scala-1
    * arrived holding 56 PE sources against C++'s 1 and LSP's 0 and emitted
    * `localai` output cells at step 0 that neither of the others did:
    *
    *     orev cpp-1   set=0 []
    *     orev lsp-1   set=0 []
    *     orev scala-1 set=4 [7449, 7457, 7581, 7589]
    *
    * C++ and LSP persist no sources at all, so they cannot reach this state and
    * the difference read as an engine divergence (#54).
    *
    * Activity is run state, not configuration. It is earned by receiving a
    * value, which is the contract the rest of the stack already states:
    * "PE sources are declared inactive and activated by their first value."
    * The registration survives a restart; the claim to be carrying live data
    * does not.
    */
  def restoreSource(src: SourceConfig): Unit = synchronized {
    val dormant = src.withActive(false)
    ensureCapacity(dormant.region.offset + dormant.region.length)
    sources = sources + (dormant.id -> dormant)
    restoredDormant = restoredDormant + dormant.id
    initRuntimeState(dormant.id, dormant)
  }

  def removeSource(id: String): Boolean = synchronized {
    if (sources.contains(id)) {
      testStep  = testStep  - id
      walkState = walkState - id
      sources   = sources   - id
      true
    } else false
  }

  def updateSource(id: String, patch: SourceConfig): Option[SourceConfig] = synchronized {
    if (!sources.contains(id)) None
    else {
      val updated = applyId(patch, id)
      sources = sources + (id -> updated)
      restoredDormant = restoredDormant - id
      Some(updated)
    }
  }

  def getSource(id: String): Option[SourceConfig] = synchronized { sources.get(id) }

  /** Sources in canonical order: (name, id).
    *
    * `sources` is a Map keyed by id and ids are generated per runtime, so every
    * PE listed sources differently — C++ by id, Scala and LSP by hash order,
    * TypeScript by insertion order.  Four engines, four orderings, on an
    * endpoint under byte comparison. */
  def getSources: Vector[SourceConfig] =
    synchronized { sources.values.toVector.sortBy(s => (s.name, s.id)) }

  /** Find an existing sensor source by its logical sensorId (not its UUID). */
  def findSensorBySensorId(sensorId: String): Option[SensorSourceConfig] = synchronized {
    sources.values.collectFirst { case s: SensorSourceConfig if s.sensorId == sensorId => s }
  }

  // ── Sensor push ───────────────────────────────────────────────────────────

  def updateSensorValue(sensorId: String, values: Vector[Double]): Boolean = synchronized {
    sources.values.find {
      case s: SensorSourceConfig if s.sensorId == sensorId => true
      case _ => false
    } match {
      case Some(s: SensorSourceConfig) =>
        val updated = s.copy(
          lastValue   = values.take(s.region.length),
          lastUpdated = Some(System.currentTimeMillis()),
        )
        sources = sources + (s.id -> updated)
        restoredDormant = restoredDormant - s.id
        true
      case _ => false
    }
  }

  // ── Vector assembly ───────────────────────────────────────────────────────

  /**
   * Assemble the next push vector from persistentVector + active sources.
   * Pure read — does not modify persistentVector.
   */
  def assembleVector(): Vector[Double] = synchronized {
    val out    = persistentVector.clone()
    val outLen = out.length
    // Canonical order, not Map order. Two machines may declare the same input
    // region — AGX032 and AGX054 both map [228:232] — and a source owns its
    // region, so where regions overlap the last writer wins. Iterating `sources`
    // directly made that winner depend on Map iteration order, which is
    // unspecified; C++ walked a std::map keyed by source id and LSP used
    // maphash, so the three runtimes assembled different input vectors from
    // identical corpora (RealityEngine_CI corpus parity sweep, 2026-08-19).
    //
    // Sorted by (name, id) — the order already used for the listing endpoints,
    // and derived from corpus-declared names rather than runtime-minted ids.
    for ((id, src) <- sources.toSeq.sortBy { case (i, s) => (s.name, i) } if src.active) {
      val values = getSourceValues(id, src)
      val Region(offset, length) = src.region
      // Growth on addSource and on RE sync should make this unreachable; if a
      // region still does not fit, name the machine that lost its input rather
      // than dropping it silently.  Silence is how the same defect stayed
      // invisible in the other runtimes.
      if (offset < 0 || offset + length > outLen) {
        // machineId lives on TestSourceConfig, not on the SourceConfig trait.
        val machineId = src match {
          case t: TestSourceConfig => t.machineId
          case _                   => ""
        }
        System.err.println(
          s"[PerceptionEngine] source '${src.name}' region [$offset,${offset + length}) " +
            s"exceeds perceptionDimension $outLen — region not written " +
            s"(machineId=$machineId, sourceId=$id)"
        )
      }
      if (offset < outLen) {
        var i = 0
        while (i < length && i < values.length && offset + i < outLen) {
          out(offset + i) = math.max(0.0, math.min(1.0, values(i)))
          i += 1
        }
      }
    }
    out.toVector
  }

  /** Sync the persistent base vector from the RE post-merge state.  RE may
    * return a vector larger than our current dimension if it has grown to fit
    * machines whose perceptualMapping extends past our initial allocation;
    * we grow to match so the next assembleVector covers the same range. */
  def updateFromPerceptualSpace(ps: Vector[Double]): Unit = synchronized {
    if (ps.length > _vectorDimension) ensureCapacity(ps.length)
    var i = 0
    val n = _vectorDimension
    while (i < n) {
      persistentVector(i) = if (i < ps.length) ps(i) else 0.0
      i += 1
    }
  }

  // ── Advance (call after each push) ────────────────────────────────────────

  def advance(): Unit = synchronized {
    globalStep += 1
    for ((id, src) <- sources if src.active) {
      src match {
        case t: TestSourceConfig =>
          val current = testStep.getOrElse(id, 0)
          val next    = current + 1
          if (next >= t.inputs.length) {
            if (t.loop) {
              testStep = testStep + (id -> 0)
            } else {
              sources  = sources  + (id -> t.copy(active = false))
              testStep = testStep + (id -> 0)
            }
          } else {
            testStep = testStep + (id -> next)
          }

        case s: SimulatedSourceConfig if s.pattern == SimPattern.RandomWalk =>
          val prev = walkState.getOrElse(id, Vector.fill(s.region.length)(s.dcOffset))
          val next = prev.map { v =>
            val delta = (Random.nextDouble() * 2 - 1) * 0.05
            math.max(0.0, math.min(1.0, v + delta))
          }
          walkState = walkState + (id -> next)

        case _ => // no per-step state for other simulated patterns or sensors
      }
    }
  }

  // ── Progress ──────────────────────────────────────────────────────────────

  def getTestProgress(id: String): Option[TestProgress] = synchronized {
    sources.get(id) collect { case t: TestSourceConfig =>
      TestProgress(testStep.getOrElse(id, 0), t.inputs.length)
    }
  }

  // ── Reset ─────────────────────────────────────────────────────────────────

  def reset(): Unit = synchronized {
    globalStep       = 0L
    persistentVector = new Array[Double](_vectorDimension)
    for ((id, src) <- sources) src match {
      case t: TestSourceConfig if !restoredDormant.contains(id) =>
        testStep = testStep + (id -> 0)
        if (!t.active) sources = sources + (id -> t.copy(active = true))
      case s: SimulatedSourceConfig if s.pattern == SimPattern.RandomWalk =>
        walkState = walkState + (id -> Vector.fill(s.region.length)(s.dcOffset))
      case _ =>
    }
  }

  // ── State snapshot ────────────────────────────────────────────────────────

  def getState(lastPush: Option[Long], auto: AutoConfig): EngineState = synchronized {
    EngineState(
      sources         = getSources,
      assembledVector = assembleVector(),
      globalStep      = globalStep,
      auto            = auto,
      lastPush        = lastPush,
      matchAlgorithm  = matchAlgorithm,
      perceptionDimension = _vectorDimension,
    )
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private def applyId(src: SourceConfig, id: String): SourceConfig = src match {
    case s: TestSourceConfig      => s.copy(id = id)
    case s: SimulatedSourceConfig => s.copy(id = id)
    case s: SensorSourceConfig    => s.copy(id = id)
  }

  private def initRuntimeState(id: String, src: SourceConfig): Unit = src match {
    case _: TestSourceConfig =>
      testStep = testStep + (id -> 0)
    case s: SimulatedSourceConfig if s.pattern == SimPattern.RandomWalk =>
      walkState = walkState + (id -> Vector.fill(s.region.length)(s.dcOffset))
    case _ =>
  }

  private def getSourceValues(id: String, src: SourceConfig): Vector[Double] = src match {
    case t: TestSourceConfig =>
      val step = testStep.getOrElse(id, 0)
      t.inputs.applyOrElse(step, (_: Int) => Vector.fill(t.region.length)(0.0))
    case s: SimulatedSourceConfig =>
      PerceptionEngine.simValues(id, s, globalStep, walkState)
    case s: SensorSourceConfig =>
      s.lastUpdated match {
        case None => Vector.fill(s.region.length)(0.0)
        case Some(ts) if System.currentTimeMillis() - ts > s.ttlMs =>
          Vector.fill(s.region.length)(0.0)
        case _ =>
          val padded = Array.fill(s.region.length)(0.0)
          var i = 0
          while (i < s.lastValue.length && i < s.region.length) { padded(i) = s.lastValue(i); i += 1 }
          padded.toVector
      }
  }
}

object PerceptionEngine {

  def simValues(
    id: String,
    src: SimulatedSourceConfig,
    globalStep: Long,
    walkState: Map[String, Vector[Double]],
  ): Vector[Double] = {
    (0 until src.region.length).toVector.map { i =>
      computeSample(id, src.pattern, globalStep + i * 0.1, src.frequency, src.amplitude, src.dcOffset, walkState)
    }
  }

  def computeSample(
    id: String,
    pattern: SimPattern,
    t: Double,
    frequency: Double,
    amplitude: Double,
    dcOffset: Double,
    walkState: Map[String, Vector[Double]],
  ): Double = {
    val period = if (frequency > 0) 1.0 / frequency else 1.0
    val phase  = (t / period) % 1.0

    pattern match {
      case SimPattern.Sine =>
        dcOffset + amplitude * math.sin(2 * math.Pi * phase)

      case SimPattern.Sawtooth =>
        dcOffset + amplitude * (2 * phase - 1)

      case SimPattern.Square =>
        dcOffset + amplitude * (if (phase < 0.5) 1.0 else -1.0)

      case SimPattern.LinearRamp =>
        dcOffset + amplitude * phase

      case SimPattern.Constant =>
        dcOffset

      case SimPattern.RandomWalk =>
        walkState.get(id).flatMap(_.headOption).getOrElse(dcOffset)

      case SimPattern.GaussianNoise =>
        // Box-Muller transform
        val u1 = math.max(Random.nextDouble(), 1e-10)
        val u2 = Random.nextDouble()
        val z  = math.sqrt(-2.0 * math.log(u1)) * math.cos(2 * math.Pi * u2)
        dcOffset + amplitude * z

      case SimPattern.Binary =>
        if (phase < 0.5) 1.0 else 0.0
    }
  }
}
