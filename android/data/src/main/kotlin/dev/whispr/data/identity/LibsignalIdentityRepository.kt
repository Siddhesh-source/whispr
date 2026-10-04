package dev.whispr.data.identity

import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.domain.repository.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * The libsignal identity key pair, generated on first use and stored wrapped
 * by a Keystore key. Key generation, serialization and signing are all
 * libsignal calls; this class only decides where the bytes live.
 */
class LibsignalIdentityRepository(private val store: SecretFileStore, private val io: CoroutineDispatcher) :
    IdentityRepository {

    private val mutex = Mutex()

    // Cached after first load so we unwrap once per process. The private key
    // is in app memory while the process runs; the threat model covers this.
    @Volatile private var cached: IdentityKeyPair? = null

    override suspend fun hasIdentity(): Boolean = cached != null || withContext(io) { store.exists(FILE) }

    override suspend fun getOrCreatePublicKey(): ByteArray = keyPair().publicKey.serialize()

    override suspend fun sign(message: ByteArray): ByteArray = withContext(io) {
        keyPair().privateKey.calculateSignature(message)
    }

    private suspend fun keyPair(): IdentityKeyPair {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(io) { loadOrCreate() }.also { cached = it }
        }
    }

    private fun loadOrCreate(): IdentityKeyPair {
        store.read(FILE, AAD)?.let { return IdentityKeyPair(it) }
        val pair = IdentityKeyPair.generate()
        store.write(FILE, pair.serialize(), AAD)
        return pair
    }

    internal companion object {
        const val FILE = "identity.v1.bin"
        val AAD = "whispr-identity-v1".toByteArray()
    }
}
