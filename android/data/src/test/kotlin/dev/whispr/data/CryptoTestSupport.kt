package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.crypto.BundleSource
import dev.whispr.data.crypto.KeyServer
import dev.whispr.data.crypto.PreKeyMaintainer
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.IdentityKeyPairSource
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.BundleResponse
import dev.whispr.data.network.BundleResult
import dev.whispr.data.network.KeyCountsResponse
import dev.whispr.data.network.KeyUploadRequest
import dev.whispr.data.network.KeysApi
import dev.whispr.data.network.KyberKeyJson
import dev.whispr.data.network.OneTimeKeyJson
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.SignedKeyJson
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * An in-memory stand-in for the server's /v1/keys endpoints with the same
 * semantics: one-time keys are handed out once, then the last-resort Kyber
 * key; a user without a complete key set has no keys.
 */
class FakeKeyServer {
    private class Keys(val identity: ByteArray) {
        var registrationId: Int? = null
        var signed: SignedKeyJson? = null
        var lastResort: SignedKeyJson? = null
        val oneTime = ArrayDeque<OneTimeKeyJson>()
        val kyber = ArrayDeque<SignedKeyJson>()
    }

    private val users = mutableMapOf<String, Keys>()
    var countCalls = 0
        private set
    val uploads = mutableListOf<KeyUploadRequest>()

    /** Set to make every upload fail with this HTTP status. */
    var failUploadsWith: Int? = null

    fun register(userId: String, identity: ByteArray) {
        users.getOrPut(userId) { Keys(identity) }
    }

    fun oneTimeLeft(userId: String) = users.getValue(userId).oneTime.size

    fun drainOneTimeKeys(userId: String) {
        users.getValue(userId).oneTime.clear()
        users.getValue(userId).kyber.clear()
    }

    fun clientFor(userId: String): KeyServer = object : KeyServer {
        override suspend fun counts(): ApiResult<KeyCountsResponse> {
            countCalls++
            val k = users.getValue(userId)
            return ApiResult.Success(
                KeyCountsResponse(k.registrationId, k.signed?.keyId, k.lastResort?.keyId, k.oneTime.size, k.kyber.size),
            )
        }

        override suspend fun upload(request: KeyUploadRequest): ApiResult<Unit> {
            failUploadsWith?.let { return ApiResult.HttpError(it) }
            uploads += request
            val k = users.getValue(userId)
            request.registrationId?.let { k.registrationId = it }
            request.signedPreKey?.let { k.signed = it }
            request.lastResort?.let { k.lastResort = it }
            request.oneTime?.let { k.oneTime.addAll(it) }
            request.kyber?.let { k.kyber.addAll(it) }
            return ApiResult.Success(Unit)
        }
    }

    val bundles = BundleSource { target ->
        val k = users[target] ?: return@BundleSource BundleResult.UnknownUser
        val reg = k.registrationId
        val signed = k.signed
        val lastResort = k.lastResort
        if (reg == null || signed == null || lastResort == null) return@BundleSource BundleResult.NoKeys
        val kyber = k.kyber.removeFirstOrNull()
        BundleResult.Success(
            BundleResponse(
                userId = target,
                deviceId = 1,
                registrationId = reg,
                identityKey = Base64.getEncoder().encodeToString(k.identity),
                signedPreKey = signed,
                oneTime = k.oneTime.removeFirstOrNull(),
                kyber = (kyber ?: lastResort).let { KyberKeyJson(it.keyId, it.publicKey, it.signature, kyber == null) },
            ),
        )
    }
}

/** Real libsignal crypto for an engine under test, talking HTTP to [url] (fake or live server). */
class DeviceCrypto(
    db: WhisprDatabase,
    client: OkHttpClient,
    url: String,
    tokens: TokenSource,
    identity: IdentityKeyPairSource,
    localUser: suspend () -> String,
) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val keys = KeysApi(client, ServerConfig(url), tokens)
    val store = SignalStore(db.cryptoDao())
    val crypto = SessionCrypto(db, store, identity, { keys.bundle(it) }, dispatcher, localUser)
    val maintainer = PreKeyMaintainer(db, store, identity, keys, dispatcher)
}

/** One phone: its own database, identity, libsignal store and crypto, against [server]. */
class CryptoDevice(
    val server: FakeKeyServer,
    val userId: String = UUID.randomUUID().toString(),
    val identity: IdentityKeyPair = IdentityKeyPair.generate(),
    dbFile: File? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    val db: WhisprDatabase = (
        if (dbFile == null) {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WhisprDatabase::class.java)
        } else {
            Room.databaseBuilder(ApplicationProvider.getApplicationContext(), WhisprDatabase::class.java, dbFile.path)
        }
        ).build()
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val store = SignalStore(db.cryptoDao(), clock)
    var bundleSource: BundleSource = server.bundles
    val crypto = SessionCrypto(db, store, { identity }, { bundleSource.bundle(it) }, dispatcher) { userId }
    val maintainer = PreKeyMaintainer(db, store, { identity }, server.clientFor(userId), dispatcher, clock)

    init {
        server.register(userId, identity.publicKey.serialize())
    }

    /** Registers keys on the fake server. */
    fun publishKeys() = runBlocking { check(maintainer.maintain(force = true)) }

    /** Adds [other] as a contact with their identity key pinned (as a QR scan would). */
    fun pin(other: CryptoDevice, key: ByteArray = other.identity.publicKey.serialize()) = runBlocking {
        db.contactDao().upsert(ContactEntity(other.userId, "peer", key, 0))
    }

    fun close() {
        db.close()
        dispatcher.close()
    }
}
