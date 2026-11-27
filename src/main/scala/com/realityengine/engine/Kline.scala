package com.realityengine.engine

import com.realityengine.api.CanonicalJson
import com.realityengine.models._
import io.circe.Json
import io.circe.parser.parse

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import scala.collection.mutable

/** K-lines: episodes captured at their corpus boundaries while the engine
  * steps, kept in a persistent store per engine instance (RealityEngine_CI#603,
  * RealityEngine_Scala#198).
  *
  * The specification is RealityEngine_CI SURFACE_SPEC.md: "Boundaries", "Paths
  * that dwell, fork or merge", "Scope", "Identity, clock and parity", "Managing
  * K-lines during operation", "The K-line store" and "Record". The RE half, by
  * the same algorithm as RealityEngine_CPP src/kline.cpp and RealityEngine_LSP
  * src/kline.lisp: the tracker observes each committed step -- per sequence,
  * which events matched and which were activated, and which are active
  * afterwards -- and never re-derives the transition rule. */
object Kline {

  // ── JCS, SHA-256, the corpus fingerprint ───────────────────────────────────

  private def jcsString(sb: StringBuilder, s: String): Unit = {
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\b' => sb.append("\\b")
      case '\f' => sb.append("\\f")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
  }

  private def jcsInto(sb: StringBuilder, j: Json): Unit = j.fold(
    sb.append("null"),
    b => sb.append(if (b) "true" else "false"),
    n => sb.append(CanonicalJson.formatDouble(n.toDouble)),
    s => jcsString(sb, s),
    arr => { sb.append('['); arr.zipWithIndex.foreach { case (v, i) => if (i > 0) sb.append(','); jcsInto(sb, v) }; sb.append(']') },
    obj => {
      sb.append('{')
      // String.compareTo compares UTF-16 code units: JCS key order.
      obj.toList.sortWith((a, b) => a._1.compareTo(b._1) < 0).zipWithIndex.foreach { case ((k, v), i) =>
        if (i > 0) sb.append(','); jcsString(sb, k); sb.append(':'); jcsInto(sb, v)
      }
      sb.append('}')
    }
  )

  /** RFC 8785 (JCS) serialization. */
  def jcs(j: Json): String = { val sb = new StringBuilder; jcsInto(sb, j); sb.toString }

  def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** A < B comparing UTF-8 bytes (SURFACE_SPEC: collation is declared). */
  def bytesLess(a: String, b: String): Boolean = {
    val x = a.getBytes(UTF_8); val y = b.getBytes(UTF_8)
    var i = 0
    while (i < x.length && i < y.length) {
      val d = (x(i) & 0xff) - (y(i) & 0xff)
      if (d != 0) return d < 0
      i += 1
    }
    x.length < y.length
  }
  val byBytes: Ordering[String] = Ordering.fromLessThan(bytesLess)

  /** sha256/resident-declared-v1 over (name, declared machine object) pairs. */
  def corpusFingerprint(declared: Seq[(String, Json)]): String = {
    val lines = declared.map { case (n, m) => (n, sha256Hex(jcs(m))) }
      .sortWith((a, b) => if (a._1 == b._1) a._2 < b._2 else bytesLess(a._1, b._1))
    sha256Hex(lines.map { case (n, h) => s"$n\t$h\n" }.mkString)
  }

  // ── JSON helpers ───────────────────────────────────────────────────────────

  def stamp(instance: String, lamport: Long, step: Long, commit: Long): Json = Json.obj(
    "instance" -> Json.fromString(instance), "lamport" -> Json.fromLong(lamport),
    "commit" -> Json.fromLong(commit), "step" -> Json.fromLong(step))

  private[engine] def num(j: Json, k: String): Long = j.hcursor.get[Double](k).map(_.toLong).getOrElse(0L)
  private[engine] def str(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")
  private def vec(v: Seq[Double]): Json = Json.arr(v.map(d => Json.fromDoubleOrNull(d)): _*)

  final case class Active(machine: String, sequence: String, event: String, justMatched: Boolean)
  private val activeOrder: Ordering[Active] = Ordering.fromLessThan { (a, b) =>
    if (a.machine != b.machine) bytesLess(a.machine, b.machine)
    else if (a.sequence != b.sequence) bytesLess(a.sequence, b.sequence)
    else bytesLess(a.event, b.event)
  }
  def activesJson(list: Seq[Active]): Json = Json.arr(list.sorted(activeOrder).map(a => Json.obj(
    "machine" -> Json.fromString(a.machine), "sequence" -> Json.fromString(a.sequence),
    "event" -> Json.fromString(a.event), "justMatched" -> Json.fromBoolean(a.justMatched))): _*)

  def activesOf(m: Machine): List[Active] =
    m.getAllSequences.flatMap(s => s.getAllVectors.filter(_.isActive).map(e => Active(m.name, s.id, e.id, e.wasJustMatched)))
      .sorted(activeOrder)
  def initialActivesOf(m: Machine): List[Active] =
    m.getAllSequences.flatMap(s => s.getAllVectors.filter(_.isInitial).map(e => Active(m.name, s.id, e.id, false)))
      .sorted(activeOrder)

  private[engine] def entryJson(e: TrajectoryEntry, keep: Seq[RegionMapping]): Json = Json.obj(
    "stepNumber" -> Json.fromInt(e.stepNumber), "length" -> Json.fromInt(e.length),
    "nonZero" -> Json.arr(e.nonZero.filter(c => keep.isEmpty || keep.exists(r => c.index >= r.offset && c.index < r.offset + r.length))
      .map(c => Json.obj("index" -> Json.fromInt(c.index), "value" -> Json.fromDoubleOrNull(c.value))): _*))

  private[engine] def overlaps(a: RegionMapping, b: RegionMapping): Boolean =
    a.length > 0 && b.length > 0 && a.offset < b.offset + b.length && b.offset < a.offset + a.length

  /** The first comparison of `event` that failed against `input`. */
  def differenceOf(event: RealityEvent, input: Vector[Double], region: RegionMapping): Json = {
    val mr = event.match_(input)
    val idx0 = mr.metadata.get("failedAtIndex").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
    val idx = if (idx0 >= 0 && idx0 < event.elements.length) idx0 else 0
    val elem = event.elements.lift(idx)
    val t = elem.flatMap(_.comparatorType).getOrElse(event.matchAlgorithm)
    val name = t match {
      case ComparatorType.Custom | ComparatorType.Exact => "equals"
      case other => ComparatorType.serialize(other)
    }
    val threshold: Json = t match {
      case ComparatorType.Equals | ComparatorType.Exact | ComparatorType.Custom => Json.Null
      case ComparatorType.Threshold => Json.fromDoubleOrNull(elem.flatMap(_.threshold).getOrElse(0.1))
      case _ => Json.fromDoubleOrNull(elem.flatMap(_.threshold).getOrElse(0.5))
    }
    Json.obj(
      "expected" -> Json.arr(Json.fromString(event.id)),
      "elementIndex" -> Json.fromInt(idx),
      "cell" -> Json.fromInt(region.offset + idx),
      "value" -> Json.fromDoubleOrNull(input.lift(idx).getOrElse(0.0)),
      "comparator" -> Json.fromString(name),
      "threshold" -> threshold,
      "cells" -> Json.arr(Json.fromInt(region.offset), Json.fromInt(region.offset + region.length - 1)))
  }

  def pathEntry(e: RealityEvent, step: Long): Json = Json.obj(
    "event" -> Json.fromString(e.id), "step" -> Json.fromLong(step),
    "milestone" -> Json.fromBoolean(e.getOutputVectors.nonEmpty && e.getNextVectorIds.nonEmpty),
    "outputs" -> Json.arr(e.getOutputVectors.map(o => vec(o.vector)): _*))
}

/** The append-only store under <INSTANCE_KLINE_DIR>/<uuid>/ (None: in memory). */
final class KlineStore(val dir: Option[Path]) {
  import Kline._
  private val SealBytes = 64L * 1024 * 1024

  final case class Segment(name: String, var bytes: Long = 0, var isSealed: Boolean = false, var exported: Boolean = false,
                           var sha256: String = "", var fingerprint: String = "", var archived: Boolean = false)
  final case class Meta(id: String, machine: String, sequence: String, outcome: String, closeCommit: Long,
                        segment: String, offset: Long, line: Int)

  val segments = mutable.ArrayBuffer.empty[Segment]
  val metas = mutable.ArrayBuffer.empty[Meta]
  private val byId = mutable.HashMap.empty[String, Int]
  private val events = mutable.HashMap.empty[String, Vector[(Long, String)]]
  private val lines = mutable.ArrayBuffer.empty[String]
  private var appends = 0

  dir.foreach { d => Files.createDirectories(d); load(d) }

  private def segmentCommit(name: String): Long = {
    val dash = name.indexOf('-'); val dot = name.lastIndexOf('.')
    if (dash < 0 || dot <= dash) -1L else name.substring(dash + 1, dot).toLongOption.getOrElse(-1L)
  }

  private def writeDurable(path: Path, s: String): Unit = {
    val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
    val ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    try { ch.write(ByteBuffer.wrap(s.getBytes(UTF_8))); ch.force(true) } finally ch.close()
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private def load(d: Path): Unit = {
    val known = mutable.HashMap.empty[String, Segment]
    val manifest = d.resolve("manifest.json")
    if (Files.exists(manifest)) parse(new String(Files.readAllBytes(manifest), UTF_8)).toOption.foreach { m =>
      m.hcursor.downField("segments").as[Vector[Json]].getOrElse(Vector.empty).foreach { s =>
        val c = s.hcursor
        known(str(s, "segment")) = Segment(str(s, "segment"), 0, c.get[Boolean]("sealed").getOrElse(false),
          c.get[Boolean]("exported").getOrElse(false), str(s, "sha256"), str(s, "fingerprint"), c.get[Boolean]("archived").getOrElse(false))
      }
    }
    val names = Files.list(d).toArray.map(_.asInstanceOf[Path].getFileName.toString)
      .filter(n => n.startsWith("segment-") && n.endsWith(".jsonl") && segmentCommit(n) >= 0).sortBy(segmentCommit)
    names.foreach { name =>
      val path = d.resolve(name)
      var bytes = Files.readAllBytes(path)
      val nl = bytes.lastIndexWhere(_ == '\n'.toByte)
      val good = if (nl < 0) 0 else nl + 1
      if (good != bytes.length) {  // a half-written last record is truncated
        val ch = FileChannel.open(path, StandardOpenOption.WRITE)
        try ch.truncate(good.toLong) finally ch.close()
        bytes = bytes.take(good)
      }
      val seg = known.getOrElse(name, Segment(name)); seg.bytes = bytes.length.toLong
      var start = 0
      var i = 0
      while (i < bytes.length) {
        if (bytes(i) == '\n'.toByte) {
          val raw = new String(bytes, start, i - start, UTF_8)
          try indexLine(raw, name, start.toLong, 0) catch { case _: Throwable => () }
          start = i + 1
        }
        i += 1
      }
      segments += seg
    }
    writeIndex()
  }

  private def indexLine(raw: String, segment: String, offset: Long, line: Int): Unit = {
    val j = parse(raw).getOrElse(Json.Null)
    str(j, "format") match {
      case "re-kline/1" =>
        val c = j.hcursor
        val m = Meta(str(j, "id"), c.downField("focal").get[String]("machine").getOrElse(""),
          c.downField("focal").get[String]("sequence").getOrElse(""), str(j, "outcome"),
          c.downField("close").get[Double]("commit").map(_.toLong).getOrElse(0L), segment, offset, line)
        byId(m.id) = metas.length
        metas += m
      case "re-kline-event/1" =>
        val k = str(j, "kline")
        events(k) = events.getOrElse(k, Vector.empty) :+ ((num(j, "through"), raw))
      case _ => ()
    }
  }

  private def writeManifest(): Unit = dir.foreach { d =>
    writeDurable(d.resolve("manifest.json"), jcs(Json.obj("segments" -> Json.arr(segments.toSeq.map(s => Json.obj(
      "segment" -> Json.fromString(s.name), "sealed" -> Json.fromBoolean(s.isSealed), "exported" -> Json.fromBoolean(s.exported),
      "sha256" -> Json.fromString(s.sha256), "fingerprint" -> Json.fromString(s.fingerprint),
      "archived" -> Json.fromBoolean(s.archived), "bytes" -> Json.fromLong(s.bytes))): _*))) + "\n")
  }

  private def writeIndex(): Unit = dir.foreach { d =>
    val counts = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, Long]]
    metas.foreach { m =>
      val c = counts.getOrElseUpdate(s"${m.machine}\t${m.sequence}", mutable.LinkedHashMap.empty)
      c(m.outcome) = c.getOrElse(m.outcome, 0L) + 1
    }
    writeDurable(d.resolve("index.json"), jcs(Json.obj(
      "ids" -> Json.fromFields(metas.map(m => m.id -> Json.obj("segment" -> Json.fromString(m.segment), "offset" -> Json.fromLong(m.offset)))),
      "counts" -> Json.fromFields(counts.map { case (k, v) => k -> Json.fromFields(v.map { case (o, n) => o -> Json.fromLong(n) }) }))) + "\n")
  }

  private def seal(s: Segment): Unit = {
    s.isSealed = true
    dir.foreach(d => s.sha256 = sha256Hex(new String(Files.readAllBytes(d.resolve(s.name)), UTF_8)))
    writeManifest()
  }

  private def activeSegment(fingerprint: String, commit: Long): Segment = {
    segments.lastOption.filter(!_.isSealed).foreach { last =>
      val other = last.fingerprint.nonEmpty && last.fingerprint != fingerprint && last.bytes > 0
      if (!other && last.bytes < SealBytes) {
        if (last.fingerprint.isEmpty) last.fingerprint = fingerprint
        return last
      }
      seal(last)
    }
    val s = Segment(s"segment-$commit.jsonl", fingerprint = fingerprint)
    segments += s
    writeManifest()
    s
  }

  def append(out: Seq[Json], fingerprint: String, commit: Long): Unit = if (out.nonEmpty) {
    val seg = activeSegment(fingerprint, commit)
    var offset = seg.bytes
    val raws = out.map { l => val r = jcs(l); val here = offset; offset += r.getBytes(UTF_8).length + 1; (r, here) }
    dir.foreach { d =>
      val ch = FileChannel.open(d.resolve(seg.name), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
      try { ch.write(ByteBuffer.wrap(raws.map(_._1 + "\n").mkString.getBytes(UTF_8))); ch.force(true) } // durable before the completion
      finally ch.close()
    }
    raws.foreach { case (r, off) =>
      val line = if (dir.isEmpty) { lines += r; lines.length - 1 } else 0
      indexLine(r, seg.name, off, line)
    }
    seg.bytes = offset
    appends += 1
    if (appends % 64 == 0) writeIndex()  // a cache rebuilt at boot
  }

  def checkpoint(open: Json): Unit = dir.foreach(d => writeDurable(d.resolve("open.json"), jcs(open) + "\n"))
  def readCheckpoint(): Option[Json] = dir.flatMap { d =>
    val p = d.resolve("open.json")
    if (Files.exists(p)) parse(new String(Files.readAllBytes(p), UTF_8)).toOption else None
  }

  def raw(m: Meta): String = dir match {
    case None => lines(m.line)
    case Some(d) =>
      val ch = FileChannel.open(d.resolve(m.segment), StandardOpenOption.READ)
      try {
        ch.position(m.offset)
        val out = new java.io.ByteArrayOutputStream
        val buf = ByteBuffer.allocate(8192)
        var done = false
        while (!done && ch.read(buf) > 0) {
          buf.flip()
          while (!done && buf.hasRemaining) { val b = buf.get(); if (b == '\n'.toByte) done = true else out.write(b.toInt) }
          buf.clear()
        }
        new String(out.toByteArray, UTF_8)
      } finally ch.close()
  }
  def rawEvents(id: String): Seq[String] = events.getOrElse(id, Vector.empty).map(_._2)

  def record(m: Meta): Json = {
    val j = parse(raw(m)).getOrElse(Json.Null)
    events.get(m.id).flatMap(_.lastOption) match {
      case Some((through, _)) => j.mapObject(_.add("sustain", Json.obj("through" -> Json.fromLong(through), "ended" -> Json.True)))
      case None => j
    }
  }
  def recordById(id: String): Option[Json] = byId.get(id).map(i => record(metas(i)))

  def warnings(maxBytes: Long): Seq[String] = {
    val live = segments.filter(s => s.isSealed && !s.archived)
    val unexported = live.count(!_.exported)
    if (live.map(_.bytes).sum > maxBytes && unexported > 0)
      Seq(s"klineStoreMaxBytes reached: $unexported unexported sealed segment(s) are kept; export them to free space")
    else Nil
  }

  def stats(maxBytes: Long): Json = {
    val byOutcome = mutable.LinkedHashMap.empty[String, Long]
    val byMachine = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, Long]]
    metas.foreach { m =>
      byOutcome(m.outcome) = byOutcome.getOrElse(m.outcome, 0L) + 1
      val per = byMachine.getOrElseUpdate(m.machine, mutable.LinkedHashMap.empty)
      per(m.outcome) = per.getOrElse(m.outcome, 0L) + 1
    }
    val live = segments.filterNot(_.archived)
    Json.obj(
      "byOutcome" -> Json.fromFields(byOutcome.map { case (k, v) => k -> Json.fromLong(v) }),
      "byMachine" -> Json.fromFields(byMachine.map { case (k, v) => k -> Json.fromFields(v.map { case (o, n) => o -> Json.fromLong(n) }) }),
      "segments" -> Json.fromInt(live.length), "bytes" -> Json.fromLong(live.map(_.bytes).sum),
      "budget" -> Json.fromLong(maxBytes), "warnings" -> Json.arr(warnings(maxBytes).map(Json.fromString): _*))
  }

  def markExported(names: Set[String]): Unit = { segments.foreach(s => if (names(s.name)) s.exported = true); writeManifest() }

  def enforceBudget(maxBytes: Long): Unit = {
    var moved = false
    def sealedBytes = segments.filter(s => s.isSealed && !s.archived).map(_.bytes).sum
    var go = true
    while (go && sealedBytes > maxBytes) {
      segments.find(s => s.isSealed && s.exported && !s.archived) match {
        case None => go = false  // never evict an unexported segment
        case Some(s) =>
          dir.foreach { d =>
            Files.createDirectories(d.resolve("archive"))
            Files.move(d.resolve(s.name), d.resolve("archive").resolve(s.name), StandardCopyOption.REPLACE_EXISTING)
          }
          s.archived = true; moved = true
      }
    }
    if (moved) {
      val gone = segments.filter(_.archived).map(_.name).toSet
      val kept = metas.filterNot(m => gone(m.segment))
      metas.clear(); byId.clear()
      kept.foreach { m => byId(m.id) = metas.length; metas += m }
      writeManifest(); writeIndex()
    }
  }
}

/** The tracker: one per engine instance. Callers hold the step lock. */
final class KlineTracker(val instance: String, val store: KlineStore, tick: () => Long) {
  import Kline._

  @volatile var capture: Boolean = false
  var feedbackHops: Int = 3
  var storeMaxBytes: Long = 2L * 1024 * 1024 * 1024
  var longestPath: Int = 7
  private var fingerprint = ""
  private var lastStep = -1L
  private var lastCommit = 0L

  final class Open(val machine: String, val sequence: String, val activator: String, var open: Json,
                   var held: List[String], val input: RegionMapping, val output: RegionMapping) {
    var id = ""
    var path = Vector.empty[Json]
    var refusedBy = ""
    var focalPairs = Vector.empty[Json]
    var focalActiveBefore: Json = Json.arr()
    def openStep: Long = num(open, "step")
  }
  final class Sustain(val machine: String, val sequence: String, val event: String, var through: Long, val openStep: Long) {
    var id = ""
  }
  final case class Op(machine: String, region: RegionMapping, values: Vector[Double], sequenceIds: List[String])
  final class StepRecord(val step: Long, val commit: Long, val isre: TrajectoryEntry, val osre: TrajectoryEntry, val ops: List[Op]) {
    val changed = mutable.HashMap.empty[String, List[Active]]
    val asserted = mutable.HashMap.empty[(String, String), String]
    val regions = mutable.HashMap.empty[String, (RegionMapping, Option[RegionMapping])]
  }

  private var open = Vector.empty[Open]
  private var sustain = Vector.empty[Sustain]
  private val closed = mutable.HashMap.empty[String, (String, String, Long, Long)]
  private val ring = mutable.ArrayBuffer.empty[StepRecord]
  private val baseline = mutable.HashMap.empty[String, List[Active]]
  private val current = mutable.HashMap.empty[String, List[Active]]

  private def recordFor(step: Long): Option[StepRecord] = ring.find(_.step == step)
  private def activeAt(machine: String, step: Long): List[Active] =
    ring.reverseIterator.filter(_.step <= step).collectFirst { case r if r.changed.contains(machine) => r.changed(machine) }
      .getOrElse(baseline.getOrElse(machine, Nil))

  def openIds: Seq[String] = open.map(_.id) ++ sustain.map(_.id)

  private sealed trait Touch { def machine: String; def sequence: String; def openStep: Long; def isNew: Boolean }
  private final class KTouch(val k: Open, val isNew: Boolean) extends Touch {
    def machine = k.machine; def sequence = k.sequence; def openStep = k.openStep
    var milestones = 0
    var outcome = ""
    var difference: Json = Json.Null
    var subsumedBy = ""
    var through = 0L
  }
  private final class STouch(val id: String, val through: Long, val machine: String, val sequence: String, val openStep: Long) extends Touch {
    def isNew = false
  }

  def onStep(step: Long, commit: Long, sim: SimulationStep, machines: Iterable[Machine], isre: TrajectoryEntry,
             osre: TrajectoryEntry, fp: String): Unit = if (capture) {
    fingerprint = fp
    val byName = mutable.TreeMap.empty[String, Machine](byBytes)
    machines.foreach(m => byName(m.name) = m)
    val results = sim.machineResults.values.map(r => r.machineName -> r).toMap
    if (ring.isEmpty && current.isEmpty) byName.foreach { case (n, m) => baseline(n) = initialActivesOf(m) }

    val rec = new StepRecord(step, commit, isre, osre,
      sim.mergeBatch.map(op => Op(op.machineName, op.region, op.values, op.sequenceIds)))
    byName.foreach { case (n, m) =>
      m.perceptualMapping.foreach(pm => rec.regions(n) = (pm.input, Some(pm.output)))
      val now = activesOf(m)
      val before = current.getOrElse(n, baseline.getOrElse(n, initialActivesOf(m)))
      if (now != before) rec.changed(n) = now
      current(n) = now
    }

    val touches = mutable.ArrayBuffer.empty[Touch]
    val holder = mutable.HashMap.empty[(String, String, String), String]
    open.foreach(k => k.held.foreach(e => holder((k.machine, k.sequence, e)) = k.id))
    sustain.foreach(s => holder((s.machine, s.sequence, s.event)) = s.id)
    val stillOpen = mutable.HashSet.empty[Open] ++ open
    val opened = mutable.ArrayBuffer.empty[Open]

    byName.foreach { case (name, m) =>
      results.get(name).foreach { r =>
        val input = r.transitionResult.inputVector
        val inRegion = m.perceptualMapping.map(_.input).getOrElse(RegionMapping(0, 0))
        val outRegion = m.perceptualMapping.map(_.output).getOrElse(RegionMapping(0, 0))
        m.getAllSequences.sortBy(_.id)(byBytes).foreach { seq =>
          r.transitionResult.sequenceResults.get(seq.id).foreach { sr =>
            val events = seq.getAllVectors.map(e => e.id -> e).toMap
            val matched = sr.matchedVectors.toSet
            val activated = sr.activatedVectors.toSet
            def isActive(e: String) = events.get(e).exists(_.isActive)
            val claimed = mutable.HashMap.empty[String, String]

            // Existing K-lines of this sequence, older first.
            open.filter(k => k.machine == name && k.sequence == seq.id)
              .sortWith((a, b) => if (a.openStep != b.openStep) a.openStep < b.openStep else bytesLess(a.activator, b.activator))
              .foreach { k =>
                val t = new KTouch(k, false)
                val next = mutable.LinkedHashSet.empty[String]
                var completedBy = ""
                var firstFailed = ""
                k.held.foreach { e =>
                  events.get(e).foreach { ev =>
                    if (matched(e)) {
                      val p = pathEntry(ev, step)
                      k.path :+= p
                      if (p.hcursor.get[Boolean]("milestone").getOrElse(false)) t.milestones += 1
                      if (ev.getNextVectorIds.isEmpty) { if (completedBy.isEmpty) completedBy = e }
                      else {
                        ev.getNextVectorIds.foreach { n =>
                          if (n == e) { if (isActive(n)) next += n }
                          else if (activated(n)) {
                            claimed.get(n) match {
                              case Some(who) => if (k.refusedBy.isEmpty) k.refusedBy = who
                              case None => claimed(n) = k.id; next += n
                            }
                          } else holder.get((name, seq.id, n)) match {
                            case Some(h) if h != k.id => if (k.refusedBy.isEmpty) k.refusedBy = h
                            case Some(_) => next += n
                            case None => ()
                          }
                        }
                        if (ev.getOutputVectors.nonEmpty && isActive(e)) next += e
                      }
                    } else if (firstFailed.isEmpty) firstFailed = e
                  }
                }
                if (completedBy.nonEmpty) {
                  t.outcome = "completed"; t.through = step
                  sustain :+= new Sustain(name, seq.id, completedBy, step, k.openStep)
                } else if (next.isEmpty) {
                  if (k.refusedBy.nonEmpty) { t.outcome = "subsumed"; t.subsumedBy = k.refusedBy }
                  else {
                    t.outcome = "abandoned"
                    val e = if (firstFailed.nonEmpty) firstFailed else k.held.head
                    t.difference = differenceOf(events(e), input, inRegion)
                  }
                }
                k.held = next.toList.sorted(byBytes)
                if (t.outcome.nonEmpty) stillOpen -= k
                touches += t
              }

            // Sustains of this sequence.
            sustain.filter(s => s.machine == name && s.sequence == seq.id && s.id.nonEmpty).foreach { s =>
              if (matched(s.event)) s.through = step
              else { touches += new STouch(s.id, s.through, name, seq.id, s.openStep); s.through = -1 }
            }

            // A departure: the initial event matched.
            events.keys.toList.sorted(byBytes).foreach { eid =>
              val ev = events(eid)
              if (ev.isInitial && matched(eid) &&
                  !sustain.exists(s => s.machine == name && s.sequence == seq.id && s.event == eid && s.through == step)) {
                val k = new Open(name, seq.id, eid, stamp(instance, 0, step, commit), Nil, inRegion, outRegion)
                val p = pathEntry(ev, step)
                k.path = Vector(p)
                val t = new KTouch(k, true)
                if (p.hcursor.get[Boolean]("milestone").getOrElse(false)) t.milestones = 1
                var departs = true
                if (ev.getNextVectorIds.isEmpty) {
                  t.outcome = "completed"; t.through = step
                  sustain :+= new Sustain(name, seq.id, eid, step, step)
                } else {
                  ev.getNextVectorIds.foreach { n =>
                    if (n != eid && activated(n) && !claimed.contains(n)) { claimed(n) = "#new"; k.held = n :: k.held }
                  }
                  k.held = k.held.sorted(byBytes)
                  if (k.held.isEmpty) departs = false
                }
                if (departs) {
                  k.focalActiveBefore = activesJson(activeAt(name, step - 1))
                  opened += k
                  touches += t
                }
              }
            }
          }
        }
      }
    }
    sustain = sustain.filter(_.through >= 0)
    ring += rec

    // Ticks, in record order.
    val ordered = touches.sortWith { (a, b) =>
      if (a.machine != b.machine) bytesLess(a.machine, b.machine)
      else if (a.sequence != b.sequence) bytesLess(a.sequence, b.sequence)
      else if (a.openStep != b.openStep) a.openStep < b.openStep
      else !a.isNew && b.isNew
    }
    val out = mutable.ArrayBuffer.empty[Json]
    ordered.foreach {
      case s: STouch =>
        out += Json.obj("format" -> Json.fromString("re-kline-event/1"), "kline" -> Json.fromString(s.id),
          "event" -> Json.fromString("sustain-end"), "through" -> Json.fromLong(s.through),
          "at" -> stamp(instance, tick(), step, commit))
      case t: KTouch =>
        val k = t.k
        if (t.isNew) {
          val l = tick()
          k.open = k.open.mapObject(_.add("lamport", Json.fromLong(l)))
          k.id = s"$instance:$l"
        }
        (0 until t.milestones).foreach(_ => tick())
        k.focalPairs :+= Json.obj("stepNumber" -> Json.fromLong(step),
          "isre" -> entryJson(rec.isre, Seq(k.input)), "osre" -> entryJson(rec.osre, Seq(k.output)),
          "contributions" -> Json.arr(rec.ops.filter(_.machine == k.machine).map(opJson): _*))
        if (t.outcome.nonEmpty) {
          val close = stamp(instance, tick(), step, commit)
          if (t.outcome == "completed")
            sustain.find(s => s.id.isEmpty && s.machine == k.machine && s.sequence == k.sequence).foreach(_.id = k.id)
          closed(k.id) = (k.machine, k.sequence, k.openStep, step)
          out += record(k, t.outcome, Json.Null, close, t.difference, t.subsumedBy, t.through, ring = true)
        }
    }
    // What asserted this step: contributor searches at later steps read it.
    ordered.foreach {
      case t: KTouch if t.milestones > 0 || t.outcome == "completed" => rec.asserted((t.k.machine, t.k.sequence)) = t.k.id
      case _ => ()
    }
    sustain.foreach(s => if (s.through == step && s.id.nonEmpty) rec.asserted((s.machine, s.sequence)) = s.id)

    open = open.filter(stillOpen) ++ ordered.collect { case t: KTouch if t.isNew && t.outcome.isEmpty => t.k }
    lastStep = step; lastCommit = commit
    persist(out.toSeq)
    trimRing()
  }

  private def opJson(op: Op): Json = Json.obj("machine" -> Json.fromString(op.machine), "region" -> regionJsonOf(op.region),
    "values" -> Json.arr(op.values.map(d => Json.fromDoubleOrNull(d)): _*))
  private def regionJsonOf(r: RegionMapping): Json = Json.obj("offset" -> Json.fromInt(r.offset), "length" -> Json.fromInt(r.length))

  private def record(k: Open, outcome: String, reason: Json, close: Json, difference: Json, subsumedBy: String,
                     sustainThrough: Long, ring: Boolean): Json = {
    var scope = List(k.machine)
    var contributors = Vector.empty[Json]
    var truncated = false
    var pairs: Json = Json.arr(k.focalPairs: _*)
    var activeBefore = k.focalActiveBefore
    var activeAfter: Json = Json.arr()
    var shared = List.empty[Int]
    val closeStep = num(close, "step")
    if (ring) {
      var frontier = List((k.machine, k.openStep, closeStep))
      val seen = mutable.HashSet(k.id)
      val found = mutable.ArrayBuffer.empty[(Int, String, Long, String)]
      var hop = 1
      while (frontier.nonEmpty) {
        val next = mutable.ArrayBuffer.empty[(String, Long, Long)]
        frontier.foreach { case (fm, from, to) =>
          val region = if (fm == k.machine) Some(k.input) else recordFor(to).flatMap(_.regions.get(fm)).map(_._1)
          (from to to).foreach { s =>
            recordFor(s - 1) match {
              case None => if (s - 1 >= 0) truncated = true
              case Some(prev) =>
                prev.ops.foreach { op =>
                  if (op.machine != fm && region.exists(overlaps(op.region, _))) op.sequenceIds.foreach { sq =>
                    prev.asserted.get((op.machine, sq)).foreach { id =>
                      if (!seen(id)) {
                        if (hop > feedbackHops) truncated = true
                        else {
                          seen += id
                          val (cf, ct) = closed.get(id).map(c => (c._3, c._4)).getOrElse((s - 1, s - 1))
                          found += ((hop, op.machine, cf, id))
                          next += ((op.machine, cf, ct))
                        }
                      }
                    }
                  }
                }
            }
          }
        }
        frontier = next.toList
        hop += 1
      }
      val sorted = found.sortWith { (a, b) =>
        if (a._1 != b._1) a._1 < b._1 else if (a._2 != b._2) bytesLess(a._2, b._2)
        else if (a._3 != b._3) a._3 < b._3 else bytesLess(a._4, b._4)
      }
      contributors = sorted.map { case (h, m, _, id) =>
        Json.obj("kline" -> Json.fromString(id), "machine" -> Json.fromString(m), "hops" -> Json.fromInt(h)) }.toVector
      scope = (k.machine :: sorted.map(_._2).toList).distinct.sorted(byBytes)
      recordFor(closeStep).foreach { last =>
        val inputs = scope.flatMap(n => last.regions.get(n).map(_._1))
        val outputs = scope.flatMap(n => last.regions.get(n).flatMap(_._2))
        val cells = mutable.TreeSet.empty[Int]
        outputs.foreach { o =>
          last.regions.foreach { case (n, (_, out)) =>
            if (!scope.contains(n)) out.foreach { x =>
              (math.max(o.offset, x.offset) until math.min(o.offset + o.length, x.offset + x.length)).foreach(cells += _)
            }
          }
        }
        shared = cells.toList
        val ps = (k.openStep to closeStep).flatMap(recordFor).map { r =>
          Json.obj("stepNumber" -> Json.fromLong(r.step), "isre" -> entryJson(r.isre, inputs), "osre" -> entryJson(r.osre, outputs),
            "contributions" -> Json.arr(r.ops.filter(op => scope.contains(op.machine))
              .sortWith((a, b) => if (a.region.offset != b.region.offset) a.region.offset < b.region.offset else bytesLess(a.machine, b.machine))
              .map(opJson): _*))
        }
        if (ps.nonEmpty) pairs = Json.arr(ps: _*)
      }
      activeBefore = activesJson(scope.flatMap(n => activeAt(n, k.openStep - 1)))
      activeAfter = activesJson(scope.flatMap(n => activeAt(n, closeStep)))
    } else truncated = true
    val completed = outcome == "completed"
    Json.obj(
      "format" -> Json.fromString("re-kline/1"),
      "id" -> Json.fromString(k.id),
      "corpus" -> Json.obj("algorithm" -> Json.fromString("sha256/resident-declared-v1"), "fingerprint" -> Json.fromString(fingerprint)),
      "focal" -> Json.obj("machine" -> Json.fromString(k.machine), "sequence" -> Json.fromString(k.sequence)),
      "outcome" -> Json.fromString(outcome),
      "reason" -> reason,
      "open" -> k.open,
      "close" -> close,
      "path" -> Json.arr(k.path: _*),
      "difference" -> difference,
      "subsumedBy" -> (if (subsumedBy.nonEmpty) Json.fromString(subsumedBy) else Json.Null),
      "sustain" -> Json.obj("through" -> Json.fromLong(if (completed) sustainThrough else closeStep), "ended" -> Json.fromBoolean(!completed)),
      "scope" -> Json.arr(scope.map(Json.fromString): _*),
      "contributors" -> Json.arr(contributors: _*),
      "feedbackTruncated" -> Json.fromBoolean(truncated),
      "sharedCells" -> Json.arr(shared.map(Json.fromInt): _*),
      "activeBefore" -> activeBefore,
      "activeAfter" -> activeAfter,
      "pairs" -> pairs,
      "outputs" -> Json.obj("outputVector" -> Json.Null, "mergedOutputVector" -> Json.Null))
  }

  private def closeAll(reason: String): Seq[Json] = {
    val step = math.max(0L, lastStep)
    val recs = open.sortWith((a, b) =>
      if (a.machine != b.machine) bytesLess(a.machine, b.machine)
      else if (a.sequence != b.sequence) bytesLess(a.sequence, b.sequence) else a.openStep < b.openStep
    ).map(k => record(k, "interrupted", Json.fromString(reason), stamp(instance, tick(), step, lastCommit), Json.Null, "", step, ring = true))
    val ends = sustain.filter(_.id.nonEmpty)
      .sortWith((a, b) => if (a.machine != b.machine) bytesLess(a.machine, b.machine) else bytesLess(a.sequence, b.sequence))
      .map(s => Json.obj("format" -> Json.fromString("re-kline-event/1"), "kline" -> Json.fromString(s.id),
        "event" -> Json.fromString("sustain-end"), "through" -> Json.fromLong(s.through),
        "at" -> stamp(instance, tick(), step, lastCommit)))
    recs ++ ends
  }

  def onReset(): Unit = if (capture) {
    val out = closeAll("reset")
    open = Vector.empty; sustain = Vector.empty
    persist(out)
    ring.clear(); baseline.clear(); current.clear(); lastStep = -1L
  }

  def onMachineRemoved(machineName: String): Unit = if (capture) {
    val (gone, keep) = open.partition(_.machine == machineName)
    val (sGone, sKeep) = sustain.partition(_.machine == machineName)
    open = gone; sustain = sGone
    val out = closeAll("machine-removed")
    open = keep; sustain = sKeep
    current -= machineName
    persist(out)
  }

  private def checkpointJson: Json = Json.obj(
    "format" -> Json.fromString("re-kline-open/1"), "step" -> Json.fromLong(lastStep), "commit" -> Json.fromLong(lastCommit),
    "fingerprint" -> Json.fromString(fingerprint),
    "open" -> Json.arr(open.map(k => Json.obj(
      "id" -> Json.fromString(k.id), "machine" -> Json.fromString(k.machine), "sequence" -> Json.fromString(k.sequence),
      "activator" -> Json.fromString(k.activator), "open" -> k.open, "held" -> Json.arr(k.held.map(Json.fromString): _*),
      "path" -> Json.arr(k.path: _*), "refusedBy" -> Json.fromString(k.refusedBy),
      "focalPairs" -> Json.arr(k.focalPairs: _*), "focalActiveBefore" -> k.focalActiveBefore,
      "focalActiveAfter" -> activesJson(current.getOrElse(k.machine, Nil)),
      "input" -> regionJsonOf(k.input), "output" -> regionJsonOf(k.output))): _*),
    "sustain" -> Json.arr(sustain.map(s => Json.obj("id" -> Json.fromString(s.id), "machine" -> Json.fromString(s.machine),
      "sequence" -> Json.fromString(s.sequence), "event" -> Json.fromString(s.event), "through" -> Json.fromLong(s.through))): _*))

  private def persist(out: Seq[Json]): Unit = {
    store.append(out, fingerprint, lastCommit)
    store.checkpoint(checkpointJson)
    store.enforceBudget(storeMaxBytes)
  }

  /** At boot: close every K-line in the checkpoint `interrupted` (shutdown) at its step. */
  def recover(): Unit = store.readCheckpoint().foreach { cp =>
    val step = num(cp, "step"); val commit = num(cp, "commit")
    fingerprint = str(cp, "fingerprint"); lastStep = step; lastCommit = commit
    val c = cp.hcursor
    val recs = c.downField("open").as[Vector[Json]].getOrElse(Vector.empty).map { o =>
      val oc = o.hcursor
      val k = new Open(str(o, "machine"), str(o, "sequence"), str(o, "activator"), oc.downField("open").focus.getOrElse(Json.Null),
        Nil, RegionMapping(0, 0), RegionMapping(0, 0))
      k.id = str(o, "id")
      k.path = oc.downField("path").as[Vector[Json]].getOrElse(Vector.empty)
      k.focalPairs = oc.downField("focalPairs").as[Vector[Json]].getOrElse(Vector.empty)
      k.focalActiveBefore = oc.downField("focalActiveBefore").focus.getOrElse(Json.arr())
      record(k, "interrupted", Json.fromString("shutdown"), stamp(instance, tick(), step, commit), Json.Null, "", step, ring = false)
        .mapObject(_.add("activeAfter", oc.downField("focalActiveAfter").focus.getOrElse(Json.arr())))
    }
    val ends = c.downField("sustain").as[Vector[Json]].getOrElse(Vector.empty).filter(s => str(s, "id").nonEmpty).map { s =>
      Json.obj("format" -> Json.fromString("re-kline-event/1"), "kline" -> Json.fromString(str(s, "id")),
        "event" -> Json.fromString("sustain-end"), "through" -> Json.fromLong(num(s, "through")),
        "at" -> stamp(instance, tick(), step, commit))
    }
    open = Vector.empty; sustain = Vector.empty
    persist(recs ++ ends)
    lastStep = -1L
  }

  private def trimRing(): Unit = {
    var keepFrom = lastStep - math.max(1, longestPath * (feedbackHops + 1))
    open.foreach(k => keepFrom = math.min(keepFrom, k.openStep - 1))
    while (ring.nonEmpty && ring.head.step < keepFrom) {
      ring.head.changed.foreach { case (n, l) => baseline(n) = l }
      ring.remove(0)
    }
    closed.filterInPlace { case (_, c) => c._4 >= keepFrom - 1 }
  }

  // ── Routes' bodies ─────────────────────────────────────────────────────────

  def openJson: Json = Json.obj("commit" -> Json.fromLong(lastCommit), "open" -> Json.arr(
    open.sortWith((a, b) =>
      if (a.machine != b.machine) bytesLess(a.machine, b.machine)
      else if (a.sequence != b.sequence) bytesLess(a.sequence, b.sequence) else a.openStep < b.openStep
    ).map(k => Json.obj("id" -> Json.fromString(k.id),
      "focal" -> Json.obj("machine" -> Json.fromString(k.machine), "sequence" -> Json.fromString(k.sequence)),
      "open" -> k.open, "path" -> Json.arr(k.path: _*), "held" -> Json.arr(k.held.map(Json.fromString): _*))): _*))

  def list(outcome: Option[String], machine: Option[String], sequence: Option[String], since: Long, limit0: Int): Json = {
    val limit = math.min(1000, math.max(1, if (limit0 <= 0) 100 else limit0))
    val sel = store.metas.filter(m => m.closeCommit >= since && outcome.forall(_ == m.outcome) &&
      machine.forall(_ == m.machine) && sequence.forall(_ == m.sequence)).toVector
    val out = mutable.ArrayBuffer.empty[Json]
    var next: Json = Json.Null
    var i = 0
    var stop = false
    while (!stop && i < sel.length) {  // a page holds whole commits
      var j = i
      while (j < sel.length && sel(j).closeCommit == sel(i).closeCommit) j += 1
      if (out.nonEmpty && out.length + (j - i) > limit) { next = Json.fromLong(sel(i).closeCommit); stop = true }
      else {
        (i until j).foreach(x => out += store.record(sel(x)))
        i = j
        if (out.length >= limit && i < sel.length) { next = Json.fromLong(sel(i).closeCommit); stop = true }
      }
    }
    Json.obj("klines" -> Json.arr(out.toSeq: _*), "next" -> next)
  }

  def get(id: String): Option[Json] = store.recordById(id)
  def stats: Json = store.stats(storeMaxBytes)

  def exportBundle(outcomes: Set[String], since: Long, machines: Set[String]): String = {
    val body = new StringBuilder
    var count = 0
    val segs = mutable.HashSet.empty[String]
    store.metas.filter(m => m.closeCommit >= since && (outcomes.isEmpty || outcomes(m.outcome)) &&
      (machines.isEmpty || machines(m.machine))).foreach { m =>
      body.append(store.raw(m)).append('\n'); count += 1
      store.rawEvents(m.id).foreach { e => body.append(e).append('\n'); count += 1 }
      segs += m.segment
    }
    store.markExported(segs.toSet)
    val header = Json.obj(
      "format" -> Json.fromString("re-kline-bundle/1"), "source" -> Json.obj("instance" -> Json.fromString(instance)),
      "corpus" -> Json.obj("algorithm" -> Json.fromString("sha256/resident-declared-v1"), "fingerprint" -> Json.fromString(fingerprint)),
      "controls" -> Json.obj("klineCapture" -> Json.fromBoolean(capture), "klineFeedbackHops" -> Json.fromInt(feedbackHops),
        "klineStoreMaxBytes" -> Json.fromLong(storeMaxBytes)),
      "count" -> Json.fromInt(count), "sha256" -> Json.fromString(sha256Hex(body.toString)))
    jcs(header) + "\n" + body.toString
  }
}

object KlineTracker {
  /** The engine's tracker: an allocated instance's store under
    * <INSTANCE_KLINE_DIR>/<uuid>/ (the parent defaulting to the clock
    * directory's sibling), in memory for a minted one. Capture is
    * RE_KLINE_CAPTURE=1 until the three voters serve K-lines. */
  def forEngine(instance: String, tick: () => Long, env: Map[String, String] = sys.env): KlineTracker = {
    val capture = env.get("RE_KLINE_CAPTURE").exists(v => v == "1" || v == "true")
    val allocated = env.getOrElse("INSTANCE_UUID", "").toLowerCase
    val dir = if (capture && allocated == instance) {
      val parent = env.get("INSTANCE_KLINE_DIR").filter(_.nonEmpty).map(Paths.get(_)).getOrElse {
        val clockDir = env.get("INSTANCE_CLOCK_DIR").filter(_.nonEmpty).map(Paths.get(_))
          .getOrElse(Paths.get(sys.props("user.home"), ".reality-engine", "clock"))
        clockDir.toAbsolutePath.getParent.resolve("klines")
      }
      Some(parent.resolve(instance))
    } else None
    val t = new KlineTracker(instance, new KlineStore(dir), tick)
    t.capture = capture
    if (capture) {
      System.err.println(s"K-line capture on — store ${dir.map(_.toString).getOrElse("in memory")}")
      t.recover()
    }
    t
  }

  /** Longest initial-to-final path in the resident corpus, self-loops ignored. */
  def longestPath(machines: Iterable[Machine]): Int = {
    var longest = 1
    machines.foreach(_.getAllSequences.foreach { seq =>
      val events = seq.getAllVectors.map(e => e.id -> e).toMap
      def walk(id: String, seen: Set[String]): Int = events.get(id) match {
        case None => 0
        case Some(e) => 1 + (0 :: e.getNextVectorIds.filterNot(seen.contains).filterNot(_ == id).map(n => walk(n, seen + id))).max
      }
      events.values.filter(_.isInitial).foreach(e => longest = math.max(longest, walk(e.id, Set.empty)))
    })
    longest
  }
}
