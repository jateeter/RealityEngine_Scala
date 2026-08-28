package com.realityengine.perception

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import com.realityengine.perception.store.SourceStore

import java.nio.file.Files

/** The invariant the source contract reduces to:
  *
  * **An integration source's active state is always traceable to an ingress
  * event, and expires with that value's TTL.**
  *
  * Ingress is the only thing that may originate activity. Not registration —
  * 2a declares inactive. Not bootstrap. Not restore from the store. Not reset.
  *
  * Reset and restore both *touch* the flag, and neither of them originates it:
  *
  *  - reset recomputes a sensor's flag as "holds a value inside its TTL", and
  *    only `updateSensorValue` ever sets `lastUpdated`, so reset can confirm
  *    ingress-earned activity or retire it, never invent it
  *  - restore brings back the cached flag and then validates it against the
  *    cached value's TTL, so the activity still traces to an ingress in the
  *    previous run and still dies with the value
  *
  * Each leg is pinned separately elsewhere. This spec walks the whole sequence
  * — register, reset, restart, restore — against a source nothing ever fed.
  */
class IngressActivationInvariantSpec extends AnyFlatSpec with Matchers {

  private def declared(sensorId: String) = SensorSourceConfig(
    id          = sensorId,
    name        = s"localai:$sensorId",
    region      = Region(40, 4),
    active      = true,          // what the registration asked for; ignored
    sensorId    = sensorId,
    lastValue   = Vector.empty,
    lastUpdated = None,
    ttlMs       = 600000L,
    origin      = Some("localai"),
  )

  private def withStore[A](body: SourceStore => A): A = {
    val dir = Files.createTempDirectory("pe-source-store-spec")
    try body(new SourceStore(dir.toString))
    finally {
      Option(dir.toFile.listFiles()).getOrElse(Array.empty).foreach(_.delete())
      dir.toFile.delete()
      ()
    }
  }

  "a source nothing has ever fed" should "report inactive at every observation point" in {
    withStore { store =>
      val engine = new PerceptionEngine(256)

      // 1. Registration declares it.
      engine.declareSource(declared("never-fed"))
      engine.getSource("never-fed").map(_.active) shouldBe Some(false)

      // 2. A reset validates it.
      engine.reset()
      engine.getSource("never-fed").map(_.active) shouldBe Some(false)

      // 3. The process ends and the store is written.
      store.save(engine.getSources)

      // 4. A new process restores what the store kept.
      val restarted = new PerceptionEngine(256)
      store.load().foreach(restarted.restoreSource)
      restarted.getSources.map(_.id) should contain("never-fed")
      restarted.getSource("never-fed").map(_.active) shouldBe Some(false)

      // 5. And a reset in the new process still finds no ingress to point at —
      //    so the source is dropped, not merely left inactive. Reset returns the
      //    engine to the clean step 0 condition, and persisted state no
      //    integration re-registered was never membership (#61). Absence is the
      //    stronger form of the invariant: it cannot report active because it is
      //    not there.
      restarted.reset()
      restarted.getSource("never-fed") shouldBe None

      // Never contributed anything at any point in that sequence.
      restarted.assembleVector().forall(_ == 0.0) shouldBe true
    }
  }

  it should "become active only once a value arrives, and only then" in {
    val engine = new PerceptionEngine(256)
    engine.declareSource(declared("fed"))
    engine.getSource("fed").map(_.active) shouldBe Some(false)
    engine.updateSensorValue("fed", Vector(1.0, 1.0, 1.0, 1.0))
    engine.getSource("fed").map(_.active) shouldBe Some(true)
  }

  "a restored source" should "never come back active on a cached activity claim alone" in {
    val engine = new PerceptionEngine(256)
    // The store recorded it active, but kept no value to support the claim.
    engine.restoreSource(declared("claims-live").copy(active = true))
    engine.getSource("claims-live").map(_.active) shouldBe Some(false)
  }

  it should "not come back active on a cached value that has outlived its TTL" in {
    val engine = new PerceptionEngine(256)
    engine.restoreSource(declared("stale").copy(
      active      = true,
      lastValue   = Vector(1.0, 1.0, 1.0, 1.0),
      lastUpdated = Some(System.currentTimeMillis() - 60000L),
      ttlMs       = 1000L,
    ))
    engine.getSource("stale").map(_.active) shouldBe Some(false)
  }
}
