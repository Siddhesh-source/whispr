package dev.whispr.data

import dev.whispr.data.crypto.DecryptResult
import dev.whispr.data.crypto.EncryptResult
import dev.whispr.data.crypto.SessionStatus
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.BundleResponse
import dev.whispr.data.network.BundleResult
import dev.whispr.data.network.KeyCountsResponse
import dev.whispr.data.network.KeyUploadRequest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * A scripted stand-in for the Go gateway, speaking the same JSON protocol
 * and serving the same /v1/keys endpoints. Other users are real libsignal
 * peers ([CryptoDevice]): envelopes pushed "from" them are genuinely
 * encrypted, and what the device sends is decrypted by them, so tests assert
 * on content while every byte on the wire is ciphertext. Every frame and
 * HTTP body is recorded in [wire] for no-plaintext checks.
 */
class FakeGateway : Dispatcher() {
    val sends = CopyOnWriteArrayList<JsonObject>()
    val acks = CopyOnWriteArrayList<Long>()
    val transients = CopyOnWriteArrayList<JsonObject>()
    val connections = CopyOnWriteArrayList<WebSocket>()

    /** Everything that crossed the "network", both directions. */
    val wire = CopyOnWriteArrayList<String>()

    /** Reply to sends automatically. Return null to stay silent. */
    @Volatile var onSend: (JsonObject) -> String? = { f -> accepted(f.s("id")) }

    @Volatile var rejectAuth = false
    val users = mutableMapOf<String, String>() // userId -> display name
    val keys = mutableMapOf<String, ByteArray>() // userId -> identity key the server reports
    val usernames = mutableMapOf<String, String>() // username -> userId

    val keyServer = FakeKeyServer()
    private val peers = ConcurrentHashMap<String, CryptoDevice>()
    private val opened = ConcurrentHashMap<String, String>()

    /** The device under test (whose keys PUT /v1/keys uploads). */
    @Volatile var self: String = ""

    private val accepted = mutableSetOf<String>()
    private var nextSeq = 1L
    private val json = Json { ignoreUnknownKeys = true }

    val current: WebSocket? get() = connections.lastOrNull()

    fun registerSelf(userId: String, identityKey: ByteArray) {
        self = userId
        keyServer.register(userId, identityKey)
    }

    /** A real libsignal user the device can talk to; the server reports [identity] for them. */
    fun peer(
        userId: String,
        name: String = "Peer",
        identity: IdentityKeyPair = IdentityKeyPair.generate(),
    ): CryptoDevice = peers.getOrPut(userId) {
        users.putIfAbsent(userId, name)
        keys.putIfAbsent(userId, identity.publicKey.serialize())
        CryptoDevice(keyServer, userId, identity).also { it.publishKeys() }
    }

    fun closePeers() = peers.values.forEach { it.close() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        request.body?.utf8()?.let { wire += it }
        val response = when {
            path == "/v1/keys" && request.method == "PUT" -> {
                val upload = json.decodeFromString(KeyUploadRequest.serializer(), request.body!!.utf8())
                when (val r = runBlocking { keyServer.clientFor(self).upload(upload) }) {
                    is ApiResult.HttpError -> MockResponse.Builder().code(r.code).body("{}").build()
                    else -> MockResponse.Builder().code(204).build()
                }
            }
            path == "/v1/keys/count" -> {
                val c = (runBlocking { keyServer.clientFor(self).counts() } as ApiResult.Success).body
                ok(json.encodeToString(KeyCountsResponse.serializer(), c))
            }
            path.startsWith("/v1/keys/") -> when (
                val b = runBlocking {
                    keyServer.bundles.bundle(path.removePrefix("/v1/keys/"))
                }
            ) {
                is BundleResult.Success -> ok(json.encodeToString(BundleResponse.serializer(), b.bundle))
                BundleResult.NoKeys -> MockResponse.Builder().code(404).body("""{"code":"no_keys"}""").build()
                else -> MockResponse.Builder().code(404).body("""{"code":"unknown_user"}""").build()
            }
            path.startsWith("/v1/usernames/") -> usernames[path.removePrefix("/v1/usernames/")]?.let(::userResponse)
                ?: notFound()
            path.startsWith("/v1/users/") -> userResponse(path.removePrefix("/v1/users/"))
            path == "/v1/ws" && rejectAuth -> MockResponse.Builder().code(401).body("{}").build()
            path == "/v1/ws" -> MockResponse.Builder().webSocketUpgrade(listener).build()
            else -> notFound()
        }
        return response
    }

    private fun ok(body: String): MockResponse {
        wire += body
        return MockResponse.Builder().code(200).body(body).build()
    }

    private fun userResponse(id: String): MockResponse {
        val name = users[id] ?: return notFound()
        val key = Base64.getEncoder().encodeToString(keys[id] ?: ByteArray(33) { 5 })
        return ok("""{"user_id":"$id","display_name":"$name","identity_key":"$key"}""")
    }

    private fun notFound() = MockResponse.Builder().code(404).body("{}").build()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            wire += text
            val f = Json.parseToJsonElement(text).jsonObject
            when (f.s("type")) {
                "send" -> {
                    sends += f
                    onSend(f)?.let { reply ->
                        wire += reply
                        webSocket.send(reply)
                    }
                }
                "ack" -> acks += f.s("seq").toLong()
                "transient" -> transients += f
            }
        }
    }

    /** Server-side dedup like the real gateway: same ID, same answer. */
    @Synchronized
    fun accepted(id: String): String {
        accepted += id
        return """{"type":"accepted","id":"$id","seq":${nextSeq++},"server_ts":"2026-01-01T00:00:00Z"}"""
    }

    /** An envelope from [sender] (a libsignal peer) carrying [payloadJson], encrypted to the device. */
    fun envelope(
        sender: String,
        payloadJson: String,
        id: String = UUID.randomUUID().toString(),
        conversation: String = UUID.randomUUID().toString(),
        seq: Long = nextSeq(),
    ): String = frame(seq, id, conversation, sender, "envelope", null, b64(seal(sender, payloadJson)))

    /** An envelope with raw (unencrypted, possibly hostile) payload bytes. */
    fun rawEnvelope(
        sender: String,
        payload: ByteArray,
        id: String = UUID.randomUUID().toString(),
        seq: Long = nextSeq(),
    ): String = frame(seq, id, UUID.randomUUID().toString(), sender, "envelope", null, b64(payload))

    /** A typing (transient) frame from [sender], encrypted over the existing session. */
    fun typingFrom(sender: String): String {
        val payload = runBlocking { peer(sender).crypto.encryptTransient(self, """{"t":"typing"}""".toByteArray()) }
        return """{"type":"transient","sender_id":"$sender","conversation_id":"x","payload":"${b64(
            checkNotNull(payload),
        )}"}"""
    }

    /** Encrypts [payloadJson] from [sender] to the device, waiting until the device has published keys. */
    fun seal(sender: String, payloadJson: String): ByteArray = runBlocking {
        val p = peer(sender)
        val deadline = System.currentTimeMillis() + 10_000
        while (p.crypto.ensureSession(self) != SessionStatus.Ready) {
            check(System.currentTimeMillis() < deadline) { "device never published keys" }
            Thread.sleep(25)
        }
        (p.crypto.encrypt(self, payloadJson.toByteArray()) { it } as EncryptResult.Ok).value
    }

    @Synchronized
    private fun nextSeq() = nextSeq++

    @Synchronized
    fun delivered(sender: String, refId: String): String =
        frame(nextSeq++, UUID.randomUUID().toString(), UUID.randomUUID().toString(), sender, "delivered", refId, "")

    private fun frame(
        seq: Long,
        id: String,
        conversation: String,
        sender: String,
        kind: String,
        refId: String?,
        payload: String,
    ) = buildJsonObject {
        put("type", "envelope")
        put("seq", seq)
        put("id", id)
        put("conversation_id", conversation)
        put("sender_id", sender)
        put("kind", kind)
        refId?.let { put("ref_id", it) }
        put("client_ts", "2026-01-01T00:00:00Z")
        put("server_ts", "2026-01-01T00:00:01Z")
        put("payload", payload)
    }.toString()

    fun push(frame: String) {
        wire += frame
        checkNotNull(current) { "no connection" }.send(frame)
    }

    /** Ends the current connection; the client must reconnect. */
    fun drop() {
        current?.close(1001, "going away")
    }

    /** The plaintext of a send, as its recipient (a libsignal peer) decrypts it. Resends decrypt once. */
    fun payloadText(f: JsonObject): String = opened.getOrPut(f.s("id")) {
        val recipient = peer(f.s("recipient_id"))
        val bytes = Base64.getDecoder().decode(f.s("payload"))
        when (val r = runBlocking { recipient.crypto.decrypt(self, bytes) { String(it) } }) {
            is DecryptResult.Ok -> r.value
            else -> error("peer could not decrypt ${f.s("id")}: $r")
        }
    }

    /** The "body" of a text send. */
    fun payloadBody(f: JsonObject): String = Json.parseToJsonElement(payloadText(f)).jsonObject.s("body")

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}

fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content
