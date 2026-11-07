package com.realityengine.perception

// Lane TLS (RealityEngine_CI#668, RealityEngine_Scala#210): the runtime
// contract (RealityEngine_CI docs/TRANSPORT_SECURITY.md), with the checks
// RealityEngine_CPP's tests/http_tls_tests.cpp and RealityEngine_LSP's
// tests/tls-tests.lisp make. A throwaway PKI from the openssl CLI in a
// temporary directory, an RE-like and a PE-like server on loopback, the
// engine's own client factory, and s_client for the refusals.

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.settings.ServerSettings
import com.realityengine.perception.api.PerceptionRoutes
import com.realityengine.tls.LaneTls
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.net.{ServerSocket, URL}
import java.nio.file.{Files, Path}
import javax.net.ssl.HttpsURLConnection
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.sys.process._

class LaneTlsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  implicit private val system: ActorSystem = ActorSystem("lane-tls-spec")

  private val dir: Path = Files.createTempDirectory("re-scala-tls")
  private def f(name: String) = dir.resolve(name).toString
  private val openssl = Seq("/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/local/opt/openssl@3/bin/openssl")
    .find(p => new File(p).canExecute).getOrElse("openssl")

  private def sh(cmd: String): (Int, String) = {
    val out = new StringBuilder
    val code = Seq("/bin/sh", "-c", cmd).!(ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n')))
    (code, out.toString)
  }
  private def must(cmd: String): Unit = { val (c, o) = sh(cmd); if (c != 0) fail(s"test PKI: $o") }

  // A CA in the profile's shape, one instance leaf (server and client,
  // localhost and 127.0.0.1), a revoked client and a client from another CA.
  private def makePki(): Unit = {
    val d = dir.toString
    Files.writeString(dir.resolve("ca.cnf"),
      s"[ca]\ndefault_ca=d\n[d]\ndir=$d\ndatabase=$$dir/index.txt\ncrlnumber=$$dir/crlnumber\ndefault_md=sha384\ndefault_crl_days=2\n")
    Files.writeString(dir.resolve("index.txt"), "")
    Files.writeString(dir.resolve("crlnumber"), "01\n")
    Files.writeString(dir.resolve("leaf.ext"), "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\nkeyUsage=critical,digitalSignature\n")
    Files.writeString(dir.resolve("client.ext"), "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n")
    for (ca <- Seq("ca", "rogue-ca"))
      must(s"cd $d && $openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-384 -nodes -sha384 -days 2 -subj /CN=$ca " +
        s"-addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign -keyout $ca.key -out $ca.crt")
    for ((name, issuer, ext) <- Seq(("scala-1", "ca", "leaf.ext"), ("revoked", "ca", "client.ext"), ("rogue", "rogue-ca", "client.ext")))
      must(s"cd $d && $openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-384 -nodes -subj /CN=$name -keyout $name.key -out $name.csr && " +
        s"$openssl x509 -req -sha384 -days 2 -in $name.csr -CA $issuer.crt -CAkey $issuer.key -CAcreateserial -extfile $ext -out $name.crt && " +
        s"cat $name.crt $issuer.crt > $name.chain")
    must(s"cd $d && $openssl ca -config ca.cnf -keyfile ca.key -cert ca.crt -revoke revoked.crt && " +
      s"$openssl ca -config ca.cnf -keyfile ca.key -cert ca.crt -gencrl -out ca.crl")
  }

  private def freePort(): Int = { val s = new ServerSocket(0); try s.getLocalPort finally s.close() }

  private var settings: LaneTls.Settings = _
  private var rePort, pePort = 0

  override def beforeAll(): Unit = {
    makePki()
    settings = LaneTls.Settings(f("scala-1.chain"), f("scala-1.key"), f("ca.crt"), Some(f("ca.crl")))
    val ctx = LaneTls.serverContext(settings)
    val health = path("health")(get(complete(HttpEntity(ContentTypes.`application/json`, """{"status":"ok"}"""))))
    rePort = freePort()
    Await.result(Http().newServerAt("127.0.0.1", rePort)
      .enableHttps(ConnectionContext.httpsServer(() => LaneTls.serverEngine(ctx, requireAtHandshake = true)))
      .bind(health), 10.seconds)
    val peRoutes = concat(
      path("api" / "integrations" / "healthkit" / "status")(get(complete(HttpEntity(ContentTypes.`application/json`, """{"device":true}""")))),
      path("service")(get(complete(HttpEntity(ContentTypes.`application/json`, """{"service":true}""")))))
    pePort = freePort()
    val base = ServerSettings(system)
    Await.result(Http().newServerAt("127.0.0.1", pePort)
      .withSettings(base.withParserSettings(base.parserSettings.withIncludeSslSessionAttribute(true)))
      .enableHttps(ConnectionContext.httpsServer(() => LaneTls.serverEngine(ctx, requireAtHandshake = false)))
      .bind(PerceptionRoutes.laneGuard(peRoutes)), 10.seconds)
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    sh(s"rm -rf $dir")
  }

  private def sClient(port: Int, path: String, flags: String): String =
    sh(s"( printf 'GET $path HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n'; sleep 1 ) | " +
      s"perl -e 'alarm shift; exec @ARGV' 5 $openssl s_client -quiet -no_ign_eof -connect 127.0.0.1:$port " +
      s"-servername localhost -CAfile ${f("ca.crt")} $flags")._2
  private def own = s"-cert ${f("scala-1.chain")} -key ${f("scala-1.key")}"
  private def answered(out: String, status: Int) = out.contains(s"HTTP/1.1 $status")

  private def clientGet(url: String): String = {
    val conn = new URL(url).openConnection().asInstanceOf[HttpsURLConnection]
    conn.setSSLSocketFactory(LaneTls.internalSocketFactory(f("ca.crt"), Some(f("ca.crl")), Some(settings)))
    conn.setConnectTimeout(5000); conn.setReadTimeout(5000)
    try scala.io.Source.fromInputStream(conn.getInputStream).mkString finally conn.disconnect()
  }

  "the internal-peer rule" should "match C++ and LSP" in {
    for (h <- Seq("127.0.0.1", "localhost", "engine-cpp-re", "cpp-1.localhost", "host.docker.internal", "10.0.0.5",
                  "172.16.0.1", "172.31.255.1", "192.168.1.20", "169.254.1.1", "::1", "fd00::1", "fe80::1", "printer.local"))
      withClue(h)(LaneTls.isInternalHost(h) shouldBe true)
    for (h <- Seq("api.openai.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "2001:db8::1", ""))
      withClue(h)(LaneTls.isInternalHost(h) shouldBe false)
  }

  "the client" should "reach an IP SAN and a DNS SAN with the lane CA and its own certificate" in {
    clientGet(s"https://127.0.0.1:$rePort/health") shouldBe """{"status":"ok"}"""
    clientGet(s"https://localhost:$rePort/health") shouldBe """{"status":"ok"}"""
  }

  "an RE-like port" should "serve a lane client over TLS 1.3" in {
    answered(sClient(rePort, "/health", own), 200) shouldBe true
  }
  it should "refuse no client certificate, TLS 1.2, a suite outside the profile, another CA and a revoked certificate" in {
    answered(sClient(rePort, "/health", ""), 200) shouldBe false
    answered(sClient(rePort, "/health", s"$own -tls1_2"), 200) shouldBe false
    answered(sClient(rePort, "/health", s"$own -tls1_3 -ciphersuites TLS_AES_128_GCM_SHA256"), 200) shouldBe false
    answered(sClient(rePort, "/health", s"-cert ${f("rogue.chain")} -key ${f("rogue.key")}"), 200) shouldBe false
    answered(sClient(rePort, "/health", s"-cert ${f("revoked.chain")} -key ${f("revoked.key")}"), 200) shouldBe false
  }
  it should "negotiate a suite from the profile" in {
    val brief = sClient(rePort, "/health", s"$own -brief")
    (brief.contains("TLS_AES_256_GCM_SHA384") || brief.contains("TLS_CHACHA20_POLY1305_SHA256")) shouldBe true
  }

  "a PE-like port" should "serve a device route without a client certificate" in {
    answered(sClient(pePort, "/api/integrations/healthkit/status", ""), 200) shouldBe true
  }
  it should "answer 403 for any other route without one, and serve it to a lane client" in {
    val out = sClient(pePort, "/service", "")
    answered(out, 403) shouldBe true
    out should include("""{"error":"client certificate required"}""")
    answered(sClient(pePort, "/service", own), 200) shouldBe true
  }
  it should "still refuse a presented untrusted certificate" in {
    answered(sClient(pePort, "/api/integrations/healthkit/status", s"-cert ${f("rogue.chain")} -key ${f("rogue.key")}"), 200) shouldBe false
  }
}
