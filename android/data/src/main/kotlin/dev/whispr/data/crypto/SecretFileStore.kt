package dev.whispr.data.crypto

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists wrapped secrets as files in a directory that is excluded from
 * backups (the app passes `noBackupFilesDir`). Writes are atomic: a crash
 * mid-write leaves either the old file or the new one, never a torn file.
 */
class SecretFileStore(private val dir: File, private val wrapper: KeyWrapper) {
    fun exists(name: String): Boolean = File(dir, name).isFile

    fun delete(name: String) {
        File(dir, name).delete()
    }

    /** Returns null if the secret has never been written. */
    fun read(name: String, aad: ByteArray): ByteArray? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return wrapper.unwrap(file.readBytes(), aad)
    }

    fun write(name: String, secret: ByteArray, aad: ByteArray) {
        dir.mkdirs()
        val blob = wrapper.wrap(secret, aad)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use {
            it.write(blob)
            it.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            File(dir, name).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
