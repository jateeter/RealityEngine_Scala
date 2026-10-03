package com.realityengine.models

import java.security.SecureRandom

/** Minted identity: `<prefix>-<uuid>`, a time-ordered (version 7) UUID in
  * canonical lowercase form, on every runtime (RealityEngine_CI#518, #281).
  *
  * Ids are unique across the universe, recognisable by shape (corpus ids are
  * never UUIDs) and sort in creation order: 48 bits of Unix milliseconds, a
  * 12-bit counter that increases within one millisecond, then 62 random bits.
  *
  * Creation order is load-bearing. SURFACE_SPEC breaks `activeRegions` ties on
  * machineId, so two machines on one region are ordered by their ids; random
  * (v4) ids ordered them differently on every engine and split universal
  * vectors on every event. Engines loading the same machines in the same order
  * mint ids that sort the same way, as the earlier time-prefixed format did.
  */
object MintedIds {
  private val random  = new SecureRandom()
  private var lastMs  = 0L
  private var counter = 0L

  def uuidV7(): String = {
    val (ms, seq, rand) = synchronized {
      var now = System.currentTimeMillis()
      if (now <= lastMs) {
        now = lastMs
        counter += 1
        if (counter > 0xfffL) { now += 1; counter = 0 }
      } else counter = 0
      lastMs = now
      (now, counter, random.nextLong())
    }
    val hi = ((ms & 0xffffffffffffL) << 16) | 0x7000L | seq
    val lo = (rand & 0x3fffffffffffffffL) | 0x8000000000000000L
    f"${hi >>> 32}%08x-${(hi >>> 16) & 0xffffL}%04x-${hi & 0xffffL}%04x-${lo >>> 48}%04x-${lo & 0xffffffffffffL}%012x"
  }

  def mint(prefix: String): String = s"$prefix-${uuidV7()}"
}
