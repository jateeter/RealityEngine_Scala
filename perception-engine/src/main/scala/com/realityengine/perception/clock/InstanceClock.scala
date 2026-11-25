package com.realityengine.perception.clock

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}

/**
 * A participant's clock (RealityEngine_CI#296, #600; SURFACE_SPEC.md "The
 * instance clock"): {instance, lamport, commit, step}.
 *
 * The Scala PE's copy (its sbt build is separate from the RE's, so it keeps its
 * own clock, RealityEngine_Scala#195); the two must not drift.
 *
 * A UUID belongs to a *participant* -- an instance's RE (scala-1) or its PE --
 * never to an engine type or an image, and no two participants may share one.
 * CI allocates it (INSTANCE_UUID for an RE, PE_INSTANCE_UUID for a PE); a
 * participant launched without one mints a version-7 UUID at boot.
 *
 *  - `lamport` is the full Lamport clock: it ticks on every committed step,
 *    every send and every receive, and a receive is max(local, received) + 1.
 *  - `commit` counts committed steps: what `lamport` counted before #600.
 * Neither ever resets, and lamport >= commit always holds. (`step` is the
 * caller's: it restarts on reset.)
 *
 * For an allocated participant both hold across restarts. INSTANCE_CLOCK_DIR
 * (default ~/.reality-engine/clock/) keeps two high-water marks, <uuid>.lamport
 * and <uuid>.commit, each reserved `Reservation` ticks ahead. A boot resumes
 * from the marks and reserves the next blocks before issuing any tick; a tick
 * past a persisted mark -- a receive that jumps past it included -- is always
 * preceded by the write of the next mark, never followed by it. Writes are
 * temp + atomic move. A directory written before #600 holds only
 * <uuid>.lamport, which counted committed steps; the first boot under this rule
 * seeds both marks from it.
 *
 * The participant holds an exclusive lock on <uuid>.lock for its whole life
 * (FileChannel.tryLock, fcntl-based, so it excludes the C++ and LSP runtimes'
 * lockf too), and a second live process presenting the same UUID refuses to
 * boot.
 *
 * Thread-safe: receives and sends arrive on every request thread.
 */
final class InstanceClock private (val instance: String,
                                   lamportFile: Option[Path],
                                   private var lock: Option[(FileChannel, FileLock)],
                                   startLamport: Long,
                                   startCommit: Long) {
  private val commitFile      = lamportFile.map(InstanceClock.commitFileFor)
  private var currentLamport  = math.max(startLamport, startCommit)
  private var currentCommit   = startCommit
  private var lamportReserved = currentLamport
  private var commitReserved  = currentCommit

  commitFile.foreach(_ => reserveCommit(currentCommit))
  lamportFile.foreach(_ => reserveLamport(currentLamport))

  def lamport: Long = synchronized(currentLamport)
  def commit: Long  = synchronized(currentCommit)

  /** A committed step: ticks commit and lamport together; returns (lamport, commit). */
  def commitStep(): (Long, Long) = synchronized {
    val nextCommit  = currentCommit + 1
    val nextLamport = currentLamport + 1
    // Persist first, then issue: a mark is never behind what was handed out.
    if (commitFile.isDefined && nextCommit > commitReserved) reserveCommit(nextCommit)
    if (lamportFile.isDefined && nextLamport > lamportReserved) reserveLamport(nextLamport)
    currentCommit = nextCommit
    currentLamport = nextLamport
    (currentLamport, currentCommit)
  }

  /** A send -- a response or an outbound call: ticks lamport; returns it. */
  def send(): Long = synchronized(advance(currentLamport + 1))

  /** A receive, applied before the request is handled: max(local, received) + 1,
    * or local + 1 when no header was presented; returns the new lamport. */
  def receive(received: Option[Long]): Long =
    synchronized(advance(math.max(currentLamport, received.getOrElse(0L)) + 1))

  /** The RE-Clock header value for `lamport`. */
  def header(lamport: Long): String = s"instance=$instance; lamport=$lamport"

  /** Release the instance lock (a restart in tests; the OS does it at exit). */
  def close(): Unit = synchronized {
    lock.foreach { case (channel, held) => held.release(); channel.close() }
    lock = None
  }

  // A jump past the reserved mark is made durable before it is used: one jump
  // can exceed the reservation, and adopting it first could reissue a tick
  // after a crash. Caller holds the monitor.
  private def advance(next: Long): Long = {
    if (lamportFile.isDefined && next > lamportReserved) reserveLamport(next)
    currentLamport = next
    currentLamport
  }

  private def reserveLamport(through: Long): Unit = {
    val next = through + InstanceClock.Reservation
    InstanceClock.writeReservation(lamportFile.get, next)
    lamportReserved = next
  }

  private def reserveCommit(through: Long): Unit = {
    val next = through + InstanceClock.Reservation
    InstanceClock.writeReservation(commitFile.get, next)
    commitReserved = next
  }
}

object InstanceClock {
  val Reservation: Long = 1024L
  /** The largest lamport an RE-Clock header may carry: 2^53 - 1. */
  val MaxLamport: Long = 9007199254740991L

  private val Canonical = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r
  private val Lower     = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r
  private val Number    = "^(0|[1-9][0-9]{0,15})$".r

  def canonicalUuid(s: String): Boolean = s != null && Canonical.matches(s)

  /** An RE-Clock value, parsed: the sender's lamport, or None when malformed
    * (SURFACE_SPEC "The header": `instance=<lower-case uuid>; lamport=<n>`,
    * both once and in that order, n in [0, 2^53 - 1] with no sign or leading
    * zeros). */
  def parseHeader(value: String): Option[Long] = {
    val parts = value.split(";", -1)
    if (parts.length != 2) None
    else {
      val first  = parts(0).trim
      val second = parts(1).trim
      if (!first.startsWith("instance=") || !second.startsWith("lamport=")) None
      else {
        val uuid   = first.stripPrefix("instance=")
        val digits = second.stripPrefix("lamport=")
        if (!Lower.matches(uuid) || !Number.matches(digits)) None
        else Some(digits.toLong).filter(_ <= MaxLamport)
      }
    }
  }

  /** A minted, unpersisted clock -- what a participant without an allocation runs. */
  def minted(): InstanceClock = new InstanceClock(uuidV7(), None, None, 0L, 0L)

  /** A version-7 UUID: 48 bits of Unix milliseconds, then random bits. */
  def uuidV7(): String = {
    val rnd  = new java.security.SecureRandom()
    val ms   = System.currentTimeMillis()
    val high = (ms << 16) | 0x7000L | (rnd.nextInt(0x1000).toLong)
    val low  = (rnd.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L
    new java.util.UUID(high, low).toString
  }

  /** The participant's clock for this boot, from `uuidVariable` (INSTANCE_UUID
    * for an RE, PE_INSTANCE_UUID for a PE) and INSTANCE_CLOCK_DIR. Throws when
    * an allocated participant cannot keep its clock or another live process
    * holds its UUID: either way ticks could be issued twice. */
  def boot(env: Map[String, String] = sys.env, uuidVariable: String = "INSTANCE_UUID"): InstanceClock = {
    val allocated = env.getOrElse(uuidVariable, "")
    if (!canonicalUuid(allocated)) {
      if (allocated.nonEmpty)
        System.err.println(s"""$uuidVariable "$allocated" is not a canonical UUID; minting one""")
      minted()
    } else {
      val uuid = allocated.toLowerCase
      val dir = env.get("INSTANCE_CLOCK_DIR").filter(_.nonEmpty).map(Paths.get(_))
        .getOrElse(Paths.get(sys.props("user.home"), ".reality-engine", "clock"))
      persisted(uuid, dir.resolve(s"$uuid.lamport"))
    }
  }

  /** An allocated clock whose lamport mark is `file` (<uuid>.lamport) and
    * commit mark <uuid>.commit beside it. */
  def persisted(uuid: String, file: Path): InstanceClock = {
    val held = lockInstance(uuid, file) // before anything is read or written
    try {
      val commitFile = commitFileFor(file)
      val migrating  = !Files.exists(commitFile)
      val lamport    = readReservation(file)
      // A directory from before #600 holds only <uuid>.lamport, which counted
      // committed steps: it seeds both marks.
      val commit     = if (migrating) lamport else readReservation(commitFile)
      new InstanceClock(uuid, Some(file), Some(held), lamport, commit)
    } catch {
      case e: Throwable =>
        held._2.release(); held._1.close()
        throw e
    }
  }

  private def commitFileFor(file: Path): Path =
    file.resolveSibling(file.getFileName.toString.stripSuffix(".lamport") + ".commit")

  /** The high-water mark persisted in `file`; 0 when the participant has never run. */
  def readReservation(file: Path): Long =
    if (!Files.exists(file)) 0L
    else {
      val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim
      if (text.nonEmpty && text.forall(_.isDigit)) text.toLong
      else throw new IllegalStateException(s"""Lamport clock file $file does not hold a whole number: "$text"""")
    }

  private def writeReservation(file: Path, value: Long): Unit = {
    Files.createDirectories(file.getParent)
    val tmp = file.resolveSibling(file.getFileName.toString + ".tmp")
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
            "Two participants may not share a UUID (RealityEngine_CI#296).")
    }
  }
}
