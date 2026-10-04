package dev.whispr.data.messaging

import dev.whispr.data.auth.TokenSource
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.signal.libsignal.protocol.IdentityKey

/** A newly received message, for local notifications. */
data class IncomingMessage(val conversationId: ConversationId, val senderName: String, val text: String)

/** Tuning knobs, shortened in tests. */
data class EngineTimings(
    val openTimeoutMs: Long = 15_000,
    val resendAfterMs: Long = 10_000,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    val wakeWindowMs: Long = 30_000,
    val typingVisibleMs: Long = 6_000,
)

/**
 * Owns the WebSocket. Keeps a connection while it is wanted (app in
 * foreground, unsent outbox entries, or a push wake-up window), reconnects
 * with exponential backoff, drains the outbox in order, and writes incoming
 * envelopes to the database *before* acknowledging them, so a crash at any
 * point leads to redelivery rather than loss, and redelivery is deduplicated
 * by message ID.
 */
class MessagingEngine(
    private val db: WhisprDatabase,
    baseClient: OkHttpClient,
    private val api: WhisprApi,
    private val tokens: TokenSource,
    private val accounts: AccountRepository,
    private val connectivity: ConnectivityRepository,
    private val scope: CoroutineScope,
    private val timings: EngineTimings = EngineTimings(),
    private val random: Random = Random.Default,
) {
    private val wsClient = baseClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS) // client-side heartbeat
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // frames carry their "type" as a default value
    }

    private val state = MutableStateFlow(ConnectionState.Offline)
    val connection: StateFlow<ConnectionState> = state.asStateFlow()

    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingMessage> = incomingFlow.asSharedFlow()

    /** Conversation ID → epoch millis until which the peer counts as typing. */
    private val typingUntil = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = typingUntil.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val wakeActive = MutableStateFlow(false)
    private var wakeJob: Job? = null

    @Volatile private var live: LiveSession? = null

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /** A push arrived: connect and stay connected for a short window. */
    fun wake() {
        wakeJob?.cancel()
        wakeActive.value = true
        wakeJob = scope.launch {
            delay(timings.wakeWindowMs)
            wakeActive.value = false
        }
    }

    /** Best effort; dropped if not connected. */
    fun sendTransient(peer: UserId, conversation: ConversationId, payload: Payload) {
        live?.sendTransient(peer.value, conversation.value, PayloadCodec.encode(payload))
    }

    fun start() {
        scope.launch {
            val wanted = combine(
                accounts.observeAccount().map { it?.isRegistered == true },
                connectivity.isOnline,
                foreground,
                wakeActive,
                db.outboxDao().observeCount().map { it > 0 },
            ) { registered, online, fg, wake, pending -> registered && online && (fg || wake || pending) }
            wanted.distinctUntilChanged().collectLatest { want ->
                if (!want) {
                    state.value = ConnectionState.Offline
                    return@collectLatest
                }
                var attempt = 0
                while (true) {
                    state.value = ConnectionState.Connecting
                    if (runSession()) attempt = 0
                    state.value = ConnectionState.Connecting
                    delay(backoffDelay(attempt++))
                }
            }
        }
    }

    internal fun backoffDelay(attempt: Int): Long {
        val exp = (timings.backoffBaseMs shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(timings.backoffMaxMs)
        // ±20% jitter so many clients don't reconnect in lockstep after an outage.
        return (exp * (0.8 + 0.4 * random.nextDouble())).toLong()
    }

    /** Runs one connection until it closes. Returns true if it opened. */
    private suspend fun runSession(): Boolean = coroutineScope {
        val me = accounts.getAccount()?.userId ?: return@coroutineScope false
        val token = tokens.bearerToken() ?: return@coroutineScope false
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(api.webSocketUrl()).header("Authorization", "Bearer $token").build()
        val ws = wsClient.newWebSocket(request, Listener(events))
        try {
            val first = withTimeoutOrNull(timings.openTimeoutMs) { events.receive() }
            if (first !is WsEvent.Open) {
                if (first is WsEvent.Failure && first.httpCode == HTTP_UNAUTHORIZED) tokens.invalidate()
                return@coroutineScope false
            }
            val session = LiveSession(ws)
            live = session
            state.value = ConnectionState.Connected
            val pump = launch { pumpOutbox(session) }
            for (event in events) {
                when (event) {
                    is WsEvent.Text -> if (!handleFrame(session, me, event.text)) break
                    else -> break
                }
            }
            pump.cancel()
            true
        } finally {
            live = null
            ws.cancel()
        }
    }

    /** Sends the outbox head; resends it until the server accepts or rejects it. */
    private suspend fun pumpOutbox(session: LiveSession) {
        db.outboxDao().observeHead().distinctUntilChangedBy { it?.messageId }.collectLatest { head ->
            if (head == null) return@collectLatest
            while (true) {
                session.send(
                    SendFrame(
                        id = head.messageId,
                        conversationId = head.conversationId,
                        recipientId = head.recipientId,
                        clientTs = Instant.ofEpochMilli(head.clientTs).toString(),
                        payload = Base64.getEncoder().encodeToString(head.payload),
                    ),
                )
                // Duplicates are harmless: the server deduplicates by message ID.
                delay(timings.resendAfterMs)
            }
        }
    }

    /** Returns false if the connection should be dropped. */
    private suspend fun handleFrame(session: LiveSession, me: UserId, text: String): Boolean {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val id = frame.str("id")
        when (frame.str("type")) {
            "accepted" -> id?.let { db.messagingTransactions().accepted(it) }
            "rejected" -> if (id != null && frame.str("code") !in RETRYABLE) db.messagingTransactions().rejected(id)
            "envelope" -> {
                val seq = frame["seq"]?.jsonPrimitive?.longOrNull ?: return false
                storeEnvelope(me, frame)
                // Only after the write committed: a crash before this line
                // means redelivery, which the unique message ID absorbs.
                session.ack(seq)
            }
            "transient" -> handleTransient(me, frame)
        }
        return true
    }

    private suspend fun storeEnvelope(me: UserId, frame: JsonObject) {
        val sender = frame.str("sender_id") ?: return
        when (frame.str("kind")) {
            "delivered" -> frame.str("ref_id")?.let { db.messageDao().markDelivered(it) }
            "envelope" -> when (val payload = decodePayload(frame)) {
                is Payload.Text -> {
                    val messageId = frame.str("id") ?: return
                    // Never trust the client-supplied conversation ID for 1:1
                    // chats: derive it, so a sender cannot inject messages
                    // into someone else's conversation.
                    val conversation = ConversationId.direct(me, UserId(sender))
                    val contact = ensureContact(sender)
                    val serverTs =
                        frame.str("server_ts")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
                    val inserted = db.messageDao().insert(
                        MessageEntity(
                            messageId = messageId,
                            conversationId = conversation.value,
                            peerId = sender,
                            outgoing = false,
                            body = payload.body,
                            timestamp = serverTs.toEpochMilli(),
                            status = null,
                        ),
                    )
                    if (inserted !=
                        -1L
                    ) {
                        incomingFlow.tryEmit(IncomingMessage(conversation, contact.displayName, payload.body))
                    }
                    typingUntil.update { it - conversation.value }
                }
                is Payload.Read -> db.messageDao().markReadByPeer(payload.ids, sender)
                is Payload.ContactRequest -> handleContactRequest(sender, payload)
                Payload.Typing, null -> Unit // unknown or misplaced: acknowledge and drop
            }
        }
    }

    private fun handleTransient(me: UserId, frame: JsonObject) {
        val sender = frame.str("sender_id") ?: return
        if (decodePayload(frame) != Payload.Typing) return
        val conversation = ConversationId.direct(me, UserId(sender))
        typingUntil.update { it + (conversation.value to System.currentTimeMillis() + timings.typingVisibleMs) }
    }

    /**
     * Someone added us. A new requester is stored as a request with the key
     * they sent, cross-checked against the server; any difference, or a
     * difference from a key we already pinned, is flagged, never adopted.
     */
    private suspend fun handleContactRequest(sender: String, request: Payload.ContactRequest) {
        val key = runCatching { Base64.getDecoder().decode(request.key) }.getOrNull()
            ?.takeIf { runCatching { IdentityKey(it) }.isSuccess } ?: return
        val existing = db.contactDao().get(sender)
        if (existing != null) {
            when {
                existing.identityKey.isEmpty() -> db.contactDao().upsert(existing.copy(identityKey = key))
                !existing.identityKey.contentEquals(key) -> db.contactDao().flagKeyChange(sender, key)
            }
            return
        }
        val server = (api.lookupUser(sender) as? ApiResult.Success)?.body
        db.contactDao().upsert(
            ContactEntity(
                userId = sender,
                // Prefer the server-validated name over the one in the payload.
                displayName = server?.displayName ?: request.name.take(MAX_NAME).ifBlank { UNKNOWN_CONTACT },
                identityKey = key,
                addedAt = System.currentTimeMillis(),
                isRequest = true,
            ),
        )
        val serverKey = server?.identityKey?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (serverKey != null && !serverKey.contentEquals(key)) db.contactDao().flagKeyChange(sender, serverKey)
    }

    /**
     * Messages from someone not yet added still arrive, as a message request.
     * An existing contact is never modified here; its pinned key stays put.
     */
    private suspend fun ensureContact(userId: String): ContactEntity {
        val existing = db.contactDao().get(userId)
        if (existing != null && existing.identityKey.isNotEmpty()) return existing
        val looked = (api.lookupUser(userId) as? ApiResult.Success)?.body
        val contact = (
            existing
                ?: ContactEntity(userId, UNKNOWN_CONTACT, ByteArray(0), System.currentTimeMillis(), isRequest = true)
            ).copy(
            displayName = looked?.displayName ?: existing?.displayName ?: UNKNOWN_CONTACT,
            identityKey = looked?.identityKey?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0),
        )
        db.contactDao().upsert(contact)
        return contact
    }

    private fun decodePayload(frame: JsonObject): Payload? = frame.str("payload")?.let {
        runCatching { Base64.getDecoder().decode(it) }.getOrNull()
    }?.let(PayloadCodec::decode)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private inner class LiveSession(private val ws: WebSocket) {
        fun send(frame: SendFrame) {
            ws.send(json.encodeToString(SendFrame.serializer(), frame))
        }

        fun ack(seq: Long) {
            ws.send(json.encodeToString(AckFrame.serializer(), AckFrame(seq = seq)))
        }

        fun sendTransient(recipient: String, conversation: String, payload: ByteArray) {
            ws.send(
                json.encodeToString(
                    TransientFrame.serializer(),
                    TransientFrame(
                        recipientId = recipient,
                        conversationId = conversation,
                        payload = Base64.getEncoder().encodeToString(payload),
                    ),
                ),
            )
        }
    }

    private sealed interface WsEvent {
        data object Open : WsEvent
        data class Text(val text: String) : WsEvent
        data object Closed : WsEvent
        data class Failure(val httpCode: Int?) : WsEvent
    }

    private class Listener(private val events: Channel<WsEvent>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(WsEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(WsEvent.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            events.trySend(WsEvent.Closed)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(WsEvent.Closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(WsEvent.Failure(response?.code))
        }
    }

    companion object {
        const val UNKNOWN_CONTACT = "Unknown contact"
        private const val MAX_NAME = 64
        private const val HTTP_UNAUTHORIZED = 401
        private const val MAX_SHIFT = 20

        // Retried by resending the outbox head later.
        private val RETRYABLE = setOf("rate_limited", "internal")
    }
}

@Serializable
private data class SendFrame(
    val type: String = "send",
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("client_ts") val clientTs: String,
    val payload: String,
)

@Serializable
private data class AckFrame(val type: String = "ack", val seq: Long)

@Serializable
private data class TransientFrame(
    val type: String = "transient",
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("conversation_id") val conversationId: String,
    val payload: String,
)
