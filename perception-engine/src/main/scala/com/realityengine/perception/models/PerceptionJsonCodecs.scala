package com.realityengine.perception.models

import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.syntax._

/**
 * Hand-rolled circe codecs for all perception engine types.
 * Uses a flat "type" discriminator field on SourceConfig variants.
 */
object PerceptionJsonCodecs {

  // ── Region ────────────────────────────────────────────────────────────────

  implicit val encodeRegion: Encoder[Region] = r =>
    Json.obj("offset" -> r.offset.asJson, "length" -> r.length.asJson)

  implicit val decodeRegion: Decoder[Region] = c =>
    for {
      offset <- c.get[Int]("offset")
      length <- c.get[Int]("length")
    } yield Region(offset, length)

  // ── SimPattern ────────────────────────────────────────────────────────────

  implicit val encodeSimPattern: Encoder[SimPattern] = p => Json.fromString(SimPattern.asString(p))

  implicit val decodeSimPattern: Decoder[SimPattern] =
    Decoder.decodeString.emap(s => Right(SimPattern.fromString(s)))

  // ── MatchAlgorithm ────────────────────────────────────────────────────────

  implicit val encodeMatchAlgorithm: Encoder[MatchAlgorithm] = a => Json.fromString(MatchAlgorithm.asString(a))

  implicit val decodeMatchAlgorithm: Decoder[MatchAlgorithm] =
    Decoder.decodeString.emap(s => Right(MatchAlgorithm.fromString(s)))

  // ── SourceConfig variants ─────────────────────────────────────────────────

  implicit val encodeTestSourceConfig: Encoder[TestSourceConfig] = src =>
    Json.obj(
      "type"         -> "test".asJson,
      "id"           -> src.id.asJson,
      "name"         -> src.name.asJson,
      "region"       -> src.region.asJson,
      "active"       -> src.active.asJson,
      "machineId"    -> src.machineId.asJson,
      "machineName"  -> src.machineName.asJson,
      "sequenceName" -> src.sequenceName.asJson,
      "inputs"       -> src.inputs.map(_.asJson).asJson,
      "loop"         -> src.loop.asJson,
      "metadata"     -> src.sequenceMetadata,
      "sequence"     -> src.testSequence,
    )

  implicit val decodeTestSourceConfig: Decoder[TestSourceConfig] = (c: HCursor) =>
    for {
      id           <- c.getOrElse[String]("id")("")
      name         <- c.get[String]("name")
      region       <- c.get[Region]("region")
      active       <- c.get[Boolean]("active")
      machineId    <- c.get[String]("machineId")
      machineName  <- c.get[String]("machineName")
      sequenceName <- c.get[String]("sequenceName")
      inputs       <- c.get[Vector[Vector[Double]]]("inputs")
      loop         <- c.get[Boolean]("loop")
      metadata     <- c.getOrElse[Json]("metadata")(Json.obj())
      sequence     <- c.getOrElse[Json]("sequence")(Json.obj())
    } yield TestSourceConfig(id, name, region, active, machineId, machineName, sequenceName, inputs, loop, metadata, sequence)

  implicit val encodeSimulatedSourceConfig: Encoder[SimulatedSourceConfig] = src =>
    Json.obj(
      "type"      -> "simulated".asJson,
      "id"        -> src.id.asJson,
      "name"      -> src.name.asJson,
      "region"    -> src.region.asJson,
      "active"    -> src.active.asJson,
      "pattern"   -> src.pattern.asJson,
      "frequency" -> src.frequency.asJson,
      "amplitude" -> src.amplitude.asJson,
      "dcOffset"  -> src.dcOffset.asJson,
    )

  implicit val decodeSimulatedSourceConfig: Decoder[SimulatedSourceConfig] = (c: HCursor) =>
    for {
      id        <- c.getOrElse[String]("id")("")
      name      <- c.get[String]("name")
      region    <- c.get[Region]("region")
      active    <- c.get[Boolean]("active")
      pattern   <- c.get[SimPattern]("pattern")
      frequency <- c.get[Double]("frequency")
      amplitude <- c.get[Double]("amplitude")
      dcOffset  <- c.get[Double]("dcOffset")
    } yield SimulatedSourceConfig(id, name, region, active, pattern, frequency, amplitude, dcOffset)

  implicit val encodeSensorSourceConfig: Encoder[SensorSourceConfig] = src =>
    Json.obj(
      "type"        -> "sensor".asJson,
      "id"          -> src.id.asJson,
      "name"        -> src.name.asJson,
      "region"      -> src.region.asJson,
      "active"      -> src.active.asJson,
      "sensorId"    -> src.sensorId.asJson,
      "lastValue"   -> src.lastValue.asJson,
      "lastUpdated" -> src.lastUpdated.asJson,
      "ttlMs"       -> src.ttlMs.asJson,
    ).deepMerge(src.origin.fold(Json.obj())(o => Json.obj("origin" -> o.asJson)))

  implicit val decodeSensorSourceConfig: Decoder[SensorSourceConfig] = (c: HCursor) =>
    for {
      id          <- c.getOrElse[String]("id")("")
      name        <- c.get[String]("name")
      region      <- c.get[Region]("region")
      active      <- c.get[Boolean]("active")
      sensorId    <- c.get[String]("sensorId")
      lastValue   <- c.get[Vector[Double]]("lastValue")
      lastUpdated <- c.get[Option[Long]]("lastUpdated")
      ttlMs       <- c.get[Long]("ttlMs")
      origin      <- c.getOrElse[Option[String]]("origin")(None)
    } yield SensorSourceConfig(id, name, region, active, sensorId, lastValue, lastUpdated, ttlMs, origin)

  // ── SourceConfig (discriminated union) ───────────────────────────────────

  // `active` is emitted verbatim from the source handed in, and what a reader
  // must be given is the *reported* value — `stored AND validated`
  // (RealityEngine_CI#175). The engine owns that rule
  // (`PerceptionEngine.reportedSources` / `.reported`), so anything serializing
  // a source for a client passes it through one of those first; the store is the
  // one caller that deliberately does not.
  //
  // The rule lives there rather than here because an encoder has no clock to
  // read once per pass, and because moving it into implicit scope is how the
  // marshaller ambiguity in PerceptionRoutes (`jsonMarshaller` vs `marshaller`)
  // started: a second, more specific encoder for the same type resolves by
  // specificity, not by intent.
  implicit val encodeSourceConfig: Encoder[SourceConfig] = {
    case s: TestSourceConfig      => encodeTestSourceConfig(s)
    case s: SimulatedSourceConfig => encodeSimulatedSourceConfig(s)
    case s: SensorSourceConfig    => encodeSensorSourceConfig(s)
  }

  implicit val decodeSourceConfig: Decoder[SourceConfig] = (c: HCursor) =>
    c.get[String]("type").flatMap {
      case "test"      => decodeTestSourceConfig(c)
      case "simulated" => decodeSimulatedSourceConfig(c)
      case "sensor"    => decodeSensorSourceConfig(c)
      case other       => Left(io.circe.DecodingFailure(s"Unknown source type: $other", c.history))
    }

  // ── AutoConfig ────────────────────────────────────────────────────────────

  implicit val encodeAutoConfig: Encoder[AutoConfig] = a =>
    Json.obj("running" -> a.running.asJson, "intervalMs" -> a.intervalMs.asJson)

  // ── EngineState ───────────────────────────────────────────────────────────

  implicit val encodeEngineState: Encoder[EngineState] = s =>
    Json.obj(
      "sources"         -> s.sources.asJson,
      "assembledVector" -> s.assembledVector.asJson,
      "globalStep"      -> s.globalStep.asJson,
      "auto"            -> s.auto.asJson,
      "lastPush"        -> s.lastPush.asJson,
      "matchAlgorithm"  -> s.matchAlgorithm.asJson,
      "perceptionDimension" -> s.perceptionDimension.asJson,
    )

  // ── PushResult ────────────────────────────────────────────────────────────

  implicit val encodePushResult: Encoder[PushResult] = r =>
    Json.obj(
      "success"    -> r.success.asJson,
      "step"       -> r.step.asJson,
      "timestamp"  -> r.timestamp.asJson,
      "globalStep" -> r.globalStep.asJson,
      "error"      -> r.error.asJson,
    )
}
