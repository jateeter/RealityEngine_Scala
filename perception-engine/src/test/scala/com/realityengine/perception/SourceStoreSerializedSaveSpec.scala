package com.realityengine.perception

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import com.realityengine.perception.models._
import com.realityengine.perception.store.SourceStore

/** Saves are atomic and serialised (RealityEngine_CI#518): concurrent source
  * PATCHes each launch one, and they used to race on a shared `.tmp`, failing
  * renames and letting an older snapshot land last.
  */
class SourceStoreSerializedSaveSpec extends AnyFlatSpec with Matchers {

  private def source(n: Int) = TestSourceConfig(
    id               = s"test-$n",
    name             = s"test-$n",
    region           = Region(40, 4),
    active           = true,
    machineId        = "m-1",
    machineName      = "Machine One",
    sequenceName     = "seq",
    inputs           = Vector(Vector(1.0, 1.0, 1.0, 1.0)),
    loop             = true,
    sequenceMetadata = Json.Null,
    testSequence     = Json.Null,
  )

  "SourceStore.save" should "never fail or lose the newest snapshot under concurrent saves" in {
    val dir   = Files.createTempDirectory("sourcestore-spec")
    val store = new SourceStore(dir.toString)
    // The live state the snapshot reads: it grows by one source per save.
    val state = new AtomicInteger(0)
    val pool  = Executors.newFixedThreadPool(16)
    val start = new CountDownLatch(1)
    val errBuf = new ByteArrayOutputStream()
    val savedErr = System.err
    System.setErr(new PrintStream(errBuf))
    try {
      (1 to 200).foreach { _ =>
        pool.submit(new Runnable {
          def run(): Unit = {
            start.await()
            state.incrementAndGet()
            store.save((1 to state.get()).toVector.map(source))
          }
        })
      }
      start.countDown()
      pool.shutdown()
      pool.awaitTermination(60, TimeUnit.SECONDS) shouldBe true
    } finally System.setErr(savedErr)

    errBuf.toString should not include "Failed to save sources"
    // The snapshot is taken inside the lock, so the last write is the newest state.
    store.load().map(_.id).size shouldBe 200
  }
}
