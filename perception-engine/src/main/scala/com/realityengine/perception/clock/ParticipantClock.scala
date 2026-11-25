package com.realityengine.perception.clock

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RejectionHandler, Route}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import sttp.client3.{DelegateSttpBackend, Identity, Request, Response, SttpBackend}
import sttp.capabilities.Effect

/**
 * The PE's participant clock on its HTTP boundaries (RealityEngine_CI#600;
 * SURFACE_SPEC.md "The instance clock"), the same rules as the RE's:
 *
 *  - Server: every request is a receive, applied before it is handled --
 *    max(local, received) + 1 with an RE-Clock header, + 1 without -- and a
 *    malformed or repeated header is refused with 400 (still a receive). Every
 *    response, a rejection turned into one included, is a send stamped RE-Clock.
 *  - Client: every call to an internal peer is a send (stamped RE-Clock) and
 *    its response a receive. An external party is never stamped: the clock
 *    crosses the local integration boundaries only.
 *
 * The clock is the PE's own (PE_INSTANCE_UUID), booted once in PerceptionMain;
 * until then a minted, unpersisted one stands in (tests).
 */
object ParticipantClock {
  @volatile private var booted: InstanceClock = InstanceClock.minted()
  @volatile private var lastPush: (Long, Long) = (0L, 0L)

  def use(clock: InstanceClock): Unit = {
    booted = clock
    lastPush = (clock.lamport, clock.commit)
  }
  def clock: InstanceClock = booted

  /** A completed push is the PE's commit. */
  def commitPush(): Unit = { lastPush = booted.commitStep() }

  /** {instance, lamport, commit, step} at the newest completed push. */
  def json(step: Long): Json = Json.obj(
    "instance" -> Json.fromString(booted.instance),
    "lamport"  -> Json.fromLong(lastPush._1),
    "commit"   -> Json.fromLong(lastPush._2),
    "step"     -> Json.fromLong(step))

  /** The server half, around every route. */
  def directive(inner: Route): Route = extractRequest { request =>
    val c      = booted
    val values = request.headers.filter(_.lowercaseName == "re-clock").map(_.value)
    val stamp  = mapResponseHeaders(_ :+ RawHeader("RE-Clock", c.header(c.send())))
    values match {
      case Seq()                                                => c.receive(None); stamp(handleRejections(RejectionHandler.default)(inner))
      case Seq(one) if InstanceClock.parseHeader(one).isDefined => c.receive(InstanceClock.parseHeader(one)); stamp(handleRejections(RejectionHandler.default)(inner))
      case _ =>
        c.receive(None)
        stamp(complete(StatusCodes.BadRequest, Json.obj("error" -> Json.fromString("malformed RE-Clock header"))))
    }
  }

  /** The client half: `delegate` with every call to an internal peer clocked.
    * `internal` decides which hosts are internal peers. */
  def backend[P](delegate: SttpBackend[Identity, P], internal: String => Boolean): SttpBackend[Identity, P] =
    new DelegateSttpBackend[Identity, P](delegate) {
      override def send[T, R >: P with Effect[Identity]](request: Request[T, R]): Identity[Response[T]] = {
        val c = booted
        if (!request.uri.host.exists(internal)) delegate.send(request)
        else {
          val response = delegate.send(request.header("RE-Clock", c.header(c.send()), replaceExisting = true))
          c.receive(response.header("RE-Clock").flatMap(InstanceClock.parseHeader))
          response
        }
      }
    }
}
