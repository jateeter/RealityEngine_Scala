package com.realityengine.logging

import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directive0
import io.circe.Json
import io.circe.syntax._

import java.time.Instant

/** Audit logging configuration.
 *
 *  @param enabled whether audit logging is active
 *  @param level   1=MINIMAL  — mutating ops (POST/PUT/PATCH/DELETE) + errors only
 *                 2=STANDARD — all requests + status + duration
 *                 3=VERBOSE  — standard + content-type + content-length
 *  @param service service name written into every log entry
 */
case class AuditConfig(enabled: Boolean, level: Int, service: String)

object AuditConfig {
  /** Build config from environment variables with a per-service default name.
   *
   *  AUDIT_LOG_ENABLED  true|false          (default: true)
   *  AUDIT_LOG_LEVEL    1|2|3               (default: 2)
   *  AUDIT_LOG_SERVICE  arbitrary string    (default: defaultService)
   */
  def fromEnv(defaultService: String): AuditConfig = AuditConfig(
    enabled = sys.env.getOrElse("AUDIT_LOG_ENABLED", "true").toLowerCase != "false",
    level   = sys.env.getOrElse("AUDIT_LOG_LEVEL",   "2").toIntOption
                .map(n => math.max(1, math.min(3, n))).getOrElse(2),
    service = sys.env.getOrElse("AUDIT_LOG_SERVICE", defaultService),
  )
}

object AuditLogger {

  /** Akka HTTP Directive0 that wraps a route tree and emits a structured JSON
   *  audit entry to stdout for every request that matches the configured level.
   *
   *  Placed outside handleExceptions so errors surfaced by the exception
   *  handler are still captured with their final status code.
   */
  def directive(cfg: AuditConfig): Directive0 =
    if (!cfg.enabled) pass
    else
      extractRequest.flatMap { req =>
        optionalHeaderValueByName("X-Real-IP").flatMap { realIp =>
          optionalHeaderValueByName("X-Forwarded-For").flatMap { fwdFor =>
            val start = System.currentTimeMillis()
            val ip    = realIp
              .orElse(fwdFor.map(_.split(',').head.trim))
              .getOrElse("unknown")
            mapResponse { response =>
              val duration   = System.currentTimeMillis() - start
              val isMutating = req.method != HttpMethods.GET &&
                               req.method != HttpMethods.HEAD
              val status     = response.status.intValue
              val isError    = status >= 400
              if (cfg.level >= 2 || isMutating || isError)
                emit(cfg, req, ip, status, duration)
              response
            }
          }
        }
      }

  /** Log a lifecycle event (startup / shutdown) that is not tied to an HTTP request. */
  def logEvent(cfg: AuditConfig, event: String, extra: Map[String, Json] = Map.empty): Unit =
    if (cfg.enabled) {
      val entry = Json.obj(
        Seq(
          "timestamp" -> Instant.now().toString.asJson,
          "level"     -> "AUDIT".asJson,
          "service"   -> cfg.service.asJson,
          "event"     -> event.asJson,
        ) ++ extra.toSeq: _*
      )
      println(entry.noSpaces)
    }

  // ── Internal ──────────────────────────────────────────────────────────────

  private def emit(
    cfg:      AuditConfig,
    req:      HttpRequest,
    remoteIp: String,
    status:   Int,
    duration: Long,
  ): Unit = {
    val auditLevel = if (status >= 500) "ERROR" else if (status >= 400) "WARN" else "INFO"
    val base = Seq(
      "timestamp"   -> Instant.now().toString.asJson,
      "level"       -> "AUDIT".asJson,
      "audit_level" -> auditLevel.asJson,
      "service"     -> cfg.service.asJson,
      "event"       -> "http_request".asJson,
      "method"      -> req.method.value.asJson,
      "path"        -> req.uri.path.toString.asJson,
      "status"      -> status.asJson,
      "duration_ms" -> duration.asJson,
      "remote_ip"   -> remoteIp.asJson,
    )
    val verbose: Seq[(String, Json)] =
      if (cfg.level >= 3) Seq(
        "content_type"   -> req.entity.contentType.toString.asJson,
        "content_length" -> req.entity.contentLengthOption.fold(Json.Null)(_.asJson),
      )
      else Seq.empty
    println(Json.obj((base ++ verbose): _*).noSpaces)
  }
}
