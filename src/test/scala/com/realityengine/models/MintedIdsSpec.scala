package com.realityengine.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Minted ids are time-ordered UUIDs (RealityEngine_CI#518, #281): two machines
  * on one region sort by machineId, so ids must increase in creation order on
  * every engine.
  */
class MintedIdsSpec extends AnyFlatSpec with Matchers {
  "MintedIds.mint" should "produce distinct, version-7, creation-ordered ids" in {
    val ids = Vector.fill(2000)(MintedIds.mint("machine").stripPrefix("machine-"))
    ids.foreach { u =>
      u should fullyMatch regex "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    }
    ids.distinct.size shouldBe ids.size
    ids shouldBe ids.sorted
  }

  "a Machine without a declared id" should "be minted machine-<v7>" in {
    new Machine("m").id should startWith regex "machine-[0-9a-f]{8}-[0-9a-f]{4}-7"
  }
}
