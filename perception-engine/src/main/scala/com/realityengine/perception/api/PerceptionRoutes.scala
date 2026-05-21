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

import scala.concurrent.{ExecutionContext, Future}
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

  private def broadcast(json: Json): Unit =
    broadcastActor ! WsBroadcastActor.BroadcastMsg(json.noSpaces)

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
