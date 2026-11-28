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

  /** RE_SNAPSHOT_STORE: memory (default), file:<dir>, off. qdrant:<url> is phase 3
    * and is off until then, never a boot failure. */
  final class Store(val mode: String, dir: Option[Path]) {
    private val memory = mutable.LinkedHashMap.empty[String, Json]
    dir.foreach(d => Files.createDirectories(d))
    def enabled: Boolean = mode != "off"
    private def valid(id: String) = id.nonEmpty && id.forall(c => "0123456789abcdef".indexOf(c) >= 0)
    def put(id: String, body: Json): Unit = dir match {
      case Some(d) =>
        val tmp = d.resolve(s"$id.json.tmp")
        Files.write(tmp, (jcs(body) + "\n").getBytes(UTF_8))
        Files.move(tmp, d.resolve(s"$id.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      case None => memory(id) = body
    }
    def get(id: String): Option[Json] = if (!valid(id)) None else dir match {
      case Some(d) =>
        val f = d.resolve(s"$id.json")
        if (Files.exists(f)) parse(new String(Files.readAllBytes(f), UTF_8)).toOption else None
      case None => memory.get(id)
    }
    def remove(id: String): Boolean = get(id).isDefined && (dir match {
      case Some(d) => Files.deleteIfExists(d.resolve(s"$id.json"))
      case None => memory.remove(id).isDefined
    })
    def list: Json = {
      val bodies = dir match {
        case Some(d) =>
          import scala.jdk.CollectionConverters._
          Files.list(d).iterator.asScala.toList.filter(_.toString.endsWith(".json"))
            .flatMap(f => parse(new String(Files.readAllBytes(f), UTF_8)).toOption)
        case None => memory.values.toList
      }
      val lamport = (b: Json) => b.hcursor.downField("kline").downField("clocks").downField("to").get[Double]("lamport").getOrElse(0.0)
      Json.obj("snapshots" -> Json.arr(bodies.sortBy(lamport).map { b =>
        val k = b.hcursor.downField("kline")
        Json.obj("id" -> Json.fromString(idOf(b)), "digest" -> b.hcursor.downField("digest").focus.getOrElse(Json.Null),
          "scope" -> k.downField("scope").focus.getOrElse(Json.Null), "span" -> k.downField("span").focus.getOrElse(Json.Null),
          "machines" -> b.hcursor.downField("corpus").downField("machines").focus.getOrElse(Json.Null))
      }: _*))
    }
  }

  object Store {
    def fromEnvironment(env: Map[String, String] = sys.env): Store = env.getOrElse("RE_SNAPSHOT_STORE", "memory") match {
      case "" | "memory" => new Store("memory", None)
      case v if v.startsWith("file:") => new Store("file", Some(Paths.get(v.stripPrefix("file:"))))
      case _ => new Store("off", None)
    }
  }
}
