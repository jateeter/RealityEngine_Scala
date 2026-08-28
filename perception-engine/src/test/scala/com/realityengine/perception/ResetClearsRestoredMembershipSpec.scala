package com.realityengine.perception

import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The store never establishes membership (#58).
  *
  * This spec was written for #61, when boot restored persisted records straight
  * into the source set and `reset()` dropped the ones nothing had claimed. That
  * made reset responsible for cleaning up after boot, and left a window before
  * the first reset in which the space had already grown:
  *
  *     cpp-1 len=14388   lsp-1 len=14388   scala-1 len=16942
  *     FAIL isre-history diverges at step 0 (length): cpp-1+lsp-1 | scala-1
  *
  * The store now caches run state and membership comes from re-registration, so
  * there is nothing for reset to drop and no window to clean up. These assert
  * the stronger property directly: an unclaimed record is never a member, at any
  * point, so the divergence above cannot occur rather than being corrected.
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

  "a restored source" should "never be a member until an integration registers it" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-a", 44))
    engine.restoreSource(sensor("restored-b", 108))

    engine.getSources shouldBe empty
    engine.unclaimedCachedCount shouldBe 2
  }

  it should "not grow the perceptual space" in {
    // The window that made this a step-0 divergence: boot restored a record
    // with a high region, the space grew to fit it, and the space length is
    // part of every ISRE and OREV entry. An engine cannot un-grow within a run,
    // so correcting it at reset was already too late.
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-high", 16938, 4))

    engine.vectorDimension shouldBe 7680
  }

  it should "still be absent after a reset" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("restored-a", 44))
    engine.reset()
    engine.getSources shouldBe empty
    engine.vectorDimension shouldBe 7680
  }

  "registration" should "claim the cached record and take its region" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("claimed", 44))
    engine.declareSource(sensor("claimed", 44))

    engine.getSource("claimed") should not be empty
    engine.unclaimedCachedCount shouldBe 0
  }

  it should "survive a reset once claimed" in {
    val engine = new PerceptionEngine(7680)
    engine.restoreSource(sensor("claimed", 44))
    engine.declareSource(sensor("claimed", 44))
    engine.reset()
    engine.getSource("claimed") should not be empty
  }

  "a source added through the API" should "survive a reset" in {
    val engine = new PerceptionEngine(7680)
    engine.addSource(sensor("added", 52))
    engine.reset()
    engine.getSource("added") should not be empty
  }

  "two engines given the same registrations" should "agree whatever their stores held" in {
    // The property the parity gate depends on, and the one #58 broke: a store
    // is a local artefact, so it must not change what the engine presents.
    val withStore = new PerceptionEngine(7680)
    (1 to 20).foreach(i => withStore.restoreSource(sensor(s"restored-$i", 40 + i * 4)))
    withStore.addSource(sensor("registered", 928))

    val withoutStore = new PerceptionEngine(7680)
    withoutStore.addSource(sensor("registered", 928))

    withStore.getSources.map(_.id) shouldBe withoutStore.getSources.map(_.id)
    withStore.vectorDimension shouldBe withoutStore.vectorDimension
  }
}
