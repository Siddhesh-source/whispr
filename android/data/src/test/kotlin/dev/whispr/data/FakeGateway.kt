package dev.whispr.data

import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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

/**
 * A scripted stand-in for the Go gateway, speaking the same JSON protocol.
 * It records what the client sends and lets tests push frames or drop the
 * connection at chosen moments.
 */
class FakeGateway : Dispatcher() {
    val sends = CopyOnWriteArrayList<JsonObject>()
    val acks = CopyOnWriteArrayList<Long>()
    val transients = CopyOnWriteArrayList<JsonObject>()
    val connections = CopyOnWriteArrayList<WebSocket>()

    /** Reply to sends automatically. Return null to stay silent. */
    @Volatile var onSend: (JsonObject) -> String? = { f -> accepted(f.s("id")) }

    @Volatile var rejectAuth = false
    val users = mutableMapOf<String, String>() // userId -> display name
    val keys = mutableMapOf<String, ByteArray>() // userId -> identity key the server reports
    val usernames = mutableMapOf<String, String>() // username -> userId

    private val accepted = mutableSetOf<String>()
    private var nextSeq = 1L

    val current: WebSocket? get() = connections.lastOrNull()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path.startsWith("/v1/usernames/") -> usernames[path.removePrefix("/v1/usernames/")]?.let(::userResponse)
                ?: notFound()
            path.startsWith("/v1/users/") -> userResponse(path.removePrefix("/v1/users/"))
            path == "/v1/ws" && rejectAuth -> MockResponse.Builder().code(401).body("{}").build()
            path == "/v1/ws" -> MockResponse.Builder().webSocketUpgrade(listener).build()
            else -> notFound()
        }
    }

    private fun userResponse(id: String): MockResponse {
        val name = users[id] ?: return notFound()
        val key = Base64.getEncoder().encodeToString(keys[id] ?: ByteArray(33) { 5 })
        val body = """{"user_id":"$id","display_name":"$name","identity_key":"$key"}"""
        return MockResponse.Builder().code(200).body(body).build()
    }

    private fun notFound() = MockResponse.Builder().code(404).body("{}").build()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val f = Json.parseToJsonElement(text).jsonObject
            when (f.s("type")) {
                "send" -> {
                    sends += f
                    onSend(f)?.let(webSocket::send)
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

    @Synchronized
    fun envelope(
        sender: String,
        payloadJson: String,
        id: String = UUID.randomUUID().toString(),
        conversation: String = UUID.randomUUID().toString(),
        seq: Long = nextSeq++,
    ): String = frame(
        seq,
        id,
        conversation,
        sender,
        "envelope",
        null,
        Base64.getEncoder().encodeToString(payloadJson.toByteArray()),
    )

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
        checkNotNull(current) { "no connection" }.send(frame)
    }

    /** Ends the current connection; the client must reconnect. */
    fun drop() {
        current?.close(1001, "going away")
    }
}

fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.payloadText(): String = String(Base64.getDecoder().decode(s("payload")))

/** The "body" of a text payload. */
fun JsonObject.payloadBody(): String = Json.parseToJsonElement(payloadText()).jsonObject.s("body")
