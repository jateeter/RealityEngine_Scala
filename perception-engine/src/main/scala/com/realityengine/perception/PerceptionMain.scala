package com.realityengine.perception

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import com.realityengine.perception.api.{PerceptionRoutes, WsBroadcastActor}
import com.realityengine.perception.logging.{AuditConfig, AuditLogger}
import com.realityengine.perception.engine.PerceptionEngine
import com.realityengine.perception.models.{Region, TestSourceConfig}
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

  val port               = sys.env.getOrElse("PORT", "3004").toIntOption.getOrElse(3004)
  val host               = sys.env.getOrElse("HOST", "0.0.0.0")
  val realityEngineUrl   = sys.env.getOrElse("REALITY_ENGINE_URL",   "https://localhost:3000")
  val dataPath           = sys.env.getOrElse("DATA_PATH", "./data")
  val isFresh            = args.contains("--fresh") || sys.env.getOrElse("FRESH_START", "false") == "true"

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

  val vectorDimension = sys.env.getOrElse("VECTOR_DIMENSION", "768").toIntOption.getOrElse(768)
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
      seedSources(realityEngineUrl, engine, store, mergeOnly = !isFresh)

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
  def seedSources(realityEngineUrl: String, engine: PerceptionEngine, store: SourceStore, mergeOnly: Boolean): Unit = {
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

        var seeded = 0; var skipped = 0
        machines.foreach { m =>
          val mc          = m.hcursor
          val machineId   = mc.get[String]("id").getOrElse("")
          val machineName = mc.get[String]("name").getOrElse("")
          val offsetOpt   = mc.downField("perceptualMapping").downField("input").get[Int]("offset").toOption
          val lengthOpt   = mc.downField("perceptualMapping").downField("input").get[Int]("length").toOption
          val inputSeqs   = mc.downField("metadata").downField("inputSequences")
            .as[Vector[io.circe.Json]].getOrElse(Vector.empty)
          (offsetOpt, lengthOpt) match {
            case (Some(off), Some(len)) =>
              if (mergeOnly && seededMachineIds.contains(machineId)) {
                skipped += 1
              } else {
                inputSeqs.foreach { sj =>
                  val seqName = sj.hcursor.get[String]("name").getOrElse("")
                  val vectors = sj.hcursor.downField("vectors")
                    .as[Vector[Vector[Double]]].getOrElse(Vector.empty)
                  if (seqName.nonEmpty && vectors.nonEmpty) {
                    engine.addSource(TestSourceConfig(
                      id           = java.util.UUID.randomUUID().toString,
                      name         = s"$machineName — $seqName",
                      region       = Region(off, len),
                      active       = true,
                      machineId    = machineId,
                      machineName  = machineName,
                      sequenceName = seqName,
                      inputs       = vectors,
                      loop         = true,
                    ))
                    seeded += 1
                  }
                }
              }
            case _ =>
              if (machineId.nonEmpty)
                println(s"[Seed] Skipping $machineName — no perceptualMapping.input found")
          }
        }
        val mode = if (mergeOnly) "merge" else "fresh"
        println(s"[Seed] ($mode) Added $seeded source(s), skipped $skipped machines with existing sources, from ${machines.size} machine(s)")
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
