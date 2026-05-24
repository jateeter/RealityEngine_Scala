package com.realityengine.engine

import com.realityengine.models.OutputVector

/**
 * RealitySampler — samples observations from reality and feeds them to the engine.
 * Manages buffering and coordinates between PerceptionOfReality and RealityEngine.
 *
 * Note: CONTINUOUS and PERIODIC strategies require an external scheduler
 * (e.g. Akka scheduler) in the JVM context. This implementation is manual/event-driven.
 */

sealed trait SamplingStrategy
object SamplingStrategy {
  case object CONTINUOUS    extends SamplingStrategy
  case object PERIODIC      extends SamplingStrategy
  case object EVENT_DRIVEN  extends SamplingStrategy
  case object MANUAL        extends SamplingStrategy
}

case class SamplingConfig(
  strategy:      SamplingStrategy,
  intervalMs:    Option[Long]   = None,
  maxBufferSize: Int            = 1000,
  autoProcess:   Boolean        = true
)

case class TransitionResult(
  inputVector:     Vector[Double],
  timestamp:       Long,
  totalOutputs:    List[OutputVector]
)

class RealitySampler(
  perception: PerceptionOfReality,
  engine:     RealityEngine,
  config:     SamplingConfig
) {
  // Bounded ring-buffer: append is O(1) amortized; removeHead is O(1) amortized.
  // Replaces the previous Vector.:+ (O(log n)) + takeRight (copies entire buffer).
  private val observationBuffer: scala.collection.mutable.ArrayDeque[RawObservation] =
    scala.collection.mutable.ArrayDeque.empty

  private var sampleCount: Int = 0
  private var running: Boolean = config.strategy != SamplingStrategy.MANUAL

  def isRunning: Boolean = running
  def start(): Unit      = { running = true }
  def stop(): Unit       = { running = false }

  def sample(observation: RawObservation): Option[TransitionResult] = {
    sampleCount += 1
    addToBuffer(observation)
    if (config.autoProcess) Some(processSingle(observation)) else None
  }

  def sampleMultiple(observations: List[RawObservation]): List[TransitionResult] = {
    observations.flatMap { obs =>
      addToBuffer(obs)
      if (config.autoProcess) Some(processSingle(obs)) else None
    }
  }

  def processBuffer(): List[TransitionResult] = {
    val results = observationBuffer.toList.map(processSingle)
    observationBuffer.clear()
    results
  }

  def generateQuantumFoamSample(dimension: Int): RawObservation =
    PerceptionOfReality.createObservation(
      data     = Vector.fill(dimension)(scala.util.Random.nextDouble()),
      source   = Some("quantum-foam"),
      metadata = Map("type" -> io.circe.Json.fromString("stochastic"))
    )

  def getStats: io.circe.Json = {
    import io.circe.Json
    Json.obj(
      "isRunning"   -> Json.fromBoolean(running),
      "sampleCount" -> Json.fromInt(sampleCount),
      "bufferSize"  -> Json.fromInt(observationBuffer.size),
      "strategy"    -> Json.fromString(config.strategy.toString)
    )
  }

  def clearBuffer(): Unit = { observationBuffer.clear() }
  def reset(): Unit       = { sampleCount = 0; clearBuffer() }
  def getBuffer: Vector[RawObservation] = observationBuffer.toVector

  private def processSingle(observation: RawObservation): TransitionResult = {
    val perceived = perception.perceive(observation)
    engine.processInputLegacy(perceived.inputVector)
  }

  private def addToBuffer(observation: RawObservation): Unit = {
    observationBuffer.append(observation)
    if (observationBuffer.size > config.maxBufferSize)
      observationBuffer.removeHead()
  }
}
