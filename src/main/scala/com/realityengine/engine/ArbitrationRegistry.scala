package com.realityengine.engine

import java.io.File
import scala.io.Source
import scala.util.Try

/**
 * Loads domains/arbitration-registry.json — the declaration of how each
 * contended universal-vector position resolves.
 *
 * Resolution is declared, not defaulted: an undeclared contended cell is a
 * corpus error (ARBITER_CONTRACT.md 5). The engine still has to do something
 * when a cell is contended and unlisted, and what it does is apply PRECEDENCE —
 * which keeps a generated contribution from overriding a deterministic one, the
 * outcome that matters most — while the corpus gate
 * (scripts/build-arbitration-registry.py --check) is what actually prevents the
 * situation arising.
 */
object ArbitrationRegistry {

  private var entries: Map[Int, Arbiter.RegistryEntry] = Map.empty
  private var loadedFrom: Option[String] = None

  def entryFor(cell: Int): Option[Arbiter.RegistryEntry] = entries.get(cell)
  def size: Int = entries.size
  def source: Option[String] = loadedFrom

  /** Resolution order: ARBITRATION_REGISTRY explicit path, then the registry
    * beside MACHINES_DIR, then the conventional sibling checkout. */
  def candidatePaths(machinesDir: String): List[String] = {
    val explicit = sys.env.get("ARBITRATION_REGISTRY").toList
    val beside   = List(
      new File(new File(machinesDir).getParentFile, "domains/arbitration-registry.json").getPath,
      new File(new File(machinesDir), "../domains/arbitration-registry.json").getPath,
      "../RealityEngine_Machines/domains/arbitration-registry.json"
    )
    explicit ++ beside
  }

  def load(machinesDir: String): Unit = {
    val found = candidatePaths(machinesDir).find(p => new File(p).isFile)
    found match {
      case None =>
        println(s"[arbiter] no arbitration registry found; contended cells fall back to PRECEDENCE")
      case Some(path) =>
        val parsed = for {
          raw  <- Try(Source.fromFile(path).mkString).toEither.left.map(_.getMessage)
          json <- io.circe.parser.parse(raw).left.map(_.getMessage)
          arr  <- json.hcursor.downField("entries").as[Vector[io.circe.Json]].left.map(_.getMessage)
        } yield arr
        parsed match {
          case Left(err) =>
            println(s"[arbiter] failed to read $path: $err")
          case Right(arr) =>
            entries = arr.flatMap { e =>
              val c = e.hcursor
              c.get[Int]("cell").toOption.map { cell =>
                cell -> Arbiter.RegistryEntry(
                  cell          = cell,
                  rule          = c.get[String]("rule").getOrElse("PRECEDENCE"),
                  withinRank    = c.get[String]("withinRank").toOption,
                  providerRanks = c.downField("providerRanks").as[Map[String, Int]].getOrElse(Map.empty)
                )
              }
            }.toMap
            loadedFrom = Some(path)
            println(s"[arbiter] loaded ${entries.size} contended cell declaration(s) from $path")
        }
    }
  }
}
