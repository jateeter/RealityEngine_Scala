package com.realityengine.perception.models

// ── Shared primitives ─────────────────────────────────────────────────────────

case class Region(offset: Int, length: Int)

sealed trait SimPattern
object SimPattern {
  case object Sine         extends SimPattern
  case object Sawtooth     extends SimPattern
  case object Square       extends SimPattern
  case object LinearRamp   extends SimPattern
  case object RandomWalk   extends SimPattern
  case object Constant     extends SimPattern
  case object GaussianNoise extends SimPattern
  case object Binary       extends SimPattern

  def fromString(s: String): SimPattern = s match {
    case "sine"          => Sine
    case "sawtooth"      => Sawtooth
    case "square"        => Square
    case "linear-ramp"   => LinearRamp
    case "random-walk"   => RandomWalk
    case "constant"      => Constant
    case "gaussian-noise"=> GaussianNoise
    case "binary"        => Binary
    case other           => throw new IllegalArgumentException(s"Unknown SimPattern: $other")
  }

  def asString(p: SimPattern): String = p match {
    case Sine          => "sine"
    case Sawtooth      => "sawtooth"
    case Square        => "square"
    case LinearRamp    => "linear-ramp"
    case RandomWalk    => "random-walk"
    case Constant      => "constant"
    case GaussianNoise => "gaussian-noise"
    case Binary        => "binary"
  }
}

sealed trait MatchAlgorithm
object MatchAlgorithm {
  case object Gte    extends MatchAlgorithm
  case object Equals extends MatchAlgorithm

  def fromString(s: String): MatchAlgorithm = s match {
    case "gte"    => Gte
    case "equals" => Equals
    case other    => throw new IllegalArgumentException(s"Unknown MatchAlgorithm: $other")
  }

  def asString(a: MatchAlgorithm): String = a match {
    case Gte    => "gte"
    case Equals => "equals"
  }
}

// ── Source configs ────────────────────────────────────────────────────────────

sealed trait SourceConfig {
  def id: String
  def name: String
  def region: Region
  def active: Boolean
  def withActive(a: Boolean): SourceConfig
}

case class TestSourceConfig(
  id: String,
  name: String,
  region: Region,
  active: Boolean,
  machineId: String,
  machineName: String,
  sequenceName: String,
  inputs: Vector[Vector[Double]],
  loop: Boolean,
) extends SourceConfig {
  def withActive(a: Boolean): SourceConfig = copy(active = a)
}

case class SimulatedSourceConfig(
  id: String,
  name: String,
  region: Region,
  active: Boolean,
  pattern: SimPattern,
  frequency: Double,
  amplitude: Double,
  dcOffset: Double,
) extends SourceConfig {
  def withActive(a: Boolean): SourceConfig = copy(active = a)
}

case class SensorSourceConfig(
  id: String,
  name: String,
  region: Region,
  active: Boolean,
  sensorId: String,
  lastValue: Vector[Double],
  lastUpdated: Option[Long],
  ttlMs: Long,
  // Provenance — which integration feeds this source ("mqtt", "openclaw",
  // "ollama", "healthkit", "localai", ...). None for manually created sources.
  origin: Option[String] = None,
) extends SourceConfig {
  def withActive(a: Boolean): SourceConfig = copy(active = a)
}

// ── API types ─────────────────────────────────────────────────────────────────

case class AutoConfig(running: Boolean, intervalMs: Long)

case class EngineState(
  sources: Vector[SourceConfig],
  assembledVector: Vector[Double],
  globalStep: Long,
  auto: AutoConfig,
  lastPush: Option[Long],
  matchAlgorithm: MatchAlgorithm,
)

case class PushResult(
  success: Boolean,
  step: Option[io.circe.Json],
  timestamp: Long,
  globalStep: Long,
  error: Option[String],
)

case class TestProgress(current: Int, total: Int)
