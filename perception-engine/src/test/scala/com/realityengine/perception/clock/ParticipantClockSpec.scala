package com.realityengine.perception.clock

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The body fields of RealityEngine_CI#600: `clock` on a stored message and a
  * completion's `causedBy` (SURFACE_SPEC "The instance clock"). */
class ParticipantClockSpec extends AnyWordSpec with Matchers {
  private def j(s: String): Json = parse(s).toOption.get
  private val own = j("""{"instance":"0192f3a0-0000-7000-8000-0000000000c2","lamport":7}""")

  "event" should {
    "tick the PE's clock and name it {instance, lamport}" in {
      val a = ParticipantClock.event().hcursor
      val b = ParticipantClock.event().hcursor
      a.get[String]("instance").toOption shouldBe Some(ParticipantClock.clock.instance)
      b.get[Long]("lamport").toOption.get should be > a.get[Long]("lamport").toOption.get
    }
  }

  "causedBy" should {
    "prefer the PE's own dispatch record" in {
      ParticipantClock.causedBy(Some(own), Some(j("""{"instance":"0192f3a0-0000-7000-8000-000000000009","lamport":42}"""))) shouldBe own
    }
    "take a well-formed echo when the record is gone" in {
      ParticipantClock.causedBy(None, Some(j("""{"instance":"0192f3a0-0000-7000-8000-000000000009","lamport":42}"""))) shouldBe
        j("""{"instance":"0192f3a0-0000-7000-8000-000000000009","lamport":42}""")
    }
    "refuse a malformed echo" in {
      ParticipantClock.causedBy(None, Some(j("""{"instance":"NOPE","lamport":42}"""))) shouldBe Json.Null
      ParticipantClock.causedBy(None, Some(j("""{"instance":"0192F3A0-0000-7000-8000-000000000009","lamport":42}"""))) shouldBe Json.Null
      ParticipantClock.causedBy(None, Some(j("""{"instance":"0192f3a0-0000-7000-8000-000000000009","lamport":-1}"""))) shouldBe Json.Null
      ParticipantClock.causedBy(None, Some(j("""{"instance":"0192f3a0-0000-7000-8000-000000000009","lamport":9007199254740992}"""))) shouldBe Json.Null
    }
    "be null with neither" in {
      ParticipantClock.causedBy(None, None) shouldBe Json.Null
    }
  }
}
