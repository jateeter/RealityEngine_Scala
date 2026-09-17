package com.realityengine.services

import akka.actor.ActorSystem
import com.realityengine.models._
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * VectorDocumentStoreSpec — `/api/vectors` is a JSON document store.
 *
 * RealityEngine_CI#288: this runtime read `elements` and `isInitial`,
 * constructed a RealityEvent, returned RealityEvent.toJson, and stored nothing.
 * C++ and LSP keep the posted body verbatim under an id and hand it back.
 * SURFACE_SPEC settles the document-store shape; these pin it here.
 *
 * The properties that matter are the ones a typed model cannot provide: an
 * unknown field survives the round trip, and what search returns is the
 * document that was stored rather than a re-serialisation of a model.
 */
class VectorDocumentStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("vector-document-store-spec")
  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, 5.seconds)

  override def afterAll(): Unit = { system.terminate(); () }

  private def store() = new VectorStore()

  private def doc(json: String): Json =
    parse(json).getOrElse(fail(s"test fixture is not JSON: $json"))

  // ── Round trip ────────────────────────────────────────────────────────────

  "a stored document" should "come back exactly as it went in" in {
    val s = store()
    val d = doc("""{"id":"v1","elements":[{"value":0.9}],"isInitial":true}""")
    await(s.storeDocument("v1", d))
    await(s.getDocument("v1")) shouldBe Some(d)
  }

  it should "keep a field the engine has never heard of" in {
    val s = store()
    val d = doc("""{"id":"v1","marker":"issue-288","nested":{"a":[1,2]},"elements":[{"value":0.5}]}""")
    await(s.storeDocument("v1", d))

    val back = await(s.getDocument("v1")).getOrElse(fail("nothing stored"))
    back.hcursor.get[String]("marker") shouldBe Right("issue-288")
    back shouldBe d
  }

  it should "be replaced by a later write to the same id" in {
    val s = store()
    await(s.storeDocument("v1", doc("""{"id":"v1","n":1}""")))
    await(s.storeDocument("v1", doc("""{"id":"v1","n":2}""")))
    await(s.getDocument("v1")).flatMap(_.hcursor.get[Int]("n").toOption) shouldBe Some(2)
  }

  it should "be gone after delete" in {
    val s = store()
    await(s.storeDocument("v1", doc("""{"id":"v1"}""")))
    await(s.deleteVector("v1"))
    await(s.getDocument("v1")) shouldBe None
  }

  // ── Search returns the document, not a model ──────────────────────────────

  "search" should "find a posted document and hand back what was stored" in {
    val s = store()
    val d = doc("""{"id":"v1","marker":"issue-288","elements":[{"value":1.0},{"value":0.0}]}""")
    await(s.storeDocument("v1", d))

    val results = await(s.searchSimilar(Vector(1.0, 0.0), limit = 10, threshold = Some(0.0)))
    results.map(_._1) shouldBe List(d)
  }

  it should "return nothing when the store is empty" in {
    await(store().searchSimilar(Vector(1.0), 10, Some(0.0))) shouldBe empty
  }

  // The pre-fix defect, stated as a test: the route wrote nothing, so this was
  // empty no matter what had been posted.
  it should "not be empty after a document is stored" in {
    val s = store()
    await(s.storeDocument("v1", doc("""{"id":"v1","elements":[{"value":1.0}]}""")))
    await(s.searchSimilar(Vector(1.0), 10, Some(0.0))) should not be empty
  }

  it should "take first-k above threshold in id order, not top-k by score" in {
    val s = store()
    // b scores 1.0, a and c score lower. Id order is a, b, c.
    await(s.storeDocument("a", doc("""{"id":"a","elements":[{"value":0.6}]}""")))
    await(s.storeDocument("b", doc("""{"id":"b","elements":[{"value":1.0}]}""")))
    await(s.storeDocument("c", doc("""{"id":"c","elements":[{"value":0.6}]}""")))

    val ids = await(s.searchSimilar(Vector(1.0), limit = 2, threshold = Some(0.0)))
      .flatMap(_._1.hcursor.get[String]("id").toOption)

    ids shouldBe List("a", "b")
  }

  // ── Scoring source, matching C++'s searchable_vector_values ───────────────

  "searchable values" should "prefer a `vector` array when present" in {
    val s = store()
    s.searchableValues(doc("""{"vector":[1.0,2.0],"elements":[{"value":9.0}]}""")) shouldBe
      Vector(1.0, 2.0)
  }

  it should "fall back to elements[].value" in {
    val s = store()
    s.searchableValues(doc("""{"elements":[{"value":1.5},{"value":2.5}]}""")) shouldBe
      Vector(1.5, 2.5)
  }

  it should "accept a bare number as an element" in {
    val s = store()
    s.searchableValues(doc("""{"elements":[1.5,2.5]}""")) shouldBe Vector(1.5, 2.5)
  }

  it should "be empty for a document carrying neither" in {
    val s = store()
    s.searchableValues(doc("""{"id":"v1","marker":"no numbers here"}""")) shouldBe Vector.empty
  }

  // ── The RealityEvent convenience path still works ─────────────────────────

  "storeVector" should "land a RealityEvent as its JSON form" in {
    val s = store()
    val ev = new RealityEvent(Vector(VectorElement(value = 0.7)), isInitial = true, id = "ev1")
    await(s.storeVector(ev))
    await(s.getDocument("ev1")) shouldBe Some(ev.toJson)
  }
}
