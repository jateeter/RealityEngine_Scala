package com.realityengine.services

import com.realityengine.models.{Machine, MachineTransitionResult}
import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicLong

// Tab-joined hash keys mirror the AI/CPP/LSP wire format so the
// /api/metrics emitter can split them back into machine / sequence /
// vector label tuples without nested-map allocation on the hot path.
class CesCoverageRegistry {
  private val matched         = TrieMap.empty[String, AtomicLong]
  private val activated       = TrieMap.empty[String, AtomicLong]
  private val outputs         = TrieMap.empty[String, AtomicLong]
  private val steps           = TrieMap.empty[String, AtomicLong]
  private val pagingDecisions = TrieMap.empty[String, AtomicLong]
  private val deprecatedFires = TrieMap.empty[String, AtomicLong]

  private val startedAtMs = System.currentTimeMillis()

  private def bump(t: TrieMap[String, AtomicLong], k: String, by: Long = 1L): Unit = {
    val cell = t.get(k) match {
      case Some(c) => c
      case None    => t.putIfAbsent(k, new AtomicLong(0)).getOrElse(t(k))
    }
    cell.addAndGet(by)
  }

  def record(machine: Machine, result: MachineTransitionResult): Unit = {
    val base = machine.id + "\t" + machine.name
    bump(steps, base)
    result.sequenceResults.foreach { case (sequenceId, sr) =>
      sr.matchedVectors.foreach   (v => bump(matched,   base + "\t" + sequenceId + "\t" + v))
      sr.activatedVectors.foreach (v => bump(activated, base + "\t" + sequenceId + "\t" + v))
      if (result.arbiterMetadata.shouldOutput && sr.assertedOutputs.nonEmpty)
        bump(outputs, base + "\t" + sequenceId, sr.assertedOutputs.size.toLong)
    }
  }

  def recordPagingDecision(ownerTeam: String, processStatus: String,
                           ragStatusCode: String, machineId: String): Unit =
    bump(pagingDecisions, ownerTeam + "\t" + processStatus + "\t" + ragStatusCode + "\t" + machineId)

  def recordDeprecatedFire(machineId: String, machineName: String,
                           sequenceId: String, replacedBy: String): Unit =
    bump(deprecatedFires, machineId + "\t" + machineName + "\t" + sequenceId + "\t" + replacedBy)

  // Read-side snapshots — values are captured atomically per cell, the
  // overall map is point-in-time-consistent enough for a Prom scrape.
  private def snap(t: TrieMap[String, AtomicLong]): Map[String, Long] =
    t.iterator.map { case (k, v) => k -> v.get }.toMap

  def matchedSnap:         Map[String, Long] = snap(matched)
  def activatedSnap:       Map[String, Long] = snap(activated)
  def outputsSnap:         Map[String, Long] = snap(outputs)
  def stepsSnap:           Map[String, Long] = snap(steps)
  def pagingDecisionsSnap: Map[String, Long] = snap(pagingDecisions)
  def deprecatedFiresSnap: Map[String, Long] = snap(deprecatedFires)

  def uptimeSeconds: Double = (System.currentTimeMillis() - startedAtMs) / 1000.0
}

object CesCoverageRegistry {
  def splitKey(s: String): Array[String] = s.split('\t')
}
