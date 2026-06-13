package com.realityengine.services

import akka.actor.ActorSystem
import com.realityengine.models._
import sttp.client3._
import sttp.client3.akkahttp.AkkaHttpBackend
import io.circe.Json
import io.circe.syntax._
import io.circe.parser._
import scala.concurrent.{ExecutionContext, Future}

/**
 * VectorStore — Qdrant REST API integration via sttp + AkkaHttpBackend.
 *
 * All calls are natively async: send() returns Future[Response[...]] directly.
 */
class VectorStore(
  qdrantUrl:       String  = sys.env.getOrElse("QDRANT_URL", "http://localhost:4333"),
  collectionName:  String  = sys.env.getOrElse("COLLECTION_NAME", "reality-vectors"),
  vectorDimension: Int     = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)
)(implicit system: ActorSystem) {

  private implicit val ec: ExecutionContext = system.dispatcher
  private val sttpBackend                  = AkkaHttpBackend.usingActorSystem(system)
  private val allowMissingQdrant           =
    sys.env.get("ALLOW_MISSING_QDRANT").exists(_.equalsIgnoreCase("true"))
  @volatile private var qdrantUnavailableAllowed = false

  private val seqCollection = s"${collectionName}_sequences"

  // ── Initialization ────────────────────────────────────────────────────────

  def initialize(): Future[Unit] =
    if (qdrantUnavailableAllowed) Future.unit
    else
      ensureCollection(collectionName)
        .map { _ => println("VectorStore initialized successfully") }
        .recover { case e: Exception =>
          if (allowMissingQdrant) {
            qdrantUnavailableAllowed = true
            System.err.println(s"VectorStore unavailable; continuing because ALLOW_MISSING_QDRANT=true: ${e.getMessage}")
            ()
          } else {
            System.err.println(s"Failed to initialize VectorStore: ${e.getMessage}")
            throw e
          }
        }

  private def ensureCollection(name: String): Future[Unit] =
    basicRequest
      .get(uri"$qdrantUrl/collections")
      .response(asString)
      .send(sttpBackend)
      .flatMap { resp =>
        val existing = parse(resp.body.getOrElse("{}")).getOrElse(Json.Null)
        val names    = existing.hcursor.downField("result").downField("collections")
          .as[Vector[Json]].getOrElse(Vector.empty)
          .flatMap(_.hcursor.get[String]("name").toOption)
        if (names.contains(name)) Future.unit
        else {
          val createBody = Json.obj(
            "vectors" -> Json.obj(
              "size"     -> Json.fromInt(vectorDimension),
              "distance" -> Json.fromString("Cosine")
            )
          )
          basicRequest
            .put(uri"$qdrantUrl/collections/$name")
            .contentType("application/json")
            .body(createBody.noSpaces)
            .response(asString)
            .send(sttpBackend)
            .map { createResp =>
              if (createResp.code.code >= 300)
                throw new RuntimeException(s"Failed to create collection $name: ${createResp.body}")
              println(s"Created collection: $name")
            }
        }
      }

  // ── Vector storage ────────────────────────────────────────────────────────

  def storeVector(vector: RealityVector): Future[Unit] = {
    val point = Json.obj(
      "id"      -> Json.fromString(vector.id),
      "vector"  -> normalizeVector(vector.getVector).asJson,
      "payload" -> vector.toJson.deepMerge(Json.obj("timestamp" -> Json.fromLong(System.currentTimeMillis())))
    )
    upsertPoints(collectionName, Vector(point))
  }

  def storeVectors(vectors: List[RealityVector]): Future[Unit] = {
    val points = vectors.map { v =>
      Json.obj(
        "id"      -> Json.fromString(v.id),
        "vector"  -> normalizeVector(v.getVector).asJson,
        "payload" -> v.toJson.deepMerge(Json.obj("timestamp" -> Json.fromLong(System.currentTimeMillis())))
      )
    }
    upsertPoints(collectionName, points.toVector)
  }

  def getVector(id: String): Future[Option[RealityVector]] =
    basicRequest
      .post(uri"$qdrantUrl/collections/$collectionName/points")
      .contentType("application/json")
      .body(Json.obj("ids" -> Json.arr(Json.fromString(id)), "with_payload" -> Json.fromBoolean(true)).noSpaces)
      .response(asString)
      .send(sttpBackend)
      .map { resp =>
        val result = parse(resp.body.getOrElse("{}")).getOrElse(Json.Null)
        result.hcursor.downField("result").as[Vector[Json]].toOption
          .flatMap(_.headOption)
          .flatMap(_.hcursor.downField("payload").as[Json].toOption)
          .map(RealityVector.fromJson)
      }

  def searchSimilar(
    queryVector: Vector[Double],
    limit:       Int = 10,
    threshold:   Option[Double] = None
  ): Future[List[(RealityVector, Double)]] = {
    val bodyFields = scala.collection.mutable.Map(
      "vector"       -> normalizeVector(queryVector).asJson,
      "limit"        -> Json.fromInt(limit),
      "with_payload" -> Json.fromBoolean(true)
    )
    threshold.foreach(t => bodyFields += ("score_threshold" -> Json.fromDoubleOrNull(t)))
    val body = Json.fromFields(bodyFields.toSeq)
    basicRequest
      .post(uri"$qdrantUrl/collections/$collectionName/points/search")
      .contentType("application/json")
      .body(body.noSpaces)
      .response(asString)
      .send(sttpBackend)
      .map { resp =>
        val result = parse(resp.body.getOrElse("{}")).getOrElse(Json.Null)
        result.hcursor.downField("result").as[Vector[Json]].getOrElse(Vector.empty).toList.flatMap { pt =>
          for {
            payload <- pt.hcursor.downField("payload").as[Json].toOption
            score   <- pt.hcursor.get[Double]("score").toOption
          } yield (RealityVector.fromJson(payload), score)
        }
      }
  }

  // ── Sequence storage ──────────────────────────────────────────────────────

  def storeSequence(seq: CriticalEventSequence): Future[Unit] = {
    val dummy = Vector.fill(vectorDimension)(0.0)
    val point = Json.obj(
      "id"      -> Json.fromString(seq.id),
      "vector"  -> dummy.asJson,
      "payload" -> seq.toJson
    )
    for {
      _ <- storeVectors(seq.getAllVectors)
      _ <- ensureCollection(seqCollection)
      _ <- upsertPoints(seqCollection, Vector(point))
    } yield ()
  }

  def getSequence(id: String): Future[Option[CriticalEventSequence]] =
    basicRequest
      .post(uri"$qdrantUrl/collections/$seqCollection/points")
      .contentType("application/json")
      .body(Json.obj("ids" -> Json.arr(Json.fromString(id)), "with_payload" -> Json.fromBoolean(true)).noSpaces)
      .response(asString)
      .send(sttpBackend)
      .map { resp =>
        val result = parse(resp.body.getOrElse("{}")).getOrElse(Json.Null)
        result.hcursor.downField("result").as[Vector[Json]].toOption
          .flatMap(_.headOption)
          .flatMap(_.hcursor.downField("payload").as[Json].toOption)
          .map(CriticalEventSequence.fromJson)
      }
      .recover { case _: Exception => None }

  def deleteVector(id: String): Future[Unit] =
    basicRequest
      .post(uri"$qdrantUrl/collections/$collectionName/points/delete")
      .contentType("application/json")
      .body(Json.obj("points" -> Json.arr(Json.fromString(id))).noSpaces)
      .response(asString)
      .send(sttpBackend)
      .map(_ => ())

  def getStats(): Future[Json] =
    basicRequest
      .get(uri"$qdrantUrl/collections/$collectionName")
      .response(asString)
      .send(sttpBackend)
      .map { resp => parse(resp.body.getOrElse("{}")).getOrElse(Json.Null) }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def upsertPoints(collection: String, points: Vector[Json]): Future[Unit] = {
    val body = Json.obj("wait" -> Json.fromBoolean(true), "points" -> points.asJson)
    basicRequest
      .put(uri"$qdrantUrl/collections/$collection/points")
      .contentType("application/json")
      .body(body.noSpaces)
      .response(asString)
      .send(sttpBackend)
      .map { resp =>
        if (resp.code.code >= 300)
          throw new RuntimeException(s"Failed to upsert points into $collection: ${resp.body}")
      }
  }

  // Normalize in-place on a pre-sized Array[Double] (zero-initialized by the JVM).
  private def normalizeVector(v: Vector[Double]): Array[Double] = {
    val arr = new Array[Double](vectorDimension)
    val len = math.min(v.length, vectorDimension)
    var i = 0
    while (i < len) { arr(i) = v(i); i += 1 }
    arr
  }
}
