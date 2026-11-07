package com.realityengine.tls

// Lane TLS (RealityEngine_CI#668, RealityEngine_Scala#210).
//
// The runtime contract is RealityEngine_CI docs/TRANSPORT_SECURITY.md:
// RE_TLS_CERT, RE_TLS_KEY, RE_TLS_CA, RE_TLS_CRL. With RE_TLS_CERT set the RE
// and PE serve TLS 1.3 only on their own port, with the profile's suites and
// groups and a client certificate from the lane CA, and call internal peers
// over https with the lane CA and their own certificate. With it unset nothing
// here runs: the KEYSTORE_PATH path (Manager's proxied pair) and plain HTTP
// are untouched.
//
// The same file is in both sbt builds (the RE here, the PE under
// perception-engine/). C++ (src/http.cpp) and LSP (src/tls.lisp) make the same
// choices; a difference would read as an engine divergence.

import java.io.ByteArrayInputStream
import java.net.{HttpURLConnection, InetAddress, Socket}
import java.nio.file.{Files, Paths}
import java.security.cert.{CertPathBuilder, CertStore, CertificateFactory, CollectionCertStoreParameters,
  PKIXBuilderParameters, PKIXRevocationChecker, TrustAnchor, X509CRL, X509CertSelector, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, KeyStore, PrivateKey, SecureRandom}
import java.util.{Base64, EnumSet}
import javax.net.ssl._
import scala.jdk.CollectionConverters._
import scala.util.Try

object LaneTls {
  final case class Settings(cert: String, key: String, ca: String, crl: Option[String])

  val Protocols: Array[String] = Array("TLSv1.3")
  val Suites: Array[String]    = Array("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")
  val GroupsPq: String         = "X25519MLKEM768,x25519,secp384r1"
  val GroupsFallback: String   = "x25519,secp384r1"

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  /** RE_TLS_CERT set: this service serves TLS. */
  def serving: Option[Settings] = env("RE_TLS_CERT").map { cert =>
    val key = env("RE_TLS_KEY").getOrElse(fail("RE_TLS_CERT is set, so RE_TLS_KEY must be too"))
    val ca  = env("RE_TLS_CA").getOrElse(fail("RE_TLS_CERT is set, so RE_TLS_CA must be too"))
    Settings(cert, key, ca, env("RE_TLS_CRL"))
  }

  /** RE_TLS_CA set: the lane is on for calls too. */
  def laneOn: Boolean = env("RE_TLS_CA").isDefined

  private def fail(msg: String): Nothing = throw new IllegalStateException(s"TLS: $msg")

  // ── Groups ──────────────────────────────────────────────────────────────
  // JSSE reads jdk.tls.namedGroups once, when it initialises, so this runs
  // before the first context is built. A group the JDK does not know is
  // dropped; JDK 17 has no X25519MLKEM768, so the fallback is reported.
  @volatile private var groupsInForce: Option[String] = None

  def configureGroups(): String = synchronized {
    groupsInForce.getOrElse {
      System.setProperty("jdk.tls.namedGroups", GroupsPq)
      val probe  = SSLContext.getInstance("TLSv1.3")
      probe.init(null, null, null)
      val params = probe.getDefaultSSLParameters
      val named  = Try(params.getClass.getMethod("getNamedGroups").invoke(params).asInstanceOf[Array[String]].toSeq)
        .getOrElse(Seq.empty)
      val inForce = if (named.exists(_.equalsIgnoreCase("X25519MLKEM768"))) GroupsPq else GroupsFallback
      groupsInForce = Some(inForce)
      inForce
    }
  }

  def pqAvailable: Boolean = configureGroups() == GroupsPq

  // ── Material ────────────────────────────────────────────────────────────
  private def pemBlocks(path: String, label: String): Seq[Array[Byte]] = {
    val text  = new String(Files.readAllBytes(Paths.get(path)), "US-ASCII")
    val begin = s"-----BEGIN $label-----"
    val end   = s"-----END $label-----"
    text.split(begin).toSeq.drop(1).map { rest =>
      Base64.getMimeDecoder.decode(rest.substring(0, rest.indexOf(end)))
    }
  }

  def certificates(path: String): Seq[X509Certificate] = {
    val cf = CertificateFactory.getInstance("X.509")
    pemBlocks(path, "CERTIFICATE").map(b => cf.generateCertificate(new ByteArrayInputStream(b)).asInstanceOf[X509Certificate])
  }

  def privateKey(path: String): PrivateKey = {
    val der = pemBlocks(path, "PRIVATE KEY").headOption.getOrElse(fail(s"no PKCS#8 PRIVATE KEY in $path"))
    val spec = new PKCS8EncodedKeySpec(der)
    Try(KeyFactory.getInstance("EC").generatePrivate(spec))
      .orElse(Try(KeyFactory.getInstance("RSA").generatePrivate(spec)))
      .getOrElse(fail(s"cannot read the private key in $path"))
  }

  private def must[T](what: String, path: String)(f: => T): T =
    Try(f).getOrElse(fail(s"cannot load $what $path"))

  def keyManagers(s: Settings): Array[KeyManager] = {
    val chain = must("RE_TLS_CERT", s.cert)(certificates(s.cert))
    if (chain.isEmpty) fail(s"no certificate in RE_TLS_CERT ${s.cert}")
    val key   = must("RE_TLS_KEY", s.key)(privateKey(s.key))
    val ks    = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    val pass  = Array.emptyCharArray
    ks.setKeyEntry("instance", key, pass, chain.toArray[java.security.cert.Certificate])
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, pass)
    kmf.getKeyManagers
  }

  /** The lane CA alone, with the CRL checked (no OCSP, no fallback). */
  def laneTrustManagers(ca: String, crl: Option[String]): Array[TrustManager] = {
    val anchors = must("RE_TLS_CA", ca)(certificates(ca)).map(c => new TrustAnchor(c, null)).toSet.asJava
    if (anchors.isEmpty) fail(s"no certificate in RE_TLS_CA $ca")
    val params = new PKIXBuilderParameters(anchors, new X509CertSelector())
    crl match {
      case Some(path) =>
        val crls = must("RE_TLS_CRL", path) {
          val cf = CertificateFactory.getInstance("X.509")
          pemBlocks(path, "X509 CRL").map(b => cf.generateCRL(new ByteArrayInputStream(b)).asInstanceOf[X509CRL])
        }
        if (crls.isEmpty) fail(s"no CRL in RE_TLS_CRL $path")
        params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(crls.asJava)))
        val checker = CertPathBuilder.getInstance("PKIX").getRevocationChecker.asInstanceOf[PKIXRevocationChecker]
        checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.PREFER_CRLS, PKIXRevocationChecker.Option.NO_FALLBACK))
        params.addCertPathChecker(checker)
        params.setRevocationEnabled(true)
      case None =>
        params.setRevocationEnabled(false)
    }
    val tmf = TrustManagerFactory.getInstance("PKIX")
    tmf.init(new CertPathTrustManagerParameters(params))
    tmf.getTrustManagers
  }

  private def context(km: Array[KeyManager], tm: Array[TrustManager]): SSLContext = {
    configureGroups()
    val ctx = SSLContext.getInstance("TLSv1.3")
    ctx.init(km, tm, new SecureRandom())
    ctx
  }

  // ── Serving ─────────────────────────────────────────────────────────────
  def serverContext(s: Settings): SSLContext = context(keyManagers(s), laneTrustManagers(s.ca, s.crl))

  /** An engine for one inbound connection. With no device routes the handshake
    * requires a client certificate; with some it requests one, verifies any
    * presented, and the route decides (see [[peerVerified]]). */
  def serverEngine(ctx: SSLContext, requireAtHandshake: Boolean): SSLEngine = {
    val e = ctx.createSSLEngine()
    e.setUseClientMode(false)
    e.setEnabledProtocols(Protocols)
    e.setEnabledCipherSuites(Suites)
    if (requireAtHandshake) e.setNeedClientAuth(true) else e.setWantClientAuth(true)
    e
  }

  /** Did the peer present a certificate the lane CA verified? */
  def peerVerified(session: SSLSession): Boolean =
    Try(session.getPeerCertificates.nonEmpty).getOrElse(false)

  def startupLine(port: Int, deviceRoutes: Boolean): String = {
    val groups = configureGroups()
    val req    = if (deviceRoutes) "required except device routes" else "required"
    val note   = if (groups == GroupsFallback) " - this JDK has no X25519MLKEM768" else ""
    s"listening on $port (TLS 1.3, client certificate $req, groups $groups$note)"
  }

  // ── Calling ─────────────────────────────────────────────────────────────

  /** An internal peer: loopback, a private or link-local address, a single-label
    * name (a compose service), or a name under .localhost, .internal or .local.
    * Internal links trust the lane CA alone and present this instance's
    * certificate; every other host is an external party, verified against the
    * system store. */
  def isInternalHost(raw: String): Boolean = {
    val h = Option(raw).getOrElse("").stripPrefix("[").stripSuffix("]").toLowerCase
    if (h.isEmpty) false
    else if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")) true
    else if (isIpv4(h))
      Try(InetAddress.getByName(h)).toOption.exists(a => a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress)
    else if (h.contains(':'))
      // IPv6: loopback, link-local and unique-local (fc00::/7), as C++ and LSP.
      Try(InetAddress.getByName(h)).toOption.exists(a =>
        a.isLoopbackAddress || a.isLinkLocalAddress || (a.getAddress.length == 16 && (a.getAddress()(0) & 0xfe) == 0xfc))
    else !h.contains('.')
  }

  private def isIpv4(h: String): Boolean = {
    val parts = h.split('.')
    parts.length == 4 && parts.forall(p => p.nonEmpty && p.length <= 3 && p.forall(_.isDigit) && p.toInt <= 255)
  }

  /** The lane CA alone (and its CRL), presenting `own` where a peer asks. */
  def internalSocketFactory(ca: String, crl: Option[String], own: Option[Settings]): SSLSocketFactory =
    new ProfileSocketFactory(context(own.map(keyManagers).orNull, laneTrustManagers(ca, crl)).getSocketFactory)

  private lazy val internalClient: SSLSocketFactory =
    internalSocketFactory(env("RE_TLS_CA").get, env("RE_TLS_CRL"), serving)

  private lazy val externalClient: SSLSocketFactory =
    new ProfileSocketFactory(context(null, null).getSocketFactory)

  /** For sttp's HttpURLConnectionBackend(customizeConnection = ...): with the
    * lane on, an https connection gets the lane factory for an internal host
    * and the profile-held system factory otherwise. HttpsURLConnection keeps
    * the connection alive and checks the host name or IP. With the lane off
    * this changes nothing. */
  def customizeConnection(conn: HttpURLConnection): Unit = conn match {
    case https: HttpsURLConnection if laneOn =>
      https.setSSLSocketFactory(if (isInternalHost(https.getURL.getHost)) internalClient else externalClient)
    case _ => ()
  }

  /** TLS 1.3 and the profile's suites on every socket a factory creates. */
  final class ProfileSocketFactory(inner: SSLSocketFactory) extends SSLSocketFactory {
    private def pin(s: Socket): Socket = {
      s match {
        case ssl: SSLSocket =>
          ssl.setEnabledProtocols(Protocols)
          ssl.setEnabledCipherSuites(Suites)
          val p = ssl.getSSLParameters
          p.setEndpointIdentificationAlgorithm("HTTPS")
          ssl.setSSLParameters(p)
        case _ => ()
      }
      s
    }
    override def getDefaultCipherSuites: Array[String] = Suites
    override def getSupportedCipherSuites: Array[String] = Suites
    override def createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket = pin(inner.createSocket(s, host, port, autoClose))
    override def createSocket(): Socket = pin(inner.createSocket())
    override def createSocket(host: String, port: Int): Socket = pin(inner.createSocket(host, port))
    override def createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = pin(inner.createSocket(host, port, local, localPort))
    override def createSocket(host: InetAddress, port: Int): Socket = pin(inner.createSocket(host, port))
    override def createSocket(a: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = pin(inner.createSocket(a, port, local, localPort))
  }
}
