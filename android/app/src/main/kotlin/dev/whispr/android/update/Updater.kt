package dev.whispr.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.whispr.android.BuildConfig
import dev.whispr.domain.repository.SettingsRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The newest release, as release.yml publishes it next to the APK. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
    val notes: String = "",
)

/** Where an update stands; every failure says why. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState

    /** Android needs the user to allow installs from Whispr once. */
    data class NeedsPermission(val manifest: UpdateManifest) : UpdateState

    /** Handed to Android's installer, which asks the user to confirm. */
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val reason: UpdateFailure, val manifest: UpdateManifest? = null) : UpdateState
}

enum class UpdateFailure { Network, Corrupt, InstallFailed }

/**
 * Keeps the app current without visiting GitHub: checks the release
 * manifest, downloads the APK, verifies its SHA-256 against the manifest,
 * and hands it to Android's package installer. Android itself refuses an
 * APK that isn't signed with the same key as the installed app, so a
 * tampered download can't replace Whispr. Development builds (no
 * [manifestUrl]) never check.
 */
class Updater(
    private val context: Context,
    private val settings: SettingsRepository,
    /** The manifest URL; empty in development builds, which never update. */
    private val manifestUrl: String = BuildConfig.UPDATE_URL,
    private val currentVersion: Int = BuildConfig.VERSION_CODE,
    /** Only tests (a local HTTP server) turn this off. */
    private val requireHttps: Boolean = true,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutable.asStateFlow()

    val enabled: Boolean get() = manifestUrl.isNotEmpty()

    /** At start: checks if allowed and the last check is old enough. */
    suspend fun checkIfDue() {
        if (!enabled || !settings.observePrivacy().first().updateChecks) return
        val last = prefs.getLong(LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            // Still offer what the last check found.
            cached()?.takeIf { it.versionCode > currentVersion }?.let {
                mutable.value = UpdateState.Available(it)
            }
            return
        }
        check()
    }

    /** "Check for updates" in Settings, or the twice-daily check. */
    suspend fun check() {
        if (!enabled) return
        mutable.value = UpdateState.Checking
        val manifest = try {
            fetch(manifestUrl)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            mutable.value = UpdateState.Failed(UpdateFailure.Network)
            return
        }
        prefs.edit().putLong(
            LAST_CHECK,
            System.currentTimeMillis(),
        ).putString(CACHED, json.encodeToString(UpdateManifest.serializer(), manifest)).apply()
        mutable.value = if (manifest.versionCode > currentVersion) {
            UpdateState.Available(manifest)
        } else {
            UpdateState.UpToDate
        }
    }

    /** Downloads, verifies and installs [manifest]'s APK. */
    suspend fun update(manifest: UpdateManifest) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutable.value = UpdateState.NeedsPermission(manifest)
            return
        }
        mutable.value = UpdateState.Downloading(manifest, 0f)
        val apk = try {
            download(manifest)
        } catch (c: CancellationException) {
            throw c
        } catch (_: IOException) {
            mutable.value = UpdateState.Failed(UpdateFailure.Network, manifest)
            return
        } ?: run {
            mutable.value = UpdateState.Failed(UpdateFailure.Corrupt, manifest)
            return
        }
        try {
            install(apk)
            mutable.value = UpdateState.Installing(manifest)
        } catch (e: IOException) {
            android.util.Log.w(TAG, "install session failed", e)
            mutable.value = UpdateState.Failed(UpdateFailure.InstallFailed, manifest)
        }
    }

    /** The system screen where the user allows Whispr to install updates. */
    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dismiss() {
        mutable.value = UpdateState.Idle
    }

    internal fun installResult(status: Int, message: String?) {
        val manifest = (mutable.value as? UpdateState.Installing)?.manifest
        if (status != PackageInstaller.STATUS_SUCCESS) {
            android.util.Log.w(TAG, "install status $status: $message")
            mutable.value = UpdateState.Failed(UpdateFailure.InstallFailed, manifest)
        }
    }

    private fun cached(): UpdateManifest? = prefs.getString(CACHED, null)?.let {
        runCatching { json.decodeFromString(UpdateManifest.serializer(), it) }.getOrNull()
    }

    private suspend fun fetch(url: String): UpdateManifest = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body.string()
            if (body.length > MAX_MANIFEST) throw IOException("manifest too large")
            json.decodeFromString(UpdateManifest.serializer(), body).also {
                require(!requireHttps || it.apk.startsWith("https://")) { "APK must come over HTTPS" }
                require(it.sha256.matches(SHA256_HEX)) { "bad digest" }
            }
        }
    }

    /** The verified APK, or null if it doesn't match the manifest. */
    private suspend fun download(m: UpdateManifest): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "whispr.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(m.apk).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val input = r.body.byteStream()
            target.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > m.size) return@withContext null.also { target.delete() }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    mutable.value = UpdateState.Downloading(m, total.toFloat() / m.size)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(m.sha256, ignoreCase = true)) {
            target.delete()
            return@withContext null
        }
        target
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("whispr.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }

    private companion object {
        const val TAG = "Updater"
        const val LAST_CHECK = "last_check"
        const val CACHED = "cached_manifest"
        const val CHECK_EVERY_MS = 12 * 60 * 60_000L
        const val TIMEOUT_S = 30L
        const val BUFFER = 64 * 1024
        const val MAX_MANIFEST = 16 * 1024
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

/**
 * Android reports the install here. It usually needs the user to confirm
 * first (STATUS_PENDING_USER_ACTION): we open its confirmation screen.
 */
@dagger.hilt.android.AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: Updater

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        updater.installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
