package com.realityengine

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import com.realityengine.api.Routes
import com.realityengine.engine.{PerceptualSpaceSimulator, RealityEngine}
import com.realityengine.logging.{AuditConfig, AuditLogger}
import com.realityengine.services.VectorStore

import java.io.{File, FileInputStream}
import java.security.{KeyStore, SecureRandom}
import java.security.cert.CertificateFactory
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

object Main extends App {
  implicit val system: ActorSystem    = ActorSystem("reality-engine")
  implicit val ec: ExecutionContext   = system.dispatcher

  val port    = sys.env.get("REALITY_ENGINE_PORT").orElse(sys.env.get("PORT")).getOrElse("5001").toIntOption.getOrElse(5001)
  val host    = sys.env.getOrElse("HOST", "0.0.0.0")

  val auditCfg = AuditConfig.fromEnv("reality-engine")

  println("Starting Reality Engine (Scala/Akka)...")
  AuditLogger.logEvent(auditCfg, "startup", Map(
    "audit_enabled" -> io.circe.Json.fromBoolean(auditCfg.enabled),
    "audit_level"   -> io.circe.Json.fromInt(auditCfg.level),
  ))

  // ── TLS setup ─────────────────────────────────────────────────────────────
  // When KEYSTORE_PATH and CA_CERT_PATH are set, build a custom SSLContext
  // that (a) presents our cert to inbound connections and (b) trusts our CA
  // for all outgoing HTTPS calls (Qdrant REST API, etc.).
  val keystorePath     = sys.env.getOrElse("KEYSTORE_PATH", "")
  val keystorePassword = sys.env.getOrElse("KEYSTORE_PASSWORD", "").toCharArray
  val caCertPath       = sys.env.getOrElse("CA_CERT_PATH", "")

  val tlsEnabled = keystorePath.nonEmpty && new File(keystorePath).exists() &&
                   caCertPath.nonEmpty   && new File(caCertPath).exists()

  val sslContext: Option[SSLContext] =
    if (tlsEnabled) Some(buildSslContext(keystorePath, keystorePassword, caCertPath))
    else None

  // Set as JVM-wide default so the Akka HTTP client (AkkaHttpBackend) trusts
  // our CA for all outgoing HTTPS calls (Qdrant REST API, etc.).
  sslContext.foreach(SSLContext.setDefault)

  // ── Engine bootstrap ──────────────────────────────────────────────────────

  val vectorStore  = new VectorStore()
  val engine       = new RealityEngine(vectorStore)
  val simulator    = new PerceptualSpaceSimulator(sys.env.getOrElse("VECTOR_DIMENSION", "7680").toIntOption.getOrElse(7680))

  simulator.setOnStepComplete { (_, spaceVector) =>
    engine.perceptionEngine.getPerceptualSpace.setPerceptualVector(spaceVector)
  }
  // Share the engine's coverage registry so /api/perceive transitions
  // route through to /api/metrics without a second instance drifting.
  simulator.setCoverageRegistry(engine.coverage)

  val startup = for {
    _ <- vectorStore.initialize()
    _ <- engine.initialize()
  } yield ()

  startup.onComplete {
    case Failure(e) =>
      println(s"Failed to initialize: ${e.getMessage}")
      system.terminate()
    case Success(_) =>
      println("✓ Vector store initialized")
      println("✓ Reality Engine initialized")

      val routes   = new Routes(engine, simulator, auditCfg)
      routes.loadDefaultMachines()

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
          println(s"Failed to bind to $host:$port: ${e.getMessage}")
          system.terminate()
        case Success(b) =>
          val scheme = if (tlsEnabled) "https" else "http"
          println(s"\n✅ Reality Engine running on $scheme://$host:$port")
          println(s"🗄️  Qdrant URL: ${sys.env.getOrElse("QDRANT_URL", "http://localhost:4333")}")

          sys.addShutdownHook {
            println("\nShutting down gracefully...")
            Await.result(b.unbind(), 10.seconds)
            Await.result(system.terminate(), 10.seconds)
            println("✓ Shutdown complete")
          }
      }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  def buildSslContext(
    keystorePath: String,
    password: Array[Char],
    caCertPath: String,
  ): SSLContext = {
    // Key material — server cert + private key from PKCS12
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(new FileInputStream(keystorePath), password)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, password)

    // Trust material — CA cert only; loaded from PEM without needing keytool
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
