package com.realityengine.engine

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/**
 * Sharding for the arbiter's resolve phase.
 *
 * Cells never interact: gather is a map, resolve is a per-cell reduction over an
 * independent contributor set, and commit is a scatter over disjoint cells. So
 * the work parallelises by cell range with no shared mutable state and no
 * locking on the commit — if a mutex seems necessary there, the partitioning is
 * wrong.
 *
 * Correctness does not depend on the shard count. Every admissible rule is a
 * commutative monoid (ARBITER_CONTRACT.md 4.1), so any partitioning yields the
 * same values; ARBITER_SHARDS exists to tune throughput, and acceptance
 * criterion 3 requires that varying it changes nothing observable.
 */
object ArbiterParallelism {

  val shards: Int =
    sys.env.get("ARBITER_SHARDS").flatMap(_.toIntOption).filter(_ > 0)
      .getOrElse(math.max(1, Runtime.getRuntime.availableProcessors()))

  /** Daemon threads: the arbiter must never hold the JVM open past shutdown. */
  lazy val ec: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(shards, (r: Runnable) => {
      val t = new Thread(r, "re-arbiter")
      t.setDaemon(true)
      t
    })
  )
}
