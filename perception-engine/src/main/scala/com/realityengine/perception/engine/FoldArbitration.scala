package com.realityengine.perception.engine

import io.circe.parser.parse

import java.nio.file.{Files, Path, Paths}

/**
 * What the PE's Source-vs-OSRE fold needs from the arbiter (ARBITER_CONTRACT.md
 * §4.4b, amended 2026-10-04, RealityEngine_CI#525): the declared rule of a
 * cell, the rank of a provider under it, and the provider of a source.
 *
 * The Reality Engine owns the arbiter; this PE is a separate build with no
 * access to it, so the little it needs is read here from the same registry
 * file the RE loads (domains/arbitration-registry.json), and ranked the way
 * the arbiter ranks: a declared providerRanks entry, else the determinism class.
 */
object FoldArbitration {

  final case class Entry(rule: String, providerRanks: Map[String, Int])

  // Contract §3: the determinism class of each registered provider. An
  // unregistered surface is generated -- a misclassified generated source could
  // otherwise outrank a reading. Keep in step with the RE arbiter's table.
  private val determinism: Map[String, Int] = Map(
    "machine" -> 3,
    "sensor" -> 2, "mqtt" -> 2, "healthkit" -> 2, "stream" -> 2, "ui" -> 2, "synthetic" -> 2,
    "acp" -> 1, "mcp" -> 1, "localai" -> 1)
  def determinismRank(provider: String): Int = determinism.getOrElse(provider, 1)

  // Surface names an integration writes as its origin, mapped to the provider
  // they are.
  private val aliases = Map("openclaw" -> "acp", "ollama" -> "localai", "localaistack" -> "localai")

  /** The contract provider of a source: the first `.` segment of its origin
    * through the surface aliases; an empty origin or `signal` falls back to the
    * kind (test and simulated -> synthetic, else sensor). The first segment,
    * never a substring: a substring match lets `localai.x-mcp-y` be mcp. */
  def sourceProvider(origin: Option[String], kind: String): String = {
    val head = origin.getOrElse("").toLowerCase.takeWhile(_ != '.')
    if (head.isEmpty || head == "signal") { if (kind == "test" || kind == "simulated") "synthetic" else "sensor" }
    else aliases.getOrElse(head, head)
  }

  def rank(provider: String, entry: Entry): Int =
    entry.providerRanks.getOrElse(provider, determinismRank(provider))

  @volatile private var entries: Map[Int, Entry] = Map.empty
  def entryFor(cell: Int): Option[Entry] = entries.get(cell)

  /** Replace the registry (tests). */
  def install(declared: Map[Int, Entry]): Unit = entries = declared

  /** Load ARBITRATION_REGISTRY, else the registry beside MACHINES_DIR, as the
    * RE does. A missing registry leaves every cell undeclared: the fold then
    * applies the machine's operator everywhere, which is what it did before. */
  def load(env: Map[String, String] = sys.env): Unit = {
    val machinesDir = env.getOrElse("MACHINES_DIR", "../RealityEngine_Machines/machines")
    val candidates: List[Path] =
      env.get("ARBITRATION_REGISTRY").map(Paths.get(_)).toList ++ List(
        Paths.get(machinesDir, "..", "domains", "arbitration-registry.json"),
        Paths.get(machinesDir, "domains", "arbitration-registry.json"),
        Paths.get("..", "RealityEngine_Machines", "domains", "arbitration-registry.json"))
    candidates.find(p => Files.isRegularFile(p)) match {
      case None =>
        System.err.println("[fold] no arbitration registry found; every fold cell is undeclared")
      case Some(path) =>
        parse(new String(Files.readAllBytes(path), "UTF-8")) match {
          case Left(e) => System.err.println(s"[fold] failed to read $path: ${e.getMessage}")
          case Right(doc) =>
            val loaded = doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).flatMap { e =>
              val c = e.hcursor
              c.get[Int]("cell").toOption.map { cell =>
                cell -> Entry(c.get[String]("rule").getOrElse("PRECEDENCE"),
                              c.get[Map[String, Int]]("providerRanks").getOrElse(Map.empty))
              }
            }.toMap
            entries = loaded
            System.err.println(s"[fold] loaded ${loaded.size} declared cell(s) from $path")
        }
    }
  }
}
