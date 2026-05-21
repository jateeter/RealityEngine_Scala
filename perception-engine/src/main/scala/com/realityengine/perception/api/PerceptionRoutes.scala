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

  // SSE broadcast hub — mirrors /ws but as Server-Sent Events for /api/events
  private val (ssePEQueue, ssePEBroadcast) = {
    Source.queue[Json](16, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[Json](bufferSize = 1))(Keep.both)
      .run()
  }

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
          case Success(r) => complete(r)
          case Failure(e) => complete(StatusCodes.InternalServerError ->
            Json.obj("error" -> e.getMessage.asJson))
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
        "loaded"             -> false.asJson,
        "path"               -> Json.Null,
        "error"              -> Json.Null,
        "integrationCount"   -> 0.asJson,
        "integrations"       -> Json.arr(),
        "completionEndpoint" -> "/api/integrations/completions".asJson
      )) }
    },
    path("api" / "integrations" / "completions") {
      post { entity(as[Json]) { body =>
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
        complete(record)
      } }
    },
    path("api" / "integrations" / "ollama" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "ollama" / "dispatch") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "Ollama integration not configured".asJson)) }
    },
    path("api" / "integrations" / "openai" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "openai" / "dispatch") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "OpenAI integration not configured".asJson)) }
    },
    path("api" / "integrations" / "acp" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "acp" / "dispatch") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "ACP integration not configured".asJson)) }
    },
    path("api" / "integrations" / "healthkit" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "healthkit" / "ingest") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "HealthKit integration not configured".asJson)) }
    },
    path("api" / "integrations" / "carekit" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "carekit" / "ingest") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "CareKit integration not configured".asJson)) }
    },
    path("api" / "integrations" / "localai" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson, "configured" -> false.asJson)) }
    },
    path("api" / "integrations" / "localai" / "catalog") {
      get { complete(Json.obj("models" -> Json.arr())) }
    },
    path("api" / "integrations" / "localai" / "bootstrap") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "LocalAI integration not configured".asJson)) }
    },
    path("api" / "integrations" / "localai" / "invoke") {
      post { complete(StatusCodes.NotImplemented -> Json.obj("error" -> "LocalAI integration not configured".asJson)) }
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

    // ── MQTT bridge (disabled — set MQTT_BROKER_HOST to enable) ──────────────
    path("api" / "mqtt" / "status") {
      get { complete(Json.obj("enabled" -> false.asJson)) }
    },
    path("api" / "mqtt" / "mappings") {
      get { complete(Json.obj("enabled" -> false.asJson, "mappings" -> Json.arr())) }
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
