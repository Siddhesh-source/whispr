package dev.whispr.data

import dev.whispr.data.crypto.PreKeyMaintainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreKeyMaintainerTest {
    private val server = FakeKeyServer()
    private var now = 1_000_000_000L
    private val device = CryptoDevice(server, clock = { now })

    @After
    fun tearDown() = device.close()

    @Test
    fun firstRunRegistersAFullKeySet() = runBlocking {
        assertTrue(device.maintainer.maintain())
        val up = server.uploads.single()
        assertNotNull(up.registrationId)
        assertNotNull(up.signedPreKey)
        assertNotNull(up.lastResort)
        assertEquals(PreKeyMaintainer.TARGET, up.oneTime!!.size)
        assertEquals(PreKeyMaintainer.TARGET, up.kyber!!.size)
    }

    @Test
    fun throttledForSixHoursUnlessForced() = runBlocking {
        device.maintainer.maintain()
        val calls = server.countCalls
        now += 60 * 60 * 1000L
        device.maintainer.maintain()
        assertEquals("no server call within 6 h", calls, server.countCalls)
        device.maintainer.maintain(force = true) // e.g. a one-time key was just used
        assertEquals(calls + 1, server.countCalls)
        assertEquals("nothing missing, nothing uploaded", 1, server.uploads.size)
    }

    @Test
    fun topsUpWhenLowAndRotatesAfterSevenDays() = runBlocking {
        device.maintainer.maintain()
        repeat(85) { server.bundles.bundle(device.userId) } // 15 left
        now += PreKeyMaintainer.CHECK_INTERVAL_MS
        device.maintainer.maintain()
        val topUp = server.uploads.last()
        assertEquals(85, topUp.oneTime!!.size)
        assertNull("signed key not due yet", topUp.signedPreKey)

        now += PreKeyMaintainer.ROTATE_MS
        device.maintainer.maintain()
        val rotation = server.uploads.last()
        assertNotNull(rotation.signedPreKey)
        assertNotNull(rotation.lastResort)
        assertTrue(rotation.signedPreKey!!.keyId > server.uploads.first().signedPreKey!!.keyId)
    }

    @Test
    fun failedUploadIsReportedAndRetriedOnNextRun() = runBlocking {
        server.failUploadsWith = 400
        assertFalse(device.maintainer.maintain())
        assertFalse(device.maintainer.keysRegistered.value)
        server.failUploadsWith = null
        assertTrue("retried without waiting 6 h", device.maintainer.maintain())
        assertTrue(device.maintainer.keysRegistered.value)
    }
}
