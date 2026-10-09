package dev.whispr.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.android.update.UpdateFailure
import dev.whispr.android.update.UpdateManifest
import dev.whispr.android.update.UpdateState
import dev.whispr.android.update.Updater
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Finding, downloading and verifying an update (installation itself is Android's). */
@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val server = MockWebServer()
    private val apk = ByteArray(50_000) { (it % 251).toByte() }
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
        "%02x".format(it)
    }

    private fun manifest(code: Int, digest: String = sha(apk), size: Long = apk.size.toLong()) = """
        {"versionCode":$code,"versionName":"0.1.0-beta.$code","apk":"${server.url("/whispr.apk")}",
         "sha256":"$digest","size":$size,"future":"ignored"}
    """.trimIndent()

    private fun updater(current: Int = 10002, requireHttps: Boolean = false) =
        Updater(context, FakeSettings(), server.url("/update.json").toString(), current, requireHttps)

    private fun serve(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun newerReleaseIsOfferedAndTheSameOneIsNot() = runBlocking {
        serve(manifest(10003))
        val u = updater()
        u.check()
        assertEquals(10003, (u.state.value as UpdateState.Available).manifest.versionCode)

        serve(manifest(10002))
        u.check()
        assertEquals(UpdateState.UpToDate, u.state.value)
    }

    @Test
    fun developmentBuildsNeverCheck() = runBlocking {
        val u = Updater(context, FakeSettings(), "", 1)
        u.check()
        assertFalse(u.enabled)
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachableOrMalformedManifestSaysSo() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val u = updater()
        u.check()
        assertEquals(UpdateFailure.Network, (u.state.value as UpdateState.Failed).reason)
        // Released builds refuse a plain-HTTP APK link.
        serve(manifest(10003))
        val strict = updater(requireHttps = true)
        strict.check()
        assertEquals(UpdateFailure.Network, (strict.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun aDownloadThatDoesNotMatchTheDigestIsDeleted() = runBlocking {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val m = UpdateManifest(10003, "0.1.0-beta.3", server.url("/whispr.apk").toString(), sha(apk), apk.size.toLong())
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk.copyOf().also { it[0] = 9 })).build())
        val u = updater()
        u.update(m)
        assertEquals(UpdateFailure.Corrupt, (u.state.value as UpdateState.Failed).reason)
        assertFalse(java.io.File(context.cacheDir, "update/whispr.apk").exists())
    }

    @Test
    fun aDownloadLargerThanAnnouncedIsRefused() = runBlocking {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val m = UpdateManifest(10003, "0.1.0-beta.3", server.url("/whispr.apk").toString(), sha(apk), 1_000)
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk)).build())
        val u = updater()
        u.update(m)
        assertEquals(UpdateFailure.Corrupt, (u.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun withoutInstallPermissionTheUserIsAskedFirst() = runBlocking {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val m = UpdateManifest(10003, "0.1.0-beta.3", "https://example.invalid/a.apk", sha(apk), 1)
        val u = updater()
        u.update(m)
        assertTrue(u.state.value is UpdateState.NeedsPermission)
        assertEquals(0, server.requestCount)
    }
}
