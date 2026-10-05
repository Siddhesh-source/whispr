package dev.whispr.data.messaging

import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.ConversationRow
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.MessagingRepository
import dev.whispr.domain.repository.SettingsRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

class RoomMessagingRepository(
    private val db: WhisprDatabase,
    private val engine: MessagingEngine,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : MessagingRepository {

    override val connection: StateFlow<ConnectionState> = engine.connection

    private val lastTypingSent = AtomicLong(0)

    override fun observeConversations(): Flow<List<ConversationSummary>> =
        combine(db.messageDao().observeConversations(), settings.observePrivacy(), accounts.observeAccount()) {
                rows,
                privacy,
                account,
            ->
            val me = account?.userId ?: return@combine emptyList()
            rows.map { it.toSummary(ConversationId.direct(me, UserId(it.peerId)), privacy.readReceipts) }
        }

    override fun observeMessages(conversation: ConversationId): Flow<List<Message>> =
        combine(db.messageDao().observe(conversation.value), settings.observePrivacy()) { rows, privacy ->
            rows.map { it.toDomain(privacy.readReceipts) }
        }

    override suspend fun sendText(peer: UserId, text: String): Boolean {
        // A changed key the user has not acknowledged blocks sending.
        if (db.contactDao().get(peer.value)?.trust == TrustState.KeyChanged.name) return false
        val conversation = conversationWith(peer)
        val id = UUID.randomUUID().toString()
        val now = clock()
        db.messagingTransactions().sendNew(
            MessageEntity(
                messageId = id,
                conversationId = conversation.value,
                peerId = peer.value,
                outgoing = true,
                body = text,
                timestamp = now,
                status = MessageStatus.Sending.name,
            ),
            OutboxEntity(
                messageId = id,
                conversationId = conversation.value,
                recipientId = peer.value,
                payload = PayloadCodec.encode(Payload.Text(text, mid = id, ts = now)),
                clientTs = now,
            ),
        )
        return true
    }

    override suspend fun retry(messageId: String) {
        val message = db.messageDao().get(messageId) ?: return
        if (!message.outgoing || message.status != MessageStatus.Failed.name) return
        db.messagingTransactions().requeue(
            messageId,
            OutboxEntity(
                messageId = messageId,
                conversationId = message.conversationId,
                recipientId = message.peerId,
                payload = PayloadCodec.encode(Payload.Text(message.body, mid = messageId, ts = message.timestamp)),
                clientTs = message.timestamp,
            ),
        )
    }

    override suspend fun markRead(conversation: ConversationId) {
        val unread = db.messageDao().unreadIncomingIds(conversation.value)
        if (unread.isEmpty()) return
        db.messageDao().markAllReadByMe(conversation.value)
        if (!settings.observePrivacy().first().readReceipts) return
        val peer = db.messageDao().get(unread.first())?.peerId ?: return
        // Through the outbox, so the receipt survives going offline.
        db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = conversation.value,
                recipientId = peer,
                payload = PayloadCodec.encode(Payload.Read(unread)),
                clientTs = clock(),
            ),
        )
    }

    override suspend fun onTyping(peer: UserId) {
        if (!settings.observePrivacy().first().typingIndicators) return
        val now = clock()
        val last = lastTypingSent.get()
        if (now - last < TYPING_THROTTLE_MS || !lastTypingSent.compareAndSet(last, now)) return
        engine.sendTransient(peer, conversationWith(peer), Payload.Typing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePeerTyping(conversation: ConversationId): Flow<Boolean> =
        combine(engine.typing, settings.observePrivacy()) { map, privacy ->
            if (privacy.typingIndicators) map[conversation.value] ?: 0L else 0L
        }.distinctUntilChanged().transformLatest { until ->
            val remaining = until - clock()
            if (remaining > 0) {
                emit(true)
                delay(remaining)
            }
            emit(false)
        }.distinctUntilChanged()

    private suspend fun conversationWith(peer: UserId): ConversationId {
        val me = accounts.getAccount()?.userId ?: error("not registered")
        return ConversationId.direct(me, peer)
    }

    private fun MessageEntity.toDomain(showRead: Boolean) = Message(
        id = messageId,
        conversationId = ConversationId(conversationId),
        outgoing = outgoing,
        text = body,
        timestamp = Instant.ofEpochMilli(timestamp),
        status = status?.let { statusFor(it, showRead) },
        notice = placeholder?.let { MessageNotice.valueOf(it) },
    )

    private fun ConversationRow.toSummary(conversationId: ConversationId, showRead: Boolean) = ConversationSummary(
        id = conversationId,
        peer = Contact(UserId(peerId), displayName, identityKey, TrustState.valueOf(trust), isRequest),
        lastMessage = messageId?.let {
            Message(
                id = it,
                conversationId = conversationId,
                outgoing = outgoing == true,
                text = body.orEmpty(),
                timestamp = Instant.ofEpochMilli(timestamp ?: 0),
                status = status?.let { s -> statusFor(s, showRead) },
                notice = placeholder?.let { MessageNotice.valueOf(it) },
            )
        },
        unreadCount = unread,
    )

    /** Read receipts are reciprocal: if you don't send them, you don't see them. */
    private fun statusFor(raw: String, showRead: Boolean): MessageStatus {
        val status = MessageStatus.valueOf(raw)
        return if (status == MessageStatus.Read && !showRead) MessageStatus.Delivered else status
    }

    private companion object {
        const val TYPING_THROTTLE_MS = 3_000L
    }
}

internal fun ContactEntity.toDomain() =
    Contact(UserId(userId), displayName, identityKey, TrustState.valueOf(trust), isRequest)
