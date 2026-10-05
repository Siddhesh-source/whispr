package dev.whispr.data.messaging

import dev.whispr.data.auth.TokenSource
import dev.whispr.data.crypto.EncryptResult
import dev.whispr.data.crypto.ParkReason
import dev.whispr.data.crypto.PreKeyMaintainer
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SessionStatus
import dev.whispr.data.db.GroupSendEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.ParkedRecipientEntity
import dev.whispr.data.db.SentEnvelopeEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.IncomingPipeline.Companion.isResend
import dev.whispr.data.messaging.IncomingPipeline.Companion.kind
import dev.whispr.data.messaging.IncomingPipeline.Companion.originalId
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
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
    /** How long a lane waits before retrying after a transient failure. */
    val parkRetryMs: Long = 30_000,
    /** How long a lane waits for a peer without keys before checking again. */
    val noKeysRetryMs: Long = 5 * 60_000,
    /** How often pending session resets are re-examined while connected. */
    val resetTickMs: Long = 5 * 60_000,
)

/**
 * Owns the WebSocket. Keeps a connection while it is wanted (app in
 * foreground, unsent outbox entries, or a push wake-up window), reconnects
 * with exponential backoff, and acknowledges incoming envelopes only after
 * everything they produced is committed, so a crash at any point leads to
 * redelivery rather than loss.
 *
 * Every payload is encrypted with libsignal ([SessionCrypto]): outgoing
 * entries are encrypted once, when they reach the head of their recipient's
 * lane, and resent byte-identically. A recipient whose lane is blocked (no
 * keys yet, key change awaiting acknowledgement) never stalls other chats.
 */
class MessagingEngine(
    private val db: WhisprDatabase,
    baseClient: OkHttpClient,
    private val api: WhisprApi,
    private val tokens: TokenSource,
    private val accounts: AccountRepository,
    private val connectivity: ConnectivityRepository,
    private val scope: CoroutineScope,
    private val crypto: SessionCrypto,
    private val maintainer: PreKeyMaintainer,
    private val timings: EngineTimings = EngineTimings(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val wsClient = baseClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS) // client-side heartbeat
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // frames carry their "type" as a default value
    }
    private val dao get() = db.cryptoDao()

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

    /** Nudges the outbox pump (new entry, lane unparked) and the reset worker. */
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)
    private val resetSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var live: LiveSession? = null

    val resets = ResetCoordinator(db, crypto, clock)

    val groups = GroupManager(db, crypto, clock)

    val pipeline = IncomingPipeline(
        db,
        crypto,
        lookup = { (api.lookupUser(it) as? ApiResult.Success)?.body },
        events = object : PipelineEvents {
            override fun onText(conversation: ConversationId, senderName: String, body: String) {
                incomingFlow.tryEmit(IncomingMessage(conversation, senderName, body))
                typingUntil.update { it - conversation.value }
            }

            override fun onTyping(conversation: ConversationId) {
                typingUntil.update { it + (conversation.value to clock() + timings.typingVisibleMs) }
            }

            override fun onOneTimeKeyUsed() {
                scope.launch { maintainer.maintain(force = true) }
            }

            override fun onDecryptFailure() {
                resetSignal.trySend(Unit)
            }
        },
        groups = groups,
        clock = clock,
    )

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

    /** Best effort; dropped if not connected or if there is no session yet. */
    fun sendTransient(peer: UserId, conversation: ConversationId, payload: Payload) {
        val session = live ?: return
        scope.launch {
            crypto.encryptTransient(peer.value, PayloadCodec.encode(payload))?.let {
                session.sendTransient(peer.value, conversation.value, it)
            }
        }
    }

    /** Runs [block] as one database transaction on the crypto thread. */
    suspend fun <T> transaction(block: () -> T): T = crypto.transaction(block)

    /**
     * Stores [message] (if any) and queues [payload] for [groupId] in one
     * transaction. Returns false, storing nothing, if we can't send there.
     * With nobody else in the group the message is simply Sent.
     */
    suspend fun sendToGroup(groupId: String, message: MessageEntity?, payload: Payload): Boolean {
        val me = accounts.getAccount()?.userId?.value ?: return false
        return crypto.transaction {
            val id = message?.messageId ?: UUID.randomUUID().toString()
            val ts = message?.timestamp ?: clock()
            val recipients = groups.enqueueGroup(me, groupId, id, PayloadCodec.encode(payload), ts)
                ?: return@transaction false
            message?.let {
                dao.insertMessage(
                    if (recipients.isEmpty()) it.copy(status = MessageStatus.Sent.name) else it,
                )
            }
            true
        }
    }

    /** The user acknowledged [peer]'s new key: resume their lane and show what we held. */
    suspend fun onKeyChangeAcknowledged(peer: UserId) {
        crypto.onKeyChangeAcknowledged(peer.value)
        crypto.transaction { dao.unpark(peer.value) }
        accounts.getAccount()?.userId?.let { pipeline.releaseHeld(it.value, peer.value) }
        outboxSignal.trySend(Unit)
        resetSignal.trySend(Unit)
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
        // Wake the pump whenever the outbox changes.
        scope.launch { db.outboxDao().observeCount().collect { outboxSignal.trySend(Unit) } }
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
            val pump = launch { pumpOutbox(session, me.value) }
            val upkeep = launch { upkeep(me.value) }
            for (event in events) {
                when (event) {
                    is WsEvent.Text -> if (!handleFrame(session, me.value, event.text)) break
                    else -> break
                }
            }
            pump.cancel()
            upkeep.cancel()
            true
        } finally {
            live = null
            ws.cancel()
        }
    }

    /** Key maintenance and session resets while connected. */
    private suspend fun upkeep(me: String) {
        var keysOk = maintainKeys()
        resetSignal.trySend(Unit)
        while (true) {
            // Until our keys are on the server nobody can start a chat with us: retry soon, not next tick.
            withTimeoutOrNull(if (keysOk) timings.resetTickMs else timings.parkRetryMs) { resetSignal.receive() }
            if (!keysOk) keysOk = maintainKeys()
            try {
                resets.run(me)
                // Group messages whose sender key never came are dropped after 30 days.
                crypto.transaction { db.groupDao().purgeHeld(clock() - HELD_GROUP_TTL_MS) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Retried on the next tick; resets are durable.
            }
            outboxSignal.trySend(Unit)
        }
    }

    /** Uploads missing keys; false (retried by [upkeep]) if the server could not be reached or refused. */
    private suspend fun maintainKeys(): Boolean = try {
        maintainer.maintain()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }

    /**
     * Sends the head of the first unblocked lane, resending it until the
     * server accepts or rejects it. One envelope is in flight at a time.
     */
    private suspend fun pumpOutbox(session: LiveSession, me: String) {
        while (true) {
            val now = clock()
            val head = crypto.transaction { dao.outboxHead(now) }
            if (head == null) {
                val next = crypto.transaction { dao.nextUnpark(now) }
                withTimeoutOrNull(
                    next?.let {
                        (it - now).coerceAtLeast(1)
                    } ?: Long.MAX_VALUE,
                ) { outboxSignal.receive() }
                continue
            }
            if (head.groupId != null) {
                val (wire, recipients) = prepareGroup(me, head) ?: continue
                session.sendMulti(
                    SendMultiFrame(
                        id = head.messageId,
                        conversationId = head.conversationId,
                        recipientIds = recipients,
                        clientTs = Instant.ofEpochMilli(head.clientTs).toString(),
                        payload = Base64.getEncoder().encodeToString(wire),
                    ),
                )
            } else {
                val wire = head.ciphertext ?: prepare(head) ?: continue
                session.send(
                    SendFrame(
                        id = head.messageId,
                        conversationId = head.conversationId,
                        recipientId = head.recipientId,
                        clientTs = Instant.ofEpochMilli(head.clientTs).toString(),
                        payload = Base64.getEncoder().encodeToString(wire),
                    ),
                )
            }
            // Wait for accepted/rejected to remove it, or resend after a while.
            // Duplicates are harmless: the server deduplicates by message ID.
            withTimeoutOrNull(timings.resendAfterMs) {
                while (crypto.transaction { dao.inOutbox(head.messageId) }) outboxSignal.receive()
            }
        }
    }

    /** Encrypts the lane head once; on a blocked lane parks it and returns null. */
    private suspend fun prepare(head: OutboxEntity): ByteArray? {
        val peer = head.recipientId
        when (val s = crypto.ensureSession(peer)) {
            is SessionStatus.Blocked -> return park(head, s.reason)
            SessionStatus.Ready -> Unit
        }
        val payload = PayloadCodec.decode(head.payload)
        val result = crypto.encrypt(peer, head.payload) { wire ->
            dao.setOutboxCiphertext(head.messageId, wire)
            dao.putSent(
                SentEnvelopeEntity(
                    transportId = head.messageId,
                    recipientId = peer,
                    mid = payload?.originalId(head.messageId),
                    kind = payload?.kind() ?: IncomingPipeline.KIND_CONTROL,
                    plaintext = head.payload.takeIf { payload?.kind() != IncomingPipeline.KIND_CONTROL },
                    sentAt = clock(),
                    autoResent = payload?.isResend() == true,
                ),
            )
            dao.unpark(peer)
            wire
        }
        return when (result) {
            is EncryptResult.Ok -> result.value
            is EncryptResult.Blocked -> park(head, result.reason)
        }
    }

    /**
     * The head of a group lane: sent to whoever of its recipients is still an
     * active member, encrypted once with our current sender key. If a
     * removal rotated our key after this was queued, the new key goes out
     * now (recipients hold the message until it arrives).
     */
    private suspend fun prepareGroup(me: String, head: OutboxEntity): Pair<ByteArray, List<String>>? =
        crypto.transaction {
            val gdao = db.groupDao()
            val g = gdao.group(head.groupId!!)
            if (g == null || g.status != GroupStatus.Active.name) {
                gdao.removeOutbox(head.messageId)
                gdao.setStatus(head.messageId, MessageStatus.Failed.name)
                return@transaction null
            }
            val current = groups.activeMembers(g.groupId).toSet() - me
            val recipients = head.recipients.orEmpty().split(',').filter { it.isNotEmpty() && it in current }
            if (recipients.isEmpty()) {
                gdao.removeOutbox(head.messageId)
                gdao.setStatus(head.messageId, MessageStatus.Sent.name)
                return@transaction null
            }
            groups.ensureShares(me, g, recipients)
            val wire = head.ciphertext
                ?: crypto.encryptGroup(UUID.fromString(g.myDistributionId), head.payload).also {
                    dao.setOutboxCiphertext(head.messageId, it)
                }
            gdao.setOutboxRecipients(head.messageId, recipients.joinToString(","))
            gdao.putGroupSends(recipients.map { GroupSendEntity(head.messageId, it) })
            wire to recipients
        }

    private suspend fun park(head: OutboxEntity, reason: ParkReason): ByteArray? {
        if (reason == ParkReason.UnknownUser) {
            db.messagingTransactions().rejected(head.messageId)
            return null
        }
        val retryAt = when (reason) {
            ParkReason.KeyChanged -> Long.MAX_VALUE // until the user acknowledges
            ParkReason.NoKeys -> clock() + timings.noKeysRetryMs
            else -> clock() + timings.parkRetryMs
        }
        crypto.transaction { dao.park(ParkedRecipientEntity(head.recipientId, reason.name, retryAt)) }
        return null
    }

    /** Returns false if the connection should be dropped. */
    private suspend fun handleFrame(session: LiveSession, me: String, text: String): Boolean {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val id = frame.str("id")
        when (frame.str("type")) {
            "accepted" -> id?.let {
                db.messagingTransactions().accepted(it)
                outboxSignal.trySend(Unit)
            }
            "rejected" -> if (id != null && frame.str("code") !in RETRYABLE) {
                db.messagingTransactions().rejected(id)
                outboxSignal.trySend(Unit)
            }
            "envelope" -> {
                val seq = frame["seq"]?.jsonPrimitive?.longOrNull ?: return false
                val envelope = IncomingEnvelope(
                    seq = seq,
                    id = id ?: return false,
                    sender = frame.str("sender_id") ?: return false,
                    kind = frame.str("kind") ?: IncomingPipeline.KIND_ENVELOPE,
                    refId = frame.str("ref_id"),
                    serverTs =
                    frame.str("server_ts")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: clock(),
                    payload = frame.bytes("payload") ?: ByteArray(0),
                )
                val ack = try {
                    pipeline.process(me, envelope)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    false // not stored: drop the connection; the envelope is redelivered
                }
                if (!ack) return false
                // Only after the write committed: a crash before this line
                // means redelivery, which dedup absorbs.
                session.ack(seq)
            }
            "transient" -> {
                val sender = frame.str("sender_id")
                val payload = frame.bytes("payload")
                if (sender != null && payload != null) pipeline.processTransient(me, sender, payload)
            }
        }
        return true
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bytes(key: String): ByteArray? =
        str(key)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private inner class LiveSession(private val ws: WebSocket) {
        fun send(frame: SendFrame) {
            ws.send(json.encodeToString(SendFrame.serializer(), frame))
        }

        fun sendMulti(frame: SendMultiFrame) {
            ws.send(json.encodeToString(SendMultiFrame.serializer(), frame))
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
        private const val HTTP_UNAUTHORIZED = 401
        private const val MAX_SHIFT = 20
        private const val HELD_GROUP_TTL_MS = 30L * 24 * 60 * 60 * 1000

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

@Serializable
private data class SendMultiFrame(
    val type: String = "send_multi",
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("recipient_ids") val recipientIds: List<String>,
    @SerialName("client_ts") val clientTs: String,
    val payload: String,
)
