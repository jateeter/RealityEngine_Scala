package com.realityengine.perception.api

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.realityengine.perception.{MachineCorpus, VectorAggregator}
import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.metrics.SemanticMetrics
import com.realityengine.perception.models._
import com.realityengine.perception.models.PerceptionJsonCodecs._
import com.realityengine.perception.store.SourceStore
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes
import io.circe.Encoder
import io.circe.Json
import io.circe.Printer
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
import com.realityengine.perception.triggers.TriggerDispatcher

class PerceptionRoutes(
  engine: PerceptionEngine,
  store: SourceStore,
  broadcastActor: ActorRef,
  realityEngineUrl: String,
  auditCfg: AuditConfig,
  // PE_SOURCE_ACTIVATE_ON_LOAD, threaded through so the runtime bootstrap route
  // seeds sources the same way startup does. Without it the setting was honoured
  // on boot and ignored by POST /api/sources/bootstrap-from-machines, so a
  // machine added to the running corpus got an inactive source here and an
  // active one on C++ and LSP — the same corpus presenting different stimulus
  // per runtime (RealityEngine_CI corpus parity sweep, 2026-08-19).
  activateOnLoad: Boolean = false,
)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) {

  // Blocking sttp backend runs on calling thread (routes are already on a
  // dedicated blocking dispatcher when needed — see doPush).

  // Canonical JSON key order: sorted.
  //
  // C++ emits every object key-sorted because its Json::Object is a
  // std::map; Scala and LSP preserved insertion order, and their two
  // insertion orders differed from each other. Three key orderings for the
  // same object meant nothing could ever be byte-identical
  // (RealityEngine_CI#91).
  //
  // Sorting is the rule all runtimes can honour without declaring a field
  // order for every object type. FailFastCirceSupport's marshaller takes the
  // Printer implicitly, so this covers every `complete(json)` response.
  implicit val canonicalJsonPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)

  // Canonical number rendering, applied to the whole document on the way out.
  // Named `marshaller` deliberately: that is the name FailFastCirceSupport
  // exports, so this local definition shadows it rather than competing with it
  // as an equally-specific implicit (which resolves to neither).
  implicit def marshaller[A](implicit encoder: Encoder[A]): ToEntityMarshaller[A] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(value =>
      canonicalJsonPrinter.print(CanonicalJson.canonicalizeNumbers(encoder(value))))

  // BaseCirceSupport also exports a Json-specific `jsonMarshaller`, which is
  // more specific than the generic one above and would otherwise win for every
  // `complete(Json.obj(...))` -- that is how /api/sources kept emitting
  // "[[0.0,1.0]]" while /api/state, completed with a case class, did not.
  implicit def jsonMarshaller(implicit printer: Printer): ToEntityMarshaller[Json] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(json =>
      printer.print(CanonicalJson.canonicalizeNumbers(json)))

  private val sttpBackend = HttpURLConnectionBackend()

  // RC-5: thread-safe timer and state refs
  // Machine facts the PE resolves the merge mapping against — provenance and
  // the governance contract.  Populated from the same machine list that seeds
  // sources, so whoever seeds also arms the merge batch.
  private val machineCorpus = new AtomicReference[MachineCorpus](MachineCorpus.empty)

  def setMachineCorpus(corpus: MachineCorpus): Unit = machineCorpus.set(corpus)

  private val autoTimer    = new AtomicReference[Option[akka.actor.Cancellable]](None)
  @volatile private var autoIntervalMs: Long = 1000L
  private val lastPush     = new AtomicReference[Json](Json.Null)
  // A-1: prevents push cycles from stacking when doPush takes longer than the interval
  private val pushInFlight = new AtomicBoolean(false)

  // In-memory dispatch ledger (ring buffer, capped at dispatchLedgerLimit entries).
  // A diagnostic window, not an audit trail (INTEGRATION_ROADMAP.md §6 Q2):
  // default 256 in every runtime. It was 100 here and in LSP, 256 in C++, so one
  // run left different ledger histories on different engines.
  private val dispatchLedgerLimit = sys.env.get("TRIGGER_DISPATCH_LEDGER_LIMIT").flatMap(_.toIntOption).filter(_ > 0).getOrElse(256)
  private val dispatchLedger      = new AtomicReference[Vector[Json]](Vector.empty)

  // localAI/MCP invocation ledger (RealityEngine_Machines#152). Mirrors the
  // dispatch ledger above — bounded, oldest first — so a long-running PE cannot
  // grow without bound and both ledgers read alike across runtimes.
  private val localAiLedgerLimit = sys.env.get("LOCALAI_INVOCATION_LEDGER_LIMIT").flatMap(_.toIntOption).getOrElse(256)
  private val localAiLedger      = new AtomicReference[Vector[Json]](Vector.empty)

  private def recordLocalAiInvocation(rec: Json): Unit =
    localAiLedger.updateAndGet(v => (v :+ rec).takeRight(localAiLedgerHardLimit))

  // takeRight on every append is O(n) but n is 256; the alternative is a mutable
  // deque behind a lock, which buys nothing at this size and costs the atomic
  // update's simplicity.
  private def localAiLedgerHardLimit: Int = localAiLedgerLimit

  // In-memory push history (ring buffer, capped at pushHistoryLimit entries)
  private val pushHistoryLimit = sys.env.get("PUSH_HISTORY_LIMIT").flatMap(_.toIntOption).getOrElse(100)
  private val pushHistory      = new AtomicReference[Vector[Json]](Vector.empty)

  // Semantic audit ring buffer — re:PerceptionEvent records emitted on push
  // (RealityEngine_Machines docs/SEMANTIC_AUDIT_CONTRACT.md, milestone M5).
  private val semanticAuditCapacity = 1000
  private val semanticAudit = new java.util.concurrent.ConcurrentLinkedDeque[Json]()

  private def recordSemanticAudit(record: Json): Unit = {
    semanticAudit.addLast(record)
    while (semanticAudit.size() > semanticAuditCapacity) semanticAudit.pollFirst()
  }

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

    // One builder for both seeding paths — see MachineCorpus.testSourceFor.
    machines.foreach { m =>
      MachineCorpus.testSourceFor(m, activateOnLoad)
        .filterNot(src => existingMachineIds.contains(src.machineId)) match {
        case Some(src) =>
          engine.addSource(src)
          existingMachineIds = existingMachineIds + src.machineId
          created += 1
        case None =>
          skipped += 1
      }
    }

    (created, skipped)
  }

  private def mqttSource(sensorId: String, offset: Int, length: Int, ttlMs: Long): SensorSourceConfig =
    SensorSourceConfig(
      id          = sensorId,
      name        = s"mqtt:$sensorId",
      region      = com.realityengine.perception.models.Region(offset, length),
      active      = false,
      sensorId    = sensorId,
      lastValue   = Vector.empty,
      lastUpdated = None,
      ttlMs       = ttlMs,
      origin      = Some("mqtt"),
    )

  /** Enabling the bridge is the MQTT integration registering, so it declares
    * its whole source set there and then — one inactive sensor source per
    * mapping rule — rather than materialising each one when its first message
    * lands (RealityEngine_CI#163 points 1 and 2a).
    *
    * A rule whose `sensorIdTemplate` interpolates topic captures (`{1}`, `{2}`)
    * names a source per matching topic, so its id is not knowable until a
    * message arrives; those still declare on first signal, via the same
    * idempotent `declareSource` call in `mqttIngest`.
    *
    * Returns how many rules were declarable up front.
    */
  private def declareMqttSources(rules: Vector[MqttMappingRule]): Int = {
    val declarable = rules.filterNot(_.sensorIdTemplate.contains("{"))
    declarable.foreach { r =>
      engine.declareSource(mqttSource(r.sensorIdTemplate, r.regionOffset, r.regionLength, r.ttlMs))
    }
    declarable.length
  }

  private def mqttIngest(sensorId: String, offset: Int, length: Int,
                          values: Vector[Double], ttlMs: Long,
                          topic: String, mappingId: String): Unit = {
    // Idempotent: declared at registration for every rule with a static
    // sensorIdTemplate, and here for the topic-interpolated ones. Either way
    // the record exists inactive before updateSensorValue earns it activity.
    engine.declareSource(mqttSource(sensorId, offset, length, ttlMs))
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
        val declared = declareMqttSources(cfg.rules)
        println(s"[mqtt-bridge] started — broker=${cfg.brokerUrl} mappings=${cfg.rules.size} declared=$declared")
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
  // Canonical default shared by every runtime; override per engine with
  // OLLAMA_MODEL. See RealityEngine_CI/docs/OLLAMA_INTEGRATION.md.
  private val ollamaModel        = sys.env.getOrElse("OLLAMA_MODEL",      "llama3.1:8b")
  private val ollamaCompletionSourceMappingId = sys.env.getOrElse("OLLAMA_COMPLETION_SOURCE_MAPPING_ID", "agent-completion-risk")
  private val openAiBaseUrl      = sys.env.getOrElse("OPENAI_BASE_URL",   "https://api.openai.com/v1")
  private val openAiApiKey       = sys.env.get("OPENAI_API_KEY")
  private val openAiModel        = sys.env.getOrElse("OPENAI_MODEL",      "gpt-5")
  private val openAiCompletionSourceMappingId = sys.env.getOrElse("OPENAI_COMPLETION_SOURCE_MAPPING_ID", "agent-completion-risk")
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
  private val triggerDispatchMode = sys.env.get("TRIGGER_DISPATCH_MODE").filter(_.nonEmpty).getOrElse("dry-run")
  private val triggerGraphqlEndpoint =
    sys.env.get("TRIGGER_GRAPHQL_URL").filter(_.nonEmpty).getOrElse(localAiApiUrl.stripSuffix("/") + "/graphql")
  private def truthyEnv(v: String): Boolean = Set("1", "true", "TRUE", "yes", "YES").contains(v)
  private val triggersEnabled = sys.env.get("TRIGGERS_ENABLED").exists(truthyEnv)

  // ── Machine catalog (trigger dispatch) ────────────────────────────────────
  // A read-through cache of the RE's machine list, which dispatch reads to
  // resolve a merge entry's machine. machineCatalogRefreshedAt is 0 until the
  // first successful fetch, so 0 is an unambiguous never-loaded marker
  // (droppedCatalogCold, RealityEngine_LSP#63). Warm-up retries on a backoff
  // until that first fetch lands -- the PE usually starts before its RE is
  // listening -- then refreshes every 60 s, as LSP does.
  private val machineCatalog            = new AtomicReference[Map[String, Json]](Map.empty)
  private val machineCatalogRefreshedAt = new java.util.concurrent.atomic.AtomicLong(0L)
  private def refreshMachineCatalog(): Boolean =
    try {
      val resp = basicRequest.get(uri"$realityEngineUrl/api/machines").response(asString).send(sttpBackend)
      val machines = if (resp.isSuccess)
        resp.body.toOption.flatMap(b => io.circe.parser.parse(b).toOption)
          .flatMap(j => j.hcursor.downField("machines").as[Vector[Json]].toOption.orElse(j.as[Vector[Json]].toOption))
      else None
      machines.exists { ms =>
        machineCatalog.set(ms.flatMap(m => m.hcursor.get[String]("id").toOption.filter(_.nonEmpty).map(_ -> m)).toMap)
        machineCatalogRefreshedAt.set(System.currentTimeMillis())
        true
      }
    } catch { case _: Exception => false }
  if (triggersEnabled) {
    val t = new Thread(() => {
      var delay = 100L
      while (!refreshMachineCatalog()) { Thread.sleep(delay); delay = math.min(delay * 2, 5000L) }
      while (true) { Thread.sleep(60000L); refreshMachineCatalog() }
    }, "machine-catalog-refresher")
    t.setDaemon(true)
    t.start()
  }

  private val triggerDispatcher = new TriggerDispatcher(
    enabled          = triggersEnabled,
    mode             = triggerDispatchMode,
    graphqlEndpoint  = triggerGraphqlEndpoint,
    realityEngineUrl = realityEngineUrl,
    catalog          = () => (machineCatalog.get(), machineCatalogRefreshedAt.get()),
    semanticsBase    = name => SemanticMetrics.baseIriFor(Some(name)),
    onSemantics      = (joined, escalation) => SemanticMetrics.recordDispatch(joined, escalation),
  )

  // ── ACP / OpenClaw settings, with C++'s precedence: built-in default, then
  // the acp integration entry of INTEGRATIONS_CONFIG, then the environment. ──
  private val acpEntry: Json = try {
    val src = scala.io.Source.fromFile(sys.env.getOrElse("INTEGRATIONS_CONFIG", "config/integrations.json"))
    val text = try src.mkString finally src.close()
    io.circe.parser.parse(text).toOption
      .flatMap(_.hcursor.downField("integrations").as[Vector[Json]].toOption)
      .flatMap(_.find(i => i.hcursor.get[String]("kind").toOption.exists(k => k == "acp" || k == "openclaw-acp")))
      .getOrElse(Json.obj())
  } catch { case _: Exception => Json.obj() }
  private def acpSetting(key: String, env: Seq[String], default: String): String =
    env.reverse.flatMap(sys.env.get).headOption
      .orElse(acpEntry.hcursor.get[String](key).toOption)
      .getOrElse(default)
  private val acpCfgEnabled  = sys.env.get("ACP_ENABLED").map(truthyEnv)
    .orElse(acpEntry.hcursor.get[Boolean]("enabled").toOption).getOrElse(true)
  private val acpCfgPlatform = acpSetting("platform", Seq("ACP_PLATFORM"), "OpenClaw")
  private val acpCfgSurface  = acpSetting("surface", Seq("ACP_SURFACE"), "xACP")
  private val acpCfgCommand  = acpSetting("command", Seq("ACP_COMMAND", "OPENCLAW_ACP_COMMAND"), "openclaw acp")
  private val acpCfgGateway  = acpSetting("gatewayUrl", Seq("ACP_GATEWAY_URL", "OPENCLAW_GATEWAY_URL"), "ws://127.0.0.1:18789")
  private val acpCfgSession  = acpSetting("sessionKey", Seq("ACP_SESSION_KEY", "OPENCLAW_ACP_SESSION"), "agent:main:main")
  private val acpCfgTarget   = acpSetting("targetAgent", Seq("ACP_TARGET_AGENT"), "openclaw")
  private val acpCfgMapping  = acpSetting("completionSourceMappingId", Seq("ACP_COMPLETION_SOURCE_MAPPING_ID"), "acp-openclaw-completion")
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

  // ── localAI invoke allow-list ─────────────────────────────────────────────
  // allowedOperations on the localai integration of INTEGRATIONS_CONFIG: the
  // curated policy every runtime reads (SURFACE_SPEC.md, localAI invoke
  // contract). None configured allows nothing. This runtime used a hand-written
  // OpenAI-shaped list, most of which localAIStack does not serve.
  private val (localAiAllowedOps: Vector[Json], localAiAllowedSource: Option[String]) = {
    val cfgPath = sys.env.getOrElse("INTEGRATIONS_CONFIG", "config/integrations.json")
    try {
      val src  = scala.io.Source.fromFile(cfgPath)
      val text = try src.mkString finally src.close()
      io.circe.parser.parse(text).toOption.flatMap { json =>
        json.hcursor.downField("integrations").as[Vector[Json]].getOrElse(Vector.empty)
          .find(_.hcursor.get[String]("kind").toOption.contains("localai"))
          .flatMap(_.hcursor.downField("allowedOperations").as[Vector[Json]].toOption)
          .map(ops => (ops, Some(cfgPath)))
      }.getOrElse((Vector.empty[Json], None))
    } catch { case _: Exception => (Vector.empty[Json], None) }
  }

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

  /** Loading the source-mapping registry is the boot-time half of integration
    * registration: `INTEGRATIONS_CONFIG` is where ACP/OpenClaw, Ollama, OpenAI,
    * HealthKit and CareKit declare which perceptual-space region each of their
    * signals owns. Declaring here is what gives those integrations a
    * declared-inactive state at all — before this they had none, because the
    * source record was conjured `active = true` by the first value to arrive
    * (RealityEngine_CI#163 point 2a).
    *
    * A mapping is declarable when it carries a region and names its sensor
    * without interpolation. Most do not: `agent.{agent}.completion` and
    * `carekit.{taskId}.{sampleType}` name one source per agent and per task, so
    * the set is not knowable from configuration alone and those still declare
    * on first ingest — inactive, through the same idempotent `declareSource`.
    */
  /** Whether the integration that owns a mapping is enabled on this runtime.
    *
    * Registration is an integration declaring its sources (RealityEngine_CI
    * SURFACE_SPEC.md). An integration that is not running has not registered,
    * so its sources are not part of the set — declaring them anyway puts
    * membership in the PE that no integration owns.
    *
    * It showed up as three `healthkit.*` sensors on a run started with no
    * HealthKit bridge: cpp-1 and lsp-1 held 12 sources, scala-1 held 15
    * (RealityEngine_Scala#63). They were inactive, so they contributed no
    * values, but membership is compared and three runtimes disagreeing about
    * it is a divergence before any stimulus.
    *
    * Unknown origins declare. This gates the integrations that have an
    * enable flag; anything else keeps its existing behaviour rather than
    * being silently suppressed by a default this function chose.
    */
  private def mappingIntegrationEnabled(origin: Option[String]): Boolean =
    PerceptionRoutes.integrationEnabled(origin, hkEnabled, ckEnabled, acpEnabled)

  private def declareMappedSources(): Int = {
    val declared = sourceMappings.values.toVector.flatMap { m =>
      val c = m.hcursor
      val sensorId = c.get[String]("sensorId").toOption.filter(_.nonEmpty)
        .orElse(c.get[String]("sensorIdTemplate").toOption.filter(t => t.nonEmpty && !t.contains("{")))
      // Same derivation the `origin` field below uses, so the gate and the
      // recorded origin cannot disagree about which integration owns a mapping.
      val owner = c.get[String]("origin").toOption
        .orElse(c.get[String]("id").toOption.map(_.takeWhile(ch => ch != ':' && ch != '-')))
      for {
        sid    <- sensorId
        region <- c.get[Region]("region").toOption
        if mappingIntegrationEnabled(owner)
      } yield engine.declareSource(SensorSourceConfig(
        id          = sid,
        name        = c.get[String]("name").getOrElse(sid),
        region      = region,
        active      = false,
        sensorId    = sid,
        lastValue   = Vector.empty,
        lastUpdated = None,
        ttlMs       = c.get[Long]("ttlMs").getOrElse(300000L),
        origin      = c.get[String]("origin").toOption
                       .orElse(c.get[String]("id").toOption.map(_.takeWhile(ch => ch != ':' && ch != '-'))),
      ))
    }
    declared.length
  }

  {
    val n = declareMappedSources()
    if (n > 0) println(s"[integrations] declared $n source(s) from the source-mapping registry")
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

    // Declare the sensor source against the mapping's perceptual-space region.
    // Idempotent, and inactive: the completion arriving immediately below is
    // what earns it activity. Templates that interpolate the agent name cannot
    // be declared from configuration alone, so this is where those first
    // appear — declared, then activated by their value, never conjured live.
    mapping.foreach { m =>
      for {
        offset <- m.hcursor.downField("region").get[Int]("offset").toOption
        length <- m.hcursor.downField("region").get[Int]("length").toOption
      } {
        val ttl = m.hcursor.get[Long]("ttlMs").getOrElse(300000L)
        engine.declareSource(com.realityengine.perception.models.SensorSourceConfig(
          id          = sensorId,
          name        = m.hcursor.get[String]("name").getOrElse(s"acp:$sensorId"),
          region      = com.realityengine.perception.models.Region(offset, length),
          active      = false,
          sensorId    = sensorId,
          lastValue   = Vector.empty,
          lastUpdated = None,
          ttlMs       = ttl,
          origin      = Some(body.hcursor.get[String]("provider").getOrElse("openclaw")),
        ))
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
    // Completions are not dispatch records and do not go in the dispatch
    // ledger: C++ and LSP keep it to records built from envelopes, and the
    // record key set is 3-of-3 (SURFACE_SPEC.md, Dispatch surface shapes).
    broadcast(Json.obj("type" -> "agent.completion.received".asJson, "record" -> record))
    record
  }

  private def acpStatusJson: Json = Json.obj(
    "enabled"                   -> acpCfgEnabled.asJson,
    "platform"                  -> acpCfgPlatform.asJson,
    "surface"                   -> acpCfgSurface.asJson,
    "adapter"                   -> "openclaw-xacp".asJson,
    "command"                   -> acpCfgCommand.asJson,
    "gatewayUrl"                -> acpCfgGateway.asJson,
    "sessionKey"                -> acpCfgSession.asJson,
    "targetAgent"               -> acpCfgTarget.asJson,
    "completionSourceMappingId" -> acpCfgMapping.asJson,
    "dispatchEndpoint"          -> "/api/integrations/acp/dispatch".asJson,
    "completionEndpoint"        -> "/api/integrations/completions".asJson,
    "noWaitDispatch"            -> true.asJson,
    "contract"                  -> Json.obj(
      "dispatch"   -> "Record an ACP/OpenClaw handoff receipt only; do not run or wait for the harness in the PE cycle.".asJson,
      "completion" -> "External ACP/OpenClaw adapters commit finished results through /api/integrations/completions.".asJson
    )
  )

  private def probeHttp(url: String): (Boolean, String) =
    try {
      val resp = basicRequest.get(uri"$url").response(asString).send(sttpBackend)
      (resp.isSuccess, resp.body.fold(identity, identity))
    } catch { case e: Exception => (false, e.getMessage) }

  private def decodePointerToken(token: String): String =
    token.replace("~1", "/").replace("~0", "~")

  private def evalJsonPointer(doc: Json, pointer: String): Option[Json] = {
    if (pointer.isEmpty) Some(doc)
    else if (!pointer.startsWith("/")) None
    else {
      pointer.drop(1).split("/", -1).foldLeft(Option(doc)) { (cursor, raw) =>
        cursor.flatMap { json =>
          val token = decodePointerToken(raw)
          json.asObject.flatMap(_.apply(token))
            .orElse(json.asArray.flatMap { arr =>
              token.toIntOption.filter(i => i >= 0 && i < arr.length).map(arr)
            })
        }
      }
    }
  }

  private def numericLeaf(json: Json): Option[Double] =
    json.asNumber.map(_.toDouble)
      .orElse(json.asBoolean.map(if (_) 1.0 else 0.0))
      .orElse(json.asString.flatMap(s => Try(s.toDouble).toOption))
      .filter(_.isFinite)

  private def clamp01(value: Double): Double =
    if (!value.isFinite) 0.0 else math.max(0.0, math.min(1.0, value))

  private def normalizeCompletionValues(values: Vector[Double], mapping: Json): Vector[Double] = {
    val normalize = mapping.hcursor.downField("normalize")
    val mode      = normalize.get[String]("mode").getOrElse("passthrough")
    val clamp     = normalize.get[Boolean]("clamp").getOrElse(false)
    values.map { value =>
      val normalized = mode match {
        case "minmax" =>
          val min  = normalize.get[Double]("min").getOrElse(0.0)
          val max  = normalize.get[Double]("max").getOrElse(0.0)
          val span = max - min
          if (span == 0.0) 0.0 else (value - min) / span
        case "linear" =>
          value * normalize.get[Double]("scale").getOrElse(1.0) + normalize.get[Double]("offset").getOrElse(0.0)
        case _ => value
      }
      if (clamp) clamp01(normalized) else normalized
    }
  }

  private def finiteValues(values: Vector[Double]): Either[String, Vector[Double]] =
    if (values.forall(_.isFinite)) Right(values) else Left("provider completion value is not finite")

  private def fallbackValues(doc: Json): Either[String, Vector[Double]] =
    doc.hcursor.downField("values").as[Vector[Double]]
      .orElse(doc.hcursor.downField("completion").downField("values").as[Vector[Double]])
      .left.map(_ => "provider response did not include completion values")
      .flatMap(finiteValues)

  private def completionValuesFromContent(doc: Json, mapping: Option[Json]): Either[String, Vector[Double]] =
    mapping match {
      case Some(m) if m.hcursor.downField("extract").get[String]("type").toOption.contains("json") =>
        val extract = m.hcursor.downField("extract")
        val pointers = extract.downField("pointers").as[Vector[String]].toOption
          .getOrElse(extract.get[String]("pointer").toOption.toVector)
        if (pointers.isEmpty) fallbackValues(doc).map(normalizeCompletionValues(_, m))
        else {
          pointers.foldLeft(Right(Vector.empty): Either[String, Vector[Double]]) { (acc, pointer) =>
            for {
              values <- acc
              leaf   <- evalJsonPointer(doc, pointer).toRight(s"missing required JSON pointer: $pointer")
              number <- numericLeaf(leaf).toRight(s"JSON pointer resolved to a non-finite value: $pointer")
            } yield values :+ number
          }.map(normalizeCompletionValues(_, m))
        }
      case Some(m) => fallbackValues(doc).map(normalizeCompletionValues(_, m))
      case None    => fallbackValues(doc)
    }

  private def topLevelPointerKey(pointer: String): String =
    pointer.stripPrefix("/").split("/", 2).headOption.map(decodePointerToken).filter(_.nonEmpty).getOrElse("value")

  private def completionSchema(mapping: Option[Json]): Json = {
    val pointers = mapping.toVector.flatMap { m =>
      val extract = m.hcursor.downField("extract")
      if (extract.get[String]("type").toOption.contains("json"))
        extract.downField("pointers").as[Vector[String]].toOption
          .getOrElse(extract.get[String]("pointer").toOption.toVector)
      else Vector.empty
    }
    if (pointers.nonEmpty) {
      val keys = pointers.map(topLevelPointerKey)
      Json.obj(
        "type" -> "object".asJson,
        "additionalProperties" -> false.asJson,
        "properties" -> Json.obj(keys.map(k => k -> Json.obj("type" -> Json.arr("number".asJson, "boolean".asJson))): _*),
        "required" -> Json.arr(keys.map(_.asJson): _*),
      )
    } else {
      Json.obj(
        "type" -> "object".asJson,
        "additionalProperties" -> false.asJson,
        "properties" -> Json.obj("values" -> Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "number".asJson))),
        "required" -> Json.arr("values".asJson),
      )
    }
  }

  private def openAiTextFormat(mapping: Option[Json]): Json =
    Json.obj("format" -> Json.obj(
      "type"   -> "json_schema".asJson,
      "name"   -> "reality_engine_completion".asJson,
      "strict" -> true.asJson,
      "schema" -> completionSchema(mapping),
    ))

  private def openAiOutputText(response: Json): String =
    response.hcursor.get[String]("output_text").getOrElse {
      response.hcursor.downField("output").downArray.downField("content").downArray.get[String]("text").getOrElse("")
    }

  private def assertOpenAiReady(response: Json): Either[String, Unit] = {
    response.hcursor.downField("error").focus.filter(!_.isNull).map(_.noSpaces) match {
      case Some(error) => Left(error)
      case None =>
        val status = response.hcursor.get[String]("status").getOrElse("")
        if (status.nonEmpty && status != "completed") Left(s"OpenAI response did not complete: $status")
        else {
          val refused = response.hcursor.get[String]("refusal").toOption.exists(_.nonEmpty) ||
            response.hcursor.downField("output").values.toVector.flatten
              .flatMap(_.hcursor.downField("content").values.toVector.flatten)
              .exists(part => part.hcursor.get[String]("type").toOption.contains("refusal") || part.hcursor.get[String]("refusal").toOption.exists(_.nonEmpty))
          if (refused) Left("OpenAI response refused") else Right(())
        }
    }
  }

  private def receipt(provider: String, status: String, model: String, externalRunId: Option[String] = None, error: Option[String] = None): Json =
    Json.obj(
      "provider"      -> provider.asJson,
      "adapter"       -> provider.asJson,
      "status"        -> status.asJson,
      "externalRunId" -> externalRunId.asJson,
      "error"         -> error.asJson,
      "providerReceipt" -> Json.obj("model" -> model.asJson),
    )

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

  /** @param compact omit the machine results from the emitted step, as
    *   `POST /api/push {"compact": true}` asks for. Note this deliberately
    *   does not change what we ask the Reality Engine for: machineResults is
    *   what VectorAggregator gates the perceptual space and the mergeBatch on
    *   below, so requesting less would quietly change what a push *does*
    *   rather than what it reports.
    * @param only the caller's subset selector (RealityEngine_CI#367). Applied
    *   to the reply, for the same reason `compact` is: the Reality Engine
    *   filters `machineResults` by the selector like every other field, so
    *   forwarding it would hand `VectorAggregator.aggregate` a subset of the
    *   corpus's outputs and move the next InputSpaceVector. Measured on C++,
    *   which did forward it: 408 machines fed the aggregator unfiltered and 0
    *   fed it with a selector present.
    */
  def doPush(compact: Boolean = false, only: Option[Json] = None): Future[PushResult] = Future {
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
        // `lastPush` is set below, once `parsed` holds the step — it is the
        // step object now, not this timestamp (SURFACE_SPEC.md, #407).

        // Semantic audit (SEMANTIC_AUDIT_CONTRACT.md): one re:PerceptionEvent
        // per active source whose region this push wrote into the universal
        // Reality Event, attributed to the upstream that produced it and
        // joined to the corpus ABox when the source names a machine.
        engine.getSources.filter(_.active).foreach { src =>
          val machineName = src match {
            case t: TestSourceConfig => Option(t.machineName).filter(_.nonEmpty)
            case _                   => None
          }
          val integration = src match {
            case s: SensorSourceConfig => s.origin.filter(_.nonEmpty).getOrElse("sensor")
            case _: TestSourceConfig   => "test"
            case _                     => "unattributed"
          }
          val base = SemanticMetrics.baseIriFor(machineName)
          SemanticMetrics.recordPerceptionEvent(integration, base.isDefined)
          recordSemanticAudit(Json.obj(
            "type"        -> "re:PerceptionEvent".asJson,
            "at"          -> ts.asJson,
            "sourceId"    -> src.id.asJson,
            "machineName" -> machineName.map(_.asJson).getOrElse(Json.Null),
            "machineIri"  -> base.map(b => s"$b#machine".asJson).getOrElse(Json.Null),
            "offset"      -> src.region.offset.asJson,
            "length"      -> src.region.length.asJson,
            "integration" -> integration.asJson,
          ))
        }

        val parsed = resp.body.toOption
          .flatMap(b => io.circe.parser.parse(b).toOption)
          .getOrElse(Json.Null)
        // The step itself, which carries its own `timestamp`, so the "when"
        // this field used to hold is still readable as `lastPush.timestamp`.
        lastPush.set(parsed)

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

        // Consume the Reality Engine's mergeBatch; do not rebuild it.
        //
        // This used to `deepMerge` a locally reconstructed batch over the RE's,
        // which silently discarded the authoritative one. The RE composes that
        // batch at the end of the machine's atomic step — folded value, joined
        // governance, deprecation mark, provenance union — and emits it
        // unconditionally. Rebuilding it here from `machineResults` could only
        // ever produce a subset: `machineResults` carries no CES lifecycle data,
        // so the reconstruction could not express `deprecation` at all, and
        // Manager's trigger envelope reads that field
        // (perception-engine/backend/src/triggers/envelopeBuilder.ts).
        //
        // The C++ and LSP Perception Engines both read the RE's batch. This one
        // rebuilding it was the outlier, and it is the same defect shape as
        // RealityEngine_CI#154 one level up: the PE recomputing something the RE
        // is authoritative for, and losing information doing it.
        //
        // The reconstruction is kept ONLY as a fallback for a Reality Engine
        // that predates the fold move and sends no batch. That is a genuine
        // mixed-version case, not a silent default: a current RE always sends
        // one, so this arm should never be taken against a matching stack.
        val machineResults = parsed.hcursor.downField("machineResults").focus.getOrElse(Json.Null)
        val reSuppliedBatch = parsed.hcursor.downField("mergeBatch").focus.exists(!_.isNull)
        val withMergeBatch =
          if (reSuppliedBatch) parsed
          else parsed.deepMerge(Json.obj(
            "mergeBatch" -> Json.arr(VectorAggregator.mergeBatch(machineResults, machineCorpus.get()): _*)
          ))
        // Narrowed after the aggregation and the dispatch/audit passes above,
        // so asking for less never means the engine did less — only that it
        // reported less. Before the redaction, because the selector is defined
        // in terms of machineResults: `selectedIds` resolves the caller's
        // machine *names* to this runtime's minted ids, and there is nowhere
        // else in the step those two are carried together.
        // Trigger dispatch reads the RE's batch -- after the aggregation, before
        // the reply is narrowed, so asking for less never means dispatching less.
        val created = triggerDispatcher.dispatchStep(withMergeBatch)
        if (created.nonEmpty) {
          dispatchLedger.updateAndGet(l => (l ++ created).takeRight(dispatchLedgerLimit))
          created.foreach { r =>
            val c = r.hcursor
            broadcast(Json.obj(
              "type"          -> "trigger.envelope.created".asJson,
              "envelopeId"    -> c.downField("envelopeId").focus.getOrElse(Json.Null),
              "correlationId" -> c.downField("correlationId").focus.getOrElse(Json.Null),
              "dispatchId"    -> c.downField("id").focus.getOrElse(Json.Null),
              "target"        -> c.downField("target").focus.getOrElse(Json.Null),
              "mode"          -> triggerDispatchMode.asJson
            ))
          }
        }
        val selected = PushRequest.applySelector(withMergeBatch, only)
        val stepJson = Some(
          if (compact) PushRequest.redactMachineResults(selected)
          else selected
        )

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
    // getSources, deliberately: the store caches the *stored* flag. Persisting
    // the reported one would make a read mutate state — a sensor whose TTL
    // happened to lapse during a save would come back from the store demoted,
    // and the demotion would outlive the restart that reset it.
    store.save(engine.getSources)
    broadcastState()
  }(system.dispatchers.lookup("blocking-io-dispatcher"))

  private def resetAndBroadcast(): Unit = {
    engine.reset()
    lastPush.set(Json.Null)
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

    // ── Prometheus metrics (PE_METRICS_CONTRACT.md) ────────────────────────
    // The semantic_* block must stay byte-identical across PE runtimes after
    // normalizing the runtime label; SemanticMetrics owns the exact wording.
    path("api" / "metrics") {
      get {
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`,
          SemanticMetrics.render(
            sources            = engine.getSources.length,
            globalStep         = engine.globalStep,
            vectorSize         = engine.vectorDimension,
            // The metric stays a timestamp; it now reads the one inside the step.
            lastPushMs         = lastPush.get().hcursor.get[Long]("timestamp").getOrElse(0L),
            auditBufferRecords = semanticAudit.size(),
          )))
      }
    },

    // ── Semantic audit trail (SEMANTIC_AUDIT_CONTRACT.md, milestone M5) ────
    path("api" / "audit" / "semantics") {
      get { parameters("limit".as[Int].withDefault(100)) { limit =>
        val bounded = math.max(0, math.min(limit, semanticAuditCapacity))
        val records = {
          import scala.jdk.CollectionConverters._
          semanticAudit.asScala.toVector.takeRight(bounded)
        }
        complete(Json.obj("records" -> Json.arr(records: _*), "count" -> records.length.asJson))
      } }
    },

    // ── Push ────────────────────────────────────────────────────────────────
    path("api" / "push") {
      post {
        // extractStrictEntity, not entity(as[...]): no unmarshaller is
        // involved, so this cannot be captured by implicit scope. It reads
        // the bytes whatever the content type, and an absent body is simply
        // empty — which matters because this route took no entity at all
        // before, so callers post empty bodies and non-JSON content types.
        //
        // as[String] looks like the obvious choice and is a trap here:
        // FailFastCirceSupport._ is imported at the top of this file and
        // supplies a FromEntityUnmarshaller for any Decoder, so as[String]
        // resolves to "decode a JSON string" and answers a JSON *object*
        // with 400 "Got value '{"compact":true}' with wrong type, expecting
        // string" — rejecting exactly the request it was added to read.
        extractStrictEntity(3.seconds) { strict =>
          val raw = strict.data.utf8String
          onComplete(doPush(PushRequest.compactFrom(raw), PushRequest.onlyFrom(raw))) {
            case Success(r) =>
              val id     = s"push-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
              val record = r.asJson.deepMerge(Json.obj("id" -> id.asJson))
              pushHistory.updateAndGet(h => (h :+ record).takeRight(pushHistoryLimit))
              complete(record)
            case Failure(e) => complete(StatusCodes.InternalServerError ->
              Json.obj("error" -> e.getMessage.asJson))
          }
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
        // reportedSources, not getSources: `active` is derived at every read as
        // `stored AND validated`, so an expired sensor stops advertising itself
        // as live the moment its TTL lapses rather than at the next reset
        // (RealityEngine_CI#175). The stored flag is untouched by the read.
        get { complete(Json.obj("sources" -> engine.reportedSources.asJson)) },
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
              complete(StatusCodes.OK -> Json.obj("source" -> engine.reported(src).asJson))
            case None =>
              // A caller may not assert a sensor into activity it has not
              // earned. Registration declares a source completely and inactive
              // (RealityEngine_CI#163 point 2a); activity is earned by the
              // first value (point 2b). The integration paths already honour
              // this — they go through declareSource — but POST /api/sources
              // reaches addSource directly, which is a constructor and takes
              // the flag it is given. So an external caller could do what no
              // integration could (RealityEngine_CI#199).
              //
              // Derived, not forced to false: a create that carries a live
              // lastValue/lastUpdated still comes out active, which is the
              // MQTT auto-provision and signal-ingest shape and is what C++
              // does in add_source.
              val src = engine.addSource(engine.deriveSensorActivity(config, System.currentTimeMillis()))
              onComplete(saveAndBroadcast()) { _ =>
                complete(StatusCodes.OK -> Json.obj("source" -> engine.reported(src).asJson))
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
              // The patch merges onto the *stored* source (engine.getSource) and
              // the response reports the *validated* one — a PATCH must be able
              // to set a flag the read then declines to report, not have a stale
              // reported value fed back into storage.
              engine.updateSource(id, merged) match {
                case None =>
                  complete(StatusCodes.NotFound -> Json.obj("error" -> "Source not found".asJson))
                case Some(updated0) =>
                  // Activation is earned; deactivation is not. updateSource
                  // derives a sensor's flag from value liveness and ignores what
                  // the caller asked for, which is right for activation — no
                  // caller may assert a sensor into activity it has not earned
                  // (RealityEngine_CI#199). Applied to deactivation too it would
                  // silently drop an explicit `"active": false` and leave a live
                  // sensor with no way to be paused (RealityEngine_CPP#43).
                  //
                  // Honour the clear, and only the clear. Telling "asked for
                  // false" from "field absent" needs the request body, not the
                  // merged SourceConfig, which is why this reads the JSON.
                  val asksForDeactivation =
                    body.hcursor.get[Boolean]("active").toOption.contains(false)
                  val updated =
                    if (asksForDeactivation && engine.deactivateSource(id))
                      engine.getSource(id).getOrElse(updated0)
                    else updated0
                  onComplete(saveAndBroadcast()) { _ =>
                    complete(Json.obj("source" -> engine.reported(updated).asJson))
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
    // Shapes settled 3-of-3 in RealityEngine_CI SURFACE_SPEC.md, "Dispatch
    // surface shapes". The record contents still differ until this runtime has
    // a dispatcher (#149); the wrappers and PATCH semantics agree now.
    path("api" / "dispatch" / "ledger") {
      get { complete(Json.obj(
        "enabled" -> triggersEnabled.asJson,
        "mode"    -> triggerDispatchMode.asJson,
        "records" -> Json.arr(dispatchLedger.get(): _*)
      )) }
    },
    path("api" / "dispatch" / "records" / Segment) { id =>
      concat(
        get {
          dispatchLedger.get().find(_.hcursor.get[String]("id").toOption.contains(id)) match {
            case Some(r) => complete(Json.obj("record" -> r))
            case None    => complete(StatusCodes.NotFound -> Json.obj("error" -> "Dispatch record not found".asJson))
          }
        },
        patch { entity(as[Json]) { body =>
          if (!body.isObject) complete(StatusCodes.BadRequest -> Json.obj("error" -> "dispatch update body must be a JSON object".asJson))
          else {
            val now = System.currentTimeMillis()
            val updated = dispatchLedger.updateAndGet(_.map { r =>
              if (r.hcursor.get[String]("id").toOption.contains(id)) PerceptionRoutes.patchDispatchRecord(r, body, now) else r
            })
            updated.find(_.hcursor.get[String]("id").toOption.contains(id)) match {
              case Some(r) =>
                broadcast(Json.obj(
                  "type"       -> "dispatch.record.updated".asJson,
                  "dispatchId" -> id.asJson,
                  "status"     -> r.hcursor.downField("status").focus.getOrElse(Json.Null),
                  "target"     -> r.hcursor.downField("target").focus.getOrElse(Json.Null),
                  "attempts"   -> r.hcursor.downField("attempts").focus.getOrElse(Json.fromInt(0)),
                  "timestamp"  -> now.asJson
                ))
                complete(Json.obj("success" -> true.asJson, "record" -> r))
              case None => complete(StatusCodes.NotFound -> Json.obj("error" -> "Dispatch record not found".asJson))
            }
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
          "completionSourceMappingId" -> ollamaCompletionSourceMappingId.asJson,
          "reachable"  -> reachable.asJson,
          "models"     -> Json.arr(models: _*)
        ))
      }
    },
    path("api" / "integrations" / "ollama" / "dispatch") {
      post { entity(as[Json]) { body =>
        val model           = body.hcursor.get[String]("model").getOrElse(ollamaModel)
        val sourceMappingId = body.hcursor.get[String]("sourceMappingId").getOrElse(ollamaCompletionSourceMappingId)
        val mapping         = sourceMappings.get(sourceMappingId)
        val agentId         = body.hcursor.get[String]("agentId").orElse(body.hcursor.get[String]("agent")).getOrElse("ollama")
        val messages = body.hcursor.downField("messages").as[Json].getOrElse(Json.arr(
          Json.obj("role" -> "system".asJson, "content" -> "Return one JSON object matching the configured RealityEngine source mapping.".asJson),
          Json.obj("role" -> "user".asJson, "content" -> body.hcursor.get[String]("prompt").getOrElse("Produce a provider completion.").asJson),
        ))
        val reqBody  = Json.obj("model" -> model.asJson, "messages" -> messages, "format" -> "json".asJson, "stream" -> false.asJson).noSpaces
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
            completionValuesFromContent(cJson, mapping) match {
              case Right(values) =>
                val record = ingestCompletion(Json.obj(
                  "provider" -> "ollama".asJson,
                  "agent" -> agentId.asJson,
                  "sourceMappingId" -> sourceMappingId.asJson,
                  "values" -> values.asJson,
                  "metadata" -> Json.obj("model" -> model.asJson, "content" -> content.asJson),
                ))
                complete(Json.obj(
                  "success" -> true.asJson,
                  "provider" -> "ollama".asJson,
                  "content" -> content.asJson,
                  "record" -> record,
                  "receipt" -> receipt("ollama", "sent", model, parsed.hcursor.get[String]("created_at").toOption),
                ))
              case Left(error) =>
                complete(StatusCodes.BadGateway -> Json.obj(
                  "success" -> false.asJson,
                  "provider" -> "ollama".asJson,
                  "error" -> error.asJson,
                  "receipt" -> receipt("ollama", "failed", model, error = Some(error)),
                ))
            }
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
        "keyConfigured" -> openAiApiKey.isDefined.asJson,
        "completionSourceMappingId" -> openAiCompletionSourceMappingId.asJson
      )) }
    },
    path("api" / "integrations" / "openai" / "dispatch") {
      post { entity(as[Json]) { body =>
        openAiApiKey match {
          case None =>
            complete(StatusCodes.BadRequest -> Json.obj("error" -> "OPENAI_API_KEY not configured".asJson))
          case Some(key) =>
            val input           = body.hcursor.get[String]("input").orElse(body.hcursor.get[String]("prompt")).getOrElse("")
            val model           = body.hcursor.get[String]("model").getOrElse(openAiModel)
            val sourceMappingId = body.hcursor.get[String]("sourceMappingId").getOrElse(openAiCompletionSourceMappingId)
            val mapping         = sourceMappings.get(sourceMappingId)
            val agentId         = body.hcursor.get[String]("agentId").orElse(body.hcursor.get[String]("agent")).getOrElse("openai")
            val reqBody = Json.obj(
              "model" -> model.asJson,
              "input" -> input.asJson,
              "text" -> openAiTextFormat(mapping),
              "metadata" -> Json.obj("sourceMappingId" -> sourceMappingId.asJson, "agent" -> agentId.asJson),
              "stream" -> false.asJson,
            ).noSpaces
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
                assertOpenAiReady(parsed) match {
                  case Left(error) =>
                    complete(StatusCodes.BadGateway -> Json.obj(
                      "success" -> false.asJson,
                      "provider" -> "openai".asJson,
                      "error" -> error.asJson,
                      "receipt" -> receipt("openai", "failed", model, parsed.hcursor.get[String]("id").toOption, Some(error)),
                    ))
                  case Right(_) =>
                    val outputText = openAiOutputText(parsed)
                    val cJson = io.circe.parser.parse(outputText).toOption.getOrElse(Json.Null)
                    completionValuesFromContent(cJson, mapping) match {
                      case Right(values) =>
                        val record = ingestCompletion(Json.obj(
                          "provider" -> "openai".asJson,
                          "agent" -> agentId.asJson,
                          "sourceMappingId" -> sourceMappingId.asJson,
                          "values" -> values.asJson,
                          "metadata" -> Json.obj("model" -> model.asJson, "responseId" -> parsed.hcursor.get[String]("id").toOption.asJson),
                        ))
                        complete(Json.obj(
                          "success" -> true.asJson,
                          "provider" -> "openai".asJson,
                          "outputText" -> outputText.asJson,
                          "record" -> record,
                          "receipt" -> receipt("openai", "sent", model, parsed.hcursor.get[String]("id").toOption),
                        ))
                      case Left(error) =>
                        complete(StatusCodes.BadGateway -> Json.obj(
                          "success" -> false.asJson,
                          "provider" -> "openai".asJson,
                          "error" -> error.asJson,
                          "receipt" -> receipt("openai", "failed", model, parsed.hcursor.get[String]("id").toOption, Some(error)),
                        ))
                    }
                }
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
    // ACP status and dispatch, as C++ and LSP already agree
    // (RealityEngine_CI scripts/test-openclaw-integration.sh holds both).
    path("api" / "integrations" / "acp" / "status") {
      get { complete(acpStatusJson) }
    },
    path("api" / "integrations" / "acp" / "dispatch") {
      post { entity(as[Json]) { body =>
        val c  = body.hcursor
        val id = c.get[String]("dispatchId").toOption.orElse(c.get[String]("id").toOption).filter(_.nonEmpty)
        id match {
          case None => complete(StatusCodes.BadRequest -> Json.obj("error" -> "ACP dispatch requires dispatchId".asJson))
          case Some(dispatchId) =>
            dispatchLedger.get().find(_.hcursor.get[String]("id").toOption.contains(dispatchId)) match {
              case None => complete(StatusCodes.NotFound -> Json.obj("error" -> "Dispatch record not found".asJson))
              case Some(record) =>
                val r = record.hcursor
                val targetAgent = c.get[String]("targetAgent").toOption
                  .orElse(c.get[String]("agent").toOption)
                  .getOrElse(r.get[String]("target").toOption.filter(_.nonEmpty).getOrElse(acpCfgTarget))
                val externalRunId = c.get[String]("externalRunId")
                  .getOrElse(s"acp-handoff-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000000)}")
                val handoff0 = Json.obj(
                  "protocol"                  -> "ACP".asJson,
                  "surface"                   -> acpCfgSurface.asJson,
                  "platform"                  -> acpCfgPlatform.asJson,
                  "adapter"                   -> "openclaw-xacp".asJson,
                  "command"                   -> c.get[String]("command").getOrElse(acpCfgCommand).asJson,
                  "gatewayUrl"                -> c.get[String]("gatewayUrl").getOrElse(acpCfgGateway).asJson,
                  "sessionKey"                -> c.get[String]("sessionKey").getOrElse(acpCfgSession).asJson,
                  "targetAgent"               -> targetAgent.asJson,
                  "completionEndpoint"        -> "/api/integrations/completions".asJson,
                  "completionSourceMappingId" -> c.get[String]("sourceMappingId").getOrElse(acpCfgMapping).asJson,
                  "noWaitDispatch"            -> true.asJson,
                  "prompt"                    -> c.get[String]("prompt").getOrElse(
                    "Handle this RealityEngine trigger envelope through the configured OpenClaw ACP session and return a PE completion values array.").asJson,
                  "dispatchId"                -> dispatchId.asJson,
                  "envelopeId"                -> r.downField("envelopeId").focus.getOrElse(Json.Null),
                  "correlationId"             -> r.downField("correlationId").focus.getOrElse(Json.Null)
                )
                val handoff = c.downField("metadata").focus.filter(_.isObject)
                  .fold(handoff0)(m => handoff0.mapObject(_.add("metadata", m)))
                val now = System.currentTimeMillis()
                dispatchLedger.updateAndGet(_.map { rec =>
                  if (rec.hcursor.get[String]("id").toOption.contains(dispatchId))
                    PerceptionRoutes.patchDispatchRecord(rec, Json.obj(
                      "status"            -> c.get[String]("status").getOrElse("accepted").asJson,
                      "adapter"           -> "openclaw-xacp".asJson,
                      "provider"          -> "acp".asJson,
                      "externalRunId"     -> externalRunId.asJson,
                      "incrementAttempts" -> c.get[Boolean]("incrementAttempts").getOrElse(true).asJson,
                      "clearError"        -> true.asJson,
                      "providerReceipt"   -> handoff
                    ), now)
                  else rec
                })
                complete(StatusCodes.Accepted -> Json.obj(
                  "success"        -> true.asJson,
                  "accepted"       -> true.asJson,
                  "dispatchId"     -> dispatchId.asJson,
                  "provider"       -> "acp".asJson,
                  "platform"       -> acpCfgPlatform.asJson,
                  "surface"        -> acpCfgSurface.asJson,
                  "externalRunId"  -> externalRunId.asJson,
                  "noWaitDispatch" -> true.asJson,
                  "handoff"        -> handoff
                ))
            }
        }
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
          "auth"         -> (if (hkBridgeToken.isDefined) "bridgeToken|bearer" else "none").asJson
        )
      )) }
    },
    path("api" / "integrations" / "healthkit" / "ingest") {
      post {
        optionalHeaderValueByName("Authorization") { authHeader =>
        entity(as[Json]) { body =>
          // No-token mode: allowed when HEALTHKIT_BRIDGE_TOKEN is unset.
          // If token is configured, require bridgeToken/token in body or
          // an equivalent Authorization: Bearer header.
          val bearerToken = authHeader.collect {
            case h if h.regionMatches(true, 0, "Bearer ", 0, 7) => h.drop(7).trim
          }
          val tokenOk = hkBridgeToken.forall { expected =>
            val bodyToken = body.hcursor.get[String]("bridgeToken").toOption
              .orElse(body.hcursor.get[String]("token").toOption)
            bodyToken.contains(expected) || bearerToken.contains(expected)
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
                    // Declare, then feed. The bridge handshake declares what the
                    // registry can name up front; a sample whose mapping
                    // interpolates its type or source name declares here, still
                    // inactive, and the value below is what activates it.
                    engine.declareSource(SensorSourceConfig(
                      id          = "",
                      name        = name,
                      region      = region,
                      active      = false,
                      sensorId    = sensorId,
                      lastValue   = Vector.empty,
                      lastUpdated = None,
                      ttlMs       = ttlMs,
                      origin      = Some("healthkit"),
                    ))
                    engine.updateSensorValue(sensorId, values)
                    val source = engine.findSensorBySensorId(sensorId)
                    val r = Json.obj(
                      "resolved"        -> true.asJson,
                      "sensorId"        -> sensorId.asJson,
                      "name"            -> name.asJson,
                      "type"            -> tpe.asJson,
                      "sourceName"      -> sourceName.asJson,
                      "sourceMappingId" -> mapId.asJson,
                      "region"          -> region.asJson,
                      "values"          -> values.asJson,
                      "source"          -> source.map(engine.reported(_).asJson).getOrElse(Json.obj("lastValue" -> values.asJson)),
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
    // Shape settled 3-of-3 (SURFACE_SPEC.md, localAI invoke contract). It was
    // {schema, events}, so no operationId could be resolved for the ledger.
    path("api" / "integrations" / "localai" / "catalog") {
      get {
        def fetch(p: String): Json = try {
          val resp = basicRequest.get(PerceptionRoutes.localAiInvokeUri(localAiApiUrl, p)).response(asString).send(sttpBackend)
          if (resp.isSuccess) io.circe.parser.parse(resp.body.fold(identity, identity)).toOption.getOrElse(Json.Null)
          else Json.Null
        } catch { case _: Exception => Json.Null }
        val (reachable, _) = probeHttp(localAiApiUrl)
        complete(Json.obj(
          "success" -> true.asJson,
          "status"  -> Json.obj(
            "enabled"     -> true.asJson,
            "configured"  -> true.asJson,
            "baseUrl"     -> localAiApiUrl.asJson,
            "reachable"   -> reachable.asJson,
            "machinesDir" -> localAiMachinesDir.map(_.asJson).getOrElse(Json.Null)
          ),
          "graphSchema"            -> fetch("/graph/schema"),
          "recentGraphQLEvents"    -> fetch("/graphql/events"),
          "invokeEndpoint"         -> "/api/integrations/localai/invoke".asJson,
          "allowedEndpoints"       -> Json.arr(localAiAllowedOps: _*),
          "allowedEndpointsSource" -> localAiAllowedSource.map(_.asJson).getOrElse(Json.Null),
          "realityBridge"          -> Json.obj(
            "sensors"           -> Json.arr("localai_rag_retrieval".asJson, "localai_rag_grading".asJson, "localai_agent_activity".asJson),
            "bootstrapEndpoint" -> "/api/integrations/localai/bootstrap".asJson,
            "signalEndpoint"    -> "/api/signals".asJson
          )
        ))
      }
    },
    path("api" / "integrations" / "localai" / "bootstrap") {
      post {
        val defaultSensors = List(
          ("localai_rag_retrieval",  "LocalAI RAG Retrieval",  0),
          ("localai_rag_grading",    "LocalAI RAG Grading",    4),
          ("localai_agent_activity", "LocalAI Agent Activity", 8)
        )
        // This route *is* the localAI integration registering, so it declares
        // its source set — inactive. Declaring these active was the sharpest
        // form of the 2a violation: a `localai` window can only ever hold the
        // answer to a dispatch this process made, so a source claiming to be
        // live there before one has gone out is claiming something impossible
        // (#54).
        val created = defaultSensors.flatMap { case (sid, name, offset) =>
          engine.findSensorBySensorId(sid) match {
            case Some(_) => None
            case None =>
              val src = engine.declareSource(SensorSourceConfig(
                id          = sid,
                name        = name,
                region      = com.realityengine.perception.models.Region(offset, 4),
                active      = false,
                sensorId    = sid,
                lastValue   = Vector.empty,
                lastUpdated = None,
                ttlMs       = 60000L,
                origin      = Some("localai"),
              ))
              Some(engine.reported(src).asJson)
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
    // Contract settled 3-of-3 (SURFACE_SPEC.md, localAI invoke contract).
    path("api" / "integrations" / "localai" / "invoke") {
      post { entity(as[Json]) { body =>
        val c = body.hcursor
        // The correlation id is what lets a completion write-back be joined to
        // the invocation that justified it. Taken from the caller when given,
        // minted otherwise so no record is left unjoinable.
        val correlationId = c.get[String]("correlationId")
          .getOrElse(s"localai-invocation-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000000)}")
        val invocationId  = s"localai-inv-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000000)}"
        val startedAt     = System.currentTimeMillis()

        // Target is `endpoint`, falling back to `path` -- this read only `path`,
        // so one request body reached different endpoints on different runtimes.
        c.get[String]("endpoint").toOption.orElse(c.get[String]("path").toOption).filter(_.nonEmpty) match {
          case None =>
            complete(StatusCodes.BadRequest -> Json.obj(
              "success" -> false.asJson, "error" -> "localAI invocation requires endpoint or path".asJson))
          case Some(raw) =>
            val endpoint  = if (raw.startsWith("/")) raw else "/" + raw
            val routePath = endpoint.takeWhile(_ != '?')
            val method = c.get[String]("method").toOption.filter(_.nonEmpty)
              .orElse(localAiAllowedOps.find(_.hcursor.get[String]("path").toOption.contains(routePath))
                        .flatMap(_.hcursor.get[String]("method").toOption))
              .getOrElse("POST").toUpperCase
            val operationId = PerceptionRoutes.localAiOperationId(localAiAllowedOps, method, routePath)
              .filter(_ => !endpoint.contains("..") && !endpoint.contains("//"))

            def carry(key: String): Option[(String, Json)] =
              c.get[String](key).toOption.filter(_.nonEmpty).map(v => key -> v.asJson)

            def record(success: Boolean, response: Option[Json], error: Option[String]): Unit = {
              val base = List(
                "id"            -> invocationId.asJson,
                "correlationId" -> correlationId.asJson,
                "provider"      -> "localai".asJson,
                "endpoint"      -> endpoint.asJson,
                "method"        -> method.asJson,
                "startedAt"     -> startedAt.asJson,
                "completedAt"   -> System.currentTimeMillis().asJson,
                "success"       -> success.asJson
              )
              val op      = operationId.map(id => "operationId" -> id.asJson).toList
              val carried = List("machineName", "sequenceId", "requestClass", "resultClass").flatMap(carry)
              val err     = error.map(e => "error" -> e.asJson).toList
              // The response is summarised, never stored whole: a ledger holding
              // every RAG passage becomes the largest object in the process and is
              // read by nobody. What a trace needs is that evidence existed and
              // where it came from.
              val evidence = response.map { r =>
                "evidence" -> Json.obj(
                  "uri"   -> PerceptionRoutes.localAiInvokeUri(localAiApiUrl, endpoint).toString.asJson,
                  "shape" -> (if (r.isObject) "object" else if (r.isArray) "array" else "scalar").asJson
                )
              }.toList
              recordLocalAiInvocation(Json.obj((base ++ op ++ carried ++ err ++ evidence): _*))
            }
            def reply(status: StatusCode, success: Boolean, extra: (String, Json)*) =
              complete(status -> Json.obj((List(
                "success" -> success.asJson, "endpoint" -> endpoint.asJson, "method" -> method.asJson,
                "correlationId" -> correlationId.asJson, "invocationId" -> invocationId.asJson) ++ extra): _*))

            if (operationId.isEmpty) {
              // Recorded before the refusal is returned. An attempt on a forbidden
              // endpoint is exactly the event a runtime trace must carry, and an
              // unrecorded path loses it entirely.
              record(success = false, response = None, error = Some("endpoint is not allowed"))
              reply(StatusCodes.Forbidden, success = false, "error" -> "localAI endpoint is not allowed".asJson)
            } else {
              val payload = c.downField("payload").focus.orElse(c.downField("body").focus).getOrElse(Json.obj())
              val uri     = PerceptionRoutes.localAiInvokeUri(localAiApiUrl, endpoint)
              try {
                val req  = if (method == "GET") basicRequest.get(uri)
                           else basicRequest.post(uri).contentType("application/json").body(payload.noSpaces)
                val resp = req.response(asString).send(sttpBackend)
                val parsed = resp.body.toOption.flatMap(b => io.circe.parser.parse(b).toOption).getOrElse(Json.Null)
                if (resp.isSuccess) {
                  record(success = true, response = Some(parsed), error = None)
                  reply(StatusCodes.OK, success = true, "response" -> parsed)
                } else {
                  val e = s"provider returned ${resp.code}"
                  record(success = false, response = None, error = Some(e))
                  reply(StatusCodes.BadGateway, success = false, "error" -> e.asJson)
                }
              } catch { case e: Exception =>
                // A call that failed is still a call that was made. A ledger of
                // successes cannot answer "was this attempted".
                record(success = false, response = None, error = Some(e.getMessage))
                reply(StatusCodes.BadGateway, success = false, "error" -> Option(e.getMessage).getOrElse("request failed").asJson)
              }
            }
        }
      } }
    },

    // GET /api/integrations/localai/ledger — wire-compatible with the C++ PE.
    path("api" / "integrations" / "localai" / "ledger") {
      get {
        complete(Json.obj(
          "provider" -> "localai".asJson,
          "endpoint" -> localAiApiUrl.asJson,
          "records"  -> Json.arr(localAiLedger.get(): _*)
        ))
      }
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
    // Shape settled 3-of-3 (SURFACE_SPEC.md, Dispatch surface shapes).
    path("api" / "triggers" / "status") {
      get {
        val refreshedAt = machineCatalogRefreshedAt.get()
        complete(Json.obj(
          "participation"             -> (if (triggersEnabled) "active" else "not-active").asJson,
          "enabled"                   -> triggersEnabled.asJson,
          "mode"                      -> triggerDispatchMode.asJson,
          "graphqlEndpoint"           -> triggerGraphqlEndpoint.asJson,
          "records"                   -> dispatchLedger.get().length.asJson,
          "envelopesCreated"          -> triggerDispatcher.envelopesCreated.asJson,
          "droppedNoGovernance"       -> triggerDispatcher.droppedNoGovernance.asJson,
          "droppedNoDispatch"         -> triggerDispatcher.droppedNoDispatch.asJson,
          "droppedCatalogCold"        -> triggerDispatcher.droppedCatalogCold.asJson,
          "dispatchErrors"            -> triggerDispatcher.dispatchErrors.asJson,
          "machineCatalogCold"        -> (refreshedAt == 0L).asJson,
          "machineCatalogRefreshedAt" -> refreshedAt.asJson,
          "machineCatalogSize"        -> machineCatalog.get().size.asJson
        ))
      }
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
                      declareMqttSources(rules)
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
                  declareMqttSources(rules)
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
            setMachineCorpus(MachineCorpus.build(machines))
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

object PerceptionRoutes {

  /** Whether the integration owning a source mapping is enabled here.
    *
    * Pure so it can be tested without an ActorSystem: the decision is the part
    * worth pinning, and constructing routes to reach it would test Akka
    * instead.
    *
    * Unknown origins return true. This gates the integrations that have an
    * enable flag; anything else keeps its existing behaviour rather than being
    * silently suppressed by a default chosen here.
    */
  def integrationEnabled(origin: Option[String],
                         healthkit: Boolean,
                         carekit: Boolean,
                         acp: Boolean): Boolean =
    origin.map(_.toLowerCase) match {
      case Some("healthkit") => healthkit
      case Some("carekit")   => carekit
      case Some("acp")       => acp
      case _                 => true
    }

  /** The URI a localAI invocation is sent to: the configured base URL with the
    * allow-listed path appended.
    *
    * Built by parsing the joined string, never by `uri"$base$path"`. Two
    * adjacent interpolations in sttp's `uri` interpolator do not concatenate:
    * with a full base URL followed by a path, the result kept only the scheme
    * and host, so every invocation went to `POST http://localhost` and failed
    * for every endpoint in the allow-list (#126). Pure so the constructed URI
    * can be asserted directly rather than inferred from a provider's response.
    */
  def localAiInvokeUri(base: String, path: String): sttp.model.Uri =
    sttp.model.Uri.unsafeParse(base.stripSuffix("/") + path)

  /** The configured operation's id for exactly (method, path): no prefixes and
    * no "/" wildcard (SURFACE_SPEC.md, localAI invoke contract). None when the
    * route is not allowed -- including when nothing is configured.
    */
  def localAiOperationId(ops: Vector[Json], method: String, path: String): Option[String] =
    ops.find { op =>
      op.hcursor.get[String]("method").toOption.exists(_.equalsIgnoreCase(method)) &&
      op.hcursor.get[String]("path").toOption.contains(path)
    }.flatMap(_.hcursor.get[String]("id").toOption)

  /** PATCH /api/dispatch/records/:id, as settled 3-of-3 (SURFACE_SPEC.md,
    * "Dispatch surface shapes"): status, error, clearError, attempts or
    * incrementAttempts, providerReceipt (merged), and provider / adapter /
    * externalRunId folded into providerReceipt. Every other field is ignored,
    * so the envelope cannot be rewritten -- this used to deepMerge the whole
    * body onto the record.
    */
  def patchDispatchRecord(record: Json, body: Json, now: Long): Json = {
    val c = body.hcursor
    var r = record
    def set(k: String, v: Json): Unit = r = r.mapObject(_.add(k, v))
    c.get[String]("status").toOption.foreach(v => set("status", v.asJson))
    c.get[String]("error").toOption.foreach(v => set("error", v.asJson))
    if (c.get[Boolean]("clearError").getOrElse(false)) set("error", Json.Null)
    c.get[Int]("attempts").toOption match {
      case Some(n) => set("attempts", n.asJson)
      case None =>
        if (c.get[Boolean]("incrementAttempts").getOrElse(false))
          set("attempts", (r.hcursor.get[Int]("attempts").getOrElse(0) + 1).asJson)
    }
    val existing = r.hcursor.downField("providerReceipt").focus.filter(_.isObject).getOrElse(Json.obj())
    val merged0  = c.downField("providerReceipt").focus.filter(_.isObject).fold(existing)(existing.deepMerge)
    val folded   = List("provider", "adapter", "externalRunId").foldLeft(merged0) { (acc, k) =>
      c.get[String](k).toOption.fold(acc)(v => acc.mapObject(_.add(k, v.asJson)))
    }
    if (folded.asObject.exists(_.nonEmpty)) set("providerReceipt", folded)
    set("updatedAt", now.asJson)
    r
  }
}
