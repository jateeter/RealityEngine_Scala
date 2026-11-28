package com.realityengine.perception.isre

import com.realityengine.perception.api.CanonicalJson
import io.circe.Json
import io.circe.parser.parse

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import scala.collection.mutable

/** The PE half of a K-line: one ISRE composition record per completed push
  * (re-isre-composition/1), kept in the K-line store's format per PE instance
  * (RealityEngine_CI#603; SURFACE_SPEC "The PE half: ISRE composition"). The
  * same rules as RealityEngine_CPP src/isre.cpp and RealityEngine_LSP
  * src/isre.lisp. JCS and the store are the RE build's
  * (com.realityengine.engine.Kline), kept here because this is its own build. */
object Isre {

  /** The source-kind taxonomy version the PEs record (owner, 2026-11-27). */
  val TaxonomyVersion = 2

  // ── JCS, SHA-256 (as com.realityengine.engine.Kline) ───────────────────────

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


  private[isre] def num(j: Json, k: String): Long = j.hcursor.get[Double](k).map(_.toLong).getOrElse(0L)
  private[isre] def str(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  // ── The taxonomy ───────────────────────────────────────────────────────────

  final case class Entry(kind: String, provider: Option[String], cls: String)

  final case class Taxonomy(version: Int, providerMapping: Map[String, String], kindClass: Map[String, String],
                            providerClass: Map[String, Map[String, String]], unknownProvider: String, unknownClass: String) {
    def name: String = s"re-source-kinds/$version"

    /** A source's entry: its kind, a sensor's provider from its origin (first
      * `.` segment, lowercased, through providerMapping; empty is `signal`;
      * unlisted is the unknown provider), and the version's class. */
    def sourceEntry(kind: String, origin: String): Entry = kindClass.get(kind) match {
      case Some(c) => Entry(kind, None, c)
      case None =>
        val asKind = if (providerClass.contains(kind)) kind else "sensor"
        val dot = origin.indexOf('.')
        val first = (if (dot < 0) origin else origin.substring(0, dot)).toLowerCase(java.util.Locale.ROOT)
        val p0 = if (first.isEmpty) "signal" else first
        val provider = providerMapping.getOrElse(p0, p0)
        providerClass.get(asKind).flatMap(_.get(provider)) match {
          case Some(c) => Entry(asKind, Some(provider), c)
          case None    => Entry(asKind, Some(unknownProvider), unknownClass)
        }
    }
    def feedbackEntry: Entry = Entry("feedback", None, kindClass.getOrElse("feedback", "deterministic"))
  }

  /** <MACHINES_DIR>/../schemas/source-kinds */
  def taxonomyDir(machinesDir: String): Path = {
    val m = java.nio.file.Paths.get(if (machinesDir.isEmpty) "../RealityEngine_Machines/machines" else machinesDir).toAbsolutePath.normalize
    m.getParent.resolve("schemas").resolve("source-kinds")
  }

  def loadTaxonomy(dir: Path, version: Int = TaxonomyVersion): Option[Taxonomy] = {
    val file = dir.resolve(s"re-source-kinds-$version.json")
    if (!Files.exists(file)) None
    else parse(new String(Files.readAllBytes(file), UTF_8)).toOption
      .filter(d => str(d, "format") == "re-source-kinds" && d.hcursor.get[Double]("version").toOption.contains(version.toDouble))
      .map { d =>
        val c = d.hcursor
        val mapping = c.get[Map[String, String]]("providerMapping").getOrElse(Map.empty)
        val kinds = c.get[Vector[Json]]("kinds").getOrElse(Vector.empty)
        val withProviders = kinds.flatMap { k =>
          k.hcursor.get[Vector[Json]]("providers").toOption.map(ps => str(k, "kind") -> ps.map(p => str(p, "provider") -> str(p, "class")).toMap)
        }.toMap
        val plain = kinds.filterNot(k => withProviders.contains(str(k, "kind")))
          .flatMap(k => k.hcursor.get[String]("class").toOption.map(str(k, "kind") -> _)).toMap
        val u = c.downField("unknownProvider")
        Taxonomy(version, mapping, plain, withProviders,
          u.get[String]("provider").getOrElse("unattributed"), u.get[String]("class").getOrElse("generated"))
      }
  }

  // ── The record's sources ───────────────────────────────────────────────────

  /** One cell's winner at a step: a source, or the feedback term. */
  final case class Winner(cell: Int, feedback: Boolean, id: String = "", kind: String = "", origin: String = "",
                          causedBy: Json = Json.Null)

  /** The step's `sources`: runs of consecutive cells with one winner, ascending. */
  def sourcesJson(cells: Seq[Winner], taxonomy: Taxonomy): Json = {
    final case class Run(first: Int, var last: Int, e: Entry, id: String, causedBy: Json)
    val runs = Vector.newBuilder[Json]
    var cur: Option[Run] = None
    def flush(): Unit = cur.foreach { r =>
      runs += Json.fromFields(
        List("cells" -> Json.arr(Json.fromInt(r.first), Json.fromInt(r.last)), "kind" -> Json.fromString(r.e.kind)) ++
          r.e.provider.map(p => "provider" -> Json.fromString(p)).toList ++
          List("class" -> Json.fromString(r.e.cls)) ++
          (if (r.id.nonEmpty) List("id" -> Json.fromString(r.id)) else Nil) ++
          List("causedBy" -> r.causedBy))
      cur = None
    }
    cells.sortBy(_.cell).foreach { w =>
      val e = if (w.feedback) taxonomy.feedbackEntry else taxonomy.sourceEntry(w.kind, w.origin)
      val id = if (w.feedback) "" else w.id
      val cb = if (w.feedback) Json.Null else w.causedBy
      cur match {
        case Some(r) if r.last + 1 == w.cell && r.e == e && r.id == id && jcs(r.causedBy) == jcs(cb) => r.last = w.cell
        case _ => flush(); cur = Some(Run(w.cell, w.cell, e, id, cb))
      }
    }
    flush()
    Json.arr(runs.result(): _*)
  }
}

final class IsreStore(val dir: Option[Path], val budgetName: String = "isreStoreMaxBytes") {
  // The budget warnings as of the last budget check, export or control change,
  // for /api/health: read from any thread without the step lock, so health
  // never waits on a step (SURFACE_SPEC "The K-line store", budget).
  @volatile private var cachedWarnings: Seq[String] = Nil
  @volatile private var lastMaxBytes: Long = 2147483648L
  def refreshWarnings(maxBytes: Long): Unit = { lastMaxBytes = maxBytes; cachedWarnings = warnings(maxBytes) }
  def healthWarnings: Seq[String] = cachedWarnings
  import Isre._
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
      case "re-isre-composition/1" =>
        // The PE's composition records, keyed by the RE's commit (CI#603).
        val c = j.hcursor.downField("re").get[Double]("commit").map(_.toLong).getOrElse(0L)
        val m = Meta(c.toString, "", "", "", c, segment, offset, line)
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
      Seq(s"$budgetName reached: $unexported unexported sealed segment(s) are kept; export them to free space")
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

  def markExported(names: Set[String]): Unit = {
    segments.foreach(s => if (names(s.name)) s.exported = true); writeManifest(); refreshWarnings(lastMaxBytes)
  }

  def enforceBudget(maxBytes: Long): Unit = { enforceBudgetNow(maxBytes); refreshWarnings(maxBytes) }

  private def enforceBudgetNow(maxBytes: Long): Unit = {
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


/** The PE's recorder. Callers hold the engine lock. */
final class IsreRecorder(val instance: String, val store: IsreStore, val taxonomy: Option[Isre.Taxonomy]) {
  import Isre._
  @volatile var capture: Boolean = false
  @volatile var storeMaxBytes: Long = 2L * 1024 * 1024 * 1024
  store.refreshWarnings(storeMaxBytes)  // an over-budget population from an earlier run warns from boot
  private var reInstance = ""

  /** One completed push: `re` is the RE step's clock, `pe` the PE's tick for the record. */
  def record(re: Json, pe: Json, cells: Seq[Winner], folds: Vector[Json]): Unit = synchronized {
    if (capture) taxonomy.foreach { tx =>
      re.hcursor.get[String]("instance").foreach(reInstance = _)
      val rec = Json.obj("format" -> Json.fromString("re-isre-composition/1"), "taxonomy" -> Json.fromString(tx.name),
        "re" -> re, "pe" -> pe, "sources" -> sourcesJson(cells, tx), "folds" -> Json.arr(folds: _*),
        "delta" -> Json.False)
      store.append(Seq(rec), tx.name, num(re, "commit"))
      store.enforceBudget(storeMaxBytes)
    }
  }

  def list(from: Long, to: Long, limit0: Int): Json = synchronized {
    val limit = math.min(1000, math.max(1, if (limit0 <= 0) 100 else limit0))
    val out = Vector.newBuilder[Json]
    var count = 0
    var next: Json = Json.Null
    val it = store.metas.iterator.filter(m => m.closeCommit >= from && (to < 0 || m.closeCommit <= to))
    var go = true
    while (go && it.hasNext) {
      val m = it.next()
      if (count >= limit) { next = Json.fromLong(m.closeCommit); go = false }
      else { out += parse(store.raw(m)).getOrElse(Json.Null); count += 1 }
    }
    Json.obj("records" -> Json.arr(out.result(): _*), "next" -> next)
  }

  def stats: Json = synchronized {
    val s = store.stats(storeMaxBytes).hcursor
    Json.obj(
      "segments" -> s.downField("segments").focus.getOrElse(Json.Null),
      "bytes" -> s.downField("bytes").focus.getOrElse(Json.Null),
      "budget" -> s.downField("budget").focus.getOrElse(Json.Null),
      "warnings" -> s.downField("warnings").focus.getOrElse(Json.Null),
      "records" -> Json.fromInt(store.metas.length),
      "isreCapture" -> Json.fromBoolean(capture),
      "taxonomy" -> taxonomy.map(t => Json.fromString(t.name)).getOrElse(Json.Null))
  }

  def exportBundle(from: Long, to: Long): String = synchronized {
    val chosen = store.metas.filter(m => m.closeCommit >= from && (to < 0 || m.closeCommit <= to)).toVector
    val body = chosen.map(m => store.raw(m) + "\n").mkString
    val header = Json.obj("format" -> Json.fromString("re-isre-bundle/1"),
      "source" -> Json.obj("instance" -> Json.fromString(instance)),
      "re" -> Json.obj("instance" -> Json.fromString(reInstance)),
      "taxonomy" -> taxonomy.map(t => Json.fromString(t.name)).getOrElse(Json.Null),
      "count" -> Json.fromInt(chosen.length), "sha256" -> Json.fromString(sha256Hex(body)))
    store.markExported(chosen.map(_.segment).toSet)
    jcs(header) + "\n" + body
  }
}

object IsreRecorder {
  /** The running PE's recorder, installed at boot (PerceptionMain). */
  @volatile var current: IsreRecorder = new IsreRecorder("", new IsreStore(None), None)

  /** The PE's recorder: its store under <INSTANCE_ISRE_DIR>/<pe_uuid>/ for an
    * allocated instance (the parent defaulting to the clock directory's
    * sibling), in memory for a minted one. With RE_ISRE_CAPTURE=1 and the
    * taxonomy missing, the boot is refused. */
  def forEngine(instance: String, env: Map[String, String] = sys.env): IsreRecorder = {
    val allocated = env.getOrElse("PE_INSTANCE_UUID", "").toLowerCase(java.util.Locale.ROOT)
    val capture = Set("1", "true")(env.getOrElse("RE_ISRE_CAPTURE", ""))
    val tdir = Isre.taxonomyDir(env.getOrElse("MACHINES_DIR", ""))
    val taxonomy = Isre.loadTaxonomy(tdir)
    if (capture && taxonomy.isEmpty)
      throw new IllegalStateException(s"isreCapture is on and re-source-kinds/${Isre.TaxonomyVersion} is not in the corpus ($tdir)")
    val dir = if (allocated == instance) Some {
      val parent = env.get("INSTANCE_ISRE_DIR").filter(_.nonEmpty).map(java.nio.file.Paths.get(_)).getOrElse {
        val clock = env.get("INSTANCE_CLOCK_DIR").filter(_.nonEmpty).map(java.nio.file.Paths.get(_))
          .getOrElse(java.nio.file.Paths.get(sys.props("user.home"), ".reality-engine", "clock"))
        clock.toAbsolutePath.normalize.getParent.resolve("isre")
      }
      parent.resolve(instance)
    } else None
    val r = new IsreRecorder(instance, new IsreStore(dir), taxonomy)
    r.capture = capture
    if (capture) System.err.println(s"ISRE composition capture on — store ${dir.map(_.toString).getOrElse("in memory")}")
    r
  }
}
