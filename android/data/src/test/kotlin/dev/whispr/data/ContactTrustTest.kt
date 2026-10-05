package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.contacts.ContactCard
import dev.whispr.data.contacts.ContactQr
import dev.whispr.data.contacts.RoomContactsRepository
import dev.whispr.data.contacts.SafetyNumbers
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair

/** Pinning, requests, key changes and verification against a scripted server. */
@RunWith(RobolectricTestRunner::class)
class ContactTrustTest {
    @get:Rule val tmp = TemporaryFolder()

    private val me = UserId(UUID.randomUUID().toString())
    private val peer = UserId(UUID.randomUUID().toString())
    private val peerPair = IdentityKeyPair.generate()
    private val peerKey = peerPair.publicKey.serialize()

    private val server = MockWebServer()
    private val gateway = FakeGateway()
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WhisprDatabase::class.java,
    ).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var contacts: RoomContactsRepository
    private lateinit var messaging: RoomMessagingRepository
    private lateinit var identity: LibsignalIdentityRepository
    private lateinit var origin: String

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = gateway
        server.start()
        db.accountDao().upsert(AccountEntity(userId = me.value, displayName = "Me", avatarPath = null))
        gateway.users[peer.value] = "Peer"
        gateway.keys[peer.value] = peerKey
        gateway.peer(peer.value, "Peer", peerPair)
        val client = OkHttpClient()
        val tokens = object : TokenSource {
            override suspend fun bearerToken() = "token"
            override fun invalidate() = Unit
        }
        val api = WhisprApi(client, ServerConfig(server.url("/").toString()), tokens)
        origin = api.origin
        val accounts = object : AccountRepository {
            override fun observeAccount() = flow {
                db.accountDao().observe().collect { e ->
                    emit(e?.let { Account(it.userId?.let(::UserId), it.displayName, null) })
                }
            }
            override suspend fun getAccount() = db.accountDao().get()?.let {
                Account(it.userId?.let(::UserId), it.displayName, null)
            }
            override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) = Unit
            override suspend fun markRegistered(userId: UserId) = Unit
        }
        identity = LibsignalIdentityRepository(SecretFileStore(tmp.root, SoftwareKeyWrapper()), Dispatchers.Unconfined)
        gateway.registerSelf(me.value, identity.getOrCreatePublicKey())
        val url = server.url("/").toString()
        val crypto = DeviceCrypto(db, client, url, tokens, identity) { me.value }
        lateinit var engineRef: MessagingEngine
        contacts = RoomContactsRepository(db, api, accounts, identity, allowInsecureLoopback = true) {
            engineRef.onKeyChangeAcknowledged(it)
        }
        val engine = MessagingEngine(
            db,
            client,
            api,
            tokens,
            accounts,
            object : ConnectivityRepository {
                override val isOnline = flowOf(true)
            },
            scope,
            crypto.crypto,
            crypto.maintainer,
            EngineTimings(resendAfterMs = 500, backoffBaseMs = 50, backoffMaxMs = 200),
        ).also {
            engineRef = it
            it.setForeground(true)
            it.start()
        }
        messaging = RoomMessagingRepository(db, engine, accounts, RoomSettingsRepository(db.settingDao()))
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        gateway.closePeers()
        db.close()
    }

    private fun peerCode(key: ByteArray = peerKey, serverOrigin: String = origin) =
        ContactQr.encode(ContactCard(UUID.fromString(peer.value), key, serverOrigin))

    @Test
    fun scanPinsKeyAndSendsContactRequestWithOurKey() = runBlocking {
        val result = contacts.addFromCode(peerCode())
        assertTrue(result is AddContactResult.Added)
        val c = contacts.contact(peer)!!
        assertArrayEquals(peerKey, c.identityKey)
        assertEquals(TrustState.Unverified, c.trust) // scanning alone is not verification
        assertFalse(c.isRequest)

        eventually("request sent") { gateway.sends.any { it.s("recipient_id") == peer.value } }
        val request = PayloadCodec.decode(
            gateway.payloadText(gateway.sends.first()).toByteArray(),
        ) as Payload.ContactRequest
        assertArrayEquals(identity.getOrCreatePublicKey(), Base64.getDecoder().decode(request.key))
    }

    @Test
    fun serverDisagreeingWithScannedKeyAddsNothing() = runBlocking {
        gateway.keys[peer.value] = IdentityKeyPair.generate().publicKey.serialize() // a lying server
        assertEquals(AddContactResult.KeyMismatch, contacts.addFromCode(peerCode()))
        assertNull(contacts.contact(peer))
    }

    @Test
    fun hostileCodesAddNothing() = runBlocking {
        assertEquals(AddContactResult.InvalidCode, contacts.addFromCode("https://evil.example"))
        assertEquals(AddContactResult.InvalidCode, contacts.addFromCode("whispr:AAAA"))
        assertEquals(
            AddContactResult.DifferentServer,
            contacts.addFromCode(peerCode(serverOrigin = "https://evil.example")),
        )
        val myCode = contacts.myContactCode()
        assertEquals(AddContactResult.IsSelf, contacts.addFromCode(myCode))
        assertTrue(contacts.observeContacts().first().isEmpty())
    }

    @Test
    fun keyChangeIsFlaggedBlocksSendingAndNeedsAcknowledgement() = runBlocking {
        contacts.addFromCode(peerCode())
        contacts.setVerified(peer, true)
        assertEquals(TrustState.Verified, contacts.contact(peer)!!.trust)

        val newKey = IdentityKeyPair.generate().publicKey.serialize()
        gateway.keys[peer.value] = newKey
        contacts.refreshKey(peer)

        val changed = contacts.contact(peer)!!
        assertEquals("verified state cleared", TrustState.KeyChanged, changed.trust)
        assertArrayEquals("pinned key NOT replaced", peerKey, changed.identityKey)
        assertFalse("sending blocked", messaging.sendText(peer, "hello?"))
        contacts.setVerified(peer, true)
        assertEquals("cannot verify before acknowledging", TrustState.KeyChanged, contacts.contact(peer)!!.trust)
        assertNull("no safety number for an unacknowledged change", contacts.safetyNumber(peer))

        contacts.acknowledgeKeyChange(peer)
        val acked = contacts.contact(peer)!!
        assertEquals(TrustState.Unverified, acked.trust)
        assertArrayEquals(newKey, acked.identityKey)
        assertTrue(messaging.sendText(peer, "ok now"))
    }

    @Test
    fun acknowledgingPinsTheLatestServerKeyNotAStaleOne() = runBlocking {
        contacts.addFromCode(peerCode())
        // The server flips to a fake key, then back to the real one.
        gateway.keys[peer.value] = IdentityKeyPair.generate().publicKey.serialize()
        contacts.refreshKey(peer)
        assertEquals(TrustState.KeyChanged, contacts.contact(peer)!!.trust)
        gateway.keys[peer.value] = peerKey
        contacts.refreshKey(peer)
        // Still flagged (something odd happened; never cleared silently)...
        assertEquals(TrustState.KeyChanged, contacts.contact(peer)!!.trust)
        // ...but accepting must not pin the fake key the server showed earlier.
        contacts.acknowledgeKeyChange(peer)
        assertArrayEquals(peerKey, contacts.contact(peer)!!.identityKey)
        assertEquals(TrustState.Unverified, contacts.contact(peer)!!.trust)
    }

    @Test
    fun rescanningWithDifferentKeyIsAKeyChangeNotAnOverwrite() = runBlocking {
        contacts.addFromCode(peerCode())
        val other = IdentityKeyPair.generate().publicKey.serialize()
        gateway.keys[peer.value] = other
        assertEquals(AddContactResult.KeyMismatch, contacts.addFromCode(peerCode(key = other)))
        assertArrayEquals(peerKey, contacts.contact(peer)!!.identityKey)
        assertEquals(TrustState.KeyChanged, contacts.contact(peer)!!.trust)
    }

    @Test
    fun incomingContactRequestBecomesARequestWithPinnedKey() = runBlocking {
        eventually("connected") { gateway.current != null }
        gateway.push(gateway.envelope(peer.value, request(peerKey)))
        eventually("request stored") { contacts.contact(peer)?.isRequest == true }
        assertArrayEquals(peerKey, contacts.contact(peer)!!.identityKey)
        assertEquals(TrustState.Unverified, contacts.contact(peer)!!.trust)

        contacts.acceptRequest(peer)
        assertFalse(contacts.contact(peer)!!.isRequest)
    }

    @Test
    fun requestKeyDisagreeingWithServerIsFlagged() = runBlocking {
        eventually("connected") { gateway.current != null }
        val forged = IdentityKeyPair.generate().publicKey.serialize()
        gateway.push(gateway.envelope(peer.value, request(forged)))
        eventually("flagged") { contacts.contact(peer)?.trust == TrustState.KeyChanged }
    }

    @Test
    fun requestFromKnownContactWithNewKeyIsFlagged() = runBlocking {
        contacts.addFromCode(peerCode())
        eventually("connected") { gateway.current != null }
        gateway.push(gateway.envelope(peer.value, request(IdentityKeyPair.generate().publicKey.serialize())))
        eventually("flagged") { contacts.contact(peer)?.trust == TrustState.KeyChanged }
        assertArrayEquals(peerKey, contacts.contact(peer)!!.identityKey)
    }

    @Test
    fun messageFromStrangerIsAMessageRequestAndDeclineRemovesIt() = runBlocking {
        eventually("connected") { gateway.current != null }
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"hello stranger"}"""))
        eventually("request") { contacts.contact(peer)?.isRequest == true }
        contacts.declineRequest(peer)
        assertNull(contacts.contact(peer))
        assertTrue(messaging.observeConversations().first().isEmpty())
    }

    @Test
    fun addByUsernamePinsServerKey() = runBlocking {
        gateway.usernames["peer.42"] = peer.value
        assertTrue(contacts.addByUsername("Peer.42") is AddContactResult.Added)
        assertArrayEquals(peerKey, contacts.contact(peer)!!.identityKey)
        assertEquals(AddContactResult.InvalidId, contacts.addByUsername("peer"))
        assertEquals(AddContactResult.NotFound, contacts.addByUsername("nobody.11"))
    }

    @Test
    fun scannedSafetyNumberMustMatch() = runBlocking {
        contacts.addFromCode(peerCode())
        // What the peer would show: the same computation from their side.
        val myKey = identity.getOrCreatePublicKey()
        val theirs = SafetyNumbers.compute(UUID.fromString(peer.value), peerKey, UUID.fromString(me.value), myKey)
        val mine = contacts.safetyNumber(peer)!!
        assertEquals(mine.digits, theirs.digits)

        val wrong = SafetyNumbers.compute(
            UUID.fromString(peer.value),
            IdentityKeyPair.generate().publicKey.serialize(),
            UUID.fromString(me.value),
            myKey,
        )
        assertEquals(VerifyResult.Mismatch, contacts.verifyScanned(peer, wrong.qrCode))
        assertEquals(VerifyResult.InvalidCode, contacts.verifyScanned(peer, "whispr-sn:!!!"))
        assertEquals(TrustState.Unverified, contacts.contact(peer)!!.trust)

        assertEquals(VerifyResult.Match, contacts.verifyScanned(peer, theirs.qrCode))
        assertEquals(TrustState.Verified, contacts.contact(peer)!!.trust)
    }

    private fun request(key: ByteArray) =
        """{"t":"contact_request","name":"Peer","key":"${Base64.getEncoder().encodeToString(key)}"}"""

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
