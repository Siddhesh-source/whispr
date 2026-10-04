package dev.whispr.data.db

import dev.whispr.data.crypto.SecretFileStore
import java.security.SecureRandom

/** Loads the SQLCipher passphrase, creating a random one on first launch. */
class DatabaseKey(private val store: SecretFileStore) {

    @Synchronized
    fun getOrCreate(): ByteArray {
        store.read(FILE, AAD)?.let { return it }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        store.write(FILE, key, AAD)
        return key
    }

    internal companion object {
        const val FILE = "database-key.v1.bin"
        const val KEY_BYTES = 32
        val AAD = "whispr-database-key-v1".toByteArray()
    }
}
