package com.realityengine.perception.mqtt

import io.circe.Json
import io.circe.parser.parse
import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import scala.util.Try
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

// ── Mapping rule (parsed from the registry JSON) ──────────────────────────────
//
// Schema is wire-compatible with RealityEngine_CPP/include/reality/mqtt_mapping.hpp,
// RealityEngine_Manager/perception-engine/backend/src/MqttMapping.ts, and
// RealityEngine_LSP/src/mqtt-mapping.lisp.

case class MqttMappingRule(
  id:               String,
  topicFilter:      String,
  sensorIdTemplate: String,
  regionOffset:     Int,
  regionLength:     Int,
  extractType:      String,           // json | csv-float | single-float | raw
  extractPointer:   Option[String],   // RFC-6901 lite JSON pointer (json mode)
  extractIndex:     Option[Int],      // CSV column index (csv-float mode)
  normalizeMode:    String,           // passthrough | minmax | linear | band
  normalizeMin:     Double,
  normalizeMax:     Double,
  normalizeScale:   Double,           // linear mode: out = v * scale + offset
  normalizeOffset:  Double,
  clamp:            Boolean,
  ttlMs:            Long,
  qos:              Int,              // 0 | 1 | 2
  acceptRetained:   Boolean,
  pushMode:         String,           // debounced | immediate | manual
  debounceMs:       Long,
)

object MqttMappingRule {
  def fromJson(m: Json, defaults: Json): Option[MqttMappingRule] = {
    val c  = m.hcursor
    val dc = defaults.hcursor
    for {
      id          <- c.get[String]("id").toOption.filter(_.nonEmpty)
      topicFilter <- c.get[String]("topicFilter").toOption.filter(_.nonEmpty)
      sensorTpl   <- c.get[String]("sensorIdTemplate").toOption.filter(_.nonEmpty)
    } yield {
      val region  = c.downField("region")
      val extract = c.downField("extract")
      val norm    = c.downField("normalize")
      def dfBool(field: String, fallback: Boolean): Boolean =
        c.get[Boolean](field).orElse(dc.get[Boolean](field)).getOrElse(fallback)
      def dfLong(field: String, fallback: Long): Long =
        c.get[Long](field).orElse(dc.get[Long](field)).getOrElse(fallback)
      def dfInt(field: String, fallback: Int): Int =
        c.get[Int](field).orElse(dc.get[Int](field)).getOrElse(fallback)
      def dfStr(field: String, fallback: String): String =
        c.get[String](field).orElse(dc.get[String](field)).getOrElse(fallback)
      MqttMappingRule(
        id               = id,
        topicFilter      = topicFilter,
        sensorIdTemplate = sensorTpl,
        regionOffset     = region.downField("offset").as[Int].getOrElse(0),
        regionLength     = region.downField("length").as[Int].getOrElse(1).max(1),
        extractType      = extract.downField("type").as[String].getOrElse("csv-float"),
        extractPointer   = extract.downField("pointer").as[String].toOption,
        extractIndex     = extract.downField("index").as[Int].toOption,
        normalizeMode    = norm.downField("mode").as[String].getOrElse("passthrough"),
        normalizeMin     = norm.downField("min").as[Double].getOrElse(0.0),
        normalizeMax     = norm.downField("max").as[Double].getOrElse(1.0),
        normalizeScale   = norm.downField("scale").as[Double].getOrElse(1.0),
        normalizeOffset  = norm.downField("offset").as[Double].getOrElse(0.0),
        clamp            = norm.downField("clamp").as[Boolean].getOrElse(true),
        ttlMs            = dfLong("ttlMs", 30000L),
        qos              = dfInt("qos", 0),
        acceptRetained   = dfBool("acceptRetained", true),
        pushMode         = dfStr("pushMode", "debounced"),
        debounceMs       = dfLong("debounceMs", 250L),
      )
    }
  }
}

// ── Per-rule runtime counters ─────────────────────────────────────────────────

class MqttRuleMetrics {
  val received:         AtomicLong = new AtomicLong(0)
  val mapped:           AtomicLong = new AtomicLong(0)
  val rejected:         AtomicLong = new AtomicLong(0)
  val lastMessageAtMs:  AtomicLong = new AtomicLong(0)
  val lastError:        AtomicReference[String]  = new AtomicReference[String]("")
  val lastErrorAtMs:    AtomicLong = new AtomicLong(0)

  def noteError(topic: String, err: String): Unit = {
    rejected.incrementAndGet()
    lastError.set(s"[$topic] $err")
    lastErrorAtMs.set(System.currentTimeMillis())
  }

  def toJson: Json = Json.obj(
    "received"        -> Json.fromLong(received.get()),
    "mapped"          -> Json.fromLong(mapped.get()),
    "rejected"        -> Json.fromLong(rejected.get()),
    "lastMessageAtMs" -> Json.fromLong(lastMessageAtMs.get()),
    "lastError"       -> Json.fromString(lastError.get()),
    "lastErrorAtMs"   -> Json.fromLong(lastErrorAtMs.get()),
  )
}

// ── Bridge-level stats ────────────────────────────────────────────────────────

class MqttBridgeStats {
  val messagesReceived:        AtomicLong = new AtomicLong(0)
  val messagesMapped:          AtomicLong = new AtomicLong(0)
  val messagesRejected:        AtomicLong = new AtomicLong(0)
  val messagesUnmatched:       AtomicLong = new AtomicLong(0)
  val messagesRetainedDropped: AtomicLong = new AtomicLong(0)
  val pushesTriggered:         AtomicLong = new AtomicLong(0)
}

// ── MqttBridge ────────────────────────────────────────────────────────────────
//
// Twin of RealityEngine_CPP/src/mqtt_bridge.cpp,
//         RealityEngine_Manager/perception-engine/backend/src/MqttBridge.ts, and
//         RealityEngine_LSP/src/mqtt-bridge.lisp.
//
// Design rule: MQTT is not special-cased downstream.  Every accepted PUBLISH
// resolves to {sensorId, region, values, ttlMs} and is fed through the same
// ingest callback the HTTP POST /api/signals path uses.
//
// Push policy: one bridge-wide ScheduledExecutorService handles debounce.
// A single Immediate from any rule in the fan-out fires the push trigger now.
// Debounced rules extend a shared deadline; the push fires once the window
// settles.  Manual rules never trigger a push automatically.

class MqttBridge(
  val brokerUrl:  String,
  val clientId:   String,
  val rules:      Vector[MqttMappingRule],
  onIngest:       (String, Int, Int, Vector[Double], Long, String, String) => Unit,
  onPushTrigger:  () => Unit,
) {
  val stats:   MqttBridgeStats        = new MqttBridgeStats
  val metrics: Vector[MqttRuleMetrics] = rules.map(_ => new MqttRuleMetrics)

  @volatile private var client: Option[MqttClient] = None

  private val scheduler   = Executors.newSingleThreadScheduledExecutor(r => {
    val t = new Thread(r, "mqtt-bridge-debounce")
    t.setDaemon(true)
    t
  })
  private val pendingFuture: AtomicReference[ScheduledFuture[_]] =
    new AtomicReference[ScheduledFuture[_]](null.asInstanceOf[ScheduledFuture[_]])

  def start(): Unit = {
    val c    = new MqttClient(brokerUrl, clientId, new MemoryPersistence())
    val opts = new MqttConnectOptions()
    opts.setAutomaticReconnect(true)
    opts.setCleanSession(true)

    // Group rules by topicFilter and take the max QoS declared for each filter.
    val filterQos: Map[String, Int] = rules.groupBy(_.topicFilter).map {
      case (f, rs) => f -> rs.map(_.qos).max
    }

    c.setCallback(new MqttCallbackExtended {
      override def connectComplete(reconnect: Boolean, uri: String): Unit =
        filterQos.foreach { case (f, q) => Try(c.subscribe(f, q)) }
      override def connectionLost(cause: Throwable): Unit = ()
      override def messageArrived(topic: String, msg: MqttMessage): Unit =
        handleMessage(topic, msg)
      override def deliveryComplete(token: IMqttDeliveryToken): Unit = ()
    })
    c.connect(opts)
    filterQos.foreach { case (f, q) => Try(c.subscribe(f, q)) }
    client = Some(c)
  }

  def stop(): Unit = {
    val f = pendingFuture.getAndSet(null)
    if (f != null) f.cancel(false)
    client.foreach { c => Try(c.disconnect()); Try(c.close()) }
    client = None
  }

  def isConnected: Boolean = client.exists(_.isConnected)

  def injectMessage(topic: String, payload: String, retained: Boolean = false): Unit = {
    val msg = new MqttMessage(payload.getBytes("UTF-8"))
    msg.setRetained(retained)
    handleMessage(topic, msg)
  }

  // ── Per-message dispatch ─────────────────────────────────────────────────────

  private def handleMessage(topic: String, msg: MqttMessage): Unit = {
    stats.messagesReceived.incrementAndGet()
    val payload = new String(msg.getPayload, "UTF-8")

    val matched: Vector[(MqttMappingRule, MqttRuleMetrics, Int)] =
      rules.zip(metrics).zipWithIndex.collect {
        case ((r, m), i) if topicMatches(r.topicFilter, topic) => (r, m, i)
      }

    if (matched.isEmpty) { stats.messagesUnmatched.incrementAndGet(); return }

    var anyImmediate = false
    var minDebounce  = Long.MaxValue

    matched.foreach { case (rule, ruleMetrics, _) =>
      if (msg.isRetained && !rule.acceptRetained) {
        stats.messagesRetainedDropped.incrementAndGet()
      } else {
        ruleMetrics.received.incrementAndGet()
        ruleMetrics.lastMessageAtMs.set(System.currentTimeMillis())
        decodeAndIngest(rule, ruleMetrics, topic, payload)
        rule.pushMode match {
          case "immediate" => anyImmediate = true
          case "debounced" => if (rule.debounceMs < minDebounce) minDebounce = rule.debounceMs
          case _           => // manual — no push
        }
      }
    }

    if (anyImmediate) {
      val f = pendingFuture.getAndSet(null)
      if (f != null) f.cancel(false)
      Try(onPushTrigger())
      stats.pushesTriggered.incrementAndGet()
    } else if (minDebounce != Long.MaxValue) {
      schedulePush(minDebounce)
    }
  }

  private def decodeAndIngest(rule: MqttMappingRule, ruleMetrics: MqttRuleMetrics,
                               topic: String, payload: String): Unit = {
    extractValues(rule, payload) match {
      case Left(err) =>
        ruleMetrics.noteError(topic, err)
        stats.messagesRejected.incrementAndGet()
      case Right(raw) =>
        val normalized = raw.map(v => normalizeValue(rule, v))
        if (normalized.exists(v => v.isNaN || v.isInfinite)) {
          ruleMetrics.noteError(topic, "value not finite after normalize")
          stats.messagesRejected.incrementAndGet()
        } else if (normalized.length != rule.regionLength) {
          ruleMetrics.noteError(topic,
            s"transformed value count ${normalized.length} != region.length ${rule.regionLength}")
          stats.messagesRejected.incrementAndGet()
        } else {
          val sensorId = resolveSensorId(rule.sensorIdTemplate, rule.topicFilter, topic)
          Try(onIngest(sensorId, rule.regionOffset, rule.regionLength, normalized, rule.ttlMs, topic, rule.id))
            .failed.foreach(e => ruleMetrics.noteError(topic, s"ingest error: ${e.getMessage}"))
          ruleMetrics.mapped.incrementAndGet()
          stats.messagesMapped.incrementAndGet()
        }
    }
  }

  private def schedulePush(debounceMs: Long): Unit = {
    val existing = pendingFuture.get()
    if (existing != null) existing.cancel(false)
    val f = scheduler.schedule(new Runnable {
      override def run(): Unit = {
        Try(onPushTrigger())
        stats.pushesTriggered.incrementAndGet()
      }
    }, debounceMs, TimeUnit.MILLISECONDS)
    pendingFuture.set(f)
  }

  // ── Extraction ───────────────────────────────────────────────────────────────

  private def extractValues(rule: MqttMappingRule, payload: String): Either[String, Vector[Double]] =
    rule.extractType match {
      case "json" =>
        parse(payload) match {
          case Left(e)  => Left(s"json parse: ${e.message}")
          case Right(j) =>
            val node = rule.extractPointer match {
              case None      => j
              case Some(ptr) => navigatePointer(j, ptr)
            }
            jsonToDoubles(node) match {
              case None => Left(s"json pointer ${rule.extractPointer.getOrElse("")} not found or not numeric")
              case Some(vs) => Right(vs)
            }
        }
      case "csv-float" =>
        val all = payload.trim.split("[\\s,]+").filter(_.nonEmpty).flatMap(s => Try(s.toDouble).toOption)
        rule.extractIndex match {
          case None =>
            if (all.isEmpty) Left("csv-float: no numeric tokens")
            else Right(all.toVector)
          case Some(i) =>
            if (i < 0 || i >= all.length) Left(s"csv index $i out of range (have ${all.length})")
            else Right(Vector(all(i)))
        }
      case "single-float" | "raw" =>
        Try(payload.trim.toDouble).toOption match {
          case None    => Left(s"cannot parse '$payload' as float")
          case Some(v) => Right(Vector(v))
        }
      case other =>
        Left(s"unknown extract.type: $other")
    }

  private def jsonToDoubles(j: Json): Option[Vector[Double]] =
    if (j.isNull) None
    else j.asNumber.map(n => Vector(n.toDouble))
      .orElse(j.asBoolean.map(b => Vector(if (b) 1.0 else 0.0)))
      .orElse(j.asString.flatMap(s => Try(s.toDouble).toOption).map(Vector(_)))
      .orElse(j.asArray.map(_.flatMap(e => e.asNumber.map(_.toDouble)).toVector).filter(_.nonEmpty))

  private def navigatePointer(root: Json, pointer: String): Json = {
    if (pointer.isEmpty || pointer == "/") return root
    val segments = pointer.stripPrefix("/").split("/", -1)
    segments.foldLeft(root) { (j, key) =>
      j.asObject.flatMap(_(key))
        .orElse(key.toIntOption.flatMap(i => j.asArray.flatMap(_.lift(i))))
        .getOrElse(Json.Null)
    }
  }

  // ── Normalization ────────────────────────────────────────────────────────────

  private def normalizeValue(rule: MqttMappingRule, v: Double): Double = {
    val n = rule.normalizeMode match {
      case "minmax" =>
        val range = rule.normalizeMax - rule.normalizeMin
        if (range == 0.0) 0.0 else (v - rule.normalizeMin) / range
      case "linear" =>
        v * rule.normalizeScale + rule.normalizeOffset
      case "band" =>
        if (v >= rule.normalizeMin && v <= rule.normalizeMax) 1.0 else 0.0
      case _ =>
        v  // passthrough
    }
    if (rule.clamp) n.max(0.0).min(1.0) else n
  }

  // ── Topic matching ───────────────────────────────────────────────────────────

  private def topicMatches(filter: String, topic: String): Boolean = {
    if (filter == "#") return true
    val fp = filter.split("/", -1)
    val tp = topic.split("/", -1)
    if (fp.last == "#")
      tp.length >= fp.length - 1 && fp.init.zip(tp).forall { case (f, t) => f == "+" || f == t }
    else
      fp.length == tp.length && fp.zip(tp).forall { case (f, t) => f == "+" || f == t }
  }

  // ── sensorIdTemplate interpolation ──────────────────────────────────────────

  private def resolveSensorId(template: String, filter: String, topic: String): String = {
    if (!template.contains("{")) return template
    val captures = filter.split("/", -1).zip(topic.split("/", -1))
      .collect { case (f, t) if f == "+" => t }
    captures.zipWithIndex.foldLeft(template) { case (s, (seg, i)) =>
      s.replace(s"{${i + 1}}", seg)
    }
  }

  // ── Serialisation for /api/mqtt/mappings ────────────────────────────────────

  def toJson: Json = Json.obj(
    "mappings" -> Json.fromValues(rules.zip(metrics).map { case (r, m) =>
      Json.obj(
        "id"               -> Json.fromString(r.id),
        "topicFilter"      -> Json.fromString(r.topicFilter),
        "sensorIdTemplate" -> Json.fromString(r.sensorIdTemplate),
        "region"           -> Json.obj(
          "offset" -> Json.fromInt(r.regionOffset),
          "length" -> Json.fromInt(r.regionLength),
        ),
        "extract" -> {
          val base = List(
            "type" -> Json.fromString(r.extractType),
          ) ++
            r.extractPointer.map(p => "pointer" -> Json.fromString(p)).toList ++
            r.extractIndex.map(i => "index" -> Json.fromInt(i)).toList
          Json.obj(base: _*)
        },
        "normalize" -> Json.obj(
          "mode"   -> Json.fromString(r.normalizeMode),
          "min"    -> Json.fromDoubleOrNull(r.normalizeMin),
          "max"    -> Json.fromDoubleOrNull(r.normalizeMax),
          "scale"  -> Json.fromDoubleOrNull(r.normalizeScale),
          "offset" -> Json.fromDoubleOrNull(r.normalizeOffset),
          "clamp"  -> Json.fromBoolean(r.clamp),
        ),
        "ttlMs"          -> Json.fromLong(r.ttlMs),
        "qos"            -> Json.fromInt(r.qos),
        "acceptRetained" -> Json.fromBoolean(r.acceptRetained),
        "pushMode"       -> Json.fromString(r.pushMode),
        "debounceMs"     -> Json.fromLong(r.debounceMs),
        "counters"       -> m.toJson,
      )
    })
  )
}

// ── Registry parser + env-driven factory ─────────────────────────────────────

object MqttBridge {

  val DEFAULT_BROKER_URL = "mqtt://yuma.lateraledge.cloud:1883"

  def parseRegistry(body: Json): Either[String, Vector[MqttMappingRule]] = {
    val defaults = body.hcursor.downField("defaults").as[Json].getOrElse(Json.obj())
    val arr      = body.hcursor.downField("mappings").as[Vector[Json]].getOrElse(Vector.empty)
    if (arr.isEmpty) return Left("mappings array is empty — at least one rule is required")
    val rules = arr.flatMap(MqttMappingRule.fromJson(_, defaults))
    if (rules.isEmpty) Left("no valid mapping rules found — each rule needs id, topicFilter, sensorIdTemplate")
    else Right(rules)
  }

  def validateOverlaps(rules: Vector[MqttMappingRule], allowOverlap: Boolean = false): Vector[String] = {
    if (allowOverlap) return Vector.empty
    val warnings = for {
      i <- rules.indices
      j <- (i + 1) until rules.size
      a = rules(i); b = rules(j)
      aEnd = a.regionOffset + a.regionLength
      bEnd = b.regionOffset + b.regionLength
      if a.regionOffset < bEnd && b.regionOffset < aEnd
    } yield s"""mappings "${a.id}" [${a.regionOffset},$aEnd) and "${b.id}" [${b.regionOffset},$bEnd) overlap"""
    warnings.toVector
  }

  case class EnvConfig(brokerUrl: String, clientId: String, rules: Vector[MqttMappingRule], allowOverlap: Boolean)

  def fromEnvironment(): Option[EnvConfig] = {
    val env = sys.env
    if (env.get("MQTT_DISABLED").exists(v => v == "1" || v == "true")) return None
    if (env.get("MQTT_BROKER_URL").contains("")) return None

    val brokerUrl: String = env.get("MQTT_BROKER_URL") match {
      case Some(u) if u.nonEmpty => u
      case _ =>
        env.get("MQTT_BROKER_HOST") match {
          case None | Some("") => DEFAULT_BROKER_URL
          case Some(host) =>
            val port = env.getOrElse("MQTT_BROKER_PORT", "1883")
            s"mqtt://$host:$port"
        }
    }

    val clientId = env.getOrElse("MQTT_CLIENT_ID", "reality-engine-pe-scala")

    val rawJson: Option[String] = env.get("MQTT_MAPPINGS_FILE").filter(_.nonEmpty).flatMap { path =>
      Try(scala.io.Source.fromFile(path).mkString).toOption
    }.orElse(env.get("MQTT_MAPPINGS_JSON").filter(_.nonEmpty))

    rawJson match {
      case None =>
        System.err.println(s"[mqtt-bridge] MQTT_BROKER_URL set but no MQTT_MAPPINGS_FILE/MQTT_MAPPINGS_JSON — bridge disabled")
        None
      case Some(raw) =>
        parse(raw) match {
          case Left(e) =>
            System.err.println(s"[mqtt-bridge] MQTT mappings parse error: ${e.message} — bridge disabled")
            None
          case Right(json) =>
            parseRegistry(json) match {
              case Left(e) =>
                System.err.println(s"[mqtt-bridge] MQTT mappings invalid: $e — bridge disabled")
                None
              case Right(rules) =>
                val allowOverlap = env.get("MQTT_ALLOW_REGION_OVERLAP").exists(v => v == "1" || v == "true")
                validateOverlaps(rules, allowOverlap).foreach { w =>
                  System.err.println(s"[mqtt-bridge] warning: $w")
                }
                Some(EnvConfig(brokerUrl, clientId, rules, allowOverlap))
            }
        }
    }
  }
}
