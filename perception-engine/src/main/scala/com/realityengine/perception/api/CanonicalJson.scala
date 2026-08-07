package com.realityengine.perception.api

import io.circe.{Json, JsonNumber}

import java.math.{BigDecimal => JBigDecimal, MathContext, RoundingMode}

/** Canonical numeric rendering for JSON responses.
  *
  * The wire form is ECMAScript `Number::toString` (ECMA-262 6.1.6.1.20) -- what
  * `JSON.stringify` emits, and therefore what the TypeScript runtime already
  * produces without any change. Shortest round-trip digits; integer form when
  * the value is whole and within plain range; exponential only outside 1e21 /
  * 1e-7.
  *
  * The JDK is deliberately not used for this. `java.lang.Double.toString` is
  * not shortest round-trip on JDK 17 (it emits extra digits for some values,
  * e.g. `4.7511435871098592E16` where `4.751143587109859E16` round-trips
  * identically) and its algorithm was replaced in JDK 19. Pegging the wire
  * format to it would make the bytes depend on the JVM version, so the digits
  * are derived here with exact `BigDecimal` arithmetic instead
  * (RealityEngine_CI#91).
  */
object CanonicalJson {

  def formatDouble(value: Double): String = {
    if (value.isNaN || value.isInfinite) "null"
    else if (value == 0.0) "0"
    else {
      val negative = value < 0
      val magnitude = math.abs(value)
      val exact = new JBigDecimal(magnitude)

      // Smallest digit count that reads back as the identical double.
      var digits = ""
      var n = 0
      var p = 1
      var found = false
      while (!found && p <= 17) {
        val rounded = exact.round(new MathContext(p, RoundingMode.HALF_EVEN))
        if (rounded.doubleValue() == magnitude) {
          val stripped = rounded.stripTrailingZeros()
          digits = stripped.unscaledValue().toString
          n = digits.length - stripped.scale()
          found = true
        }
        p += 1
      }

      val k = digits.length
      val out = new StringBuilder
      if (negative) out.append('-')
      if (k <= n && n <= 21) {
        out.append(digits)
        out.append("0" * (n - k))
      } else if (0 < n && n <= 21) {
        out.append(digits.substring(0, n)).append('.').append(digits.substring(n))
      } else if (-6 < n && n <= 0) {
        out.append("0.").append("0" * (-n)).append(digits)
      } else {
        out.append(digits.charAt(0))
        if (k > 1) out.append('.').append(digits.substring(1))
        out.append('e').append(if (n - 1 >= 0) '+' else '-').append(math.abs(n - 1))
      }
      out.toString
    }
  }

  /** Rewrite every non-integral number in the tree to its canonical form.
    *
    * Applied to the whole document rather than to an `Encoder[Double]` because
    * doubles reach the AST by several routes -- derived codecs, `asJson`, and
    * direct `Json.fromDoubleOrNull` calls -- and only a tree rewrite catches
    * all of them. Integer literals are left untouched so that `Long` values
    * beyond double precision are not degraded.
    */
  def canonicalizeNumbers(json: Json): Json =
    json.fold(
      Json.Null,
      Json.fromBoolean,
      number => {
        val rendered = number.toString
        if (rendered.indexOf('.') >= 0 || rendered.indexOf('e') >= 0 || rendered.indexOf('E') >= 0)
          Json.fromJsonNumber(JsonNumber.fromDecimalStringUnsafe(formatDouble(number.toDouble)))
        else json
      },
      Json.fromString,
      values => Json.fromValues(values.map(canonicalizeNumbers)),
      obj => Json.fromJsonObject(obj.mapValues(canonicalizeNumbers))
    )
}
