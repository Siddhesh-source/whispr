package dev.whispr.data.messaging

import dev.whispr.data.db.AttachmentEntity
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.ConversationRow
import dev.whispr.data.db.ConversationSettingEntity
import dev.whispr.data.db.GroupRow
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.ReactionEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.media.MediaService
import dev.whispr.domain.model.Attachment
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.GroupSummary
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageRules
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.Quote
import dev.whispr.domain.model.Reaction
import dev.whispr.domain.model.SearchHit
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.MessagingRepository
import dev.whispr.domain.repository.SettingsRepository
import java.time.Duration
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
    private val media: MediaService? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : MessagingRepository {

    override val connection: StateFlow<ConnectionState> = engine.connection

    private val lastTypingSent = AtomicLong(0)

    override fun observeConversations(): Flow<List<ConversationSummary>> = combine(
        db.messageDao().observeConversations(),
        db.groupQueries().observeGroupRows(),
        db.contactDao().observeEveryone(),
        settings.observePrivacy(),
        accounts.observeAccount(),
    ) { rows, groups, everyone, privacy, account ->
        val me = account?.userId ?: return@combine emptyList()
        val names = everyone.associate { it.userId to it.displayName }
        val direct = rows.map { it.toSummary(ConversationId.direct(me, UserId(it.peerId)), privacy.readReceipts) }
        val grouped = groups.map { it.toSummary(names) }
        (
            direct.map { it to (it.lastMessage?.timestamp?.toEpochMilli() ?: 0L) } +
                grouped.zip(groups).map { (s, r) -> s to (r.timestamp ?: r.createdAt) }
            )
            .sortedByDescending { it.second }
            .map { it.first }
    }

    override fun observeMessages(conversation: ConversationId): Flow<List<Message>> = combine(
        db.messageDao().observe(conversation.value),
        db.groupQueries().observeAttachments(conversation.value),
        db.groupQueries().observeReactions(conversation.value),
        db.contactDao().observeEveryone(),
        combine(
            settings.observePrivacy(),
            accounts.observeAccount(),
            db.groupQueries().observeGroup(conversation.value),
        ) { p, a, g -> Triple(p, a?.userId?.value, g != null) },
    ) { rows, attachments, reactions, everyone, (privacy, me, isGroup) ->
        val names = everyone.associate { it.userId to it.displayName }
        val byRow = attachments.associateBy { it.messageRow }
        val byTarget = reactions.groupBy { it.targetAuthor to it.targetMid }
        val byAuthor = rows.associateBy { (if (it.outgoing) me.orEmpty() else it.peerId) to it.messageId }
        rows.map { m ->
            val author = if (m.outgoing) me.orEmpty() else m.peerId
            val quote = m.quoteId?.let { qid ->
                val target = byAuthor[m.quoteAuthor.orEmpty() to qid]?.takeIf { !it.deleted && it.placeholder == null }
                Quote(
                    messageId = qid,
                    outgoing = m.quoteAuthor == me,
                    authorName = names[m.quoteAuthor],
                    text = target?.body.orEmpty(),
                    attachmentKind = target?.let { byRow[it.localOrder] }?.let { AttachmentKind.valueOf(it.kind) },
                    found = target != null,
                )
            }
            m.toDomain(
                showRead = privacy.readReceipts,
                attachment = byRow[m.localOrder],
                reactions = byTarget[author to m.messageId].orEmpty(),
                me = me,
                authorName = if (isGroup && !m.outgoing && !m.system) names[m.peerId] else null,
                quote = quote,
            )
        }
    }

    override suspend fun sendText(peer: UserId, text: String, replyTo: String?): Boolean =
        sendDirectText(peer, text, replyTo, forwarded = false)

    private suspend fun sendDirectText(peer: UserId, text: String, replyTo: String?, forwarded: Boolean): Boolean {
        // A changed key the user has not acknowledged blocks sending.
        if (db.contactDao().get(peer.value)?.trust == TrustState.KeyChanged.name) return false
        val conversation = conversationWith(peer)
        val id = UUID.randomUUID().toString()
        val now = clock()
        val quote = replyTo?.let { quoteRef(conversation, it) }
        val exp = timerOf(conversation)
        db.messagingTransactions().sendNew(
            outgoingRow(id, conversation, peer.value, text, now, quote, forwarded, exp),
            OutboxEntity(
                messageId = id,
                conversationId = conversation.value,
                recipientId = peer.value,
                payload = PayloadCodec.encode(
                    Payload.Text(
                        text,
                        mid = id,
                        ts = now,
                        q = quote?.first,
                        qa = quote?.second,
                        fwd = forwarded,
                        exp = exp,
                    ),
                ),
                clientTs = now,
            ),
        )
        return true
    }

    override suspend fun sendGroupText(group: GroupId, text: String, replyTo: String?): Boolean =
        sendGroupText(group, text, replyTo, forwarded = false)

    private suspend fun sendGroupText(group: GroupId, text: String, replyTo: String?, forwarded: Boolean): Boolean {
        val id = UUID.randomUUID().toString()
        val now = clock()
        val conversation = ConversationId(group.value)
        val quote = replyTo?.let { quoteRef(conversation, it) }
        val exp = timerOf(conversation)
        return engine.sendToGroup(
            group.value,
            outgoingRow(id, conversation, group.value, text, now, quote, forwarded, exp),
            Payload.Text(
                text,
                mid = id,
                ts = now,
                g = group.value,
                q = quote?.first,
                qa = quote?.second,
                fwd = forwarded,
                exp = exp,
            ),
        )
    }

    /** Outgoing disappearing messages start their clock when sent. */
    private fun outgoingRow(
        id: String,
        conversation: ConversationId,
        peerId: String,
        text: String,
        now: Long,
        quote: Pair<String, String>?,
        forwarded: Boolean,
        exp: Long?,
    ) = MessageEntity(
        messageId = id,
        conversationId = conversation.value,
        peerId = peerId,
        outgoing = true,
        body = text,
        timestamp = now,
        status = MessageStatus.Sending.name,
        quoteId = quote?.first,
        quoteAuthor = quote?.second,
        forwarded = forwarded,
        expiresIn = exp,
        expireAt = exp?.let { now + it * MS_PER_S },
    )

    /** The (logical ID, author) a reply quotes, if it is shown content in this conversation. */
    private suspend fun quoteRef(conversation: ConversationId, messageId: String): Pair<String, String>? {
        val me = accounts.getAccount()?.userId?.value ?: return null
        val m = engineTx { db.groupDao().messageIn(conversation.value, messageId) } ?: return null
        if (m.system || m.deleted || m.placeholder != null) return null
        return m.messageId to (if (m.outgoing) me else m.peerId)
    }

    /** The conversation's timer in seconds, or null when off. */
    private suspend fun timerOf(conversation: ConversationId): Long? =
        engineTx { db.cryptoDao().conversationSetting(conversation.value)?.timer }?.takeIf { it > 0 }

    override suspend fun sendMedia(conversation: ConversationId, source: MediaSource): SendResult {
        val service = media ?: return SendResult.NotAllowed
        val target = target(conversation) ?: return SendResult.NotAllowed
        return service.send(conversation, target, source, expiresIn = timerOf(conversation))
    }

    override suspend fun forward(from: ConversationId, messageId: String, to: ConversationId): SendResult {
        val m = engineTx { db.groupDao().messageIn(from.value, messageId) } ?: return SendResult.NotAllowed
        if (m.system || m.deleted || m.placeholder != null) return SendResult.NotAllowed
        val target = target(to) ?: return SendResult.NotAllowed
        if (db.groupQueries().attachment(m.localOrder) != null) {
            val service = media ?: return SendResult.NotAllowed
            return service.forward(m.localOrder, to, target, expiresIn = timerOf(to))
        }
        val ok = when (target) {
            is MediaService.Target.Group -> sendGroupText(GroupId(target.id), m.body, null, forwarded = true)
            is MediaService.Target.Direct -> sendDirectText(UserId(target.peer), m.body, null, forwarded = true)
        }
        return if (ok) SendResult.Ok else SendResult.NotAllowed
    }

    override suspend fun deleteForMe(conversation: ConversationId, messageId: String) {
        val me = accounts.getAccount()?.userId?.value ?: return
        val files = engineTx {
            val m = db.groupDao().messageIn(conversation.value, messageId) ?: return@engineTx emptyList()
            // Not sent yet: it must not go out after the user deleted it.
            if (m.outgoing) db.groupDao().removeOutbox(m.messageId)
            MessageDeletion.remove(db.cryptoDao(), m, if (m.outgoing) me else m.peerId)
        }
        MessageDeletion.deleteFiles(files)
    }

    override suspend fun deleteForEveryone(conversation: ConversationId, messageId: String): Boolean {
        val me = accounts.getAccount()?.userId?.value ?: return false
        val m = engineTx { db.groupDao().messageIn(conversation.value, messageId) } ?: return false
        val now = clock()
        val sent = m.status != MessageStatus.Sending.name && m.status != MessageStatus.Failed.name
        val inWindow = now - m.timestamp <= MessageRules.DELETE_FOR_EVERYONE_WINDOW.toMillis()
        if (!m.outgoing || m.system || m.deleted || !sent || !inWindow) return false
        val group = db.groupQueries().observeGroup(conversation.value).first()
        val payload = Payload.Delete(m.messageId, now, g = group?.groupId)
        if (group != null) {
            if (!engine.sendToGroup(group.groupId, null, payload)) return false
        } else {
            db.outboxDao().enqueue(
                OutboxEntity(
                    messageId = UUID.randomUUID().toString(),
                    conversationId = conversation.value,
                    recipientId = m.peerId,
                    payload = PayloadCodec.encode(payload),
                    clientTs = now,
                ),
            )
        }
        val files = engineTx { MessageDeletion.tombstone(db.cryptoDao(), m, me) }
        MessageDeletion.deleteFiles(files)
        return true
    }

    override fun observeTimer(conversation: ConversationId): Flow<Long> =
        db.messageDao().observeSetting(conversation.value).map { it?.timer ?: 0L }.distinctUntilChanged()

    override suspend fun setTimer(conversation: ConversationId, seconds: Long): Boolean {
        if (seconds !in 0..MessageRules.MAX_TIMER_SECONDS) return false
        val now = clock()
        val group = db.groupQueries().observeGroup(conversation.value).first()
        val noticePeer: String
        if (group != null) {
            if (group.status != GroupStatus.Active.name) return false
            if (!engine.sendToGroup(group.groupId, null, Payload.Timer(seconds, now, g = group.groupId))) return false
            noticePeer = group.groupId
        } else {
            val peer = (target(conversation) as? MediaService.Target.Direct)?.peer ?: return false
            db.outboxDao().enqueue(
                OutboxEntity(
                    messageId = UUID.randomUUID().toString(),
                    conversationId = conversation.value,
                    recipientId = peer,
                    payload = PayloadCodec.encode(Payload.Timer(seconds, now)),
                    clientTs = now,
                ),
            )
            noticePeer = peer
        }
        engineTx {
            val dao = db.cryptoDao()
            dao.putConversationSetting(ConversationSettingEntity(conversation.value, seconds, now))
            dao.insertMessage(
                MessageDeletion.notice(conversation.value, noticePeer, TimerText.changed(null, seconds), now),
            )
        }
        return true
    }

    override suspend fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.messageDao().search(pattern, SEARCH_LIMIT).map { r ->
            val isGroup = r.groupName != null
            val conversation = ConversationId(r.conversationId)
            SearchHit(
                conversation = conversation,
                title = r.groupName ?: r.contactName.orEmpty(),
                message = Message(
                    id = r.messageId,
                    conversationId = conversation,
                    outgoing = r.outgoing,
                    text = r.body,
                    timestamp = Instant.ofEpochMilli(r.timestamp),
                    status = r.status?.let { statusFor(it, showRead = false) },
                ),
                peer = if (isGroup) null else UserId(r.peerId),
                group = if (isGroup) GroupId(r.conversationId) else null,
            )
        }
    }

    /** Where a conversation's messages go, or null if we can't send there now. */
    private suspend fun target(conversation: ConversationId): MediaService.Target? {
        db.groupQueries().observeGroup(conversation.value).first()?.let {
            return if (it.status == GroupStatus.Active.name) MediaService.Target.Group(it.groupId) else null
        }
        val me = accounts.getAccount()?.userId ?: return null
        val peer =
            db.contactDao().everyone().firstOrNull { ConversationId.direct(me, UserId(it.userId)) == conversation }
                ?: return null
        if (peer.trust == TrustState.KeyChanged.name || peer.isRequest) return null
        return MediaService.Target.Direct(peer.userId)
    }

    override suspend fun react(conversation: ConversationId, messageId: String, emoji: String?) {
        val me = accounts.getAccount()?.userId?.value ?: return
        val message = engineTx { db.groupDao().messageIn(conversation.value, messageId) } ?: return
        if (message.system || message.placeholder != null) return
        val author = if (message.outgoing) me else message.peerId
        val now = clock()
        val group = db.groupQueries().observeGroup(conversation.value).first()
        val payload = Payload.Reaction(messageId, author, emoji, now, g = group?.groupId)
        if (group != null) {
            if (!engine.sendToGroup(group.groupId, null, payload)) return
        } else {
            if (db.contactDao().get(message.peerId)?.trust == TrustState.KeyChanged.name) return
            db.outboxDao().enqueue(
                OutboxEntity(
                    messageId = UUID.randomUUID().toString(),
                    conversationId = conversation.value,
                    recipientId = message.peerId,
                    payload = PayloadCodec.encode(payload),
                    clientTs = now,
                ),
            )
        }
        engineTx {
            val gdao = db.groupDao()
            if (emoji == null) {
                gdao.deleteReaction(conversation.value, author, messageId, me)
            } else {
                gdao.putReaction(ReactionEntity(conversation.value, author, messageId, me, emoji, now))
            }
        }
    }

    override suspend fun download(conversation: ConversationId, messageId: String) {
        rowOf(conversation, messageId)?.let { media?.download(it) }
    }

    override suspend fun attachmentBytes(conversation: ConversationId, messageId: String): ByteArray? =
        rowOf(conversation, messageId)?.let { media?.bytes(it) }

    override suspend fun exportAttachment(conversation: ConversationId, messageId: String): String? =
        rowOf(conversation, messageId)?.let { media?.export(it) }

    private suspend fun rowOf(conversation: ConversationId, messageId: String): Long? =
        engineTx { db.groupDao().messageIn(conversation.value, messageId)?.localOrder }

    override suspend fun retry(messageId: String) {
        val message = db.messageDao().get(messageId) ?: return
        if (!message.outgoing || message.status != MessageStatus.Failed.name) return
        if (db.groupQueries().attachment(message.localOrder) != null) {
            media?.upload(message.localOrder)
            return
        }
        if (db.groupQueries().observeGroup(message.conversationId).first() != null) {
            db.messageDao().setStatus(messageId, MessageStatus.Sending.name)
            val ok = engine.sendToGroup(
                message.conversationId,
                null,
                message.textPayload(g = message.conversationId),
            )
            if (!ok) db.messageDao().setStatus(messageId, MessageStatus.Failed.name)
            return
        }
        db.messagingTransactions().requeue(
            messageId,
            OutboxEntity(
                messageId = messageId,
                conversationId = message.conversationId,
                recipientId = message.peerId,
                payload = PayloadCodec.encode(message.textPayload(g = null)),
                clientTs = message.timestamp,
            ),
        )
    }

    /** A retry sends exactly what was first queued: same ID, time, quote, forward flag and timer. */
    private fun MessageEntity.textPayload(g: String?) = Payload.Text(
        body,
        mid = messageId,
        ts = timestamp,
        g = g,
        q = quoteId,
        qa = quoteAuthor,
        fwd = forwarded,
        exp = expiresIn,
    )

    override suspend fun markRead(conversation: ConversationId) {
        // Seeing a disappearing message starts its clock.
        db.messageDao().startTimers(conversation.value, clock())
        val unread = db.messageDao().unreadIncomingIds(conversation.value)
        if (unread.isEmpty()) return
        db.messageDao().markAllReadByMe(conversation.value)
        // No read receipts in groups (they would tell every member when you read).
        if (db.groupQueries().observeGroup(conversation.value).first() != null) return
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

    private suspend fun <T> engineTx(block: () -> T): T = engine.transaction(block)

    private suspend fun conversationWith(peer: UserId): ConversationId {
        val me = accounts.getAccount()?.userId ?: error("not registered")
        return ConversationId.direct(me, peer)
    }

    private fun MessageEntity.toDomain(
        showRead: Boolean,
        attachment: AttachmentEntity? = null,
        reactions: List<ReactionEntity> = emptyList(),
        me: String? = null,
        authorName: String? = null,
        quote: Quote? = null,
    ) = Message(
        id = messageId,
        conversationId = ConversationId(conversationId),
        outgoing = outgoing,
        text = body,
        timestamp = Instant.ofEpochMilli(timestamp),
        status = status?.let { statusFor(it, showRead) },
        notice = placeholder?.let { MessageNotice.valueOf(it) },
        author = if (!outgoing && !system) UserId(peerId) else null,
        authorName = authorName,
        attachment = attachment?.toDomain(),
        reactions = reactions.groupBy { it.emoji }.map { (emoji, list) ->
            Reaction(emoji, list.size, list.any { it.reactorId == me })
        }.sortedByDescending { it.count },
        system = system,
        quote = quote,
        forwarded = forwarded,
        deleted = deleted,
        expiresIn = expiresIn?.let(Duration::ofSeconds),
        expiresAt = expireAt?.let(Instant::ofEpochMilli),
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

    private fun GroupRow.toSummary(names: Map<String, String>): ConversationSummary {
        val id = ConversationId(groupId)
        return ConversationSummary(
            id = id,
            peer = null,
            group = GroupSummary(GroupId(groupId), name, avatar, GroupStatus.valueOf(status)),
            lastMessage = messageId?.let {
                Message(
                    id = it,
                    conversationId = id,
                    outgoing = outgoing == true,
                    text = body.orEmpty(),
                    timestamp = Instant.ofEpochMilli(timestamp ?: createdAt),
                    status = msgStatus?.let { s -> statusFor(s, showRead = false) },
                    notice = placeholder?.let { p -> MessageNotice.valueOf(p) },
                    author = peerId?.takeIf { outgoing != true && system != true }?.let(::UserId),
                    authorName = peerId?.takeIf { outgoing != true && system != true }?.let { p -> names[p] },
                    attachment = attachmentKind?.let { k ->
                        Attachment(AttachmentKind.valueOf(k), "", null, 0, state = AttachmentState.Ready)
                    },
                    system = system == true,
                )
            },
            unreadCount = unread,
        )
    }

    /** Read receipts are reciprocal: if you don't send them, you don't see them. */
    private fun statusFor(raw: String, showRead: Boolean): MessageStatus {
        val status = MessageStatus.valueOf(raw)
        return if (status == MessageStatus.Read && !showRead) MessageStatus.Delivered else status
    }

    private companion object {
        const val TYPING_THROTTLE_MS = 3_000L
        const val MS_PER_S = 1_000L
        const val SEARCH_LIMIT = 100
    }
}

internal fun AttachmentEntity.toDomain() = Attachment(
    kind = AttachmentKind.valueOf(kind),
    contentType = contentType,
    fileName = fileName,
    size = size,
    width = width,
    height = height,
    durationMs = durationMs,
    thumbnail = thumbnail,
    state = AttachmentState.valueOf(state),
)

internal fun ContactEntity.toDomain() =
    Contact(UserId(userId), displayName, identityKey, TrustState.valueOf(trust), isRequest)
