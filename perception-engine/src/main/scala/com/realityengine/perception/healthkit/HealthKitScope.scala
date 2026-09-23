package com.realityengine.perception.healthkit

import io.circe.Json
import io.circe.syntax._

/** HealthKit scope and resync -- localHealthkitBridge INGEST_CONTRACT.md,
  * "Scope and resync", held 3-of-3 with C++ and LSP.
  *
  * The data scope (which HealthKit types flow) changes through an
  * authorization workflow tied to the owner's Solid pod. The PE is the scope
  * authority. A bridge is open until its first scope message; from then on only
  * `active` types are ingested. Resync runs the other way: a consumer asks,
  * through the PE, for the producer to re-send.
  *
  * Pure and synchronised: the routes do the HTTP and remove the sources this
  * reports, so every rule here is testable without an engine.
  */
final class HealthKitScope {
  private final case class Bridge(
    declared: Boolean = false,
    generation: Long = 0,
    types: Map[String, Json] = Map.empty,
    sensors: Map[String, Set[String]] = Map.empty,
    resync: Vector[Json] = Vector.empty,
  )
  private var bridges = Map.empty[String, Bridge]
  private def of(id: String): Bridge = bridges.getOrElse(id, Bridge())

  private val States = Map("add" -> "active", "lock" -> "locked", "remove" -> "removed")

  def json(bridgeId: String): Json = synchronized {
    val b = of(bridgeId)
    Json.obj(
      "declared"       -> b.declared.asJson,
      "generation"     -> b.generation.asJson,
      "types"          -> Json.fromFields(b.types.toSeq.sortBy(_._1)),
      "resyncRequests" -> Json.arr(b.resync: _*)
    )
  }

  private def stateOf(b: Bridge, tpe: String): Option[String] =
    b.types.get(tpe).flatMap(_.hcursor.get[String]("state").toOption)

  /** None when the type may be ingested, otherwise the refusal reason. */
  def refusal(bridgeId: String, tpe: String): Option[String] = synchronized {
    val b = of(bridgeId)
    if (!b.declared) None
    else stateOf(b, tpe) match {
      case Some("active") => None
      case Some("locked") => Some("locked")
      case _              => Some("not-in-scope")
    }
  }

  def noteSensor(bridgeId: String, tpe: String, sensorId: String): Unit = synchronized {
    val b = of(bridgeId)
    bridges += bridgeId -> b.copy(sensors = b.sensors.updated(tpe, b.sensors.getOrElse(tpe, Set.empty) + sensorId))
  }

  /** Left(error) for a 400; Right(response, sensor ids to remove from the PE). */
  def change(bridgeId: String, action: String, types: Vector[String], source: Option[String], now: Long)
      : Either[String, (Json, Vector[String])] = synchronized {
    States.get(action) match {
      case None => Left("scope action must be add, lock or remove")
      case Some(_) if types.isEmpty => Left("scope requires a non-empty types array")
      case Some(target) =>
        var b = of(bridgeId).copy(declared = true)
        var removed = Vector.empty[String]
        val applied = types.map { tpe =>
          val previous = stateOf(b, tpe).map(_.asJson).getOrElse(Json.Null)
          b = b.copy(types = b.types.updated(tpe, Json.obj(
            "state" -> target.asJson, "source" -> source.map(_.asJson).getOrElse(Json.Null), "updatedAt" -> now.asJson)))
          if (action == "remove") {
            removed ++= b.sensors.getOrElse(tpe, Set.empty).toVector.sorted
            b = b.copy(sensors = b.sensors - tpe)
          }
          Json.obj("type" -> tpe.asJson, "state" -> target.asJson, "previous" -> previous)
        }
        b = b.copy(generation = b.generation + 1)
        bridges += bridgeId -> b
        Right((Json.obj("success" -> true.asJson, "bridgeId" -> bridgeId.asJson, "action" -> action.asJson,
          "generation" -> b.generation.asJson, "applied" -> Json.arr(applied: _*)), removed))
    }
  }

  /** (HTTP status, body). */
  def resync(bridgeId: String, types: Vector[String], requestedBy: Option[String], now: Long,
             newId: String => String): (Int, Json) = synchronized {
    requestedBy.filter(_.nonEmpty) match {
      case None => (400, Json.obj("error" -> "resync requires requestedBy".asJson))
      case Some(by) =>
        val b = of(bridgeId)
        val requested =
          if (types.nonEmpty) types
          else if (b.declared) b.types.collect { case (t, _) if stateOf(b, t).contains("active") => t }.toVector.sorted
          else b.sensors.keys.toVector.sorted
        val (accepted, refused) = requested.foldLeft((Vector.empty[String], Vector.empty[Json])) { case ((a, r), t) =>
          val reason = if (!b.declared) None else stateOf(b, t) match {
            case Some("active") => None
            case Some("locked") => Some("locked")
            case _              => Some("not-in-scope")
          }
          reason.fold((a :+ t, r))(why => (a, r :+ Json.obj("type" -> t.asJson, "reason" -> why.asJson)))
        }
        if (accepted.isEmpty)
          (409, Json.obj("success" -> false.asJson, "request" -> Json.Null, "refused" -> Json.arr(refused: _*)))
        else {
          val request = Json.obj(
            "id" -> newId("hk-resync").asJson, "bridgeId" -> bridgeId.asJson, "types" -> accepted.asJson,
            "requestedBy" -> by.asJson, "requestedAt" -> now.asJson, "state" -> "pending".asJson,
            "fulfilledAt" -> Json.Null)
          bridges += bridgeId -> b.copy(resync = (b.resync :+ request).takeRight(32))
          (202, Json.obj("success" -> true.asJson, "request" -> request, "refused" -> Json.arr(refused: _*)))
        }
    }
  }

  def fulfil(bridgeId: String, resyncId: String, now: Long): Unit = synchronized {
    val b = of(bridgeId)
    bridges += bridgeId -> b.copy(resync = b.resync.map { r =>
      val c = r.hcursor
      if (c.get[String]("id").toOption.contains(resyncId) && c.get[String]("state").toOption.contains("pending"))
        r.mapObject(_.add("state", "fulfilled".asJson).add("fulfilledAt", now.asJson))
      else r
    })
  }
}
