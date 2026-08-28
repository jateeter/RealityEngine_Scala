package com.realityengine.perception.store

import com.realityengine.perception.models._
import com.realityengine.perception.models.PerceptionJsonCodecs._
import io.circe.parser.decode
import io.circe.syntax._

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import scala.util.{Failure, Success, Try}

/**
 * Persists perception engine sources to a JSON file using atomic writes
 * (write to .tmp then rename) to prevent corruption on crash.
 *
 * The store caches run state — cached value and cached activity — and never
 * membership (RealityEngine_CI SURFACE_SPEC.md, source contract point 5).
 * Membership comes from re-registration: an integration that has not registered
 * this run owns no sources, whatever this file records.
 *
 * Sensor `lastValue` and `lastUpdated` are persisted rather than stripped.
 * Stripping them made the file record that a source *was* active while
 * discarding the only evidence that could justify the claim, so a restored
 * source could not be validated — the combination
 * `PerceptionEngine.cachedValueSupportsActivity` needs (#58).
 *
 * Staleness is handled by validation on restore, not by erasure here: a cached
 * value outside its TTL deactivates the source on the evidence. That is what
 * the blanket strip was approximating.
 *
 * save() is called from a blocking-io-dispatcher Future in PerceptionRoutes
 * so the file I/O never blocks Akka's default dispatcher.
 */
class SourceStore(dataDir: String) {
  private val dir: Path      = Paths.get(dataDir)
  private val filePath: Path = dir.resolve("perception-sources.json")

  if (!Files.exists(dir)) Files.createDirectories(dir)

  def load(): Vector[SourceConfig] = {
    if (!Files.exists(filePath)) return Vector.empty

    Try {
      val raw  = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
      val json = io.circe.parser.parse(raw).getOrElse(io.circe.Json.Null)
      json.hcursor.get[Vector[SourceConfig]]("sources").getOrElse(Vector.empty)
    } match {
      case Success(sources) => sources
      case Failure(e) =>
        System.err.println(s"[SourceStore] Failed to load sources file, starting fresh: ${e.getMessage}")
        Vector.empty
    }
  }

  def save(sources: Vector[SourceConfig]): Unit = {
    val payload = io.circe.Json.obj(
      "version" -> 1.asJson,
      "sources" -> sources.asJson,
    )

    val tmp = filePath.resolveSibling(filePath.getFileName.toString + ".tmp")
    try {
      Files.write(tmp, payload.spaces2.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case e: IOException =>
        System.err.println(s"[SourceStore] Failed to save sources: ${e.getMessage}")
    }
  }
}
