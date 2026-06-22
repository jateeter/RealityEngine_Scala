package com.realityengine.perception.api

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.realityengine.perception.VectorAggregator
import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import com.realityengine.perception.models.PerceptionJsonCodecs._
import com.realityengine.perception.store.SourceStore
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import io.circe.syntax._
import sttp.client3._

import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.stream.OverflowStrategy
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import com.realityengine.perception.logging.{AuditConfig, AuditLogger}

class PerceptionRoutes(
  engine: PerceptionEngine,
  store: SourceStore,
  broadcastActor: ActorRef,
  realityEngineUrl: String,
  auditCfg: AuditConfig,
)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) {

  // Blocking sttp backend runs on calling thread (routes are already on a
  // dedicated blocking dispatcher when needed — see doPush).
  private val sttpBackend = HttpURLConnectionBackend()

  // RC-5: thread-safe timer and state refs
  private val autoTimer    = new AtomicReference[Option[akka.actor.Cancellable]](None)
  @volatile private var autoIntervalMs: Long = 1000L
  private val lastPush     = new AtomicReference[Option[Long]](None)
  // A-1: prevents push cycles from stacking when doPush takes longer than the interval
  private val pushInFlight = new AtomicBoolean(false)

  // In-memory dispatch ledger (ring buffer, capped at dispatchLedgerLimit entries)
  private val dispatchLedgerLimit = sys.env.get("TRIGGER_DISPATCH_LEDGER_LIMIT").flatMap(_.toIntOption).getOrElse(100)
  private val dispatchLedger      = new AtomicReference[Vector[Json]](Vector.empty)

  // In-memory push history (ring buffer, capped at pushHistoryLimit entries)
  private val pushHistoryLimit = sys.env.get("PUSH_HISTORY_LIMIT").flatMap(_.toIntOption).getOrElse(100)
  private val pushHistory      = new AtomicReference[Vector[Json]](Vector.empty)

  // MQTT bridge — boots from env vars at construction; also startable via POST /api/mqtt/enable.
  // mqttBrokerUrlRef is kept separately for the status endpoint display.
  import com.realityengine.perception.mqtt.{MqttBridge, MqttMappingRule}
  private val mqttBridgeRef    = new AtomicReference[Option[MqttBridge]](None)
  private val mqttBrokerUrlRef = new AtomicReference[Option[String]](None)

  private def bootstrapSummaryJson(created: Int, errors: Vector[String], machinesSeen: Int, skipped: Int): String = {
    val errorsJson = errors.asJson.noSpaces
    s"""{"created":$created,"errors":$errorsJson,"machinesSeen":$machinesSeen,"skipped":$skipped,"success":true}"""
  }

  private def bootstrapSourcesFromMachines(machines: Vector[Json]): (Int, Int) = {
    val machineIds = machines.flatMap(_.hcursor.get[String]("id").toOption).filter(_.nonEmpty).toSet

    engine.getSources.collect { case t: TestSourceConfig => (t.machineId, t.id) }
      .groupBy(_._1)
      .foreach { case (_, entries) =>
        if (entries.length > 1) entries.foreach { case (_, id) => engine.removeSource(id) }
      }

    engine.getSources.collect {
      case s: SensorSourceConfig if machineIds.contains(s.sensorId) && s.name.startsWith("Machine:") => s.id
    }.foreach(engine.removeSource)

    var created = 0
    var skipped = 0
    var existingMachineIds = engine.getSources.collect { case t: TestSourceConfig => t.machineId }.toSet

    machines.foreach { m =>
      val c = m.hcursor
      val machineId = c.get[String]("id").getOrElse("")
      val machineName = c.get[String]("name").getOrElse(machineId)
      val offsetOpt = c.downField("perceptualMapping").downField("input").get[Int]("offset").toOption
      val lengthOpt = c.downField("perceptualMapping").downField("input").get[Int]("length").toOption
      val inputSeqs = c.downField("metadata").downField("inputSequences")
        .as[Vector[Json]].getOrElse(Vector.empty)

      val segments = inputSeqs.flatMap { seq =>
        val seqName = seq.hcursor.get[String]("name").getOrElse("Test sequence")
        val vectors = seq.hcursor.downField("vectors").as[Vector[Vector[Double]]].getOrElse(Vector.empty)
        if (vectors.nonEmpty) Some((seqName, vectors, seq.hcursor.get[Boolean]("active").getOrElse(false))) else None
      }
      val inputs = segments.flatMap(_._2)

      (machineId.nonEmpty, offsetOpt, lengthOpt, existingMachineIds.contains(machineId), inputs.nonEmpty) match {
        case (true, Some(offset), Some(length), false, true) =>
          val label =
            if (segments.length == 1) segments.head._1
            else s"${segments.length} sequences"
          engine.addSource(TestSourceConfig(
            id           = s"test-$machineId",
            name         = s"$machineName / $label",
            region       = Region(offset, length),
            active       = segments.exists(_._3),
            machineId    = machineId,
            machineName  = machineName,
            sequenceName = label,
            inputs       = inputs,
            loop         = true,
          ))
          existingMachineIds = existingMachineIds + machineId
          created += 1
        case _ =>
          skipped += 1
      }
    }

    (created, skipped)
  }

  private def mqttIngest(sensorId: String, offset: Int, length: Int,
                          values: Vector[Double], ttlMs: Long,
                          topic: String, mappingId: String): Unit = {
    if (engine.findSensorBySensorId(sensorId).isEmpty)
      engine.addSource(com.realityengine.perception.models.SensorSourceConfig(
        id          = sensorId,
        name        = s"mqtt:$sensorId",
        region      = com.realityengine.perception.models.Region(offset, length),
        active      = true,
        sensorId    = sensorId,
        lastValue   = Vector.empty,
        lastUpdated = None,
        ttlMs       = ttlMs,
      ))
    engine.updateSensorValue(sensorId, values)
    broadcast(Json.obj(
      "type"      -> "mqtt-ingest".asJson,
      "payload"   -> Json.obj(
        "sensorId"  -> sensorId.asJson,
        "mappingId" -> mappingId.asJson,
        "topic"     -> topic.asJson,
        "offset"    -> offset.asJson,
        "length"    -> length.asJson,
        "values"    -> values.asJson,
        "ttlMs"     -> ttlMs.asJson,
        "timestamp" -> System.currentTimeMillis().asJson,
      ),
    ))
    broadcastState()
  }

  // Env-driven MQTT boot — mirrors RealityEngine_Manager and RealityEngine_CPP startup behaviour.
  MqttBridge.fromEnvironment().foreach { cfg =>
    val bridge = new MqttBridge(cfg.brokerUrl, cfg.clientId, cfg.rules, mqttIngest, () => doPush(), cfg.username, cfg.password)
    Try(bridge.start()) match {
      case scala.util.Failure(e) =>
        System.err.println(s"[mqtt-bridge] failed to start at boot: ${e.getMessage}")
      case scala.util.Success(_) =>
        mqttBridgeRef.set(Some(bridge))
        mqttBrokerUrlRef.set(Some(cfg.brokerUrl))
        println(s"[mqtt-bridge] started — broker=${cfg.brokerUrl} mappings=${cfg.rules.size}")
    }
  }

  // SSE broadcast hub — mirrors /ws but as Server-Sent Events for /api/events
  private val (ssePEQueue, ssePEBroadcast) = {
    Source.queue[Json](16, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[Json](bufferSize = 1))(Keep.both)
      .run()
  }

  // ── Integration configuration ─────────────────────────────────────────────

  private val ollamaBaseUrl      = sys.env.getOrElse("OLLAMA_BASE_URL",   "http://localhost:11434")
  private val ollamaModel        = sys.env.getOrElse("OLLAMA_MODEL",      "llama3.2")
  private val openAiBaseUrl      = sys.env.getOrElse("OPENAI_BASE_URL",   "https://api.openai.com/v1")
  private val openAiApiKey       = sys.env.get("OPENAI_API_KEY")
  private val openAiModel        = sys.env.getOrElse("OPENAI_MODEL",      "gpt-4o")
  private val acpEnabled         = sys.env.get("ACP_ENABLED").forall(v => Set("true", "1", "yes").contains(v.toLowerCase))
  private val acpEndpointUrl     = sys.env.get("OPENCLAW_GATEWAY_URL")
    .orElse(sys.env.get("ACP_GATEWAY_URL"))
    .orElse(sys.env.get("ACP_ENDPOINT_URL"))
  private val acpSessionKey      = sys.env.get("OPENCLAW_ACP_SESSION").orElse(sys.env.get("ACP_SESSION_KEY"))
  private val acpAgentId         = sys.env.get("ACP_TARGET_AGENT")
    .orElse(sys.env.get("ACP_AGENT_ID"))
    .getOrElse("openclaw")
  private val acpCompletionSourceMappingId = sys.env.getOrElse("ACP_COMPLETION_SOURCE_MAPPING_ID", "acp-openclaw-completion")
  private val hkBridgeToken      = sys.env.get("HEALTHKIT_BRIDGE_TOKEN")
  private val hkBridgeId         = sys.env.getOrElse("HEALTHKIT_BRIDGE_ID", "healthkit-ios-bridge")
  private val hkDefaultMappingId = sys.env.getOrElse("HEALTHKIT_DEFAULT_SOURCE_MAPPING_ID", "healthkit-activity")
  private val hkEnabled          = sys.env.get("HEALTHKIT_ENABLED").exists(v => v == "true" || v == "1")
  private val ckBridgeToken      = sys.env.get("CAREKIT_BRIDGE_TOKEN")
  private val ckBridgeId         = sys.env.getOrElse("CAREKIT_BRIDGE_ID", "carekit-ios-bridge")
  private val ckEnabled          = sys.env.get("CAREKIT_ENABLED").exists(v => v == "true" || v == "1")
  private val ckDefaultMappingId = sys.env.getOrElse("CAREKIT_DEFAULT_SOURCE_MAPPING_ID", "carekit-task")
  private val localAiApiUrl      = sys.env.getOrElse("LOCAL_AI_API_URL",  "http://localhost:4000")
  private val localAiMachinesDir = sys.env.get("LOCAL_AI_MACHINES_DIR")

  // Bundled yuma-agriculture demo mapping registry — mirrors
  // RealityEngine_CPP/config/mqtt-mappings.yuma-agriculture.json.
  // Uses band normalization so each cell emits 1.0 (in range) or 0.0 (out of
  // range), producing the 4-bit status pattern the agriculture machines expect.
  private val mqttExampleMappings: Json = io.circe.parser.parse("""
    {"version":"1.0","defaults":{"ttlMs":60000,"qos":0,"acceptRetained":true,"pushMode":"debounced","debounceMs":500},"mappings":[
      {"id":"agx001-ph-ok",        "topicFilter":"LATERAL/WaterSuite/DEV0000001/SensorReadings/v1",     "sensorIdTemplate":"agx001.water.ph.ok",        "region":{"offset":40, "length":1},"extract":{"type":"json","pointer":"/data/wpH"},        "normalize":{"mode":"band","min":6.5,  "max":8.5 }},
      {"id":"agx001-ec-ok",        "topicFilter":"LATERAL/WaterSuite/DEV0000001/SensorReadings/v1",     "sensorIdTemplate":"agx001.water.ec.ok",        "region":{"offset":41, "length":1},"extract":{"type":"json","pointer":"/data/wEC"},         "normalize":{"mode":"band","min":0.5,  "max":3.0 }},
      {"id":"agx001-orp-ok",       "topicFilter":"LATERAL/WaterSuite/DEV0000001/SensorReadings/v1",     "sensorIdTemplate":"agx001.water.orp.ok",       "region":{"offset":42, "length":1},"extract":{"type":"json","pointer":"/data/wORP"},        "normalize":{"mode":"band","min":200,  "max":600 }},
      {"id":"agx001-turbidity-ok", "topicFilter":"LATERAL/WaterSuite/DEV0000001/SensorReadings/v1",     "sensorIdTemplate":"agx001.water.turbidity.ok", "region":{"offset":43, "length":1},"extract":{"type":"json","pointer":"/data/wTurbidity"}, "normalize":{"mode":"band","min":0,    "max":100 }},
      {"id":"agx005-do-ok",        "topicFilter":"LATERAL/DOSuite/DEV0000017/SensorReadings/v1",        "sensorIdTemplate":"agx005.do.level.ok",        "region":{"offset":84, "length":1},"extract":{"type":"json","pointer":"/data/wDO"},         "normalize":{"mode":"band","min":5,    "max":25  }},
      {"id":"agx005-do-temp-ok",   "topicFilter":"LATERAL/DOSuite/DEV0000017/SensorReadings/v1",        "sensorIdTemplate":"agx005.do.temp.ok",         "region":{"offset":85, "length":1},"extract":{"type":"json","pointer":"/data/wDOTemp"},     "normalize":{"mode":"band","min":60,   "max":85  }},
      {"id":"agx005-do-watch",     "topicFilter":"LATERAL/DOSuite/DEV0000017/SensorReadings/v1",        "sensorIdTemplate":"agx005.do.watch",           "region":{"offset":86, "length":1},"extract":{"type":"json","pointer":"/data/wDO"},         "normalize":{"mode":"band","min":3,    "max":5   }},
      {"id":"agx005-temp-watch",   "topicFilter":"LATERAL/DOSuite/DEV0000017/SensorReadings/v1",        "sensorIdTemplate":"agx005.do.temp.watch",      "region":{"offset":87, "length":1},"extract":{"type":"json","pointer":"/data/wDOTemp"},     "normalize":{"mode":"band","min":85,   "max":95  }},
      {"id":"agx026-temp-ok",      "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx026.temp.ok",            "region":{"offset":184,"length":1},"extract":{"type":"json","pointer":"/data/aTemp"},       "normalize":{"mode":"band","min":65,   "max":85  }},
      {"id":"agx026-humidity-ok",  "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx026.humidity.ok",        "region":{"offset":185,"length":1},"extract":{"type":"json","pointer":"/data/aHum"},        "normalize":{"mode":"band","min":40,   "max":70  }},
      {"id":"agx026-temp-watch",   "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx026.temp.watch",         "region":{"offset":186,"length":1},"extract":{"type":"json","pointer":"/data/aTemp"},       "normalize":{"mode":"band","min":85,   "max":95  }},
      {"id":"agx026-humidity-watch","topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",  "sensorIdTemplate":"agx026.humidity.watch",     "region":{"offset":187,"length":1},"extract":{"type":"json","pointer":"/data/aHum"},        "normalize":{"mode":"band","min":20,   "max":40  }},
      {"id":"agx032-co2-ok",       "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx032.co2.ok",             "region":{"offset":228,"length":1},"extract":{"type":"json","pointer":"/data/aCO2"},        "normalize":{"mode":"band","min":600,  "max":1500}},
      {"id":"agx032-co2-watch",    "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx032.co2.watch",          "region":{"offset":229,"length":1},"extract":{"type":"json","pointer":"/data/aCO2"},        "normalize":{"mode":"band","min":1500, "max":3000}},
      {"id":"agx032-co2-danger",   "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx032.co2.danger",         "region":{"offset":230,"length":1},"extract":{"type":"json","pointer":"/data/aCO2"},        "normalize":{"mode":"band","min":3000, "max":5000}},
      {"id":"agx032-temp-ok",      "topicFilter":"LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1",   "sensorIdTemplate":"agx032.temp.ok",            "region":{"offset":231,"length":1},"extract":{"type":"json","pointer":"/data/aTemp"},       "normalize":{"mode":"band","min":65,   "max":85  }}
    ]}""").getOrElse(Json.obj())

  // ── Source mapping registry ───────────────────────────────────────────────

  private val sourceMappings: scala.collection.concurrent.TrieMap[String, Json] = {
    val m       = new scala.collection.concurrent.TrieMap[String, Json]()
    val cfgPath = sys.env.getOrElse("INTEGRATIONS_CONFIG", "config/integrations.json")
    try {
      val src  = scala.io.Source.fromFile(cfgPath)
      val text = try src.mkString finally src.close()
      io.circe.parser.parse(text).toOption.foreach { json =>
        json.hcursor.downField("sourceMappings").as[Vector[Json]].getOrElse(Vector.empty).foreach { sm =>
          sm.hcursor.get[String]("id").toOption.foreach(id => m.put(id, sm))
        }
      }
    } catch { case _: Exception => }
    m
  }

  // ── Integration helpers ───────────────────────────────────────────────────

  private def resolveTemplate(template: String, tokens: Map[String, String]): String =
    tokens.foldLeft(template) { case (t, (k, v)) => t.replace(s"{$k}", v) }

  private def ingestCompletion(body: Json): Json = {
    val agentId = body.hcursor.get[String]("agent").toOption
      .orElse(body.hcursor.get[String]("agentId").toOption)
      .getOrElse(acpAgentId)

    // Resolve sensorId: explicit field wins, then sourceMappingId template, then fallback.
    val smId = body.hcursor.get[String]("sourceMappingId").toOption.filter(_.nonEmpty)
      .orElse(Some(acpCompletionSourceMappingId))
    val mapping = smId.flatMap(sourceMappings.get)

    val sensorId = body.hcursor.get[String]("sensorId").toOption.filter(_.nonEmpty).getOrElse {
      mapping.flatMap(_.hcursor.get[String]("sensorIdTemplate").toOption)
        .map(tpl => resolveTemplate(tpl, Map("agent" -> agentId)))
        .getOrElse("completion_agent")
    }

    val values = body.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector(1.0))

    // Register the sensor source with the correct perceptual-space region on first use.
    if (engine.findSensorBySensorId(sensorId).isEmpty) {
      mapping.foreach { m =>
        for {
          offset <- m.hcursor.downField("region").get[Int]("offset").toOption
          length <- m.hcursor.downField("region").get[Int]("length").toOption
        } {
          val ttl = m.hcursor.get[Long]("ttlMs").getOrElse(300000L)
          engine.addSource(com.realityengine.perception.models.SensorSourceConfig(
            id          = sensorId,
            name        = m.hcursor.get[String]("name").getOrElse(s"acp:$sensorId"),
            region      = com.realityengine.perception.models.Region(offset, length),
            active      = true,
            sensorId    = sensorId,
            lastValue   = Vector.empty,
            lastUpdated = None,
            ttlMs       = ttl,
          ))
        }
      }
    }

    engine.updateSensorValue(sensorId, values)
    val ts     = System.currentTimeMillis()
    val record = Json.obj(
      "id"              -> s"compl-$ts".asJson,
      "type"            -> "completion".asJson,
      "timestamp"       -> ts.asJson,
      "sensorId"        -> sensorId.asJson,
      "sourceMappingId" -> smId.asJson,
      "body"            -> body
    )
    dispatchLedger.updateAndGet(l => (l :+ record).takeRight(dispatchLedgerLimit))
    broadcast(Json.obj("type" -> "agent.completion.received".asJson, "record" -> record))
    record
  }

  private def probeHttp(url: String): (Boolean, String) =
    try {
      val resp = basicRequest.get(uri"$url").response(asString).send(sttpBackend)
      (resp.isSuccess, resp.body.fold(identity, identity))
    } catch { case e: Exception => (false, e.getMessage) }

  // ── Auto-push scheduler ───────────────────────────────────────────────────

  def startAuto(intervalMs: Long): Unit = {
    stopAuto()
    autoIntervalMs = intervalMs
    import scala.concurrent.duration._
    val delay = intervalMs.millis
    autoTimer.set(Some(system.scheduler.scheduleWithFixedDelay(delay, delay) { () =>
      if (pushInFlight.compareAndSet(false, true)) {
        doPush().onComplete { _ => pushInFlight.set(false) }
      }
    }))
  }

  def stopAuto(): Unit = {
    autoTimer.getAndSet(None).foreach(_.cancel())
  }

  def isAutoRunning: Boolean = autoTimer.get().isDefined

  // ── Push ──────────────────────────────────────────────────────────────────

  def doPush(): Future[PushResult] = Future {
    val vector  = engine.assembleVector()
    val algoStr = MatchAlgorithm.asString(engine.matchAlgorithm)

    val bodyJson = Json.obj(
      "vector"                 -> vector.asJson,
      "matchAlgorithm"         -> algoStr.asJson,
      "includeMachineResults"  -> true.asJson,
      "includePerceptualSpace" -> true.asJson,
    ).noSpaces

    // Push directly to the Reality Engine — VB is a passive SSE observer, not in the path
    val request = basicRequest
      .post(uri"$realityEngineUrl/api/perceive")
      .contentType("application/json")
      .body(bodyJson)
      .response(asString)

    request.send(sttpBackend) match {
      case resp if resp.isSuccess =>
        engine.advance()
        val ts = System.currentTimeMillis()
        lastPush.set(Some(ts))

        val parsed = resp.body.toOption
          .flatMap(b => io.circe.parser.parse(b).toOption)
          .getOrElse(Json.Null)

        // RE returns SimulationStep directly (perceptualSpace at top level).
        // Aggregate gated machine CES output vectors from machineResults into
        // the perceptual space before updating the persistent vector — this is
        // the PE-side gate that ensures machine outputs are explicitly merged
        // into the next InputSpaceVector before the next PE→RE→PE cycle.
        parsed.hcursor.get[Vector[Double]]("perceptualSpace") match {
          case Right(ps) if ps.nonEmpty =>
            val machineResults = parsed.hcursor.downField("machineResults").focus.getOrElse(Json.Null)
            val nextPs         = VectorAggregator.aggregate(ps, machineResults)
            engine.updateFromPerceptualSpace(nextPs)
          case _ =>
        }

        val machineResults = parsed.hcursor.downField("machineResults").focus.getOrElse(Json.Null)
        val stepJson = Some(parsed.deepMerge(Json.obj(
          "mergeBatch" -> Json.arr(VectorAggregator.mergeBatch(machineResults): _*)
        )))

        val result = PushResult(
          success    = true,
          step       = stepJson,
          timestamp  = ts,
          globalStep = engine.globalStep,
          error      = None,
        )
        broadcastState()
        broadcast(Json.obj("type" -> "push-result".asJson).deepMerge(encodePushResult(result)))

        result

      case resp =>
        val msg = resp.body.fold(identity, identity)
        System.err.println(s"[doPush] Push failed (step ${engine.globalStep}): $msg")
        val result = PushResult(success = false, step = None, timestamp = System.currentTimeMillis(),
          globalStep = engine.globalStep, error = Some(msg))
        broadcast(Json.obj("type" -> "push-result".asJson).deepMerge(encodePushResult(result)))
        result
    }
  }(system.dispatchers.lookup("blocking-io-dispatcher"))

  // ── Broadcast helpers ─────────────────────────────────────────────────────

  private def broadcastState(): Unit =
    broadcast(Json.obj(
      "type"  -> "state-update".asJson,
      "state" -> encodeEngineState(engine.getState(lastPush.get(), AutoConfig(isAutoRunning, autoIntervalMs))),
    ))

  private def broadcast(json: Json): Unit = {
    broadcastActor ! WsBroadcastActor.BroadcastMsg(json.noSpaces)
    ssePEQueue.offer(json)
    ()
  }

  private def saveAndBroadcast(): Future[Unit] = Future {
    store.save(engine.getSources)
    broadcastState()
  }(system.dispatchers.lookup("blocking-io-dispatcher"))

  private def resetAndBroadcast(): Unit = {
    engine.reset()
    lastPush.set(None)
    broadcastState()
  }

  // ── Routes ────────────────────────────────────────────────────────────────

  val routes: Route = AuditLogger.directive(auditCfg) { concat(

    // ── Health ──────────────────────────────────────────────────────────────
    path("api" / "health") {
      get { complete(Json.obj("status" -> "healthy".asJson)) }
    },

    // ── State ───────────────────────────────────────────────────────────────
    path("api" / "state") {
      get { complete(engine.getState(lastPush.get(), AutoConfig(isAutoRunning, autoIntervalMs))) }
    },

    // ── Push ────────────────────────────────────────────────────────────────
    path("api" / "push") {
      post {
        onComplete(doPush()) {
          case Success(r) =>
            val id     = s"push-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
            val record = r.asJson.deepMerge(Json.obj("id" -> id.asJson))
            pushHistory.updateAndGet(h => (h :+ record).takeRight(pushHistoryLimit))
            complete(record)
          case Failure(e) => complete(StatusCodes.InternalServerError ->
            Json.obj("error" -> e.getMessage.asJson))
        }
      }
    },
    path("api" / "push" / Segment) { id =>
      get {
        pushHistory.get().find(_.hcursor.get[String]("id").toOption.contains(id)) match {
          case Some(r) => complete(r)
          case None    => complete(StatusCodes.NotFound ->
            Json.obj("error" -> s"Push record $id not found".asJson))
        }
      }
    },

    // ── Auto start/stop ──────────────────────────────────────────────────────
    path("api" / "auto" / "start") {
      post { entity(as[Json]) { body =>
        val ms = body.hcursor.get[Long]("intervalMs").getOrElse(1000L)
        val interval = if (ms > 0) ms else 1000L
        startAuto(interval)
        complete(Json.obj("success" -> true.asJson, "intervalMs" -> interval.asJson))
      }}
    },
    path("api" / "auto" / "stop") {
      post {
        stopAuto()
        complete(Json.obj("success" -> true.asJson))
      }
    },

    // ── Config ──────────────────────────────────────────────────────────────
    path("api" / "config") {
      patch { entity(as[Json]) { body =>
        body.hcursor.get[String]("matchAlgorithm") match {
          case Right(s) if s == "gte" || s == "equals" =>
            engine.setMatchAlgorithm(MatchAlgorithm.fromString(s))
            onComplete(saveAndBroadcast()) { _ =>
              complete(Json.obj("success" -> true.asJson,
                "matchAlgorithm" -> MatchAlgorithm.asString(engine.matchAlgorithm).asJson))
            }
          case Right(other) =>
            complete(StatusCodes.BadRequest ->
              Json.obj("error" -> s"""matchAlgorithm must be "gte" or "equals"""".asJson))
          case Left(_) =>
            complete(Json.obj("success" -> true.asJson,
              "matchAlgorithm" -> MatchAlgorithm.asString(engine.matchAlgorithm).asJson))
        }
      }}
    },

    // ── Reset ───────────────────────────────────────────────────────────────
    path("api" / "reset") {
      post {
        resetAndBroadcast()
        complete(Json.obj("success" -> true.asJson))
      }
    },

    // ── Sources ─────────────────────────────────────────────────────────────
    path("api" / "sources") {
      concat(
        get { complete(Json.obj("sources" -> engine.getSources.asJson)) },
        post { entity(as[SourceConfig]) { config =>
          // Idempotent for sensor sources: if a sensor with the same sensorId already
          // exists (e.g. from the persisted volume after a non-fresh restart), return it
          // rather than creating a duplicate with a new UUID.
          val existing: Option[SourceConfig] = config match {
            case s: SensorSourceConfig => engine.findSensorBySensorId(s.sensorId)
            case _                     => None
          }
          existing match {
            case Some(src) =>
              complete(StatusCodes.OK -> Json.obj("source" -> src.asJson))
            case None =>
              val src = engine.addSource(config)
              onComplete(saveAndBroadcast()) { _ =>
                complete(StatusCodes.OK -> Json.obj("source" -> src.asJson))
              }
          }
        }}
      )
    },

    path("api" / "sources" / Segment) { id =>
      concat(
        patch { entity(as[Json]) { body =>
          engine.getSource(id) match {
            case None =>
              complete(StatusCodes.NotFound -> Json.obj("error" -> "Source not found".asJson))
            case Some(existing) =>
              // Merge patch fields onto existing
              val merged = mergeSourcePatch(existing, body)
              engine.updateSource(id, merged) match {
                case None =>
                  complete(StatusCodes.NotFound -> Json.obj("error" -> "Source not found".asJson))
                case Some(updated) =>
                  onComplete(saveAndBroadcast()) { _ =>
                    complete(Json.obj("source" -> updated.asJson))
                  }
              }
          }
        }},
        delete {
          if (engine.removeSource(id)) {
            onComplete(saveAndBroadcast()) { _ =>
              complete(Json.obj("success" -> true.asJson))
            }
          } else {
            complete(StatusCodes.NotFound -> Json.obj("error" -> "Source not found".asJson))
          }
        },
      )
    },

    // ── Sensor push ──────────────────────────────────────────────────────────
    path("api" / "sensors" / Segment) { sensorId =>
      post { entity(as[Json]) { body =>
        body.hcursor.get[Vector[Double]]("values") match {
          case Left(_) =>
            complete(StatusCodes.BadRequest -> Json.obj("error" -> "values must be an array".asJson))
          case Right(values) =>
            if (engine.updateSensorValue(sensorId, values)) {
              broadcastState()
              complete(Json.obj("success" -> true.asJson,
                "sensorId"  -> sensorId.asJson,
                "timestamp" -> System.currentTimeMillis().asJson))
            } else {
              complete(StatusCodes.NotFound ->
                Json.obj("error" -> s"""No sensor source with sensorId "$sensorId"""".asJson))
            }
        }
      }}
    },

    // ── Machine proxy ────────────────────────────────────────────────────────
    path("api" / "machines") {
      get {
        val req = basicRequest.get(uri"$realityEngineUrl/api/machines").response(asString)
        req.send(sttpBackend) match {
          case resp if resp.isSuccess =>
            val json = resp.body.toOption
              .flatMap(b => io.circe.parser.parse(b).toOption)
              .getOrElse(Json.Null)
            complete(json)
          case resp =>
            complete(StatusCodes.BadGateway ->
              Json.obj("error" -> resp.body.fold(identity, identity).asJson))
        }
      }
    },

    // ── SSE events endpoint — parity with /api/events on LSP/CPP ────────────
    path("api" / "events") {
      get {
        complete(ssePEBroadcast
          .map(json => ServerSentEvent(json.noSpaces))
          .keepAlive(15.seconds, () => ServerSentEvent.heartbeat))
      }
    },

    // ── Dispatch ledger ───────────────────────────────────────────────────────
    path("api" / "dispatch" / "ledger") {
      get { complete(Json.obj(
        "records" -> Json.arr(dispatchLedger.get(): _*),
        "total"   -> Json.fromInt(dispatchLedger.get().length)
      )) }
    },
    path("api" / "dispatch" / "records" / Segment) { id =>
      concat(
        get {
          dispatchLedger.get().find(_.hcursor.get[String]("id").toOption.contains(id)) match {
            case Some(r) => complete(r)
            case None    => complete(StatusCodes.NotFound -> Json.obj("error" -> "Dispatch record not found".asJson))
          }
        },
        patch { entity(as[Json]) { body =>
          val ledger  = dispatchLedger.get()
          val updated = ledger.map { r =>
            if (r.hcursor.get[String]("id").toOption.contains(id)) r.deepMerge(body) else r
          }
          dispatchLedger.set(updated)
          updated.find(_.hcursor.get[String]("id").toOption.contains(id)) match {
            case Some(r) =>
              broadcast(Json.obj("type" -> "dispatch-updated".asJson, "record" -> r))
              complete(r)
            case None => complete(StatusCodes.NotFound -> Json.obj("error" -> "Dispatch record not found".asJson))
          }
        } }
      )
    },

    // ── Integrations ─────────────────────────────────────────────────────────
    path("api" / "integrations" / "status") {
      get { complete(Json.obj(
        "loaded"             -> sourceMappings.nonEmpty.asJson,
        "path"               -> sys.env.getOrElse("INTEGRATIONS_CONFIG", "config/integrations.json").asJson,
        "error"              -> Json.Null,
        "integrationCount"   -> sourceMappings.size.asJson,
        "sourceMappings"     -> Json.arr(sourceMappings.values.toSeq: _*),
        "integrations"       -> Json.arr(sourceMappings.keys.map(Json.fromString).toSeq: _*),
        "completionEndpoint" -> "/api/integrations/completions".asJson
      )) }
    },
    path("api" / "integrations" / "completions") {
      post { entity(as[Json]) { body =>
        val result = ingestCompletion(body)
        onComplete(saveAndBroadcast()) { _ =>
          complete(result)
        }
      } }
    },

    // ── Ollama ───────────────────────────────────────────────────────────────
    path("api" / "integrations" / "ollama" / "status") {
      get {
        val (reachable, respBody) = probeHttp(s"$ollamaBaseUrl/api/tags")
        val models = if (reachable)
          io.circe.parser.parse(respBody).toOption
            .flatMap(_.hcursor.downField("models").as[Vector[Json]].toOption)
            .getOrElse(Vector.empty)
        else Vector.empty
        complete(Json.obj(
          "enabled"    -> true.asJson,
          "configured" -> true.asJson,
          "baseUrl"    -> ollamaBaseUrl.asJson,
          "model"      -> ollamaModel.asJson,
          "reachable"  -> reachable.asJson,
          "models"     -> Json.arr(models: _*)
        ))
      }
    },
    path("api" / "integrations" / "ollama" / "dispatch") {
      post { entity(as[Json]) { body =>
        val model    = body.hcursor.get[String]("model").getOrElse(ollamaModel)
        val messages = body.hcursor.downField("messages").as[Json].getOrElse(Json.arr())
        val agentId  = body.hcursor.get[String]("agentId").toOption
        val reqBody  = Json.obj("model" -> model.asJson, "messages" -> messages, "stream" -> false.asJson).noSpaces
        try {
          val resp = basicRequest
            .post(uri"$ollamaBaseUrl/api/chat")
            .contentType("application/json")
            .body(reqBody)
            .response(asString)
            .send(sttpBackend)
          if (resp.isSuccess) {
            val parsed  = resp.body.toOption.flatMap(b => io.circe.parser.parse(b).toOption).getOrElse(Json.Null)
            val content = parsed.hcursor.downField("message").get[String]("content").getOrElse("")
            val cJson   = io.circe.parser.parse(content).toOption.getOrElse(Json.Null)
            val sensorId = cJson.hcursor.get[String]("sensorId").toOption
              .orElse(agentId.map(id => s"agent.$id.completion"))
              .getOrElse("ollama.completion")
            val values = cJson.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector(1.0))
            val record = ingestCompletion(Json.obj("sensorId" -> sensorId.asJson, "values" -> values.asJson))
            complete(Json.obj("success" -> true.asJson, "content" -> content.asJson, "record" -> record))
          } else {
            complete(StatusCodes.BadGateway -> Json.obj("error" -> resp.body.fold(identity, identity).asJson))
          }
        } catch { case e: Exception =>
          complete(StatusCodes.ServiceUnavailable -> Json.obj("error" -> e.getMessage.asJson))
        }
      } }
    },

    // ── OpenAI ───────────────────────────────────────────────────────────────
    path("api" / "integrations" / "openai" / "status") {
      get { complete(Json.obj(
        "enabled"       -> openAiApiKey.isDefined.asJson,
        "configured"    -> openAiApiKey.isDefined.asJson,
        "baseUrl"       -> openAiBaseUrl.asJson,
        "model"         -> openAiModel.asJson,
        "keyConfigured" -> openAiApiKey.isDefined.asJson
      )) }
    },
    path("api" / "integrations" / "openai" / "dispatch") {
      post { entity(as[Json]) { body =>
        openAiApiKey match {
          case None =>
            complete(StatusCodes.BadRequest -> Json.obj("error" -> "OPENAI_API_KEY not configured".asJson))
          case Some(key) =>
            val input   = body.hcursor.get[String]("input").getOrElse("")
            val model   = body.hcursor.get[String]("model").getOrElse(openAiModel)
            val agentId = body.hcursor.get[String]("agentId").toOption
            val reqBody = Json.obj("model" -> model.asJson, "input" -> input.asJson).noSpaces
            try {
              val resp = basicRequest
                .post(uri"$openAiBaseUrl/responses")
                .header("Authorization", s"Bearer $key")
                .contentType("application/json")
                .body(reqBody)
                .response(asString)
                .send(sttpBackend)
              if (resp.isSuccess) {
                val parsed = resp.body.toOption.flatMap(b => io.circe.parser.parse(b).toOption).getOrElse(Json.Null)
                val outputText = parsed.hcursor.downField("output").downArray
                  .downField("content").downArray.get[String]("text").toOption.getOrElse("")
                val cJson    = io.circe.parser.parse(outputText).toOption.getOrElse(Json.Null)
                val sensorId = cJson.hcursor.get[String]("sensorId").toOption
                  .orElse(agentId.map(id => s"agent.$id.completion"))
                  .getOrElse("openai.completion")
                val values = cJson.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector(1.0))
                val record = ingestCompletion(Json.obj("sensorId" -> sensorId.asJson, "values" -> values.asJson))
                complete(Json.obj("success" -> true.asJson, "outputText" -> outputText.asJson, "record" -> record))
              } else {
                complete(StatusCodes.BadGateway -> Json.obj("error" -> resp.body.fold(identity, identity).asJson))
              }
            } catch { case e: Exception =>
              complete(StatusCodes.ServiceUnavailable -> Json.obj("error" -> e.getMessage.asJson))
            }
        }
      } }
    },

    // ── ACP ──────────────────────────────────────────────────────────────────
    path("api" / "integrations" / "acp" / "status") {
      get { complete(Json.obj(
        "enabled"                   -> acpEnabled.asJson,
        "configured"                -> acpEndpointUrl.isDefined.asJson,
        "endpointUrl"               -> acpEndpointUrl.map(_.asJson).getOrElse(Json.Null),
        "sessionKey"                -> acpSessionKey.map(_.asJson).getOrElse(Json.Null),
        "agentId"                   -> acpAgentId.asJson,
        "completionSourceMappingId" -> acpCompletionSourceMappingId.asJson
      )) }
    },
    path("api" / "integrations" / "acp" / "dispatch") {
      post { entity(as[Json]) { body =>
        val ts      = System.currentTimeMillis()
        val agentId = body.hcursor.get[String]("agentId").getOrElse(acpAgentId)
        val id      = s"acp-$ts-${java.util.UUID.randomUUID().toString.take(8)}"
        val record  = Json.obj(
          "id"        -> id.asJson,
          "type"      -> "acp-handoff".asJson,
          "agentId"   -> agentId.asJson,
          "timestamp" -> ts.asJson,
          "endpoint"  -> acpEndpointUrl.map(_.asJson).getOrElse(Json.Null),
          "sessionKey" -> acpSessionKey.map(_.asJson).getOrElse(Json.Null),
          "completionSourceMappingId" -> body.hcursor.get[String]("sourceMappingId").getOrElse(acpCompletionSourceMappingId).asJson,
          "status"    -> "accepted".asJson,
          "body"      -> body
        )
        dispatchLedger.updateAndGet(l => (l :+ record).takeRight(dispatchLedgerLimit))
        broadcast(Json.obj("type" -> "acp.handoff.accepted".asJson, "record" -> record))
        complete(StatusCodes.Accepted -> record)
      } }
    },

    // ── HealthKit ─────────────────────────────────────────────────────────────
    path("api" / "integrations" / "healthkit" / "status") {
      get { complete(Json.obj(
        "bridgeId"              -> hkBridgeId.asJson,
        "enabled"               -> hkEnabled.asJson,
        "defaultSourceMappingId" -> hkDefaultMappingId.asJson,
        "tokenConfigured"       -> hkBridgeToken.isDefined.asJson,
        "nativeAppRequired"     -> true.asJson,
        "nativeWorkOutsideRepo" -> true.asJson,
        "registryKey"           -> "healthkit:<typeIdentifier>".asJson,
        "statusEndpoint"        -> "/api/integrations/healthkit/status".asJson,
        "ingestEndpoint"        -> "/api/integrations/healthkit/ingest".asJson,
        "contract"              -> Json.obj(
          "transport"    -> "https".asJson,
          "singleSample" -> Json.arr("type".asJson, "value".asJson, "sourceName".asJson),
          "batchSamples" -> Json.arr("bridgeId".asJson, "samples[]".asJson),
          "auth"         -> (if (hkBridgeToken.isDefined) "bridgeToken" else "none").asJson
        )
      )) }
    },
    path("api" / "integrations" / "healthkit" / "ingest") {
      post {
        entity(as[Json]) { body =>
          // No-token mode: allowed when HEALTHKIT_BRIDGE_TOKEN is unset.
          // If token is configured, require bridgeToken or token field in body.
          val tokenOk = hkBridgeToken.forall { expected =>
            body.hcursor.get[String]("bridgeToken").toOption
              .orElse(body.hcursor.get[String]("token").toOption)
              .contains(expected)
          }
          if (!tokenOk) {
            complete(StatusCodes.Unauthorized ->
              Json.obj("error" -> "invalid HealthKit bridge token".asJson))
          } else {
            // Batch (samples[]) or single flat body
            val rawSamples = body.hcursor.downField("samples").as[Vector[Json]].toOption
            val samples    = rawSamples.getOrElse(Vector(body))

            val (resolved, unmapped) = samples.foldLeft(
              (Vector.empty[Json], Vector.empty[Json])
            ) { case ((res, unm), sample) =>
              val tpe        = sample.hcursor.get[String]("type").toOption
                                 .orElse(sample.hcursor.get[String]("sampleType").toOption)
                                 .getOrElse("")
              val sourceName = sample.hcursor.get[String]("sourceName").toOption.getOrElse("")
              val valuesOpt  = sample.hcursor.downField("values").as[Vector[Double]].toOption
              val valueOpt   = sample.hcursor.get[Double]("value").toOption
              val values     = valuesOpt.getOrElse(valueOpt.map(Vector(_)).getOrElse(Vector.empty))

              if (tpe.isEmpty) {
                val u = Json.obj("unmapped" -> true.asJson, "type" -> tpe.asJson,
                  "sourceName" -> sourceName.asJson, "reason" -> "sample.type is required".asJson)
                (res, unm :+ u)
              } else if (values.isEmpty) {
                val u = Json.obj("unmapped" -> true.asJson, "type" -> tpe.asJson,
                  "sourceName" -> sourceName.asJson, "reason" -> "sample.value must be a finite number".asJson)
                (res, unm :+ u)
              } else {
                // Two-level registry lookup: healthkit:<type>:<sourceName> wins over healthkit:<type>
                val explicitId = sample.hcursor.get[String]("sourceMappingId").toOption
                                   .orElse(sample.hcursor.get[String]("mappingId").toOption)
                val mapping = explicitId.flatMap(sourceMappings.get)
                  .orElse(if (sourceName.nonEmpty) sourceMappings.get(s"healthkit:$tpe:$sourceName") else None)
                  .orElse(sourceMappings.get(s"healthkit:$tpe"))

                mapping match {
                  case None =>
                    val u = Json.obj("unmapped" -> true.asJson, "type" -> tpe.asJson,
                      "sourceName" -> sourceName.asJson,
                      "reason" -> s"no registry mapping (declare healthkit:$tpe[:<sourceName>])".asJson)
                    (res, unm :+ u)
                  case Some(m) if m.hcursor.get[Region]("region").isLeft =>
                    val u = Json.obj("unmapped" -> true.asJson, "type" -> tpe.asJson,
                      "reason" -> "mapping is missing region.offset/region.length".asJson)
                    (res, unm :+ u)
                  case Some(m) =>
                    val region = m.hcursor.get[Region]("region").toOption.get
                    val sensorId = m.hcursor.get[String]("sensorId").toOption.filter(_.nonEmpty)
                      .orElse(m.hcursor.get[String]("sensorIdTemplate").toOption.filter(_.nonEmpty).map { tpl =>
                        resolveTemplate(tpl, Map("type" -> tpe, "sampleType" -> tpe,
                          "source" -> sourceName, "provider" -> "healthkit", "agent" -> sourceName))
                      })
                      .getOrElse(s"hk.${tpe.replaceAll("[^a-zA-Z0-9]", "").toLowerCase}")
                    val ttlMs = m.hcursor.get[Long]("ttlMs").getOrElse(3600000L)
                    val name  = m.hcursor.get[String]("name").getOrElse(s"healthkit:$tpe")
                    val mapId = m.hcursor.get[String]("id").getOrElse(explicitId.getOrElse(""))
                    val source = if (engine.updateSensorValue(sensorId, values)) {
                      engine.findSensorBySensorId(sensorId)
                    } else {
                      Some(engine.addSource(SensorSourceConfig(
                        id          = "",
                        name        = name,
                        region      = region,
                        active      = true,
                        sensorId    = sensorId,
                        lastValue   = values.take(region.length),
                        lastUpdated = Some(System.currentTimeMillis()),
                        ttlMs       = ttlMs,
                      )))
                    }
                    val r = Json.obj(
                      "resolved"        -> true.asJson,
                      "sensorId"        -> sensorId.asJson,
                      "name"            -> name.asJson,
                      "type"            -> tpe.asJson,
                      "sourceName"      -> sourceName.asJson,
                      "sourceMappingId" -> mapId.asJson,
                      "region"          -> region.asJson,
                      "values"          -> values.asJson,
                      "source"          -> source.map(_.asJson).getOrElse(Json.obj("lastValue" -> values.asJson)),
                      "ttlMs"           -> ttlMs.asJson)
                    (res :+ r, unm)
                }
              }
            }

            val allResolved = unmapped.isEmpty
            val status = if (allResolved) StatusCodes.OK
                         else if (resolved.isEmpty) StatusCodes.BadRequest
                         else StatusCodes.MultiStatus
            broadcastState()
            complete(status -> Json.obj(
              "success"  -> allResolved.asJson,
              "bridgeId" -> hkBridgeId.asJson,
              "resolved" -> Json.arr(resolved: _*),
              "unmapped" -> Json.arr(unmapped: _*)
            ))
          }
        }
      }
    },

    // ── CareKit ──────────────────────────────────────────────────────────────
    path("api" / "integrations" / "carekit" / "status") {
      get { complete(Json.obj(
        "bridgeId"              -> ckBridgeId.asJson,
        "enabled"               -> ckEnabled.asJson,
        "defaultSourceMappingId" -> ckDefaultMappingId.asJson,
        "tokenConfigured"       -> ckBridgeToken.isDefined.asJson,
        "nativeAppRequired"     -> true.asJson,
        "nativeWorkOutsideRepo" -> true.asJson,
        "registryKey"           -> "carekit:<sampleType>".asJson,
        "statusEndpoint"        -> "/api/integrations/carekit/status".asJson,
        "ingestEndpoint"        -> "/api/integrations/carekit/ingest".asJson,
        "contract"              -> Json.obj(
          "transport"    -> "https".asJson,
          "singleSample" -> Json.arr("bridgeId".asJson, "sampleType".asJson, "sourceMappingId".asJson, "values".asJson),
          "batchSamples" -> Json.arr("bridgeId".asJson, "samples[]".asJson),
          "auth"         -> (if (ckBridgeToken.isDefined) "bridgeToken" else "external-transport").asJson
        )
      )) }
    },
    path("api" / "integrations" / "carekit" / "ingest") {
      post {
        entity(as[Json]) { body =>
          val tokenOk = ckBridgeToken.forall { expected =>
            body.hcursor.get[String]("bridgeToken").toOption
              .orElse(body.hcursor.get[String]("token").toOption)
              .contains(expected)
          }
          if (!tokenOk) {
            complete(StatusCodes.Unauthorized ->
              Json.obj("error" -> "invalid CareKit bridge token".asJson))
          } else {
            val bridgeIdFromBody = body.hcursor.get[String]("bridgeId").getOrElse(ckBridgeId)
            val reserved         = Set("samples", "bridgeToken", "token")
            val topLevel         = body.asObject.map(_.toMap.filterKeys(!reserved(_))).getOrElse(Map.empty)

            // Batch (samples[]) or single flat body; top-level fields merged into each sample
            val rawSamples  = body.hcursor.downField("samples").as[Vector[Json]].toOption
            val ingestItems = rawSamples match {
              case Some(ss) => ss.map { s =>
                val sMap = s.asObject.map(_.toMap).getOrElse(Map.empty)
                Json.fromFields(topLevel ++ sMap)
              }
              case None => Vector(body)
            }

            val results = ingestItems.map { sample =>
              val sampleType = sample.hcursor.get[String]("sampleType").toOption
                                 .orElse(sample.hcursor.get[String]("type").toOption)
                                 .getOrElse("task-event")
              val mappingId  = sample.hcursor.get[String]("sourceMappingId").toOption
                                 .filter(_.nonEmpty).getOrElse(ckDefaultMappingId)
              val mapping    = sourceMappings.get(mappingId)
              val valuesOpt  = sample.hcursor.downField("values").as[Vector[Double]].toOption
              val valueOpt   = sample.hcursor.get[Double]("value").toOption
              val values     = valuesOpt.getOrElse(valueOpt.map(Vector(_)).getOrElse(Vector.empty))
              val tpl        = mapping.flatMap(_.hcursor.get[String]("sensorIdTemplate").toOption)
                                 .getOrElse("carekit.{sampleType}")
              val sensorId   = sample.hcursor.get[String]("sensorId").toOption.filter(_.nonEmpty)
                                 .getOrElse(resolveTemplate(tpl, Map(
                                   "bridgeId"   -> bridgeIdFromBody,
                                   "sampleType" -> sampleType,
                                   "type"       -> sampleType,
                                   "taskId"     -> sample.hcursor.get[String]("taskId").getOrElse(sampleType),
                                   "carePlanId" -> sample.hcursor.get[String]("carePlanId").getOrElse("care-plan"))))
              engine.updateSensorValue(sensorId, values)
              Json.obj(
                "success"         -> true.asJson,
                "sampleType"      -> sampleType.asJson,
                "taskId"          -> sample.hcursor.get[String]("taskId").toOption.asJson,
                "carePlanId"      -> sample.hcursor.get[String]("carePlanId").toOption.asJson,
                "sourceMappingId" -> mappingId.asJson,
                "sensorId"        -> sensorId.asJson)
            }

            val allOk = true
            broadcastState()
            complete((if (allOk) StatusCodes.OK else StatusCodes.MultiStatus) -> Json.obj(
              "success"  -> allOk.asJson,
              "bridgeId" -> bridgeIdFromBody.asJson,
              "results"  -> Json.arr(results: _*)
            ))
          }
        }
      }
    },

    // ── LocalAI ──────────────────────────────────────────────────────────────
    path("api" / "integrations" / "localai" / "status") {
      get {
        val (reachable, _) = probeHttp(localAiApiUrl)
        complete(Json.obj(
          "enabled"     -> true.asJson,
          "configured"  -> true.asJson,
          "baseUrl"     -> localAiApiUrl.asJson,
          "reachable"   -> reachable.asJson,
          "machinesDir" -> localAiMachinesDir.map(_.asJson).getOrElse(Json.Null)
        ))
      }
    },
    path("api" / "integrations" / "localai" / "catalog") {
      get {
        val schema = try {
          val resp = basicRequest.get(uri"$localAiApiUrl/graph/schema").response(asString).send(sttpBackend)
          if (resp.isSuccess) io.circe.parser.parse(resp.body.fold(identity, identity)).toOption.getOrElse(Json.Null)
          else Json.Null
        } catch { case _: Exception => Json.Null }
        val events = try {
          val resp = basicRequest.get(uri"$localAiApiUrl/graphql/events").response(asString).send(sttpBackend)
          if (resp.isSuccess) io.circe.parser.parse(resp.body.fold(identity, identity)).toOption.getOrElse(Json.Null)
          else Json.Null
        } catch { case _: Exception => Json.Null }
        complete(Json.obj("schema" -> schema, "events" -> events))
      }
    },
    path("api" / "integrations" / "localai" / "bootstrap") {
      post {
        val defaultSensors = List(
          ("localai_rag_retrieval",  "LocalAI RAG Retrieval",  0),
          ("localai_rag_grading",    "LocalAI RAG Grading",    4),
          ("localai_agent_activity", "LocalAI Agent Activity", 8)
        )
        val created = defaultSensors.flatMap { case (sid, name, offset) =>
          engine.findSensorBySensorId(sid) match {
            case Some(_) => None
            case None =>
              val src = engine.addSource(SensorSourceConfig(
                id          = sid,
                name        = name,
                region      = com.realityengine.perception.models.Region(offset, 4),
                active      = true,
                sensorId    = sid,
                lastValue   = Vector.empty,
                lastUpdated = None,
                ttlMs       = 60000L
              ))
              Some(src.asJson)
          }
        }
        val machineResults = localAiMachinesDir.map { dir =>
          val d = new java.io.File(dir)
          if (d.isDirectory)
            Option(d.listFiles()).getOrElse(Array.empty).filter(_.getName.endsWith(".json")).map { f =>
              try {
                val src  = scala.io.Source.fromFile(f)
                val text = try src.mkString finally src.close()
                val resp = basicRequest
                  .post(uri"$realityEngineUrl/api/machines/import")
                  .contentType("application/json")
                  .body(text)
                  .response(asString)
                  .send(sttpBackend)
                Json.obj("file" -> f.getName.asJson, "success" -> resp.isSuccess.asJson)
              } catch { case e: Exception =>
                Json.obj("file" -> f.getName.asJson, "success" -> false.asJson, "error" -> e.getMessage.asJson)
              }
            }.toVector
          else Vector.empty
        }.getOrElse(Vector.empty)
        onComplete(saveAndBroadcast()) { _ =>
          complete(Json.obj(
            "success"  -> true.asJson,
            "sources"  -> Json.arr(created: _*),
            "machines" -> Json.arr(machineResults: _*)
          ))
        }
      }
    },
    path("api" / "integrations" / "localai" / "invoke") {
      post { entity(as[Json]) { body =>
        val allowed = Set(
          "/v1/chat/completions", "/v1/completions", "/v1/embeddings", "/v1/models",
          "/v1/images/generations", "/v1/audio/transcriptions", "/graphql", "/api/predict"
        )
        val targetPath = body.hcursor.get[String]("path").getOrElse("/v1/chat/completions")
        if (!allowed.contains(targetPath)) {
          complete(StatusCodes.Forbidden ->
            Json.obj("error" -> "Path not in allowed list".asJson, "path" -> targetPath.asJson))
        } else {
          val payload = body.hcursor.downField("body").as[Json].getOrElse(body)
          try {
            val resp = basicRequest
              .post(uri"$localAiApiUrl$targetPath")
              .contentType("application/json")
              .body(payload.noSpaces)
              .response(asString)
              .send(sttpBackend)
            val parsed = resp.body.toOption.flatMap(b => io.circe.parser.parse(b).toOption).getOrElse(Json.Null)
            complete((if (resp.isSuccess) StatusCodes.OK else StatusCodes.BadGateway) -> parsed)
          } catch { case e: Exception =>
            complete(StatusCodes.ServiceUnavailable -> Json.obj("error" -> e.getMessage.asJson))
          }
        }
      } }
    },

    // ── Signals ───────────────────────────────────────────────────────────────
    path("api" / "signals") {
      post { entity(as[Json]) { body =>
        val sensorId = body.hcursor.get[String]("sensorId").getOrElse("localai_agent_activity")
        val values   = body.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector.empty)
        val ttlMs    = body.hcursor.get[Long]("ttlMs").getOrElse(30000L)
        val updated  = engine.updateSensorValue(sensorId, values)
        val ts       = System.currentTimeMillis()
        broadcastState()
        complete(Json.obj(
          "success"   -> updated.asJson,
          "sensorId"  -> sensorId.asJson,
          "timestamp" -> ts.asJson,
          "ttlMs"     -> ttlMs.asJson
        ))
      } }
    },

    // ── Triggers status ───────────────────────────────────────────────────────
    path("api" / "triggers" / "status") {
      get { complete(Json.obj(
        "enabled"      -> false.asJson,
        "dispatchMode" -> "dry-run".asJson
      )) }
    },

    // ── MQTT bridge ───────────────────────────────────────────────────────────
    path("api" / "mqtt" / "status") {
      get {
        val bridge = mqttBridgeRef.get()
        complete(bridge match {
          case None => Json.obj("enabled" -> false.asJson)
          case Some(b) =>
            val s = b.stats
            Json.obj(
              "enabled"   -> true.asJson,
              "connected" -> b.isConnected.asJson,
              "brokerUrl" -> mqttBrokerUrlRef.get().map(_.asJson).getOrElse(Json.Null),
              "clientId"  -> b.clientId.asJson,
              "mappings"  -> b.rules.length.asJson,
              "bridge"    -> Json.obj(
                "messagesReceived"        -> s.messagesReceived.get().asJson,
                "messagesMapped"          -> s.messagesMapped.get().asJson,
                "messagesRejected"        -> s.messagesRejected.get().asJson,
                "messagesUnmatched"       -> s.messagesUnmatched.get().asJson,
                "messagesRetainedDropped" -> s.messagesRetainedDropped.get().asJson,
                "pushesTriggered"         -> s.pushesTriggered.get().asJson,
              )
            )
        })
      }
    },
    path("api" / "mqtt" / "mappings") {
      concat(
        get {
          val bridge = mqttBridgeRef.get()
          complete(bridge match {
            case None    => Json.obj("enabled" -> false.asJson, "mappings" -> Json.arr())
            case Some(b) =>
              val body = b.toJson.asObject.map(_.toMap).getOrElse(Map.empty)
              Json.obj(
                "enabled"  -> true.asJson,
                "mappings" -> body.getOrElse("mappings", Json.arr()),
              )
          })
        },
        put { entity(as[Json]) { body =>
          mqttBridgeRef.get() match {
            case None =>
              complete(StatusCodes.Conflict ->
                Json.obj("error" -> "MQTT bridge not enabled — call POST /api/mqtt/enable first".asJson))
            case Some(current) =>
              MqttBridge.parseRegistry(body) match {
                case Left(err) =>
                  complete(StatusCodes.BadRequest -> Json.obj("error" -> err.asJson))
                case Right(rules) =>
                  current.stop()
                  val bridge = new MqttBridge(current.brokerUrl, current.clientId, rules, mqttIngest, () => doPush())
                  Try(bridge.start()) match {
                    case scala.util.Failure(e) =>
                      mqttBridgeRef.set(None)
                      complete(StatusCodes.InternalServerError ->
                        Json.obj("error" -> s"MQTT bridge failed to restart: ${e.getMessage}".asJson))
                    case scala.util.Success(_) =>
                      mqttBridgeRef.set(Some(bridge))
                      broadcast(Json.obj("type" -> "mqtt-mappings-reloaded".asJson, "mappings" -> rules.length.asJson))
                      complete(Json.obj(
                        "success"  -> true.asJson,
                        "enabled"  -> true.asJson,
                        "mappings" -> rules.length.asJson,
                        "warnings" -> Json.arr()
                      ))
                  }
              }
          }
        } }
      )
    },
    path("api" / "mqtt" / "enable") {
      post { entity(as[Json]) { body =>
        val brokerUrl = body.hcursor.downField("brokerUrl").as[String].getOrElse("")
        if (brokerUrl.isEmpty)
          complete(StatusCodes.BadRequest -> Json.obj("error" -> "brokerUrl is required".asJson))
        else {
          val registryBody = body.hcursor.downField("mappings").as[Json].getOrElse(Json.obj())
          MqttBridge.parseRegistry(registryBody) match {
            case Left(err) =>
              complete(StatusCodes.BadRequest -> Json.obj("error" -> err.asJson))
            case Right(rules) =>
              mqttBridgeRef.get().foreach(_.stop())
              val clientId = "reality-engine-pe-scala"
              val username = body.hcursor.downField("username").as[String].toOption.filter(_.nonEmpty)
              val password = body.hcursor.downField("password").as[String].toOption.filter(_.nonEmpty)
              val bridge = new MqttBridge(brokerUrl, clientId, rules, mqttIngest, () => doPush(), username, password)
              Try(bridge.start()) match {
                case scala.util.Failure(e) =>
                  mqttBridgeRef.set(None)
                  mqttBrokerUrlRef.set(None)
                  complete(StatusCodes.InternalServerError ->
                    Json.obj("error" -> s"MQTT bridge failed to start: ${e.getMessage}".asJson))
                case scala.util.Success(_) =>
                  mqttBridgeRef.set(Some(bridge))
                  mqttBrokerUrlRef.set(Some(brokerUrl))
                  broadcast(Json.obj("type" -> "mqtt-enabled".asJson, "brokerUrl" -> brokerUrl.asJson))
                  complete(Json.obj(
                    "success"  -> true.asJson,
                    "enabled"  -> true.asJson,
                    "mappings" -> rules.length.asJson,
                    "warnings" -> Json.arr()
                  ))
              }
          }
        }
      } }
    },
    path("api" / "mqtt" / "disable") {
      post {
        mqttBridgeRef.getAndSet(None).foreach(_.stop())
        mqttBrokerUrlRef.set(None)
        broadcast(Json.obj("type" -> "mqtt-disabled".asJson))
        complete(Json.obj("success" -> true.asJson, "enabled" -> false.asJson))
      }
    },
    // GET /api/mqtt/example — bundled yuma-agriculture demo mapping registry.
    // Served inline so the PE visualizer's MqttConfigModal can offer a
    // "Load example" button without requiring the host filesystem to have
    // the CPP config files present.  Mirrors mqtt-mappings.yuma-agriculture.json.
    path("api" / "mqtt" / "example") {
      get {
        complete(mqttExampleMappings)
      }
    },

    // ── Bootstrap sources from Reality Engine machines ────────────────────────
    path("api" / "sources" / "bootstrap-from-machines") {
      post {
        val req = basicRequest.get(uri"$realityEngineUrl/api/machines").response(asString)
        req.send(sttpBackend) match {
          case resp if resp.isSuccess =>
            val machines = resp.body.toOption
              .flatMap(b => io.circe.parser.parse(b).toOption)
              .flatMap(_.hcursor.downField("machines").as[Vector[Json]].toOption)
              .getOrElse(Vector.empty)
            val (created, skipped) = bootstrapSourcesFromMachines(machines)
            onComplete(saveAndBroadcast()) { _ =>
              val body = bootstrapSummaryJson(created, Vector.empty, machines.length, skipped)
              complete(HttpEntity(ContentTypes.`application/json`, body))
            }
          case resp =>
            complete(StatusCodes.BadGateway ->
              Json.obj("error" -> resp.body.fold(identity, identity).asJson))
        }
      }
    },

    // ── WebSocket ─────────────────────────────────────────────────────────────
    path("ws") {
      get {
        handleWebSocketMessages {
          // Send current state immediately after subscription is registered
          val flow = WsBroadcastActor.buildWsFlow(broadcastActor)
          val state = engine.getState(lastPush.get(), AutoConfig(isAutoRunning, autoIntervalMs))
          val initMsg = Json.obj("type" -> "state-update".asJson, "state" -> state.asJson).noSpaces
          broadcastActor ! WsBroadcastActor.BroadcastMsg(initMsg)
          flow
        }
      }
    },

    // ── Root info ─────────────────────────────────────────────────────────────
    pathEndOrSingleSlash {
      get { complete(Json.obj(
        "service" -> "Perception Engine (Scala/Akka)".asJson,
        "status"  -> "running".asJson,
      ))}
    },
  ) }

  // ── Merge JSON patch onto SourceConfig ────────────────────────────────────

  private def mergeSourcePatch(existing: SourceConfig, patch: Json): SourceConfig = {
    val c = patch.hcursor
    existing match {
      case s: SimulatedSourceConfig =>
        s.copy(
          name      = c.get[String]("name").getOrElse(s.name),
          active    = c.get[Boolean]("active").getOrElse(s.active),
          pattern   = c.get[SimPattern]("pattern").getOrElse(s.pattern),
          frequency = c.get[Double]("frequency").getOrElse(s.frequency),
          amplitude = c.get[Double]("amplitude").getOrElse(s.amplitude),
          dcOffset  = c.get[Double]("dcOffset").getOrElse(s.dcOffset),
          region    = c.get[Region]("region").getOrElse(s.region),
        )
      case s: TestSourceConfig =>
        s.copy(
          name   = c.get[String]("name").getOrElse(s.name),
          active = c.get[Boolean]("active").getOrElse(s.active),
          loop   = c.get[Boolean]("loop").getOrElse(s.loop),
          region = c.get[Region]("region").getOrElse(s.region),
        )
      case s: SensorSourceConfig =>
        s.copy(
          name     = c.get[String]("name").getOrElse(s.name),
          active   = c.get[Boolean]("active").getOrElse(s.active),
          sensorId = c.get[String]("sensorId").getOrElse(s.sensorId),
          ttlMs    = c.get[Long]("ttlMs").getOrElse(s.ttlMs),
          region   = c.get[Region]("region").getOrElse(s.region),
        )
    }
  }
}
