package dev.whispr.data

import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A failed key upload is retried while the connection stays up. Before the
 * fix, the engine tried once per connection: a device that stayed connected
 * after one failed upload published no keys, and nobody could reach it.
 */
@RunWith(RobolectricTestRunner::class)
class KeyUploadRetryTest {
    private val server = MockWebServer()
    private val relay = FakeRelay()

    @After
    fun tearDown() = server.close()

    @Test
    fun keysArePublishedOnceTheServerRecoversWithoutReconnecting() {
        server.dispatcher = relay
        server.start()
        val device = RelayDevice("Dana", relay, server.url("/").toString())
        try {
            relay.keyServer.failUploadsWith = 503
            device.start()
            eventually("connected") { device.engine.connection.value.name == "Connected" }
            eventually("the first upload failed") { !device.crypto.maintainer.keysRegistered.value }
            assertEquals(0, relay.keyServer.oneTimeLeft(device.id))

            relay.keyServer.failUploadsWith = null
            eventually("keys uploaded on the same connection") { relay.keyServer.oneTimeLeft(device.id) > 0 }
            eventually("settings warning clears") { device.crypto.maintainer.keysRegistered.value }
            assertEquals("never reconnected", 1, relay.connections(device.id))
        } finally {
            device.close()
        }
    }

    private fun eventually(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
