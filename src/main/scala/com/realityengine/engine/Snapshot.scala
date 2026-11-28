package com.realityengine.engine

import com.realityengine.models._
import io.circe.Json
import io.circe.parser.parse

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.collection.mutable

/** Runtime snapshots (RealityEngine_CI#287, phase 2; SURFACE_SPEC "K-lines and
  * runtime snapshots": "The snapshot body", "Routes"). The same rules as
  * RealityEngine_CPP src/snapshot.cpp and RealityEngine_LSP src/snapshot.lisp:
  * a re-snapshot/1 body over retained steps, its digest over JCS without the
  * clocks' instance and lamport, a store (memory, file:<dir>, off), and the
  * retention it is captured from: for every retained step (historyLimit), the
  * active list after it, its contributions and its clock, whether or not K-line
  * capture is on (owner decision 4, 2026-11-24). */
object Snapshot {
  import Kline._

  final case class Op(machine: String, region: RegionMapping, values: Vector[Double])
  final case class Retained(step: Long, lamport: Long, isre: TrajectoryEntry, osre: TrajectoryEntry,
                            ops: Seq[Op], after: List[Active])

  def allActives(machines: Iterable[Machine]): List[Active] = machines.toList.flatMap(activesOf)

  final class Retention {
    private var base: List[Active] = Nil
    private var baseSet = false
    private val steps = mutable.ArrayBuffer.empty[Retained]

    def onReset(): Unit = { base = Nil; baseSet = false; steps.clear() }
    def restore(b: List[Active]): Unit = { base = b; baseSet = true; steps.clear() }
    def onStep(step: Long, lamport: Long, isre: TrajectoryEntry, osre: TrajectoryEntry, sim: SimulationStep,
               machines: Iterable[Machine], limit: Int): Unit = {
      // The reset hook runs before the machines reset, so the base is taken at
      // the first step after it: every machine at its initial events.
      if (steps.isEmpty && !baseSet) { base = machines.toList.flatMap(initialActivesOf); baseSet = true }
      steps += Retained(step, lamport, isre, osre,
        sim.mergeBatch.map(op => Op(op.machineName, op.region, op.values.toVector)), allActives(machines))
      while (steps.size > math.max(1, limit)) { base = steps.head.after; steps.remove(0) }
    }
    def newest: Option[Long] = steps.lastOption.map(_.step)
    def at(step: Long): Option[Retained] = steps.find(_.step == step)
    def before(step: Long): Option[List[Active]] =
      at(step - 1).map(_.after).orElse(if (steps.isEmpty || steps.head.step == step) Some(base) else None)
  }

  final case class Request(scope: Option[Set[String]], from: Option[Long], to: Option[Long], instance: String,
                           fingerprint: String, machineCount: Int, controls: Json, machineControls: Json,
                           machineOverrides: Json, arbitration: Json, machines: Iterable[Machine])

  private def err(status: Int, message: String) = Left((status, Json.obj("error" -> Json.fromString(message))))

  def capture(r: Retention, q: Request): Either[(Int, Json), Json] = {
    val newest = r.newest.getOrElse(return err(410, "no step is retained"))
    val to = q.to.orElse(q.from).getOrElse(newest)
    val from = q.from.getOrElse(to)
    if (from > to) return err(422, "span.from is after span.to")
    for (s <- Seq(from, to) if r.at(s).isEmpty) return err(410, s"step $s is no longer retained")
    val before = r.before(from).getOrElse(return err(410, s"step ${from - 1} is no longer retained"))

    val mapped = q.machines.toSeq.flatMap(m => m.perceptualMapping.map(pm => (m.name, pm)))
    val (inside, outside) = mapped.partition { case (n, _) => q.scope.forall(_.contains(n)) }
    val inputs = inside.map(_._2.input)
    val outputs = inside.map(_._2.output)
    val shared = (for {
      o <- if (q.scope.isDefined) outputs else Nil
      (_, x) <- outside
      c <- math.max(o.offset, x.output.offset) until math.min(o.offset + o.length, x.output.offset + x.output.length)
    } yield c).distinct.sorted

    def scoped(list: List[Active]) = q.scope.fold(list)(s => list.filter(a => s.contains(a.machine)))
    def clock(s: Retained) = Json.obj("instance" -> Json.fromString(q.instance), "lamport" -> Json.fromLong(s.lamport),
                                      "step" -> Json.fromLong(s.step))
    val none = Seq(RegionMapping(0, 0))
    val pairs = (from to to).map { k =>
      val s = r.at(k).getOrElse(return err(410, s"step $k is no longer retained"))
      val ops = s.ops.filter(op => q.scope.forall(_.contains(op.machine))).sortWith { (a, b) =>
        if (a.region.offset != b.region.offset) a.region.offset < b.region.offset else bytesLess(a.machine, b.machine)
      }
      Json.obj(
        "stepNumber" -> Json.fromLong(k),
        "isre" -> entryJson(s.isre, if (q.scope.isEmpty) Nil else if (inputs.isEmpty) none else inputs),
        "osre" -> entryJson(s.osre, if (q.scope.isEmpty) Nil else if (outputs.isEmpty) none else outputs),
        "contributions" -> Json.arr(ops.map(op => Json.obj(
          "machine" -> Json.fromString(op.machine),
          "region" -> Json.obj("offset" -> Json.fromInt(op.region.offset), "length" -> Json.fromInt(op.region.length)),
          "values" -> Json.arr(op.values.map(Json.fromDoubleOrNull): _*))): _*))
    }
    val body = Json.obj(
      "format" -> Json.fromString("re-snapshot/1"),
      "corpus" -> Json.obj("algorithm" -> Json.fromString("sha256/resident-declared-v1"),
        "fingerprint" -> Json.fromString(q.fingerprint), "machines" -> Json.fromInt(q.machineCount)),
      "controls" -> q.controls, "machineControls" -> q.machineControls, "machineOverrides" -> q.machineOverrides,
      "kline" -> Json.obj(
        "scope" -> q.scope.fold(Json.fromString("corpus"))(s => Json.arr(s.toSeq.sorted(byBytes).map(Json.fromString): _*)),
        "span" -> Json.obj("from" -> Json.fromLong(from), "to" -> Json.fromLong(to)),
        "clocks" -> Json.obj("from" -> clock(r.at(from).get), "to" -> clock(r.at(to).get)),
        "activeBefore" -> activesJson(scoped(before)),
        "pairs" -> Json.arr(pairs: _*),
        "activeAfter" -> activesJson(scoped(r.at(to).get.after)),
        "sharedCells" -> Json.arr(shared.map(Json.fromInt): _*)),
      "arbitration" -> q.arbitration)
    Right(body.mapObject(_.add("digest", Json.fromString(digest(body)))))
  }

  /** sha256:<hex> over the body without digest and the clocks' instance and lamport. */
  def digest(body: Json): String = {
    val stripped = body.mapObject(_.remove("digest")).hcursor.downField("kline").downField("clocks")
      .withFocus(_.mapObject(_.mapValues(_.mapObject(_.remove("instance").remove("lamport")))))
      .top.getOrElse(body.mapObject(_.remove("digest")))
    "sha256:" + sha256Hex(jcs(stripped))
  }

  def idOf(body: Json): String = body.hcursor.get[String]("digest").getOrElse("").stripPrefix("sha256:")

  // ── A snapshot's state, and nearest (SURFACE_SPEC "Nearest", #900) ──────────

  /** Cell -> value. */
  type State = Map[Long, Double]

  /** A snapshot's state: the ISRE at span.to. */
  def stateOf(body: Json): State = {
    val k = body.hcursor.downField("kline")
    val to = k.downField("span").get[Double]("to").getOrElse(-1.0)
    k.downField("pairs").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      .filter(_.hcursor.get[Double]("stepNumber").toOption.contains(to))
      .flatMap(_.hcursor.downField("isre").downField("nonZero").focus.flatMap(_.asArray).getOrElse(Vector.empty))
      .flatMap(c => for {
        i <- c.hcursor.get[Double]("index").toOption
        v <- c.hcursor.get[Double]("value").toOption
      } yield i.toLong -> v).toMap
  }

  /** {length, nonZero: [{index, value}]} as a state; None when malformed. */
  def parseState(isre: Json): Option[State] =
    isre.hcursor.downField("nonZero").focus.flatMap(_.asArray).filter(_ => isre.isObject).flatMap { cells =>
      val parsed = cells.map(c => for {
        i <- c.hcursor.downField("index").focus.flatMap(_.asNumber).map(_.toDouble) if i >= 0
        v <- c.hcursor.downField("value").focus.flatMap(_.asNumber).map(_.toDouble)
      } yield i.toLong -> v)
      if (parsed.forall(_.isDefined)) Some(parsed.flatten.toMap) else None
    }

  /** The sparse dot product, summed in ascending cell order, as every runtime sums it. */
  def score(query: State, state: State): Double =
    query.keys.toVector.sorted.foldLeft(0.0)((acc, c) => state.get(c).fold(acc)(w => acc + query(c) * w))

  private def summary(b: Json): Json = {
    val k = b.hcursor.downField("kline")
    Json.obj("id" -> Json.fromString(idOf(b)), "digest" -> b.hcursor.downField("digest").focus.getOrElse(Json.Null),
      "scope" -> k.downField("scope").focus.getOrElse(Json.Null), "span" -> k.downField("span").focus.getOrElse(Json.Null),
      "machines" -> b.hcursor.downField("corpus").downField("machines").focus.getOrElse(Json.Null))
  }

  private def lamportOf(b: Json): Double =
    b.hcursor.downField("kline").downField("clocks").downField("to").get[Double]("lamport").getOrElse(0.0)

  private def sparse(state: State): Json = {
    val cells = state.toVector.sortBy(_._1)
    Json.obj("indices" -> Json.arr(cells.map(c => Json.fromLong(c._1)): _*),
      "values" -> Json.arr(cells.map(c => Json.fromDoubleOrNull(c._2)): _*))
  }

  /** A store that cannot answer: Qdrant unreachable or refusing (#900). The route answers 503. */
  final class StoreUnavailable(reason: String) extends RuntimeException(reason)

  /** qdrant:<url> (#900): one collection, points filtered by this instance. */
  final case class QdrantTarget(url: String, collection: String, instance: String)

  /** RE_SNAPSHOT_STORE: memory (default), file:<dir>, qdrant:<https url>, off. An
    * unusable value is off, with the reason in offReason; never a boot failure. */
  final class Store(val mode: String, dir: Option[Path], qdrant: Option[QdrantTarget] = None,
                    val offReason: Option[String] = None) {
    private val memory = mutable.LinkedHashMap.empty[String, Json]
    @volatile private var collectionReady = false
    dir.foreach(d => Files.createDirectories(d))
    def enabled: Boolean = mode != "off"
    private def valid(id: String) = id.nonEmpty && id.forall(c => "0123456789abcdef".indexOf(c) >= 0)

    /** One Qdrant call: its `result`, or StoreUnavailable. The call carries no
      * RE-Clock: Qdrant is not a clock participant, so the clock never moves. */
    private def call(method: String, path: String, body: Option[Json] = None): Json = {
      val t = qdrant.get
      val text = try {
        val conn = java.net.URI.create(t.url + path).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
        com.realityengine.tls.LaneTls.customizeConnection(conn)
        conn.setRequestMethod(method)
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(30000)
        conn.setRequestProperty("Content-Type", "application/json")
        body.foreach { b =>
          conn.setDoOutput(true)
          val os = conn.getOutputStream
          try os.write(b.noSpaces.getBytes(UTF_8)) finally os.close()
        }
        val code = conn.getResponseCode
        val in = if (code >= 400) conn.getErrorStream else conn.getInputStream
        if (in == null) "" else try new String(in.readAllBytes(), UTF_8) finally in.close()
      } catch { case e: Exception => throw new StoreUnavailable(s"Qdrant: ${e.getMessage}") }
      val j = parse(text).getOrElse(throw new StoreUnavailable("Qdrant answered something that is not JSON"))
      if (j.hcursor.get[String]("status").toOption.contains("ok")) j.hcursor.downField("result").focus.getOrElse(Json.Null)
      else throw new StoreUnavailable("Qdrant: " + j.hcursor.downField("status").get[String]("error").getOrElse("an error"))
    }

    private def ready(): Unit = if (!collectionReady) {
      val c = qdrant.get.collection
      if (!call("GET", s"/collections/$c/exists").hcursor.get[Boolean]("exists").getOrElse(false)) {
        call("PUT", s"/collections/$c", Some(Json.obj("sparse_vectors" -> Json.obj("isre" -> Json.obj()))))
        call("PUT", s"/collections/$c/index?wait=true",
          Some(Json.obj("field_name" -> Json.fromString("instance"), "field_schema" -> Json.fromString("keyword"))))
      }
      collectionReady = true
    }

    private def pointId(id: String): String = {
      val h = sha256Hex(s"${qdrant.get.instance}:$id")
      s"${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    private def instanceFilter: Json = Json.obj("must" -> Json.arr(Json.obj("key" -> Json.fromString("instance"),
      "match" -> Json.obj("value" -> Json.fromString(qdrant.get.instance)))))

    private def coll = qdrant.get.collection

    def put(id: String, body: Json): Unit = (dir, qdrant) match {
      case (Some(d), _) =>
        val tmp = d.resolve(s"$id.json.tmp")
        Files.write(tmp, (jcs(body) + "\n").getBytes(UTF_8))
        Files.move(tmp, d.resolve(s"$id.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      case (None, Some(t)) =>
        ready()
        val k = body.hcursor.downField("kline")
        val payload = Json.obj("instance" -> Json.fromString(t.instance), "id" -> Json.fromString(id),
          "digest" -> body.hcursor.downField("digest").focus.getOrElse(Json.Null),
          "scope" -> k.downField("scope").focus.getOrElse(Json.Null), "span" -> k.downField("span").focus.getOrElse(Json.Null),
          "machines" -> body.hcursor.downField("corpus").downField("machines").focus.getOrElse(Json.Null),
          "lamport" -> k.downField("clocks").downField("to").downField("lamport").focus.getOrElse(Json.Null),
          "body" -> Json.fromString(jcs(body)))
        call("PUT", s"/collections/$coll/points?wait=true", Some(Json.obj("points" -> Json.arr(Json.obj(
          "id" -> Json.fromString(pointId(id)), "vector" -> Json.obj("isre" -> sparse(stateOf(body))), "payload" -> payload)))))
      case _ => memory(id) = body
    }

    def get(id: String): Option[Json] = if (!valid(id)) None else (dir, qdrant) match {
      case (Some(d), _) =>
        val f = d.resolve(s"$id.json")
        if (Files.exists(f)) parse(new String(Files.readAllBytes(f), UTF_8)).toOption else None
      case (None, Some(t)) =>
        ready()
        call("POST", s"/collections/$coll/points",
          Some(Json.obj("ids" -> Json.arr(Json.fromString(pointId(id))), "with_payload" -> Json.True)))
          .asArray.getOrElse(Vector.empty).map(_.hcursor.downField("payload"))
          .find(p => p.get[String]("instance").toOption.contains(t.instance) && p.get[String]("id").toOption.contains(id))
          .flatMap(_.get[String]("body").toOption).flatMap(parse(_).toOption)
      case _ => memory.get(id)
    }

    def remove(id: String): Boolean = get(id).isDefined && ((dir, qdrant) match {
      case (Some(d), _) => Files.deleteIfExists(d.resolve(s"$id.json"))
      case (None, Some(_)) =>
        call("POST", s"/collections/$coll/points/delete?wait=true", Some(Json.obj("points" -> Json.arr(Json.fromString(pointId(id))))))
        true
      case _ => memory.remove(id).isDefined
    })

    private def bodies: List[Json] = (dir, qdrant) match {
      case (Some(d), _) =>
        import scala.jdk.CollectionConverters._
        Files.list(d).iterator.asScala.toList.filter(_.toString.endsWith(".json"))
          .flatMap(f => parse(new String(Files.readAllBytes(f), UTF_8)).toOption)
      case (None, Some(_)) =>
        ready()
        val out = mutable.ListBuffer.empty[Json]
        var offset: Option[Json] = None
        var more = true
        while (more) {
          val req = Json.obj("filter" -> instanceFilter, "limit" -> Json.fromInt(256), "with_payload" -> Json.True,
            "with_vector" -> Json.False).deepMerge(offset.fold(Json.obj())(o => Json.obj("offset" -> o)))
          val res = call("POST", s"/collections/$coll/points/scroll", Some(req))
          res.hcursor.downField("points").focus.flatMap(_.asArray).getOrElse(Vector.empty)
            .flatMap(_.hcursor.downField("payload").get[String]("body").toOption).flatMap(parse(_).toOption)
            .foreach(out += _)
          offset = res.hcursor.downField("next_page_offset").focus.filterNot(_.isNull)
          more = offset.isDefined
        }
        out.toList
      case _ => memory.values.toList
    }

    /** {snapshots: [summary]}, oldest first by clocks.to.lamport, then id: every store alike. */
    def list: Json =
      Json.obj("snapshots" -> Json.arr(bodies.sortBy(b => (lamportOf(b), idOf(b))).map(summary): _*))

    /** {snapshots: [{id, score, scope, span, machines}]}: score > 0, highest first, then id. */
    def nearest(query: State, limit: Int): Json = {
      val candidates: List[Json] =
        if (query.isEmpty) Nil
        else if (qdrant.isDefined) {
          // Qdrant's candidates, scored and ordered here (ties at the limit-th
          // place are Qdrant's to break; SURFACE_SPEC "Nearest").
          ready()
          call("POST", s"/collections/$coll/points/query", Some(Json.obj("query" -> sparse(query),
            "using" -> Json.fromString("isre"), "filter" -> instanceFilter, "limit" -> Json.fromInt(limit),
            "with_payload" -> Json.True)))
            .hcursor.downField("points").focus.flatMap(_.asArray).getOrElse(Vector.empty).toList
            .flatMap(_.hcursor.downField("payload").get[String]("body").toOption).flatMap(parse(_).toOption)
        } else bodies
      val ranked = candidates.map(b => (score(query, stateOf(b)), b)).filter(_._1 > 0)
        .sortBy { case (sc, b) => (-sc, idOf(b)) }.take(limit)
      Json.obj("snapshots" -> Json.arr(ranked.map { case (sc, b) =>
        summary(b).mapObject(_.add("score", Json.fromDoubleOrNull(sc))) }: _*))
    }
  }

  object Store {
    def fromEnvironment(env: Map[String, String] = sys.env): Store = env.getOrElse("RE_SNAPSHOT_STORE", "memory") match {
      case "" | "memory" => new Store("memory", None)
      case "off" => new Store("off", None)
      case v if v.startsWith("file:") => new Store("file", Some(Paths.get(v.stripPrefix("file:"))))
      case v if v.startsWith("qdrant:") =>
        val url = v.stripPrefix("qdrant:").reverse.dropWhile(_ == '/').reverse
        if (!url.toLowerCase.startsWith("https://"))
          // Never a plaintext link (ENGINEERING_CONTRACT, data in motion).
          new Store("off", None, offReason = Some("RE_SNAPSHOT_STORE=qdrant: needs an https URL; the snapshot store is off"))
        else new Store("qdrant", None, Some(QdrantTarget(url,
          env.get("RE_SNAPSHOT_COLLECTION").filter(_.nonEmpty).getOrElse("re-snapshots"),
          env.get("INSTANCE_UUID").filter(_.nonEmpty).getOrElse("unidentified"))))
      case v => new Store("off", None,
        offReason = Some(s"RE_SNAPSHOT_STORE=$v is not memory, file:<dir>, qdrant:<url> or off; the snapshot store is off"))
    }
  }
}
