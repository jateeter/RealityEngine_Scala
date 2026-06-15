package com.realityengine.perception.mqtt

import io.circe.Json
import io.circe.parser.parse
import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import scala.util.Try
import java.util.concurrent.atomic.AtomicLong

// ── Mapping rule (parsed from the registry JSON) ──────────────────────────────

case class MqttMappingRule(
  id:               String,
  topicFilter:      String,
  sensorIdTemplate: String,
  regionOffset:     Int,
  regionLength:     Int,
  extractType:      String,
  extractPointer:   Option[String],
  normalizeMode:    String,
  normalizeMin:     Double,
  normalizeMax:     Double,
  clamp:            Boolean,
  ttlMs:            Long,
  pushMode:         String,
)

object MqttMappingRule {
  def fromJson(m: Json, defaults: Json): Option[MqttMappingRule] = {
    val c = m.hcursor
    val dc = defaults.hcursor
    for {
      id          <- c.get[String]("id").toOption.filter(_.nonEmpty)
      topicFilter <- c.get[String]("topicFilter").toOption.filter(_.nonEmpty)
      sensorTpl   <- c.get[String]("sensorIdTemplate").toOption.filter(_.nonEmpty)
    } yield {
      val region  = c.downField("region")
      val extract = c.downField("extract")
      val norm    = c.downField("normalize")
      MqttMappingRule(
        id               = id,
        topicFilter      = topicFilter,
        sensorIdTemplate = sensorTpl,
        regionOffset     = region.downField("offset").as[Int].getOrElse(0),
        regionLength     = region.downField("length").as[Int].getOrElse(1).max(1),
        extractType      = extract.downField("type").as[String].getOrElse("passthrough"),
        extractPointer   = extract.downField("pointer").as[String].toOption,
        normalizeMode    = norm.downField("mode").as[String].getOrElse("passthrough"),
        normalizeMin     = norm.downField("min").as[Double].getOrElse(0.0),
        normalizeMax     = norm.downField("max").as[Double].getOrElse(1.0),
        clamp            = norm.downField("clamp").as[Boolean].getOrElse(false),
        ttlMs            = c.get[Long]("ttlMs").orElse(dc.get[Long]("ttlMs")).getOrElse(30000L),
        pushMode         = c.get[String]("pushMode").orElse(dc.get[String]("pushMode")).getOrElse("debounced"),
      )
    }
  }
}

// ── Bridge stats (atomic — called from Paho I/O thread) ───────────────────────

class MqttBridgeStats {
  val messagesReceived:  AtomicLong = new AtomicLong(0)
  val messagesMapped:    AtomicLong = new AtomicLong(0)
  val messagesRejected:  AtomicLong = new AtomicLong(0)
  val messagesUnmatched: AtomicLong = new AtomicLong(0)
  val pushesTriggered:   AtomicLong = new AtomicLong(0)
}

// ── MqttBridge ────────────────────────────────────────────────────────────────
//
// Wraps Eclipse Paho.  connect() / subscribe() are called in start(); the
// Paho thread delivers messageArrived() callbacks which run the extract →
// normalise → ingest pipeline and optionally fire the push trigger.
//
// All PerceptionEngine methods are synchronized so calling them from the
// Paho thread is safe.

class MqttBridge(
  val brokerUrl:  String,
  val clientId:   String,
  val rules:      Vector[MqttMappingRule],
  onIngest:       (String, Int, Int, Vector[Double], Long, String, String) => Unit,
  onPushTrigger:  () => Unit,
) {
  val stats = new MqttBridgeStats

  @volatile private var client: Option[MqttClient] = None

  def start(): Unit = {
    val c = new MqttClient(brokerUrl, clientId, new MemoryPersistence())
    val opts = new MqttConnectOptions()
    opts.setAutomaticReconnect(true)
    opts.setCleanSession(true)
    val filters = rules.map(_.topicFilter).distinct

    c.setCallback(new MqttCallbackExtended {
      override def connectComplete(reconnect: Boolean, uri: String): Unit =
        filters.foreach(f => Try(c.subscribe(f, 1)))
      override def connectionLost(cause: Throwable): Unit = ()
      override def messageArrived(topic: String, msg: MqttMessage): Unit =
        handleMessage(topic, new String(msg.getPayload))
      override def deliveryComplete(token: IMqttDeliveryToken): Unit = ()
    })
    c.connect(opts)
    filters.foreach(f => Try(c.subscribe(f, 1)))
    client = Some(c)
  }

  def stop(): Unit = {
    client.foreach { c => Try(c.disconnect()); Try(c.close()) }
    client = None
  }

  def isConnected: Boolean = client.exists(_.isConnected)

  // ── Message dispatch ────────────────────────────────────────────────────────

  private def handleMessage(topic: String, payload: String): Unit = {
    stats.messagesReceived.incrementAndGet()
    val matched = rules.filter(r => topicMatches(r.topicFilter, topic))
    if (matched.isEmpty) { stats.messagesUnmatched.incrementAndGet(); return }
    matched.foreach { rule =>
      Try {
        val raw        = extractValue(rule, payload)
        val normalized = normalizeValue(rule, raw)
        val sensorId   = resolveSensorId(rule.sensorIdTemplate, rule.topicFilter, topic)
        val values     = Vector.fill(rule.regionLength)(normalized)
        onIngest(sensorId, rule.regionOffset, rule.regionLength, values, rule.ttlMs, topic, rule.id)
        stats.messagesMapped.incrementAndGet()
        if (rule.pushMode != "manual") {
          onPushTrigger()
          stats.pushesTriggered.incrementAndGet()
        }
      }.recover { case _ => stats.messagesRejected.incrementAndGet() }
    }
  }

  private def topicMatches(filter: String, topic: String): Boolean = {
    if (filter == "#") return true
    val fp = filter.split("/", -1)
    val tp = topic.split("/", -1)
    if (fp.last == "#")
      tp.length >= fp.length - 1 && fp.init.zip(tp).forall { case (f, t) => f == "+" || f == t }
    else
      fp.length == tp.length && fp.zip(tp).forall { case (f, t) => f == "+" || f == t }
  }

  private def resolveSensorId(template: String, filter: String, topic: String): String = {
    val wildcards = filter.split("/", -1).zip(topic.split("/", -1))
      .collect { case (f, t) if f == "+" => t }
    wildcards.zipWithIndex.foldLeft(template) { case (s, (seg, i)) =>
      s.replace(s"{${i + 1}}", seg)
    }
  }

  private def extractValue(rule: MqttMappingRule, payload: String): Double =
    rule.extractType match {
      case "json" =>
        val json = parse(payload).getOrElse(Json.Null)
        rule.extractPointer.fold(json.as[Double].getOrElse(0.0)) { ptr =>
          ptr.stripPrefix("/").split("/", -1).foldLeft(json) { (j, k) =>
            j.hcursor.downField(k).as[Json].getOrElse(Json.Null)
          }.as[Double].getOrElse(0.0)
        }
      case "csv-float" =>
        payload.trim.split(",", 2).headOption
          .flatMap(s => Try(s.trim.toDouble).toOption).getOrElse(0.0)
      case _ =>
        Try(payload.trim.toDouble).getOrElse(0.0)
    }

  private def normalizeValue(rule: MqttMappingRule, v: Double): Double =
    rule.normalizeMode match {
      case "minmax" =>
        val range = rule.normalizeMax - rule.normalizeMin
        val n = if (range == 0.0) 0.0 else (v - rule.normalizeMin) / range
        if (rule.clamp) n.max(0.0).min(1.0) else n
      case _ =>
        if (rule.clamp) v.max(0.0).min(1.0) else v
    }
}

// ── Registry parser ───────────────────────────────────────────────────────────

object MqttBridge {
  def parseRegistry(body: Json): Either[String, Vector[MqttMappingRule]] = {
    val defaults = body.hcursor.downField("defaults").as[Json].getOrElse(Json.obj())
    val arr      = body.hcursor.downField("mappings").as[Vector[Json]].getOrElse(Vector.empty)
    if (arr.isEmpty) return Left("mappings array is empty — at least one rule is required")
    val rules = arr.flatMap(MqttMappingRule.fromJson(_, defaults))
    if (rules.isEmpty) Left("no valid mapping rules found — each rule needs id, topicFilter, sensorIdTemplate")
    else Right(rules)
  }
}
