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
  // Run state the store cached, held aside until an integration registers the
  // source it belongs to. Membership comes from re-registration
  // (RealityEngine_CI SURFACE_SPEC.md point 5), so nothing here is a member of
  // the source set: it contributes no values, grows no space, and is invisible
  // to /api/sources until claimed.
  //
  // Boot used to restore these straight into `sources`. On a 12-machine corpus
  // that left scala-1 holding 815 sources against cpp-1 and lsp-1's 0, growing
  // the perceptual space to 16942 against their 14388 — and space length is
  // part of every ISRE and OREV entry, so the runtimes diverged at step 0
  // before any stimulus (#58).
  private var cachedFromStore: Map[String, SourceConfig] = Map.empty
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
    val withCached = inheritCached(src)
    sources = sources + (id -> withCached)
    initRuntimeState(id, withCached)
    withCached
  }

  /** Declare a source as part of an integration registering — the membership
    * half of the source contract (RealityEngine_CI#163 points 1, 2a and 3).
    *
    * Registration happens two ways with one meaning: at boot from declared
    * configuration (the MQTT mapping registry, INTEGRATIONS_CONFIG source
    * mappings, the PE source-bootstrap flag) or dynamically at runtime
    * (POST /api/mqtt/enable, an ACP session opening, the HealthKit bridge
    * handshake). Either way the integration declares its full source set up
    * front, and `GET /api/sources` reflects it before any traffic arrives.
    *
    * Two properties distinguish this from `addSource`:
    *
    *  - **Declared inactive.** Activity is earned by the first value and stays
    *    lazy by design (point 2b). Materialising a source `active = true` on
    *    its first value — which MQTT and the completion/HealthKit ingest paths
    *    both did — means no declared-inactive state ever exists for those
    *    integrations, so nothing can observe the set before it is live.
    *  - **Idempotent.** Re-registration is the same event as first
    *    registration, so it must not disturb an existing source's activity,
    *    cached value or playback cursor. Sensors dedupe on their logical
    *    `sensorId` as well as their id, matching what the ingest paths check.
    *
    * Returns the source now under that identity — the freshly declared one, or
    * the existing one left untouched.
    */
  def declareSource(config: SourceConfig): SourceConfig = synchronized {
    val existing = config match {
      case s: SensorSourceConfig =>
        sources.get(s.id).orElse(
          sources.values.collectFirst { case e: SensorSourceConfig if e.sensorId == s.sensorId => e })
      case other => sources.get(other.id)
    }
    existing.getOrElse {
      val id       = if (config.id.nonEmpty) config.id else uuidGen.generate().toString
      // Declared inactive (point 2a); inheritCached may then restore a cached
      // value and, if that value still supports it, the cached activity.
      val declared = inheritCached(applyId(config.withActive(false), id))
      ensureCapacity(declared.region.offset + declared.region.length)
      sources = sources + (id -> declared)
      initRuntimeState(id, declared)
      declared
    }
  }

  /** Restore a previously persisted source preserving its original ID.
    *
    * The store caches run state — cached value and cached activity — and never
    * membership (RealityEngine_CI#163 point 5). So a restored source brings its
    * recorded `active` flag back with it, and that claim is then **validated**
    * rather than overridden: a sensor holding a value outside its TTL is
    * deactivated, one holding a live value is left active.
    *
    * This supersedes the blanket "restore always inactive" rule (#55). That
    * rule was aimed at the right target — on a freshly booted one-machine
    * universe scala-1 arrived holding 56 PE sources against C++'s 1 and LSP's
    * 0, and emitted `localai` output cells at step 0 that neither of the others
    * did (#54):
    *
    *     orev cpp-1   set=0 []
    *     orev lsp-1   set=0 []
    *     orev scala-1 set=4 [7449, 7457, 7581, 7589]
    *
    * Every value in a `localai` window is a *response* to a dispatch this
    * process has not yet made, so a value present at step 0 is last run's
    * answer to a question nobody asked. But those values are stale by their own
    * TTL, so validation deactivates them on the evidence — which is what the
    * blanket rule was approximating.
    *
    * Forcing inactive additionally cost something: an integration with a
    * dispatch in flight when the process ended — an ACP completion pending, an
    * MQTT signal mid-delivery, a HealthKit batch part-ingested — needs the fact
    * that its source was live to survive the restart, or the retry resumes
    * against a source the engine believes never carried data.
    */
  def restoreSource(src: SourceConfig): Unit = synchronized {
    cachedFromStore = cachedFromStore + (src.id -> src)
  }

  /** Cached run state an integration inherits when it registers a source.
    *
    * Membership is not restored — that is the whole point (#58). A persisted
    * record no integration has re-registered this run is not a member of the
    * set, so it stays here, contributes nothing to `assembleVector`, and does
    * not grow the perceptual space.
    *
    * When registration does arrive for that id, the cached value and cached
    * activity come with it — validated, so a value outside its TTL restores as
    * inactive. That is what lets an interrupted dispatch resume against a
    * source known to have been live, without letting a stale one claim it.
    */
  private def inheritCached(declared: SourceConfig): SourceConfig =
    cachedFromStore.get(declared.id) match {
      case None => declared
      case Some(cached) =>
        cachedFromStore = cachedFromStore - declared.id
        val withValue = (declared, cached) match {
          case (d: SensorSourceConfig, c: SensorSourceConfig) =>
            d.copy(lastValue = c.lastValue, lastUpdated = c.lastUpdated)
          case _ => declared
        }
        withValue.withActive(
          cached.active && cachedValueSupportsActivity(withValue, System.currentTimeMillis()))
    }

  /** Sources the store cached that nothing has registered this run.
    *
    * Reported for diagnosis — a growing count means integrations are not
    * re-registering what the store remembers.
    */
  def unclaimedCachedCount: Int = synchronized { cachedFromStore.size }

  /** Dimension the currently-registered sources actually require.
    *
    * Never below the configured initial dimension: that is a deployment input,
    * not something the source set earns.
    */
  private def requiredDimension: Int =
    sources.values.foldLeft(initialDimension)((acc, s) => math.max(acc, s.region.offset + s.region.length))

  def removeSource(id: String): Boolean = synchronized {
    if (sources.contains(id)) {
      testStep      = testStep  - id
      walkState     = walkState - id
      sources       = sources   - id
      true
    } else false
  }

  def updateSource(id: String, patch: SourceConfig): Option[SourceConfig] = synchronized {
    if (!sources.contains(id)) None
    else {
      val updated = applyId(patch, id)
      sources = sources + (id -> updated)
      Some(updated)
    }
  }

  def getSource(id: String): Option[SourceConfig] = synchronized { sources.get(id) }

  /** Sources in canonical order: (name, id).
    *
    * `sources` is a Map keyed by id and ids are generated per runtime, so every
    * PE listed sources differently — C++ by id, Scala and LSP by hash order,
    * TypeScript by insertion order.  Four engines, four orderings, on an
    * endpoint under byte comparison.
    *
    * This is the **stored** view: each source carries the `active` flag the
    * engine is holding for it. That is what the store persists and what a PATCH
    * merges onto. What gets *reported* is `reportedSources` — see there. */
  def getSources: Vector[SourceConfig] =
    synchronized { sources.values.toVector.sortBy(s => (s.name, s.id)) }

  /** Sources as they are **reported**, with every `active` flag validated
    * against the state backing it (RealityEngine_CI#175).
    *
    *     reported_active = stored_active AND validated_active(kind)
    *
    * Activity expiry is continuous, not an event. Nothing runs when a sensor's
    * TTL lapses, so between resets the stored flag drifts away from what the
    * source can actually supply: `assembleVector` was already zeroing an expired
    * sensor's window while `/api/sources` and `/api/state` still advertised it
    * `active: true`. Validating at reset closed the gap only at reset; deriving
    * the reported value at every read closes it always.
    *
    * Both conjuncts carry weight and neither can be dropped:
    *
    *  - `stored_active` keeps activity something a source has to be *given*. An
    *    operator pause (PATCH /api/sources/:id) and a finished non-looping test
    *    source both read inactive through it, and it is what preserves the
    *    ingress invariant — a declared sensor that has never been fed has
    *    `stored_active = false` and no amount of validation can make it live.
    *  - `validated_active` stops a stale flag being reported as live.
    *
    * So validation can only ever take activity away. Granting it stays with the
    * paths entitled to: `updateSensorValue` for a sensor's first (or next)
    * value, `reset` for a rewound run, `addSource`/`declareSource` for what the
    * registration asked for.
    *
    * This is a read. The stored flag is untouched — a source demoted here is
    * still holding its own `active = true`, and the ingress or reset that
    * revalidates it finds exactly the state it left behind.
    *
    * The clock is read once for the whole pass, for the same reason `reset`
    * reads it once: two sensors with identical `lastUpdated` and `ttlMs` must
    * not serialize differently because the map crossed a millisecond boundary.
    */
  def reportedSources: Vector[SourceConfig] = synchronized {
    val now = System.currentTimeMillis()
    getSources.map(reportedView(_, now))
  }

  /** The reported view of a single source — `reportedSources` for the
    * one-source responses (`POST /api/sources`, `PATCH /api/sources/:id`, the
    * ingest and bootstrap payloads that echo the source they touched).
    *
    * Those serialize a source too, and a reported `active` that depends on
    * which endpoint answered would be the same drift in a new place. */
  def reported(src: SourceConfig): SourceConfig =
    reportedView(src, System.currentTimeMillis())

  /** Find an existing sensor source by its logical sensorId (not its UUID). */
  def findSensorBySensorId(sensorId: String): Option[SensorSourceConfig] = synchronized {
    sources.values.collectFirst { case s: SensorSourceConfig if s.sensorId == sensorId => s }
  }

  // ── Sensor push ───────────────────────────────────────────────────────────

  /** Feed a sensor source its next value.
    *
    * This is where a declared sensor **earns its activity**. Registration
    * declares the source inactive (`declareSource`) and the first value is what
    * makes it live — the stack's standing rule, "PE sources are declared
    * inactive and activated by their first value" (RealityEngine_CI#163 point
    * 2b).
    *
    * Setting the flag here is what makes the declared-inactive state and
    * reset's TTL validation safe to have. Both can deactivate a sensor; without
    * an ingress path that reactivates, a sensor demoted by an expired TTL would
    * stay demoted through every subsequent reading and contribute zeros forever
    * while holding a fresh value — a worse defect than the stale `active: true`
    * the validation is there to fix.
    */
  def updateSensorValue(sensorId: String, values: Vector[Double]): Boolean = synchronized {
    sources.values.find {
      case s: SensorSourceConfig if s.sensorId == sensorId => true
      case _ => false
    } match {
      case Some(s: SensorSourceConfig) =>
        val updated = s.copy(
          active      = true,
          lastValue   = values.take(s.region.length),
          lastUpdated = Some(System.currentTimeMillis()),
        )
        sources = sources + (s.id -> updated)
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

  /** Clear run state, then **validate** each source's activity against it.
    *
    * Reset is membership-neutral: it rewinds playback cursors, `globalStep`,
    * the persistent vector and (in `resetAndBroadcast`) `lastPush`. It never
    * manufactures sources and never re-derives the set from boot configuration
    * — doing so would drop every integration registered since boot
    * (RealityEngine_CI#163 point 4).
    *
    * What it does own is the `active` flag, and it *recomputes* that flag from
    * the rules for each kind rather than forcing a constant (point 3). All four
    * PE runtimes used to force `active = true` on every test source and leave
    * every other kind exactly as it was, so a sensor whose TTL had expired
    * before the reset was still reported `active: true` after it. The assembled
    * vector was right — an expired sensor already contributes zeros at assembly
    * — but `GET /api/sources` and `GET /api/state` advertised a source that
    * contributes nothing as live, and `active` is byte-compared across
    * runtimes (RealityEngine_CI#166).
    *
    * The rules, identical on all four runtimes:
    *
    *  - **sensor** — active iff holding a value inside its TTL. Same predicate
    *    the assembly path uses to decide what it contributes.
    *  - **test** — active iff its interned sequence is non-empty. A test source
    *    supplies its own values and the reset has rewound it to step 0, so it
    *    has one to give; a source with nothing interned can supply nothing, and
    *    reporting *that* active would be assignment rather than validation.
    *  - **simulated** — active. It generates from `globalStep`, which the reset
    *    has zeroed.
    *
    * Recomputed from the rules alone: the prior flag is not read and not
    * carried forward. An operator-deactivated source is run state, not
    * configuration, so the reset that clears run state clears the pause too.
    *
    * `lastValue` and `lastUpdated` are deliberately left alone — they are the
    * evidence the sensor rule is validated against, so clearing them would
    * destroy the information the validation needs.
    *
    * The clock is read once for the whole pass: two sensors with the same
    * `lastUpdated` and `ttlMs` must not come out differently because the fold
    * crossed a millisecond boundary.
    *
    * Reset and serialization apply the *same* predicate (`validatedActive`) and
    * differ only in what they do with the answer: reset assigns it, because a
    * rewound run is entitled to re-arm what it rewound; serialization intersects
    * it with the stored flag, because a read may take activity away but must not
    * grant it (RealityEngine_CI#175).
    */
  def reset(): Unit = synchronized {
    globalStep = 0L

    // No membership to drop. The store no longer restores sources at boot — it
    // caches their run state until an integration registers them (#58) — so a
    // persisted record nothing claimed was never a member and there is nothing
    // here to remove. Reset stays membership-neutral in the strict sense.

    persistentVector = new Array[Double](_vectorDimension)
    val now = System.currentTimeMillis()
    for ((id, src) <- sources) {
      // Run state first — the flags below are validated against the state the
      // reset leaves behind, not the one it found.
      src match {
        case _: TestSourceConfig =>
          testStep = testStep + (id -> 0)
        case s: SimulatedSourceConfig if s.pattern == SimPattern.RandomWalk =>
          walkState = walkState + (id -> Vector.fill(s.region.length)(s.dcOffset))
        case _ =>
      }
      val validated = validatedActive(src, now)
      if (validated != src.active) sources = sources + (id -> src.withActive(validated))
    }
  }

  // ── State snapshot ────────────────────────────────────────────────────────

  /** The snapshot `GET /api/state` and every WebSocket state-update serialize.
    *
    * Sources come from `reportedSources`, not `getSources`: `/api/state` embeds
    * the same source objects `/api/sources` lists, and the two must not report a
    * source's activity differently. */
  def getState(lastPush: Option[Long], auto: AutoConfig): EngineState = synchronized {
    EngineState(
      sources         = reportedSources,
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

  /** Whether a source is holding a value it can still supply.
    *
    * The one definition of sensor freshness in this engine. The assembly path
    * decides what a sensor contributes with it, and `reset`/`restoreSource`
    * decide whether a sensor's `active` claim is still supported by it, so the
    * reported flag and the contributed values can never disagree about whether
    * a TTL has run out.
    *
    * Test and simulated sources supply their own values — from an interned
    * sequence and from `globalStep` respectively — so nothing about them
    * expires and the answer is unconditionally true.
    *
    * `now` is a parameter rather than a call so a whole validation pass reads
    * the clock once: two sensors with the same `lastUpdated` and `ttlMs` must
    * not come out differently because the fold crossed a millisecond boundary.
    */
  private def holdsLiveValue(src: SourceConfig, now: Long): Boolean = src match {
    case s: SensorSourceConfig => s.lastUpdated.exists(ts => now - ts <= s.ttlMs)
    case _                     => true
  }

  /** Whether a source's state supports calling it active, per kind.
    *
    * The single definition of validated activity in this engine, and the one
    * `reset` assigns from and `reportedSources` intersects with. Identical on
    * all four PE runtimes:
    *
    *  - **sensor** — holds a value inside its TTL (`holdsLiveValue`, the same
    *    predicate the assembly path uses to decide the sensor's contribution,
    *    so the reported flag and the contributed values cannot disagree).
    *  - **test** — its interned sequence is non-empty. A test source supplies
    *    its own values, so with a sequence it has one to give; with nothing
    *    interned it can supply nothing.
    *  - **simulated** — generates from `globalStep`, so always.
    *
    * `now` is a parameter so a whole pass reads the clock once.
    */
  private def validatedActive(src: SourceConfig, now: Long): Boolean = src match {
    case t: TestSourceConfig      => t.inputs.nonEmpty
    case _: SimulatedSourceConfig => true
    case s: SensorSourceConfig    => holdsLiveValue(s, now)
  }

  /** `stored_active AND validated_active` for one source, without touching the
    * stored one — the whole of the reporting rule (RealityEngine_CI#175).
    *
    * Returns the source itself when the two agree, so the common case allocates
    * nothing and a serialization pass over an unchanged set is identity. */
  private def reportedView(src: SourceConfig, now: Long): SourceConfig = {
    val reported = src.active && validatedActive(src, now)
    if (reported == src.active) src else src.withActive(reported)
  }

  /** Whether a restored source's cached `active` flag is supported by cached
    * evidence — a different question from `holdsLiveValue`, and it has to be.
    *
    * `holdsLiveValue` asks "can this source supply a value *now*", which a test
    * or simulated source always can: it carries its own sequence or generates
    * from `globalStep`. That is the right question for the assembly path and
    * for reset, both of which run inside a live run.
    *
    * Restore asks something narrower: does this activity claim trace back to
    * anything? Activity is earned by an ingress event, and after a restart the
    * only evidence any such event happened is the cached value the store kept
    * for it. A sensor holding a live value carries that evidence, and that is
    * the case worth restoring — an integration whose dispatch was in flight
    * when the process ended needs its source to still be live for the retry to
    * resume against.
    *
    * Nothing else carries it. A restored test or simulated source has no cached
    * value at all, so an `active` flag on one is a claim with nothing behind
    * it — which is exactly what put 808 armed test sources into a freshly
    * booted scala-1 while C++ held 1 and LSP held 0, and made this runtime
    * perceive stimulus the others never saw (#54).
    *
    * Their owners re-arm them anyway: startup seeding re-declares corpus
    * sources at the activation the boot configuration asks for, and reset
    * validates every test source with a sequence back to active. So this loses
    * nothing that is not immediately re-established by whoever is entitled to
    * establish it.
    */
  private def cachedValueSupportsActivity(src: SourceConfig, now: Long): Boolean = src match {
    case s: SensorSourceConfig => s.lastUpdated.exists(ts => now - ts <= s.ttlMs)
    case _                     => false
  }

  private def getSourceValues(id: String, src: SourceConfig): Vector[Double] = src match {
    case t: TestSourceConfig =>
      val step = testStep.getOrElse(id, 0)
      t.inputs.applyOrElse(step, (_: Int) => Vector.fill(t.region.length)(0.0))
    case s: SimulatedSourceConfig =>
      PerceptionEngine.simValues(id, s, globalStep, walkState)
    case s: SensorSourceConfig =>
      if (!holdsLiveValue(s, System.currentTimeMillis())) Vector.fill(s.region.length)(0.0)
      else {
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
