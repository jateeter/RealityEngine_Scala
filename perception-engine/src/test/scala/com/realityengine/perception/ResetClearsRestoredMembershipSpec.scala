package com.realityengine.perception

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Reset must leave the engine at the clean step 0 condition (#61).
  *
  * The reported failure: a 12-machine standard-deployment boot left C++ and LSP
  * holding 0 sources and Scala holding 815 restored from the store, carrying
  * regions from the full 1328-machine corpus. They restored inactive and so
  * contributed no values, but their regions had already grown the perceptual
  * space, and the space length is part of every ISRE and OREV entry:
  *
  *     cpp-1 len=14388   lsp-1 len=14388   scala-1 len=16942
  *     FAIL isre-history diverges at step 0 (length): cpp-1+lsp-1 | scala-1
  *
  * Scala diverged before any stimulus, which invalidates every downstream
  * comparison. These fix the starting condition a parity gate depends on.
  */
class ResetClearsRestoredMembershipSpec extends AnyFlatSpec with Matchers {

  private def sensor(id: String, offset: Int, length: Int = 4) =
    SensorSourceConfig(
      id          = id,
      name        = s"restored-$id",
      region      = Region(offset, length),
      active      = false,
      sensorId    = id,
      lastValue   = Vector.empty,
      lastUpdated = None,
      ttlMs       = 600000L,
    )

  "reset" should "drop restored sources no integration registered this run" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-a", 44))
    engine.restoreSource(sensor("restored-b", 108))
    engine.getSources should have size 2

    engine.reset()

    engine.getSources shouldBe empty
  }

  it should "shrink the perceptual space back with the dropped sources" in {
    // The space cannot un-grow within a run, which is why this presented as a
    // reset defect rather than a cosmetic one: deleting the sources over the
    // API left the length divergence exactly as it was.
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-high", 16938, 4))
    engine.vectorDimension shouldBe 16942

    engine.reset()

    engine.vectorDimension shouldBe 7680
  }

  it should "never shrink below the configured initial dimension" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-low", 44))
    engine.reset()
    engine.vectorDimension shouldBe 7680
  }

  it should "keep a restored source an integration has since fed" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("claimed", 44))
    engine.updateSensorValue("claimed", Vector(1.0, 0.0, 0.0, 0.0)) shouldBe true

    engine.reset()

    engine.getSource("claimed") should not be empty
  }

  it should "keep a restored source an integration has since re-declared" in {
    // Re-registration is the same event as first registration: the integration
    // has claimed the source, so it is this run's membership.
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("redeclared", 48))
    engine.declareSource(sensor("redeclared", 48))

    engine.reset()

    engine.getSource("redeclared") should not be empty
  }

  it should "keep a source added through the API" in {
    val engine = new PerceptionEngine(7680)
    engine.addSource(sensor("added", 52))
    engine.reset()
    engine.getSource("added") should not be empty
  }

  it should "leave a restored source dropped across repeated resets" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-a", 44))
    engine.reset()
    engine.reset()
    engine.getSources shouldBe empty
    engine.vectorDimension shouldBe 7680
  }

  it should "reach the same source set as an engine that restored nothing" in {
    // The property the parity gate actually depends on: two engines given the
    // same corpus and the same registrations agree after a reset, whatever
    // either one's store happened to hold.
    val withStore = new PerceptionEngine(7680)
    (1 to 20).foreach(i => withStore.restoreSource(sensor(s"restored-$i", 40 + i * 4)))
    withStore.addSource(sensor("registered", 928))

    val withoutStore = new PerceptionEngine(7680)
    withoutStore.addSource(sensor("registered", 928))

    withStore.reset()
    withoutStore.reset()

    withStore.getSources.map(_.id) shouldBe withoutStore.getSources.map(_.id)
    withStore.vectorDimension shouldBe withoutStore.vectorDimension
  }
}
