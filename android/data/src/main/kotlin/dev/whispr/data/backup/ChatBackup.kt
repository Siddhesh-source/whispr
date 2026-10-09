package dev.whispr.data.backup

import android.content.Context
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.DatabaseKey
import dev.whispr.data.db.SettingEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair

data class BackupState(val enabled: Boolean = false, val lastBackupAt: Long? = null)

enum class RestoreResult { Ok, WrongKey, Damaged }

/** Where the app keeps what a backup holds. */
class BackupPaths(
    val database: File,
    val media: File,
    val avatar: File,
    /** A restored backup waits here until the next start (see [ChatBackup.applyStaged]). */
    val restore: File,
    val scratch: File,
) {
    companion object {
        /** The app's real locations (the same ones [dev.whispr.data.di.DataModule] uses). */
        fun of(context: Context) = BackupPaths(
            database = context.getDatabasePath(WhisprDatabase.NAME),
            media = File(context.noBackupFilesDir, "media"),
            avatar = File(context.noBackupFilesDir, "avatar"),
            restore = File(context.noBackupFilesDir, "restore"),
            scratch = File(context.cacheDir, "backup"),
        )
    }
}

/**
 * Encrypted chat backups, so chats survive reinstalling the app (Android
 * deletes app data on uninstall, and our keys can't leave the Keystore).
 *
 * A backup holds the identity key, the database (still SQLCipher-encrypted)
 * with its key, every attachment (still encrypted) and the profile photo,
 * all inside [BackupCipher] under a 32-byte recovery key that only the user
 * has: the file is useless without it, and the server never sees either.
 * Restoring brings back the account itself (sign-in is by identity key), so
 * a backup of an account that was deleted can't be used.
 */
class ChatBackup(
    private val db: WhisprDatabase,
    private val identity: SecretFileStore,
    private val databaseKey: SecretFileStore,
    private val paths: BackupPaths,
    /** Runs [block] while nothing writes to the database. */
    private val quiet: suspend (block: () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<BackupState> = db.settingDao().observeAll().map { rows ->
        val map = rows.associate { it.key to it.value }
        BackupState(enabled = map[ENABLED] == "true", lastBackupAt = map[LAST]?.toLongOrNull())
    }

    /** Turns backups on; returns the recovery key (the existing one if there is one). */
    suspend fun enable(): String {
        val key = withContext(Dispatchers.IO) {
            identity.read(KEY_FILE, KEY_AAD) ?: ByteArray(KEY_BYTES).also {
                SecureRandom().nextBytes(it)
                identity.write(KEY_FILE, it, KEY_AAD)
            }
        }
        db.settingDao().put(SettingEntity(ENABLED, "true"))
        return format(key)
    }

    suspend fun recoveryKey(): String? = withContext(Dispatchers.IO) { identity.read(KEY_FILE, KEY_AAD)?.let(::format) }

    /** Turns backups off and forgets the key; existing backup files stay where they are. */
    suspend fun disable() {
        withContext(Dispatchers.IO) { identity.delete(KEY_FILE) }
        db.settingDao().put(SettingEntity(ENABLED, "false"))
    }

    /** Writes an encrypted backup to [out] and closes it. Throws [IOException] if it can't. */
    suspend fun write(out: OutputStream) = withContext(Dispatchers.IO) {
        val key = identity.read(KEY_FILE, KEY_AAD) ?: throw IOException("backups are off")
        val identityKey = identity.read(LibsignalIdentityRepository.FILE, LibsignalIdentityRepository.AAD)
            ?: throw IOException("no identity")
        val dbKey = databaseKey.read(DatabaseKey.FILE, DatabaseKey.AAD) ?: throw IOException("no database key")
        val snapshot = File(paths.scratch, "backup").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // The database and its write-ahead log, copied while nothing commits: a consistent snapshot.
            quiet {
                for (suffix in DB_FILES) {
                    val f = File(paths.database.path + suffix)
                    if (f.isFile) f.copyTo(File(snapshot, DB_NAME + suffix), overwrite = true)
                }
            }
            ZipOutputStream(BackupCipher.encrypt(out, key)).use { zip ->
                zip.put(MANIFEST, """{"version":1,"createdAt":${clock()}}""".toByteArray())
                zip.put(IDENTITY, identityKey)
                zip.put(DB_KEY, dbKey)
                snapshot.listFiles()?.forEach { zip.putFile("$DB_DIR/${it.name}", it) }
                paths.media.listFiles()?.filter { it.isFile && SAFE.matches(it.name) && !it.name.endsWith(".tmp") }
                    ?.forEach { zip.putFile("$MEDIA_DIR/${it.name}", it) }
                paths.avatar.listFiles()?.filter { it.isFile && SAFE.matches(it.name) && !it.name.endsWith(".tmp") }
                    ?.forEach { zip.putFile("$AVATAR_DIR/${it.name}", it) }
            }
        } finally {
            snapshot.deleteRecursively()
        }
        db.settingDao().put(SettingEntity(LAST, clock().toString()))
    }

    /**
     * Decrypts and checks a backup, then puts back its keys. The database,
     * attachments and photo replace this device's on the next start, so the
     * caller restarts the app on [RestoreResult.Ok].
     */
    suspend fun stage(input: InputStream, recoveryKey: String): RestoreResult = withContext(Dispatchers.IO) {
        val key = parse(recoveryKey) ?: return@withContext RestoreResult.WrongKey
        val dir = paths.restore.apply {
            deleteRecursively()
            mkdirs()
        }
        var identityKey: ByteArray? = null
        var dbKey: ByteArray? = null
        try {
            ZipInputStream(BackupCipher.decrypt(input, key)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == IDENTITY -> identityKey = zip.readBytes()
                        name == DB_KEY -> dbKey = zip.readBytes()
                        name == MANIFEST -> zip.readBytes()
                        allowed(name) -> File(dir, name).apply { parentFile?.mkdirs() }.outputStream().use {
                            zip.copyTo(it)
                        }
                        else -> throw IOException("unexpected entry")
                    }
                }
            }
        } catch (e: BackupCipher.BadBackup) {
            dir.deleteRecursively()
            return@withContext if (e.message?.startsWith("wrong") == true) {
                RestoreResult.WrongKey
            } else {
                RestoreResult.Damaged
            }
        } catch (_: IOException) {
            dir.deleteRecursively()
            return@withContext RestoreResult.Damaged
        }
        val ik = identityKey
        val dk = dbKey
        val valid = ik != null &&
            dk?.size == DatabaseKey.KEY_BYTES &&
            runCatching { IdentityKeyPair(ik) }.isSuccess &&
            File(dir, "$DB_DIR/$DB_NAME").isFile
        if (!valid) {
            dir.deleteRecursively()
            return@withContext RestoreResult.Damaged
        }
        // Everything is on disk before the keys change: the next start swaps the rest in.
        FileOutputStream(File(dir, READY)).use { it.fd.sync() }
        identity.write(LibsignalIdentityRepository.FILE, ik!!, LibsignalIdentityRepository.AAD)
        databaseKey.write(DatabaseKey.FILE, dk!!, DatabaseKey.AAD)
        identity.write(KEY_FILE, key, KEY_AAD)
        RestoreResult.Ok
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    companion object {
        private const val ENABLED = "backup.enabled"
        private const val LAST = "backup.last"
        private const val KEY_FILE = "backup-key.v1.bin"
        private val KEY_AAD = "whispr-backup-key-v1".toByteArray()
        const val KEY_BYTES = 32
        private const val MANIFEST = "manifest.json"
        private const val IDENTITY = "identity.bin"
        private const val DB_KEY = "database-key.bin"
        private const val DB_DIR = "db"
        private const val MEDIA_DIR = "media"
        private const val AVATAR_DIR = "avatar"
        private const val DB_NAME = WhisprDatabase.NAME
        private val DB_FILES = listOf("", "-wal")
        private const val READY = "ready"
        private val SAFE = Regex("[A-Za-z0-9._-]{1,128}")
        private const val HEX = 16
        private const val GROUP = 4

        private fun allowed(name: String): Boolean {
            val parts = name.split('/')
            if (parts.size != 2 || !SAFE.matches(parts[1]) || parts[1].startsWith(".")) return false
            return when (parts[0]) {
                DB_DIR -> parts[1] in DB_FILES.map { DB_NAME + it }
                MEDIA_DIR, AVATAR_DIR -> true
                else -> false
            }
        }

        /** 64 hex digits in groups of four, the way the user writes it down. */
        fun format(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }.chunked(GROUP).joinToString(" ")

        /** Accepts the key with or without spaces, any case. */
        fun parse(text: String): ByteArray? {
            val hex = text.filter { !it.isWhitespace() && it != '-' }.lowercase()
            if (hex.length != KEY_BYTES * 2 || hex.any { Character.digit(it, HEX) < 0 }) return null
            return ByteArray(KEY_BYTES) { i -> hex.substring(i * 2, i * 2 + 2).toInt(HEX).toByte() }
        }

        /**
         * Moves a staged restore into place. Runs at app start, before
         * anything opens the database. Returns true if it did.
         */
        fun applyStaged(paths: BackupPaths): Boolean {
            val dir = paths.restore
            if (!File(dir, READY).isFile) {
                dir.deleteRecursively()
                return false
            }
            paths.database.parentFile?.mkdirs()
            listOf("", "-wal", "-shm", "-journal").forEach { File(paths.database.path + it).delete() }
            DB_FILES.forEach { suffix ->
                File(dir, "$DB_DIR/$DB_NAME$suffix").takeIf { it.isFile }?.renameTo(File(paths.database.path + suffix))
            }
            replaceDir(File(dir, MEDIA_DIR), paths.media)
            replaceDir(File(dir, AVATAR_DIR), paths.avatar)
            dir.deleteRecursively()
            return true
        }

        private fun replaceDir(from: File, to: File) {
            to.deleteRecursively()
            if (from.isDirectory) {
                to.parentFile?.mkdirs()
                if (!from.renameTo(to)) from.copyRecursively(to, overwrite = true)
            }
        }
    }
}
