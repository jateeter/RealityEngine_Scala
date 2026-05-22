package com.realityengine.perception.api

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
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
import scala.util.{Failure, Success}
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

  // MQTT bridge config — enabled when MQTT_BROKER_HOST is set at startup
  private val mqttBrokerHost  = sys.env.get("MQTT_BROKER_HOST")
  private val mqttMappingsRef = new AtomicReference[Json](Json.arr())

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
  private val acpEndpointUrl     = sys.env.get("ACP_ENDPOINT_URL")
  private val acpAgentId         = sys.env.getOrElse("ACP_AGENT_ID",      "openclaw")
  private val hkBridgeToken      = sys.env.get("HEALTHKIT_BRIDGE_TOKEN")
  private val hkEnabled          = sys.env.get("HEALTHKIT_ENABLED").exists(v => v == "true" || v == "1")
  private val ckBridgeToken      = sys.env.get("CAREKIT_BRIDGE_TOKEN")
  private val ckEnabled          = sys.env.get("CAREKIT_ENABLED").exists(v => v == "true" || v == "1")
  private val localAiApiUrl      = sys.env.getOrElse("LOCAL_AI_API_URL",  "http://localhost:8080")
  private val localAiMachinesDir = sys.env.get("LOCAL_AI_MACHINES_DIR")

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
    val sensorId = body.hcursor.get[String]("sensorId").getOrElse("completion_agent")
    val values   = body.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector(1.0))
    engine.updateSensorValue(sensorId, values)
    val ts     = System.currentTimeMillis()
    val record = Json.obj(
      "id"        -> s"compl-$ts".asJson,
      "type"      -> "completion".asJson,
      "timestamp" -> ts.asJson,
      "body"      -> body
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
      "vector"         -> vector.asJson,
      "matchAlgorithm" -> algoStr.asJson,
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
        // Length need not equal vectorDimension: updateFromPerceptualSpace
        // auto-grows the engine's persistent vector when RE's space has been
        // expanded by machines whose mapping extends past our initial size.
        parsed.hcursor.get[Vector[Double]]("perceptualSpace") match {
          case Right(ps) if ps.nonEmpty => engine.updateFromPerceptualSpace(ps)
          case _                        =>
        }

        val stepJson = Some(parsed)

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
      get { complete(Json.obj("status" -> "healthy".asJson, "timestamp" -> System.currentTimeMillis().asJson)) }
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
        "integrations"       -> Json.arr(sourceMappings.keys.map(Json.fromString).toSeq: _*),
        "completionEndpoint" -> "/api/integrations/completions".asJson
      )) }
    },
    path("api" / "integrations" / "completions") {
      post { entity(as[Json]) { body => complete(ingestCompletion(body)) } }
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
        "enabled"     -> acpEndpointUrl.isDefined.asJson,
        "configured"  -> acpEndpointUrl.isDefined.asJson,
        "endpointUrl" -> acpEndpointUrl.map(_.asJson).getOrElse(Json.Null),
        "agentId"     -> acpAgentId.asJson
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
        "enabled"        -> hkEnabled.asJson,
        "configured"     -> hkBridgeToken.isDefined.asJson,
        "tokenRequired"  -> true.asJson,
        "bridgeEndpoint" -> "/api/integrations/healthkit/ingest".asJson
      )) }
    },
    path("api" / "integrations" / "healthkit" / "ingest") {
      post {
        optionalHeaderValueByName("Authorization") { authHeader =>
          entity(as[Json]) { body =>
            hkBridgeToken match {
              case None =>
                complete(StatusCodes.ServiceUnavailable ->
                  Json.obj("error" -> "HealthKit bridge not configured".asJson))
              case Some(expectedToken) =>
                val tokenFromBody   = body.hcursor.get[String]("token").toOption
                val tokenFromHeader = authHeader.map(_.stripPrefix("Bearer ")).filter(_.nonEmpty)
                val token = tokenFromBody.orElse(tokenFromHeader).getOrElse("")
                if (token != expectedToken) {
                  complete(StatusCodes.Unauthorized -> Json.obj("error" -> "Unauthorized".asJson))
                } else {
                  val samples = body.hcursor.downField("samples").as[Vector[Json]].getOrElse(Vector.empty)
                  val results = samples.map { sample =>
                    val sampleType = sample.hcursor.get[String]("sampleType").getOrElse("unknown")
                    val values     = sample.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector.empty)
                    val sensorId   = resolveTemplate("healthkit.{sampleType}", Map("sampleType" -> sampleType))
                    val updated    = engine.updateSensorValue(sensorId, values)
                    Json.obj("sampleType" -> sampleType.asJson, "sensorId" -> sensorId.asJson, "updated" -> updated.asJson)
                  }
                  broadcastState()
                  complete(StatusCodes.MultiStatus -> Json.obj(
                    "results"   -> Json.arr(results: _*),
                    "processed" -> results.length.asJson
                  ))
                }
            }
          }
        }
      }
    },

    // ── CareKit ──────────────────────────────────────────────────────────────
    path("api" / "integrations" / "carekit" / "status") {
      get { complete(Json.obj(
        "enabled"        -> ckEnabled.asJson,
        "configured"     -> ckBridgeToken.isDefined.asJson,
        "tokenRequired"  -> true.asJson,
        "bridgeEndpoint" -> "/api/integrations/carekit/ingest".asJson
      )) }
    },
    path("api" / "integrations" / "carekit" / "ingest") {
      post {
        optionalHeaderValueByName("Authorization") { authHeader =>
          entity(as[Json]) { body =>
            ckBridgeToken match {
              case None =>
                complete(StatusCodes.ServiceUnavailable ->
                  Json.obj("error" -> "CareKit bridge not configured".asJson))
              case Some(expectedToken) =>
                val tokenFromBody   = body.hcursor.get[String]("token").toOption
                val tokenFromHeader = authHeader.map(_.stripPrefix("Bearer ")).filter(_.nonEmpty)
                val token = tokenFromBody.orElse(tokenFromHeader).getOrElse("")
                if (token != expectedToken) {
                  complete(StatusCodes.Unauthorized -> Json.obj("error" -> "Unauthorized".asJson))
                } else {
                  val samples = body.hcursor.downField("samples").as[Vector[Json]].getOrElse(Vector.empty)
                  val results = samples.map { sample =>
                    val sampleType = sample.hcursor.get[String]("sampleType").getOrElse("unknown")
                    val values     = sample.hcursor.downField("values").as[Vector[Double]].getOrElse(Vector.empty)
                    val sensorId   = resolveTemplate("carekit.{sampleType}", Map("sampleType" -> sampleType))
                    val updated    = engine.updateSensorValue(sensorId, values)
                    Json.obj("sampleType" -> sampleType.asJson, "sensorId" -> sensorId.asJson, "updated" -> updated.asJson)
                  }
                  broadcastState()
                  complete(StatusCodes.MultiStatus -> Json.obj(
                    "results"   -> Json.arr(results: _*),
                    "processed" -> results.length.asJson
                  ))
                }
            }
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

    // ── MQTT bridge (set MQTT_BROKER_HOST at startup to enable) ──────────────
    path("api" / "mqtt" / "status") {
      get { complete(Json.obj(
        "enabled"    -> mqttBrokerHost.isDefined.asJson,
        "configured" -> mqttBrokerHost.isDefined.asJson,
        "broker"     -> mqttBrokerHost.map(_.asJson).getOrElse(Json.Null)
      )) }
    },
    path("api" / "mqtt" / "mappings") {
      concat(
        get { complete(Json.obj(
          "enabled"  -> mqttBrokerHost.isDefined.asJson,
          "mappings" -> mqttMappingsRef.get()
        )) },
        put { entity(as[Json]) { body =>
          mqttBrokerHost match {
            case None =>
              complete(StatusCodes.Conflict ->
                Json.obj("error" -> "no broker config — set MQTT_BROKER_HOST at PE startup before reloading mappings".asJson))
            case Some(_) =>
              val mappings = body.hcursor.downField("mappings").as[Json].getOrElse(Json.arr())
              val count    = mappings.asArray.map(_.length).getOrElse(0)
              if (count == 0)
                complete(StatusCodes.BadRequest ->
                  Json.obj("error" -> "mappings array is empty — at least one rule is required".asJson))
              else {
                mqttMappingsRef.set(mappings)
                broadcast(Json.obj(
                  "type"     -> "mqtt-mappings-updated".asJson,
                  "mappings" -> mappings,
                  "count"    -> count.asJson
                ))
                complete(Json.obj(
                  "success"  -> true.asJson,
                  "enabled"  -> true.asJson,
                  "mappings" -> count.asJson,
                  "warnings" -> Json.arr()
                ))
              }
          }
        } }
      )
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
            val created = machines.flatMap { m =>
              val name     = m.hcursor.get[String]("name").getOrElse("unknown")
              val machineId = m.hcursor.get[String]("id").getOrElse(s"machine-${System.currentTimeMillis()}")
              engine.findSensorBySensorId(machineId) match {
                case Some(_) => None
                case None =>
                  val src = engine.addSource(SensorSourceConfig(
                    id          = machineId,
                    name        = s"Machine: $name",
                    region      = com.realityengine.perception.models.Region(0, 1),
                    active      = true,
                    sensorId    = machineId,
                    lastValue   = Vector.empty,
                    lastUpdated = None,
                    ttlMs       = 30000L
                  ))
                  Some(src.asJson)
              }
            }
            onComplete(saveAndBroadcast()) { _ =>
              complete(Json.obj(
                "success" -> true.asJson,
                "created" -> created.length.asJson,
                "sources" -> Json.arr(created: _*)
              ))
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
