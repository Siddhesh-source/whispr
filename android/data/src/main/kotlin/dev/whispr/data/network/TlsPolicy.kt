package dev.whispr.data.network

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Transport rules for every connection to the Whispr server (HTTP and the
 * WebSocket share one client). TLS 1.2+ with modern cipher suites only; the
 * certificate must chain to a system CA (network_security_config.xml) and,
 * when [ServerConfig.pins] is set, its public key must match one of the pins.
 * Plain HTTP is allowed only for a loopback debug server.
 */
object TlsPolicy {
    fun apply(builder: OkHttpClient.Builder, config: ServerConfig): OkHttpClient.Builder {
        val url = config.baseUrl.toHttpUrl()
        if (!url.isHttps) {
            require(url.host in LOOPBACK) { "plain HTTP is only allowed for a loopback server" }
            require(config.pins.isEmpty()) { "pins need an HTTPS server" }
            return builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        }
        builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        if (config.pins.isNotEmpty()) {
            builder.certificatePinner(CertificatePinner.Builder().add(url.host, *config.pins.toTypedArray()).build())
        }
        return builder
    }

    // 10.0.2.2 is the emulator's alias for the host machine (debug builds only).
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
}
