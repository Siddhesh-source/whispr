package dev.whispr.data

import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.TlsPolicy
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Certificate pinning and the transport rules every server connection uses. */
class TlsPolicyTest {
    private val server = MockWebServer()
    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val leaf = HeldCertificate.Builder()
        .signedBy(root)
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
    private val otherKey = HeldCertificate.Builder().build()

    @Before
    fun setUp() {
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
        server.useHttps(serverCerts.sslSocketFactory())
        server.enqueue(MockResponse.Builder().body("ok").build())
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client(pins: List<String>): OkHttpClient {
        // Stands in for the system trust store: the test root is trusted.
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val config = ServerConfig(server.url("/").toString(), pins)
        return TlsPolicy.apply(OkHttpClient.Builder(), config)
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
    }

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }

    @Test
    fun matchingPinConnects() {
        val pins = listOf(CertificatePinner.pin(leaf.certificate), CertificatePinner.pin(otherKey.certificate))
        assertEquals("ok", get(client(pins)))
    }

    @Test
    fun backupPinOnTheRootAlsoConnects() {
        val pins = listOf(CertificatePinner.pin(otherKey.certificate), CertificatePinner.pin(root.certificate))
        assertEquals("ok", get(client(pins)))
    }

    @Test
    fun certificateFromATrustedCaButWrongKeyIsRejected() {
        // A mis-issued certificate (or a MITM with a CA the device trusts) fails the pin check.
        val pins = listOf(CertificatePinner.pin(otherKey.certificate), "sha256/" + "A".repeat(43) + "=")
        // OkHttp then tries the next address (::1) and reports that, with the pin failure suppressed.
        val e = assertThrows(IOException::class.java) { get(client(pins)) }
        val chain = generateSequence(e as Throwable) { it.cause }.flatMap { sequenceOf(it) + it.suppressed }
        assertTrue(chain.any { it is SSLPeerUnverifiedException && "pinning" in it.message.orEmpty() })
    }

    @Test
    fun plainHttpIsOnlyForLoopback() {
        assertThrows(IllegalArgumentException::class.java) {
            TlsPolicy.apply(OkHttpClient.Builder(), ServerConfig("http://example.com/"))
        }
        TlsPolicy.apply(OkHttpClient.Builder(), ServerConfig("http://127.0.0.1:8080/"))
    }
}
