package dev.whispr.data.crypto

import dev.whispr.data.db.SettingEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.IdentityKeyPairSource
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.KeyCountsResponse
import dev.whispr.data.network.KeyUploadRequest
import dev.whispr.data.network.OneTimeKeyJson
import dev.whispr.data.network.SignedKeyJson
import dev.whispr.domain.repository.EncryptionRepository
import java.util.Base64
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** The server side of prekey maintenance (implemented by KeysApi). */
interface KeyServer {
    suspend fun counts(): ApiResult<KeyCountsResponse>
    suspend fun upload(request: KeyUploadRequest): ApiResult<Unit>
}

/**
 * Keeps our public prekeys on the server: registers them on first run, tops
 * up one-time keys when fewer than [LOW] remain, rotates the signed and
 * last-resort keys every 7 days and deletes old private keys after 30 days
 * (the server's envelope retention, so delayed messages still decrypt).
 *
 * Runs at most every 6 hours, plus immediately after one of our one-time
 * keys was used ([maintain] with force) or after a failed upload.
 */
class PreKeyMaintainer(
    private val db: WhisprDatabase,
    private val store: SignalStore,
    private val identity: IdentityKeyPairSource,
    private val server: KeyServer,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : EncryptionRepository {
    private val dao get() = db.cryptoDao()
    private val mutex = Mutex()
    private val registered = MutableStateFlow(true)

    /** False while our keys could not be uploaded; nobody can start a chat with us then. */
    val keysRegistered: StateFlow<Boolean> = registered.asStateFlow()

    override fun observeKeysRegistered(): Flow<Boolean> = keysRegistered

    /** Returns true if the server holds a complete, current key set afterwards. */
    suspend fun maintain(force: Boolean = false): Boolean = mutex.withLock {
        store.setIdentityKeyPair(identity.keyPair())
        val now = clock()
        val (lastCheck, ok) = withContext(dispatcher) {
            dao.setting(LAST_CHECK)?.toLongOrNull() to (dao.setting(REGISTERED) == "true")
        }
        if (!force && ok && lastCheck != null && now - lastCheck < CHECK_INTERVAL_MS) {
            registered.value = true
            return true
        }
        val counts = (server.counts() as? ApiResult.Success)?.body ?: return false.also { registered.value = ok }
        val request = withContext(dispatcher) { db.runInTransaction(Callable { prepare(counts, now) }) }
        val uploaded = request == null || server.upload(request) is ApiResult.Success
        withContext(dispatcher) {
            db.runInTransaction {
                if (uploaded) {
                    dao.putSetting(SettingEntity(LAST_CHECK, now.toString()))
                    pruneOld(now)
                }
                dao.putSetting(SettingEntity(REGISTERED, uploaded.toString()))
            }
        }
        registered.value = uploaded
        uploaded
    }

    /** Generates and stores whatever the server is missing; returns the upload, or null if nothing is needed. */
    private fun prepare(counts: KeyCountsResponse, now: Long): KeyUploadRequest? {
        val signing = store.identityKeyPair.privateKey
        val registrationId = store.localRegistrationId.takeIf { counts.registrationId == null }

        val currentSigned = dao.signedPreKeys().maxByOrNull { it.id }
        val signed = if (counts.signedPreKeyId == null ||
            currentSigned == null ||
            currentSigned.id != counts.signedPreKeyId ||
            now - currentSigned.createdAt >= ROTATE_MS
        ) {
            val id = store.allocateKeyIds(SignalStore.KeyKind.Signed, 1).first
            val pair = ECKeyPair.generate()
            val sig = signing.calculateSignature(pair.publicKey.serialize())
            store.storeSignedPreKey(id, SignedPreKeyRecord(id, now, pair, sig))
            SignedKeyJson(id, b64(pair.publicKey.serialize()), b64(sig))
        } else {
            null
        }

        val currentLastResort = dao.kyberPreKeys().filter { it.lastResort }.maxByOrNull { it.id }
        val lastResort = if (counts.lastResortId == null ||
            currentLastResort == null ||
            currentLastResort.id != counts.lastResortId ||
            now - currentLastResort.createdAt >= ROTATE_MS
        ) {
            newKyber(now, lastResort = true)
        } else {
            null
        }

        val oneTime = if (counts.oneTime < LOW) {
            store.allocateKeyIds(SignalStore.KeyKind.OneTime, TARGET - counts.oneTime).map { id ->
                val pair = ECKeyPair.generate()
                store.storePreKey(id, PreKeyRecord(id, pair))
                OneTimeKeyJson(id, b64(pair.publicKey.serialize()))
            }
        } else {
            null
        }
        val kyber = if (counts.kyber < LOW) List(TARGET - counts.kyber) { newKyber(now, lastResort = false) } else null

        if (registrationId == null &&
            signed == null &&
            lastResort == null &&
            oneTime == null &&
            kyber == null
        ) {
            return null
        }
        return KeyUploadRequest(registrationId, signed, lastResort, oneTime, kyber)
    }

    private fun newKyber(now: Long, lastResort: Boolean): SignedKeyJson {
        val id = store.allocateKeyIds(SignalStore.KeyKind.Kyber, 1).first
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val sig = store.identityKeyPair.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeKyberPreKey(KyberPreKeyRecord(id, now, pair, sig), lastResort)
        return SignedKeyJson(id, b64(pair.publicKey.serialize()), b64(sig))
    }

    /** Deletes signed and last-resort private keys replaced more than 30 days ago. */
    private fun pruneOld(now: Long) {
        val signed = dao.signedPreKeys()
        val currentSigned = signed.maxOfOrNull { it.id }
        signed.filter {
            it.id != currentSigned && now - it.createdAt > RETAIN_MS
        }.forEach { dao.deleteSignedPreKey(it.id) }
        val lastResorts = dao.kyberPreKeys().filter { it.lastResort }
        val currentLastResort = lastResorts.maxOfOrNull { it.id }
        lastResorts.filter { it.id != currentLastResort && now - it.createdAt > RETAIN_MS }.forEach {
            dao.deleteKyberPreKey(it.id)
            dao.deleteUsedBaseKeys(it.id)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val LOW = 20
        const val TARGET = 100
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val ROTATE_MS = 7 * 24 * 60 * 60 * 1000L
        const val RETAIN_MS = 30 * 24 * 60 * 60 * 1000L
        private const val LAST_CHECK = "e2e.lastKeyCheckAt"
        private const val REGISTERED = "e2e.keysRegistered"
    }
}
