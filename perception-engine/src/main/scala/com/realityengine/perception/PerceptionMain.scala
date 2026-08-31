package com.realityengine.perception

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import com.realityengine.perception.api.{PerceptionRoutes, WsBroadcastActor}
import com.realityengine.perception.logging.{AuditConfig, AuditLogger}
import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models.TestSourceConfig
import com.realityengine.perception.store.SourceStore
import sttp.client3._

import java.io.{File, FileInputStream}
import java.security.{KeyStore, SecureRandom}
import java.security.cert.CertificateFactory
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object PerceptionMain extends App {
  implicit val system: ActorSystem  = ActorSystem("perception-engine")
  implicit val mat: Materializer    = Materializer(system)
  implicit val ec: ExecutionContext  = system.dispatcher

  val port               = sys.env.get("PERCEPTION_ENGINE_PORT").orElse(sys.env.get("PORT")).getOrElse("5000").toIntOption.getOrElse(5000)
  val host               = sys.env.getOrElse("HOST", "0.0.0.0")
  val realityEngineUrl   = sys.env
    .get("REALITY_ENGINE_URL")
    .orElse(sys.env.get("REALITY_ENGINE_PORT").map(port => s"http://localhost:$port"))
    .getOrElse("http://localhost:5001")
  val dataPath           = sys.env.getOrElse("DATA_PATH", "./data")
  val isFresh            = args.contains("--fresh") || sys.env.getOrElse("FRESH_START", "false") == "true"
  // Drop persisted sources whose machine is not in the corpus the Reality
  // Engine actually loaded. Off by default because it deletes state; on, it is
  // what makes the corpus gate below pass rather than merely report.
  val pruneCorpus        = args.contains("--prune-corpus") ||
                           sys.env.getOrElse("PRUNE_CORPUS", "false") == "true"
  // Override: skip machines that already have a persisted source instead of
  // reloading them. Off by default — a redefined machine must replace the
  // source describing the old one, and nothing in a persisted source says
  // whether its machine still has the same CESs, interconnections or regions.
  val sourceMerge        = sys.env.getOrElse("PE_SOURCE_MERGE", "false") == "true"
  // Activate every machine source on load. Off by default: turning it on made
  // all three runtimes replay their input sequences on every push and the
  // divergence went three-way (#36). Kept as a switch for experiments.
  val activateOnLoad     = sys.env.getOrElse("PE_SOURCE_ACTIVATE_ON_LOAD", "false") == "true"
  // PE_SOURCE_BOOTSTRAP — whether the corpus test sources are interned at boot.
  // Mirrors startUniverse.sh --pe-source-bootstrap=auto|off.
  //
  // On by default: interning a machine's inputSequences as a test source over
  // its own region is part of ingesting the machine, and those sources are what
  // compose the ISRE seed queue the engines are driven with. This runtime
  // already behaved that way — it seeded unconditionally — but ignored the flag
  // entirely, so `--pe-source-bootstrap=off` silently did nothing here while
  // C++ and LSP honoured it, and the three started any comparison from
  // different membership (#63).
  val sourceBootstrap    = !Set("off", "OFF", "0", "false", "FALSE", "no", "NO")
                             .contains(sys.env.getOrElse("PE_SOURCE_BOOTSTRAP", "auto"))

  val auditCfg = AuditConfig.fromEnv("perception-engine")

  println("Starting Perception Engine (Scala/Akka)...")
  AuditLogger.logEvent(auditCfg, "startup", Map(
    "audit_enabled" -> io.circe.Json.fromBoolean(auditCfg.enabled),
    "audit_level"   -> io.circe.Json.fromInt(auditCfg.level),
  ))

  // ── TLS setup ─────────────────────────────────────────────────────────────
  // When KEYSTORE_PATH and CA_CERT_PATH are set, build a custom SSLContext
  // that (a) presents our cert to inbound connections and (b) trusts our CA
  // for all outgoing HTTPS calls (Reality Engine, visualizer notify, etc.).
  val keystorePath     = sys.env.getOrElse("KEYSTORE_PATH", "")
  val keystorePassword = sys.env.getOrElse("KEYSTORE_PASSWORD", "").toCharArray
  val caCertPath       = sys.env.getOrElse("CA_CERT_PATH", "")

  val tlsEnabled = keystorePath.nonEmpty && new File(keystorePath).exists() &&
                   caCertPath.nonEmpty   && new File(caCertPath).exists()

  val sslContext: Option[SSLContext] =
    if (tlsEnabled) Some(buildSslContext(keystorePath, keystorePassword, caCertPath))
    else None

  // Set as JVM-wide default so sttp's HttpURLConnectionBackend trusts our CA
  // for all outgoing HTTPS calls (Reality Engine, etc.).
  sslContext.foreach(SSLContext.setDefault)

  // ── Engine bootstrap ──────────────────────────────────────────────────────

  val vectorDimension = sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680)
  val store   = new SourceStore(dataPath)
  val engine  = new PerceptionEngine(vectorDimension)

  if (!isFresh) {
    val loaded = store.load()
    loaded.foreach(engine.restoreSource)
    println(s"[SourceStore] Loaded ${loaded.size} source(s) from $dataPath — will supplement any missing machine sources on start")
  } else {
    println("[SourceStore] FRESH_START: skipping persisted sources — will seed all machine sources after server starts")
  }

  val broadcastActor = system.actorOf(WsBroadcastActor.props(), "ws-broadcast")

  val routes = new PerceptionRoutes(
    engine           = engine,
    store            = store,
    broadcastActor   = broadcastActor,
    realityEngineUrl = realityEngineUrl,
    auditCfg         = auditCfg,
    activateOnLoad   = activateOnLoad,
  )

  val serverAt = Http().newServerAt(host, port)
  val binding  = sslContext match {
    case Some(ctx) =>
      println(s"✓ TLS enabled (keystore: $keystorePath)")
      serverAt.enableHttps(ConnectionContext.httpsServer(ctx)).bind(routes.routes)
    case None =>
      println("  TLS not configured — binding plain HTTP")
      serverAt.bind(routes.routes)
  }

  binding.onComplete {
    case Failure(e) =>
      println(s"Failed to bind to $host:$port — ${e.getMessage}")
      system.terminate()
    case Success(b) =>
      val scheme = if (tlsEnabled) "https" else "http"
      println(s"\n✅ Perception Engine running on $scheme://$host:$port")
      println(s"   Reality Engine : $realityEngineUrl")
      // Reload every corpus machine, whether or not a persisted source claims
      // to describe it. PE_SOURCE_MERGE=true opts back into skipping.
      if (sourceBootstrap)
        seedSources(realityEngineUrl, engine, store, routes, mergeOnly = sourceMerge,
                    pruneCorpus = pruneCorpus, activateOnLoad = activateOnLoad)
      else
        println("[SourceStore] PE_SOURCE_BOOTSTRAP=off — not interning corpus test sources at boot; " +
                "POST /api/sources/bootstrap-from-machines still available")

      sys.addShutdownHook {
        println("\nShutting down gracefully...")
        Await.result(b.unbind(), 10.seconds)
        Await.result(system.terminate(), 10.seconds)
        println("✓ Shutdown complete")
      }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  // mergeOnly=true: only add sources for machines that have no existing test sources
  //                 (preserves user-edited on/off state for known machines)
  // mergeOnly=false: replace all test sources wholesale (FRESH_START)
  def seedSources(realityEngineUrl: String, engine: PerceptionEngine, store: SourceStore,
                  routes: PerceptionRoutes, mergeOnly: Boolean,
                  pruneCorpus: Boolean = false,
                  activateOnLoad: Boolean = false): Unit = {
    Future {
      val backend = HttpURLConnectionBackend()
      var machinesJson: io.circe.Json = io.circe.Json.Null
      var success = false
      var attempt = 0
      while (attempt < 12 && !success) {
        attempt += 1
        try {
          val resp = basicRequest.get(uri"$realityEngineUrl/api/machines").response(asString).send(backend)
          if (resp.isSuccess) {
            machinesJson = resp.body.toOption
              .flatMap(b => io.circe.parser.parse(b).toOption)
              .getOrElse(io.circe.Json.Null)
            success = true
          } else {
            println(s"[Seed] Attempt $attempt/12: RE returned ${resp.code}. Retrying in 2s...")
            Thread.sleep(2000)
          }
        } catch { case e: Exception =>
          println(s"[Seed] Attempt $attempt/12 failed: ${e.getMessage}. Retrying in 2s...")
          Thread.sleep(2000)
        }
      }
      if (!success) {
        println("[Seed] Could not reach Reality Engine after 12 attempts. Seeding skipped.")
      } else {
        val machines = machinesJson.hcursor
          .downField("machines")
          .as[Vector[io.circe.Json]]
          .getOrElse(Vector.empty)

        // Build set of machine IDs that already have test sources in the engine
        val seededMachineIds: Set[String] =
          if (mergeOnly)
            engine.getSources.collect { case t: TestSourceConfig => t.machineId }.toSet
          else
            Set.empty

        // The merge mapping is the PE's alone, so the PE holds the machine
        // facts it resolves against.  Same list that seeds the sources.
        routes.setMachineCorpus(MachineCorpus.build(machines))

        var seeded = 0; var skipped = 0
        machines.foreach { m =>
          val machineId   = m.hcursor.get[String]("id").getOrElse("")
          val machineName = m.hcursor.get[String]("name").getOrElse("")
          // A machine present in the corpus is (re)loaded, always. The previous
          // behaviour skipped any machine that already had a source, so a
          // persisted source outlived the machine it described: nothing
          // guarantees the redefined machine has the same CESs, the same
          // interconnections, or the same regions — it may be a full
          // replacement. Keeping the old source in that case is silently
          // wrong, and it is not detectable from the source itself.
          //
          // addSource replaces by id, and the id is derived from the machine
          // (test-<machineId>), so a reload overwrites in place rather than
          // duplicating. PE_SOURCE_MERGE=true restores the old skip for a
          // caller that wants it.
          if (mergeOnly && seededMachineIds.contains(machineId)) {
            skipped += 1
          } else {
            // One source per machine, activation decided in one place — see
            // MachineCorpus.testSourceFor.  This used to build sources inline,
            // one per input sequence and unconditionally active, which meant
            // every corpus scenario played itself forward into the shared
            // Reality Event on every push and machines reached outcomes the
            // input never asked for (#36).
            MachineCorpus.testSourceFor(m, activateOnLoad) match {
              case Some(src) =>
                engine.addSource(src)
                seeded += 1
              case None =>
                if (machineId.nonEmpty && machineName.nonEmpty) skipped += 1
            }
          }
        }
        val mode = if (mergeOnly) "merge" else "fresh"
        println(s"[Seed] ($mode) Added $seeded source(s), skipped $skipped machines with existing sources, from ${machines.size} machine(s)")

        // ── Corpus gate ───────────────────────────────────────────────────────
        //
        // The PE's machine-backed sources must describe the same corpus the
        // Reality Engine loaded, and nothing was checking that. Persisted
        // sources are restored on start and then *supplemented* from the RE —
        // never reconciled — so a source whose machine left the corpus stays
        // forever. A full-corpus run left 1,361 sources on disk, and every
        // 12-machine deployment afterwards restored all of them and pushed
        // 1,180 active sources into the shared Reality Event on every push,
        // while C++ and LSP pushed none.
        //
        // That is not a small drift. It put this runtime one CES transition
        // ahead of the other two from the first push and was reported as a
        // Scala semantics defect (RealityEngine_Scala#43) after first being
        // reported against LSP (RealityEngine_LSP#38) — neither was a runtime
        // fault. A before/after count is cheap and would have named it
        // immediately.
        val corpusIds: Set[String] =
          machines.flatMap(_.hcursor.get[String]("id").toOption).toSet
        def machineBackedIds: Set[String] =
          engine.getSources.collect { case t: TestSourceConfig => t.machineId }.toSet

        val before      = machineBackedIds
        val beforeTotal = engine.getSources.size
        val strays      = before -- corpusIds

        // Two kinds of leftover, and pruning only the first is what left 17
        // sources behind on the first attempt at this.
        //
        //   stray   — a test source whose machine is not in the loaded corpus
        //   dangling— a source with no machine behind it at all: the sensor
        //             sources localAIStack, OpenClaw and the bridges register.
        //             They persist and are restored on every start, active,
        //             even when the stack that owns them is not running.
        //
        // Dropping the dangling ones is safe because their owners re-register
        // on their own startup — localAIStack's register_sensors() posts them
        // every time the bridge comes up — so a provider that is running gets
        // its sensors back, and one that is not should not be contributing to
        // the Reality Event in the first place.
        val prunedStray =
          if (pruneCorpus && strays.nonEmpty) {
            val ids = engine.getSources.collect {
              case t: TestSourceConfig if strays.contains(t.machineId) => t.id
            }
            ids.foreach(engine.removeSource)
            ids.size
          } else 0

        val prunedDangling =
          if (pruneCorpus) {
            val ids = engine.getSources.collect {
              case s if !s.isInstanceOf[TestSourceConfig] => s.id
            }
            ids.foreach(engine.removeSource)
            ids.size
          } else 0

        val after      = machineBackedIds
        val afterTotal = engine.getSources.size
        val dangling   = afterTotal - after.size
        println(s"[Corpus] Reality Engine loaded ${corpusIds.size} machine(s); " +
                s"PE sources before=$beforeTotal after=$afterTotal " +
                s"(machine-backed ${before.size} -> ${after.size})" +
                (if (prunedStray + prunedDangling > 0)
                   s" [pruned $prunedStray stray, $prunedDangling dangling]"
                 else ""))
        if (dangling > 0)
          println(s"[Corpus] $dangling source(s) have no machine behind them (provider-registered); " +
                  s"PRUNE_CORPUS=true drops them and lets their owners re-register")

        val remaining = after -- corpusIds
        if (remaining.nonEmpty) {
          // Loud, and not fatal: refusing to start would take out a running
          // universe over state the operator can clear with one flag. The
          // regression lane asserts on this line rather than on a health code.
          println(s"[Corpus] GATE FAILED: ${remaining.size} PE source(s) reference machines " +
                  s"outside the loaded corpus — the PE is pushing signal the other runtimes " +
                  s"never see. Restart with PRUNE_CORPUS=true (or --prune-corpus) to drop them, " +
                  s"or FRESH_START=true to ignore the persisted store entirely.")
          println(s"[Corpus] first stray machineIds: ${remaining.toVector.sorted.take(5).mkString(", ")}")
        } else if (after.size != corpusIds.size) {
          val missing = corpusIds -- after
          println(s"[Corpus] GATE WARNING: ${missing.size} loaded machine(s) have no PE source; " +
                  s"first: ${missing.toVector.sorted.take(5).mkString(", ")}")
        } else {
          println(s"[Corpus] GATE OK: PE sources and Reality Engine corpus agree at ${after.size} machine(s)")
        }

        store.save(engine.getSources)
      }
    }(ec)
    ()
  }

  def buildSslContext(
    keystorePath: String,
    password: Array[Char],
    caCertPath: String,
  ): SSLContext = {
    // Key material — server cert + private key from PKCS12 keystore
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(new FileInputStream(keystorePath), password)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, password)

    // Trust material — CA cert loaded from PEM; no keytool required
    val caCert = CertificateFactory.getInstance("X.509")
      .generateCertificate(new FileInputStream(caCertPath))
    val ts = KeyStore.getInstance(KeyStore.getDefaultType)
    ts.load(null, null)
    ts.setCertificateEntry("ca", caCert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ts)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom())
    ctx
  }
}
