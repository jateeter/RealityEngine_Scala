package com.realityengine.engine

/**
 * PerceptionOfReality — transforms raw observations into normalized InputRealityVectors.
 */

case class RawObservation(
  data:      Vector[Double],
  timestamp: Long,
  source:    Option[String]              = None,
  metadata:  Map[String, io.circe.Json]  = Map.empty
)

case class ProcessedPerception(
  inputVector:           Vector[Double],
  originalObservation:   RawObservation,
  processingTimestamp:   Long,
  transformations:       List[String]
)

class PerceptionOfReality(
  val vectorDimension:       Int,
  val preprocessingEnabled:  Boolean = true
) {
  // In-place transformer: mutates the pre-sized working array directly.
  // Eliminates one Vector[Double] allocation per transformer per call.
  type TransformFunction = Array[Double] => Unit

  private var transformers: List[TransformFunction] = Nil

  // Pre-allocated working buffer — reused across perceive() calls.
  // perceive() is called from a single thread (or synchronized externally),
  // so sharing this array is safe.
  private val workBuf: Array[Double] = new Array[Double](vectorDimension)

  if (preprocessingEnabled) addDefaultTransformers()

  def addTransformer(t: TransformFunction): Unit = { transformers = transformers :+ t }
  def clearTransformers(): Unit = { transformers = Nil }

  def perceive(observation: RawObservation): ProcessedPerception = {
    // Single pass: copy input into pre-sized buffer (zero-pad or truncate),
    // apply all transformers in-place, then snapshot once as immutable Vector.
    val src    = observation.data
    val srcLen = math.min(src.length, vectorDimension)
    var i = 0
    while (i < srcLen)            { workBuf(i) = src(i); i += 1 }
    while (i < vectorDimension)   { workBuf(i) = 0.0;    i += 1 }

    transformers.foreach(_(workBuf))

    ProcessedPerception(
      inputVector          = workBuf.toVector,   // single allocation
      originalObservation  = observation,
      processingTimestamp  = System.currentTimeMillis(),
      transformations      = List("dimension-normalization")
    )
  }

  def perceiveMultiple(observations: List[RawObservation]): List[ProcessedPerception] =
    observations.map(perceive)

  private def addDefaultTransformers(): Unit =
    addTransformer { arr =>
      // Find min/max in one pass
      var mx = 1.0; var mn = 0.0; var i = 0
      while (i < arr.length) {
        if (arr(i) > mx) mx = arr(i)
        if (arr(i) < mn) mn = arr(i)
        i += 1
      }
      val range = mx - mn
      if (range != 0.0) {
        i = 0
        while (i < arr.length) { arr(i) = (arr(i) - mn) / range; i += 1 }
      }
    }

  def getConfig: Map[String, Any] = Map(
    "vectorDimension"      -> vectorDimension,
    "preprocessingEnabled" -> preprocessingEnabled,
    "transformerCount"     -> transformers.length
  )
}

object PerceptionOfReality {
  def createObservation(
    data:     Vector[Double],
    source:   Option[String]             = None,
    metadata: Map[String, io.circe.Json] = Map.empty
  ): RawObservation =
    RawObservation(data = data, timestamp = System.currentTimeMillis(), source = source, metadata = metadata)
}
