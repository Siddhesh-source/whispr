package dev.whispr.data

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * An in-memory stand-in for the whole Go server, for several real devices
 * at once: keys, user lookup, attachments, and the WebSocket relay with
 * per-recipient queues, acks, delivery receipts and send_multi fan-out. The
 * bearer token is the user ID. It knows nothing about groups, like the real
 * server. Everything it receives is recorded for no-plaintext checks.
 */
class FakeRelay : Dispatcher() {
    data class Stored(
        val seq: Long,
        val recipient: String,
        val frame: String,
        val messageId: String,
        val sender: String,
    )

    val keyServer = FakeKeyServer()
    private val names = ConcurrentHashMap<String, String>()
    private val identities = ConcurrentHashMap<String, ByteArray>()

    /** Every queued envelope, ever (also after ack). */
    val history = CopyOnWriteArrayList<Stored>()
    private val queues = mutableMapOf<String, MutableList<Stored>>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val accepted = mutableMapOf<String, Long>() // "sender/id" -> seq

    /** "recipient/messageId" of every envelope the recipient acknowledged (stored on the device). */
    val acked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Everything that crossed the "network", both directions. */
    val wire = CopyOnWriteArrayList<String>()

    /** Raw frames the devices sent (send and send_multi). */
    val sent = CopyOnWriteArrayList<JsonObject>()

    /** Stored attachment blobs, exactly as uploaded. */
    val blobs = ConcurrentHashMap<String, ByteArray>()

    /** Envelopes matching this are queued but not delivered until [release]. */
    @Volatile var defer: (Stored) -> Boolean = { false }
    private val deferred = mutableListOf<Stored>()

    private var nextSeq = 1L
    private val json = Json { ignoreUnknownKeys = true }

    fun register(userId: String, name: String, identityKey: ByteArray) {
        names[userId] = name
        identities[userId] = identityKey
        keyServer.register(userId, identityKey)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val user = request.headers["Authorization"]?.removePrefix("Bearer ") ?: return code(401)
        val body = request.body?.toByteArray()
        if (body != null && !path.startsWith("/v1/attachments")) wire += String(body)
        return when {
            path == "/v1/keys" && request.method == "PUT" -> {
                val upload = json.decodeFromString(KeyUploadRequest.serializer(), String(body!!))
                when (val r = runBlocking { keyServer.clientFor(user).upload(upload) }) {
                    is ApiResult.HttpError -> code(r.code)
                    else -> code(204)
                }
            }
            path == "/v1/keys/count" -> {
                val c = (runBlocking { keyServer.clientFor(user).counts() } as ApiResult.Success).body
                ok(json.encodeToString(KeyCountsResponse.serializer(), c))
            }
            path.startsWith("/v1/keys/") -> when (
                val b = runBlocking { keyServer.bundles.bundle(path.removePrefix("/v1/keys/")) }
            ) {
                is BundleResult.Success -> ok(json.encodeToString(BundleResponse.serializer(), b.bundle))
                BundleResult.NoKeys -> code(404, """{"code":"no_keys"}""")
                else -> code(404, """{"code":"unknown_user"}""")
            }
            path.startsWith("/v1/users/") -> {
                val id = path.removePrefix("/v1/users/")
                val name = names[id] ?: return code(404)
                val key = Base64.getEncoder().encodeToString(identities.getValue(id))
                ok("""{"user_id":"$id","display_name":"$name","identity_key":"$key"}""")
            }
            path == "/v1/attachments" && request.method == "POST" -> {
                val id = UUID.randomUUID().toString()
                blobs[id] = body ?: ByteArray(0)
                MockResponse.Builder().code(201).body("""{"id":"$id"}""").build()
            }
            path.startsWith("/v1/attachments/") -> {
                val blob = blobs[path.removePrefix("/v1/attachments/")] ?: return code(404)
                MockResponse.Builder().code(200).body(okio.Buffer().write(blob)).build()
            }
            path == "/v1/ws" -> MockResponse.Builder().webSocketUpgrade(Listener(user)).build()
            else -> code(404)
        }
    }

    private fun ok(body: String): MockResponse {
        wire += body
        return MockResponse.Builder().code(200).body(body).build()
    }

    private fun code(code: Int, body: String = "{}") = MockResponse.Builder().code(code).body(body).build()

    private inner class Listener(private val user: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets[user] = webSocket
            flush(user)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            wire += text
            val f = Json.parseToJsonElement(text).jsonObject
            when (f.s("type")) {
                "send" -> {
                    sent += f
                    webSocket.send(accept(f, listOf(f.s("recipient_id"))))
                }
                "send_multi" -> {
                    sent += f
                    webSocket.send(accept(f, f.getValue("recipient_ids").jsonArray.map { it.jsonPrimitive.content }))
                }
                "ack" -> ack(user, f.s("seq").toLong())
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets.remove(user, webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets.remove(user, webSocket)
        }

        private fun accept(f: JsonObject, recipients: List<String>): String = synchronized(this@FakeRelay) {
            val id = f.s("id")
            val key = "$user/$id"
            val seq = accepted[key] ?: run {
                val first = nextSeq
                for (r in recipients) {
                    enqueue(r, user, id, f.s("conversation_id"), "envelope", null, f.s("payload"))
                }
                accepted[key] = first
                first
            }
            """{"type":"accepted","id":"$id","seq":$seq,"server_ts":"2026-01-01T00:00:00Z"}"""
        }.also { recipients.forEach(::flush) }
    }

    @Synchronized
    private fun enqueue(
        recipient: String,
        sender: String,
        id: String,
        conversation: String,
        kind: String,
        refId: String?,
        payload: String,
    ) {
        val seq = nextSeq++
        val frame = buildJsonObject {
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
        val stored = Stored(seq, recipient, frame, id, sender)
        history += stored
        queues.getOrPut(recipient) { mutableListOf() } += stored
    }

    private fun ack(user: String, seq: Long) {
        val receiptTo = synchronized(this) {
            val queue = queues[user] ?: return
            val acked = queue.firstOrNull { it.seq == seq } ?: return
            queue.remove(acked)
            this.acked += "$user/${acked.messageId}"
            val f = Json.parseToJsonElement(acked.frame).jsonObject
            if (f.s("kind") != "envelope") return
            enqueue(
                acked.sender,
                user,
                UUID.randomUUID().toString(),
                f.s("conversation_id"),
                "delivered",
                acked.messageId,
                "",
            )
            acked.sender
        }
        flush(receiptTo)
    }

    /** Pushes everything queued for [user] that isn't deferred. Redelivery is fine: clients dedup. */
    private fun flush(user: String) {
        val socket = sockets[user] ?: return
        val frames = synchronized(this) {
            val queue = queues[user].orEmpty()
            queue.filter { s ->
                if (s in deferred) return@filter false
                if (defer(s)) {
                    deferred += s
                    false
                } else {
                    true
                }
            }.map { it.frame }
        }
        frames.forEach {
            wire += it
            socket.send(it)
        }
    }

    /** Delivers what [defer] held back, and stops deferring. */
    fun release() {
        val users = synchronized(this) {
            defer = { false }
            val u = deferred.map { it.recipient }.toSet()
            deferred.clear()
            u
        }
        users.forEach(::flush)
    }

    /** Payload bytes of every envelope ever queued for [recipient]. */
    fun payloadsFor(recipient: String): List<ByteArray> = history.filter { it.recipient == recipient }.map {
        Base64.getDecoder().decode(Json.parseToJsonElement(it.frame).jsonObject.s("payload"))
    }

    fun disconnect(user: String) {
        sockets.remove(user)?.close(1001, "going away")
    }
}
