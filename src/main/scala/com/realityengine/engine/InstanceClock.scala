package com.realityengine.engine

import com.realityengine.models.MintedIds

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}

/**
 * The instance's Lamport clock (RealityEngine_CI#296): {instance, lamport, step}.
 *
 * A UUID belongs to an *instance* -- cpp-1, lsp-2 -- never to an engine type or
 * an image, and no two instances of any engine type may share one. The instance
 * registry allocates it and passes it as INSTANCE_UUID; an instance launched
 * without one mints a version-7 UUID at boot, which is unique by construction.
 *
 * `lamport` ticks once per committed step and is never reset, so
 * (instance, lamport) names one step uniquely. For an allocated instance that
 * holds across restarts too: INSTANCE_CLOCK_DIR (default ~/.reality-engine/clock/)
 * keeps <uuid>.lamport, a high-water mark reserved `Reservation` ticks ahead. A
 * boot resumes from the mark and reserves the next block before issuing any
 * tick, and a tick past the persisted mark is always preceded by the write of the
 * next block, never followed by it. The write is temp + atomic move, so a crash
 * leaves the old mark or the new one.
 *
 * The instance also holds an exclusive lock on <uuid>.lock for its whole life
 * (FileChannel.tryLock, fcntl-based, so it excludes the C++ and LSP runtimes'
 * lockf too), and a second live process presenting the same UUID refuses to
 * boot. The OS releases it when the process exits; a crash leaves no stale lock.
 * A separate file, because the clock file is replaced by a move and a lock on a
 * replaced inode guards nothing.
 *
 * Not thread-safe on its own: PerceptualSpaceRuntime ticks it under stepLock.
 */
final class InstanceClock private (val instance: String,
                                   file: Option[Path],
                                   private var lock: Option[(FileChannel, FileLock)],
                                   start: Long) {
  private var current  = start
  private var reserved = start

  file.foreach(_ => reserve(start))

  def lamport: Long = current

  /** Advance for a committed step; returns the new value. */
  def tick(): Long = {
    val next = current + 1
    if (file.isDefined && next > reserved) reserve(next) // persist first, then issue
    current = next
    current
  }

  /** Release the instance lock (a restart in tests; the OS does it at exit). */
  def close(): Unit = {
    lock.foreach { case (channel, held) => held.release(); channel.close() }
    lock = None
  }

  private def reserve(through: Long): Unit = {
    val next = through + InstanceClock.Reservation
    InstanceClock.writeReservation(file.get, next)
    reserved = next
  }
}

object InstanceClock {
  val Reservation: Long = 1024L

  private val Canonical = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  def canonicalUuid(s: String): Boolean = s != null && Canonical.matches(s)

  /** A minted, unpersisted clock -- what an instance without an allocation runs. */
  def minted(): InstanceClock = new InstanceClock(MintedIds.uuidV7(), None, None, 0L)

  /** The clock for this boot, from INSTANCE_UUID / INSTANCE_CLOCK_DIR. Throws
    * when an allocated instance cannot keep its clock or another live process
    * holds its UUID: either way ticks could be issued twice. */
  def boot(env: Map[String, String] = sys.env): InstanceClock = {
    val allocated = env.getOrElse("INSTANCE_UUID", "")
    if (!canonicalUuid(allocated)) {
      if (allocated.nonEmpty)
        System.err.println(s"""INSTANCE_UUID "$allocated" is not a canonical UUID; minting one""")
      minted()
    } else {
      val uuid = allocated.toLowerCase
      val dir = env.get("INSTANCE_CLOCK_DIR").filter(_.nonEmpty).map(Paths.get(_))
        .getOrElse(Paths.get(sys.props("user.home"), ".reality-engine", "clock"))
      persisted(uuid, dir.resolve(s"$uuid.lamport"))
    }
  }

  /** An allocated clock kept in `file`. */
  def persisted(uuid: String, file: Path): InstanceClock = {
    val held = lockInstance(uuid, file) // before anything is read or written
    try new InstanceClock(uuid, Some(file), Some(held), readReservation(file))
    catch {
      case e: Throwable =>
        held._2.release(); held._1.close()
        throw e
    }
  }

  /** The high-water mark persisted in `file`; 0 when the instance has never run. */
  def readReservation(file: Path): Long =
    if (!Files.exists(file)) 0L
    else {
      val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim
      if (text.nonEmpty && text.forall(_.isDigit)) text.toLong
      else throw new IllegalStateException(s"""Lamport clock file $file does not hold a whole number: "$text"""")
    }

  private def writeReservation(file: Path, value: Long): Unit = {
    Files.createDirectories(file.getParent)
    val tmp = file.resolveSibling(file.getFileName.toString.stripSuffix(".lamport") + ".lamport-tmp")
    Files.write(tmp, s"$value\n".getBytes(StandardCharsets.UTF_8))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private def lockInstance(uuid: String, file: Path): (FileChannel, FileLock) = {
    val lockFile = file.resolveSibling(file.getFileName.toString.stripSuffix(".lamport") + ".lock")
    Files.createDirectories(lockFile.getParent)
    val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val held =
      try Option(channel.tryLock())
      catch { case _: OverlappingFileLockException => None } // held elsewhere in this JVM
    held match {
      case Some(l) => (channel, l)
      case None =>
        channel.close()
        throw new IllegalStateException(
          s"Instance $uuid is already live: another process holds $lockFile. " +
            "Two instances may not share a UUID (RealityEngine_CI#296).")
    }
  }
}
