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
 * Sensor sources are stripped of their live data fields (lastValue,
 * lastUpdated) before saving — those are runtime state.
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
      case Success(sources) =>
        // Reset live sensor fields so stale data doesn't appear on restart
        sources.map {
          case s: SensorSourceConfig => s.copy(lastValue = Vector.empty, lastUpdated = None)
          case other                 => other
        }
      case Failure(e) =>
        System.err.println(s"[SourceStore] Failed to load sources file, starting fresh: ${e.getMessage}")
        Vector.empty
    }
  }

  def save(sources: Vector[SourceConfig]): Unit = {
    val sanitized = sources.map {
      case s: SensorSourceConfig => s.copy(lastValue = Vector.empty, lastUpdated = None)
      case other                 => other
    }

    val payload = io.circe.Json.obj(
      "version" -> 1.asJson,
      "sources" -> sanitized.asJson,
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
