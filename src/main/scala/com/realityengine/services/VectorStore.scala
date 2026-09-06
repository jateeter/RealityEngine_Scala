package com.realityengine.services

import akka.actor.ActorSystem
import com.realityengine.models._
import io.circe.Json
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * VectorStore — in-memory Reality Event and sequence storage.
 *
 * This was a Qdrant client. The engine no longer depends on a database.
 *
 * WHY, because "we removed the database" reads like a capability loss and is
 * the opposite:
 *
 * The `/api/vectors` route family is declared in SURFACE_SPEC with three ticks, and
 * two of the three runtimes already served them from memory — C++ iterates a
 * `std::map` computing cosine locally, LSP walks a hash table doing the same.
 * Only this runtime reached out over HTTP. So one declared route was
 * memory-backed on two engines and database-backed on the third, and its results
 * differed by whatever a shared Qdrant collection happened to hold — including
 * points written by an entirely different engine, or by a previous run.
 *
 * That is a parity hazard of the kind byte equivalence exists to catch, and it
 * could never be caught, because the divergence lived outside the engines.
 * Removing the dependency removes the divergence. The routes stay; all three now
 * answer them the same way, from their own state.
 *
 * It also removes a startup dependency. `initialize()` used to create two
 * collections and could fail, which is why `ALLOW_MISSING_QDRANT` existed — a
 * flag whose only purpose was to let the engine start when the database it did
 * not really need was absent.
 *
 * SEARCH SEMANTICS ARE C++'s, deliberately, down to a quirk:
 *
 *   iterate ids in sorted order; skip anything below the threshold; stop once
 *   `limit` results have been collected.
 *
 * That is first-k-above-threshold in id order, NOT top-k by score. C++ breaks at
 * the limit before ranking (`reality_engine_server.cpp`), and LSP does the same.
 * Ranking here would be an improvement to one runtime and a divergence from two,
 * so the improvement belongs in the contract first if it is wanted at all. Id
 * order makes the result deterministic, which is what parity needs from it.
 *
 * Qdrant is not gone from the system — it is no longer a runtime dependency of
 * the engine. Reintroducing it as a snapshot store, with save and restore of
 * runtime state, is tracked separately.
 */
class VectorStore(
  // Retained so callers and configuration keep working unchanged; the engine no
  // longer connects to anything. `/api/config` still reports them, and a
  // deployment that sets them is not wrong — it is describing a service this
  // engine has stopped using.
  qdrantUrl:       String  = sys.env.getOrElse("QDRANT_URL", "http://localhost:4333"),
  collectionName:  String  = sys.env.getOrElse("COLLECTION_NAME", "reality-events"),
  vectorDimension: Int     = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)
)(implicit system: ActorSystem) {

  private implicit val ec: ExecutionContext = system.dispatcher

  // TrieMap rather than a synchronized Map: reads are lock-free and the engine
  // reads these far more often than it writes, including from actor threads
  // running concurrently under the machine fan-out.
  private val events    = TrieMap.empty[String, RealityEvent]
  private val sequences = TrieMap.empty[String, CriticalEventSequence]

  /** No-op. Kept so `Main` and `RealityEngine` need no change, and so the
    * engine has one less way to fail at startup. */
  def initialize(): Future[Unit] = Future.successful(())

  // ── Reality Event storage ─────────────────────────────────────────────────

  def storeVector(vector: RealityEvent): Future[Unit] =
    Future.successful(events.put(vector.id, vector)).map(_ => ())

  def storeVectors(vectors: List[RealityEvent]): Future[Unit] =
    Future.successful(vectors.foreach(v => events.put(v.id, v)))

  def getVector(id: String): Future[Option[RealityEvent]] =
    Future.successful(events.get(id))

  def deleteVector(id: String): Future[Unit] =
    Future.successful(events.remove(id)).map(_ => ())

  /** First-k above threshold, in id order — see the class note. */
  def searchSimilar(
    queryVector: Vector[Double],
    limit:       Int = 10,
    threshold:   Option[Double] = None
  ): Future[List[(RealityEvent, Double)]] = Future.successful {
    val out = List.newBuilder[(RealityEvent, Double)]
    var taken = 0
    for ((_, event) <- events.toList.sortBy(_._1) if taken < limit) {
      val score = cosine(queryVector, event.getVector)
      if (threshold.forall(score >= _)) {
        out += ((event, score))
        taken += 1
      }
    }
    out.result()
  }

  // ── Sequence storage ──────────────────────────────────────────────────────

  def storeSequence(seq: CriticalEventSequence): Future[Unit] =
    Future.successful(sequences.put(seq.id, seq)).map(_ => ())

  def getSequence(id: String): Future[Option[CriticalEventSequence]] =
    Future.successful(sequences.get(id))

  // ── Observability ─────────────────────────────────────────────────────────

  /** Reports what this store holds. The Qdrant collection description it
    * replaces named a database; this names the engine's own state, which is the
    * thing a caller asking a runtime for its stats was after. */
  def getStats(): Future[Json] = Future.successful(
    Json.obj(
      "backing"         -> Json.fromString("in-memory"),
      "events"          -> Json.fromInt(events.size),
      "sequences"       -> Json.fromInt(sequences.size),
      "vectorDimension" -> Json.fromInt(vectorDimension)
    )
  )

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Cosine similarity, matching C++'s `cosine`: zero when either side has no
    * magnitude, and comparing only the overlapping prefix so a query of a
    * different width scores rather than throwing. */
  private def cosine(a: Vector[Double], b: Vector[Double]): Double = {
    val n = math.min(a.length, b.length)
    if (n == 0) return 0.0
    var dot, na, nb = 0.0
    var i = 0
    while (i < n) {
      dot += a(i) * b(i)
      na  += a(i) * a(i)
      nb  += b(i) * b(i)
      i += 1
    }
    if (na == 0.0 || nb == 0.0) 0.0 else dot / (math.sqrt(na) * math.sqrt(nb))
  }
}
