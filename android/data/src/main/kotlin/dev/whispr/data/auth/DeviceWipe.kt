package dev.whispr.data.auth

import android.content.Context
import android.util.Log
import dev.whispr.data.db.WhisprDatabase
import java.io.File
import java.security.KeyStore

/**
 * Removes everything Whispr keeps on this device after the account has been
 * deleted: the encrypted database, the wrapped identity and database keys,
 * the Keystore keys that wrap them, media blobs, the avatar and the cache.
 * Best effort: every step runs even if an earlier one fails, and failures
 * are reported to the caller rather than swallowed.
 */
class DeviceWipe(
    private val context: Context,
    private val db: WhisprDatabase,
    private val keystoreAliases: List<String>,
) {
    fun wipe() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure {
                Log.w(TAG, "wipe step failed: $name", it)
                failures += name
            }
        }
        step("database") {
            db.close()
            check(context.deleteDatabase(WhisprDatabase.NAME) || !context.getDatabasePath(WhisprDatabase.NAME).exists())
        }
        listOf("secrets", "media", "avatar").forEach { dir ->
            step(dir) { deleteTree(File(context.noBackupFilesDir, dir)) }
        }
        step("cache") { context.cacheDir.listFiles()?.forEach(::deleteTree) }
        step("keystore") {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keystoreAliases.forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
        }
        check(failures.isEmpty()) { "wipe incomplete: $failures" }
    }

    private fun deleteTree(f: File) {
        check(!f.exists() || f.deleteRecursively()) { "could not delete ${f.name}" }
    }

    private companion object {
        const val TAG = "DeviceWipe"
    }
}
