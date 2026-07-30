package com.realityengine.services

import java.util.concurrent.ConcurrentLinkedDeque
import scala.jdk.CollectionConverters._

/**
 * SemanticAuditLog — in-memory ring buffer of re:SequenceObservation records
 * (RealityEngine_Machines docs/SEMANTIC_AUDIT_CONTRACT.md, milestone M5).
 *
 * Machines record one observation per sequence step match during real
 * processing (what-if evaluations are excluded); the Routes layer joins the
 * corpus semantics manifest to attach IRIs at read time and serves the
 * buffer via GET /api/audit/semantics.
 */
object SemanticAuditLog {
  final case class Observation(
    at:              Long,
    machineId:       String,
    machineName:     String,
    sequenceId:      String,
    stepId:          String,
    completed:       Boolean,
    determinationId: Option[String],
    actionCode:      Option[String],
    ragStatus:       Option[String]
  )

  val Capacity = 1000

  private val buffer = new ConcurrentLinkedDeque[Observation]()

  def record(observation: Observation): Unit = {
    buffer.addLast(observation)
    while (buffer.size() > Capacity) buffer.pollFirst()
  }

  /** Oldest-to-newest, at most `limit` most recent observations. */
  def recent(limit: Int): List[Observation] = {
    val bounded = math.max(0, math.min(limit, Capacity))
    buffer.asScala.toList.takeRight(bounded)
  }

  def size: Int = buffer.size()

  def clear(): Unit = buffer.clear()
}
