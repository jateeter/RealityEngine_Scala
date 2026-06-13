package com.realityengine.models

/**
 * PerceptualSpace — manages the shared n-dimensional perceptual reality space (En).
 *
 * Architecture:
 *  - En: the complete event space (dimension grows to accommodate machine mappings)
 *  - Machines view subsets of En via offset/length mappings (Em)
 *  - Machine outputs are merged back into En to update reality perception
 *
 * Implementation note:
 *  The backing store is a mutable Array[Double].  All hot-path mutations
 *  (mergeMachineOutput, updateRegion) write directly into the array —
 *  no full-vector allocation per merge.  An immutable Vector[Double] snapshot
 *  is produced only by getPerceptualVector / extractMachineInput / getRegion,
 *  which are called at most once per simulation step.
 *
 *  Dimension management:
 *   growTo(n)           — expands the space to n, preserving existing values.
 *   setPerceptualVector — accepts vectors of any length: shorter vectors zero-fill
 *                         the tail; longer vectors auto-grow the space first.
 */
class PerceptualSpace(initialDimension: Int = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)) {
  private var _dimension:     Int            = initialDimension
  private var perceptualArray: Array[Double] = new Array[Double](initialDimension)

  def dimension: Int = _dimension

  // ── Dimension management ──────────────────────────────────────────────────

  /** Expand the space to newDimension, zero-filling new slots. No-op if already large enough. */
  def growTo(newDimension: Int): Unit = {
    if (newDimension <= _dimension) return
    val grown = new Array[Double](newDimension)
    System.arraycopy(perceptualArray, 0, grown, 0, _dimension)
    perceptualArray = grown
    _dimension = newDimension
  }

  // ── Accessors ────────────────────────────────────────────────────────────

  /** Returns an immutable snapshot of the full space. O(n) — call sparingly. */
  def getPerceptualVector: Vector[Double] = perceptualArray.toVector

  /**
   * Replace the perceptual vector.
   *  - v.length > dimension → auto-grows before writing.
   *  - v.length < dimension → provided elements written to [0, N); tail is zeroed.
   *  - v.length == dimension → exact replacement.
   */
  def setPerceptualVector(v: Vector[Double]): Unit = {
    if (v.length > _dimension) growTo(v.length)
    var i = 0
    while (i < v.length) { perceptualArray(i) = v(i); i += 1 }
    while (i < _dimension) { perceptualArray(i) = 0.0; i += 1 }
  }

  // ── Machine I/O ──────────────────────────────────────────────────────────

  def extractMachineInput(mapping: PerceptualMapping): Vector[Double] = {
    val RegionMapping(offset, length) = mapping.input
    require(offset >= 0 && offset < _dimension,
      s"Input offset $offset is out of bounds [0, ${_dimension})")
    require(offset + length <= _dimension,
      s"Input mapping [$offset, ${offset + length}) exceeds dimension ${_dimension}")
    val slice = new Array[Double](length)
    System.arraycopy(perceptualArray, offset, slice, 0, length)
    slice.toVector
  }

  /** In-place write — O(outputVector.length), no full-vector allocation. */
  def mergeMachineOutput(outputVector: Vector[Double], mapping: PerceptualMapping): Unit = {
    val RegionMapping(offset, length) = mapping.output
    require(offset >= 0 && offset + length <= _dimension && outputVector.length == length,
      s"Invalid output mapping: offset=$offset length=$length vectorLen=${outputVector.length} dimension=${_dimension}")
    var i = 0
    while (i < length) { perceptualArray(offset + i) = outputVector(i); i += 1 }
  }

  // ── Region helpers ────────────────────────────────────────────────────────

  /** In-place write — O(values.length), no full-vector allocation. */
  def updateRegion(offset: Int, values: Vector[Double]): Unit = {
    require(offset >= 0 && offset + values.length <= _dimension,
      s"Update region [$offset, ${offset + values.length}) out of bounds for dimension ${_dimension}")
    var i = 0
    val len = values.length
    while (i < len) { perceptualArray(offset + i) = values(i); i += 1 }
  }

  def getRegion(offset: Int, length: Int): Vector[Double] = {
    require(offset >= 0 && offset < _dimension,
      s"Offset $offset is out of bounds [0, ${_dimension})")
    require(offset + length <= _dimension,
      s"Region [$offset, ${offset + length}) exceeds dimension ${_dimension}")
    val slice = new Array[Double](length)
    System.arraycopy(perceptualArray, offset, slice, 0, length)
    slice.toVector
  }

  def reset(): Unit = java.util.Arrays.fill(perceptualArray, 0.0)

  // ── Serialisation ─────────────────────────────────────────────────────────

  def toJson: io.circe.Json = {
    import io.circe.Json
    import io.circe.syntax._
    Json.obj(
      "dimension"        -> Json.fromInt(_dimension),
      "perceptualVector" -> perceptualArray.toVector.asJson
    )
  }
}

object PerceptualSpace {
  def fromJson(json: io.circe.Json): PerceptualSpace = {
    val c    = json.hcursor
    val dim  = c.get[Int]("dimension").getOrElse(sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680))
    val ps   = new PerceptualSpace(dim)
    val vec  = c.downField("perceptualVector").as[Vector[Double]].getOrElse(Vector.fill(dim)(0.0))
    ps.setPerceptualVector(vec)
    ps
  }

  def validateMapping(mapping: PerceptualMapping, dimension: Int): (Boolean, List[String]) = {
    var errors = List.empty[String]
    val RegionMapping(iOff, iLen) = mapping.input
    val RegionMapping(oOff, oLen) = mapping.output
    if (iOff < 0)               errors = errors :+ s"Input offset $iOff must be non-negative"
    if (iLen <= 0)               errors = errors :+ s"Input length $iLen must be positive"
    if (iOff + iLen > dimension) errors = errors :+ s"Input mapping [$iOff, ${iOff + iLen}) exceeds dimension $dimension"
    if (oOff < 0)               errors = errors :+ s"Output offset $oOff must be non-negative"
    if (oLen <= 0)               errors = errors :+ s"Output length $oLen must be positive"
    if (oOff + oLen > dimension) errors = errors :+ s"Output mapping [$oOff, ${oOff + oLen}) exceeds dimension $dimension"
    (errors.isEmpty, errors)
  }
}
