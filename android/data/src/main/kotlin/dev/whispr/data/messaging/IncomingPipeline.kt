package dev.whispr.data.messaging

import dev.whispr.data.crypto.DecryptResult
import dev.whispr.data.crypto.GroupDecryptResult
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.crypto.WireFormat
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.DecryptAttemptEntity
import dev.whispr.data.db.HeldEnvelopeEntity
import dev.whispr.data.db.HeldGroupEnvelopeEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.PendingResetEntity
import dev.whispr.data.db.Placeholder
import dev.whispr.data.db.ReactionEntity
import dev.whispr.data.db.SeenEnvelopeEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.network.UserResponse
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.UserId
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.signal.libsignal.protocol.IdentityKey

/** One envelope as delivered by the gateway. [id] is the sender-chosen transport ID. */
class IncomingEnvelope(
    val seq: Long,
    val id: String,
    val sender: String,
    val kind: String,
    val refId: String?,
    val serverTs: Long,
    val payload: ByteArray,
)

/** What the pipeline reports to the engine (notifications, typing, follow-up work). */
interface PipelineEvents {
    fun onText(conversation: ConversationId, senderName: String, body: String)
    fun onTyping(conversation: ConversationId)

    /** A PreKey message used one of our one-time keys: check whether to top up. */
    fun onOneTimeKeyUsed()

    /** A decryption failure was queued for a session reset. */
    fun onDecryptFailure()
}

/**
 * Everything that happens to an envelope after the socket has parsed it:
 * sender-scoped dedup, decryption and storage in one transaction, and the
 * failure paths (replays dropped, changed keys held, undecryptable messages
 * turned into a placeholder plus a durable reset request).
 *
 * [process] returns true when the envelope may be acknowledged, which is
 * only after everything it produced is committed.
 */
class IncomingPipeline(
    private val db: WhisprDatabase,
    private val crypto: SessionCrypto,
    private val lookup: suspend (userId: String) -> UserResponse?,
    private val events: PipelineEvents,
    private val groups: GroupManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: CryptoDao get() = db.cryptoDao()

    suspend fun process(me: String, e: IncomingEnvelope, released: Boolean = false): Boolean {
        if (e.kind == KIND_DELIVERED) {
            e.refId?.let { ref -> crypto.transaction { delivered(e.sender, ref) } }
            return true
        }
        if (!released && crypto.transaction { dao.seen(e.sender, e.id) }) return true
        if (WireFormat.decode(e.payload)?.first == WireFormat.TYPE_SENDER_KEY) return processGroup(me, e, released)
        val result = try {
            crypto.decrypt(e.sender, e.payload) { plaintext -> apply(me, e, plaintext) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            if (poisoned(e, ex)) DecryptResult.Failed("poison") else throw ex
        }
        when (result) {
            is DecryptResult.Ok -> {
                if (released) crypto.transaction { dao.releaseHeld(e.sender, e.id) }
                result.value.notify?.let { (conv, name, body) -> events.onText(conv, name, body) }
                result.value.releaseGroup.forEach { releaseGroupHeld(me, it) }
                if (result.usedOneTimeKey) events.onOneTimeKeyUsed()
                if (result.value.lookupSender) refreshStranger(e.sender)
            }
            DecryptResult.Replay -> crypto.transaction {
                dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
                if (released) dao.releaseHeld(e.sender, e.id)
            }
            is DecryptResult.Untrusted -> if (!released) hold(me, e, result.identityKey)
            is DecryptResult.Failed -> {
                crypto.transaction {
                    dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
                    if (released) dao.releaseHeld(e.sender, e.id)
                    val waiting = dao.parked(e.sender) != null
                    val state = if (waiting) Placeholder.Waiting else Placeholder.Pending
                    insertPlaceholder(me, e, state)
                    dao.setPlaceholderState(e.sender, e.id, state.name, listOf(Placeholder.Held.name))
                    dao.queueReset(PendingResetEntity(e.sender, e.id, STATE_QUEUED, attempts = 0, failedAt = clock()))
                }
                events.onDecryptFailure()
            }
        }
        return true
    }

    /**
     * Unexpected (e.g. storage) failure: the caller doesn't ack, so the
     * envelope is redelivered. After 3 attempts, even across restarts, give up.
     */
    private suspend fun poisoned(e: IncomingEnvelope, ex: Exception): Boolean {
        val count = crypto.transaction {
            ((dao.attempts(e.sender, e.id) ?: 0) + 1).also {
                dao.putAttempts(DecryptAttemptEntity(e.sender, e.id, it))
            }
        }
        if (count < MAX_ATTEMPTS) return false
        return ex !is CancellationException
    }

    /**
     * A group message (sender key). It is shown only if we hold the sender's
     * key for its distribution, that distribution belongs to an active group
     * of ours, and the sender is a current member. Otherwise it is held
     * encrypted until their key or the group state arrives (30 days at most).
     */
    private suspend fun processGroup(me: String, e: IncomingEnvelope, released: Boolean): Boolean {
        val gdao = db.groupDao()
        val distribution = crypto.groupDistributionId(e.payload)
        val target = distribution?.let {
            crypto.transaction {
                val groupId = gdao.distributionGroup(e.sender, it.toString()) ?: return@transaction HOLD
                val g = gdao.group(groupId) ?: return@transaction HOLD
                when {
                    g.status == GroupStatus.Removed.name || g.status == GroupStatus.Left.name -> DROP
                    !groups.accepts(me, groupId, e.sender) -> HOLD
                    else -> groupId
                }
            }
        }
        if (target == null || target == DROP) {
            crypto.transaction {
                dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
                gdao.releaseHeld(e.sender, e.id)
            }
            return true
        }
        if (target == HOLD) {
            if (!released) holdGroup(e)
            return true
        }
        val result = try {
            crypto.decryptGroup(e.sender, e.payload) { plaintext -> applyGroup(me, e, target, plaintext) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            if (poisoned(e, ex)) GroupDecryptResult.Failed("poison") else throw ex
        }
        when (result) {
            is GroupDecryptResult.Ok -> {
                if (released) crypto.transaction { gdao.releaseHeld(e.sender, e.id) }
                result.value.notify?.let { (conv, name, body) -> events.onText(conv, name, body) }
            }
            GroupDecryptResult.Replay -> crypto.transaction {
                dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
                gdao.releaseHeld(e.sender, e.id)
            }
            GroupDecryptResult.NoSenderKey -> if (!released) holdGroup(e)
            is GroupDecryptResult.Failed -> crypto.transaction {
                dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
                gdao.releaseHeld(e.sender, e.id)
                // Groups have no resend protocol: the gap is shown, not hidden.
                dao.insertMessage(
                    MessageEntity(
                        messageId = e.id,
                        conversationId = target,
                        peerId = e.sender,
                        outgoing = false,
                        body = "",
                        timestamp = e.serverTs,
                        status = null,
                        placeholder = Placeholder.Unrecoverable.name,
                    ),
                )
            }
        }
        return true
    }

    private suspend fun holdGroup(e: IncomingEnvelope) = crypto.transaction {
        dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
        db.groupDao().hold(HeldGroupEnvelopeEntity(e.sender, e.id, e.payload, clock(), e.seq))
    }

    /** [sender]'s key or our group state changed: retry what we held from them, in arrival order. */
    suspend fun releaseGroupHeld(me: String, sender: String) {
        for (row in crypto.transaction { db.groupDao().held(sender) }) {
            process(
                me,
                IncomingEnvelope(row.seq, row.transportId, sender, KIND_ENVELOPE, null, row.receivedAt, row.ciphertext),
                released = true,
            )
        }
    }

    /** Runs inside the group decrypt transaction. */
    private fun applyGroup(me: String, e: IncomingEnvelope, groupId: String, plaintext: ByteArray): Applied {
        dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
        dao.clearAttempts(e.sender, e.id)
        val p = PayloadCodec.decode(plaintext)
        val group = db.groupDao().group(groupId)?.name.orEmpty()
        return when {
            p is Payload.Text && p.g == groupId ->
                applyContent(ConversationId(groupId), e, p.mid, p.ts, null, p.body, null, group)
            p is Payload.Media && p.g == groupId ->
                applyContent(ConversationId(groupId), e, p.mid, p.ts, null, "", p.a, group)
            p is Payload.Reaction && p.g == groupId -> {
                applyReaction(groupId, e.sender, p)
                Applied()
            }
            else -> Applied() // wrong group, a pairwise-only kind, or unknown: drop
        }
    }

    /** Typing indicators: best effort, never stored; failures are ignored. */
    suspend fun processTransient(me: String, sender: String, payload: ByteArray) {
        val decoded = try {
            crypto.decrypt(sender, payload) { PayloadCodec.decode(it) }
        } catch (_: Exception) {
            return
        }
        if ((decoded as? DecryptResult.Ok)?.value == Payload.Typing) {
            events.onTyping(ConversationId.direct(UserId(me), UserId(sender)))
        }
    }

    /** After the user acknowledged [sender]'s new key: decrypt what we held, in arrival order. */
    suspend fun releaseHeld(me: String, sender: String) {
        for (row in crypto.transaction { dao.held(sender) }) {
            process(
                me,
                IncomingEnvelope(row.seq, row.transportId, sender, KIND_ENVELOPE, null, row.receivedAt, row.ciphertext),
                released = true,
            )
        }
    }

    private suspend fun hold(me: String, e: IncomingEnvelope, identityKey: ByteArray?) = crypto.transaction {
        dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
        dao.hold(HeldEnvelopeEntity(e.sender, e.id, e.payload, clock(), e.seq))
        insertPlaceholder(me, e, Placeholder.Held)
        val contact = dao.contact(e.sender)
        if (identityKey != null && contact != null && contact.pendingKey?.contentEquals(identityKey) != true) {
            dao.flagKeyChange(e.sender, identityKey)
        }
    }

    private fun insertPlaceholder(me: String, e: IncomingEnvelope, state: Placeholder) {
        dao.insertMessage(
            MessageEntity(
                messageId = e.id,
                conversationId = ConversationId.direct(UserId(me), UserId(e.sender)).value,
                peerId = e.sender,
                outgoing = false,
                body = "",
                timestamp = e.serverTs,
                status = null,
                placeholder = state.name,
            ),
        )
    }

    private fun delivered(sender: String, ref: String) {
        val gdao = db.groupDao()
        if (gdao.groupDelivered(ref, sender) > 0) {
            // A group message is Delivered once every recipient has it.
            if (gdao.undelivered(ref) == 0) dao.markDelivered(ref)
            return
        }
        val sent = dao.sent(ref)
        when {
            sent == null -> dao.markDelivered(ref)
            sent.kind == KIND_TEXT || sent.kind == KIND_MEDIA -> dao.markDelivered(sent.mid ?: ref)
        }
        dao.resetDelivered(ref, clock())
    }

    /** What to do after the decrypt transaction committed. */
    class Applied(
        val notify: Triple<ConversationId, String, String>? = null,
        val lookupSender: Boolean = false,
        /** Senders whose held group messages may now be decryptable. */
        val releaseGroup: List<String> = emptyList(),
    )

    /** Runs inside the decrypt transaction on the crypto thread. */
    private fun apply(me: String, e: IncomingEnvelope, plaintext: ByteArray): Applied {
        dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
        dao.clearAttempts(e.sender, e.id)
        val conversation = ConversationId.direct(UserId(me), UserId(e.sender))
        val stranger = dao.contact(e.sender)?.displayName == SignalStore.UNKNOWN_CONTACT
        val p = PayloadCodec.decode(plaintext)
        // A held envelope that turned out not to be shown content leaves no bubble.
        val content = (p is Payload.Text && p.g == null) || (p is Payload.Media && p.g == null)
        if (!content) dao.deletePlaceholder(e.sender, e.id)
        val replaces = p?.replacesId()
        if (!content) replaces?.let { resolve(e.sender, it, delete = true) }
        return when (p) {
            // Group content only ever travels under a sender key, never pairwise.
            is Payload.Text -> if (p.g != null) {
                Applied()
            } else {
                applyContent(conversation, e, p.mid, p.ts, p.replaces, p.body, null, null, stranger)
            }
            is Payload.Media -> if (p.g != null) {
                Applied()
            } else {
                applyContent(conversation, e, p.mid, p.ts, p.replaces, "", p.a, null, stranger)
            }
            is Payload.Reaction -> {
                if (p.g == null && (p.author == me || p.author == e.sender)) {
                    applyReaction(conversation.value, e.sender, p)
                }
                Applied()
            }
            is Payload.Read -> {
                dao.markReadByPeer(p.ids, e.sender)
                Applied()
            }
            is Payload.ContactRequest -> {
                applyContactRequest(e.sender, p)
                Applied(lookupSender = true)
            }
            is Payload.SessionReset -> {
                answerReset(conversation, e.sender, p.failed)
                Applied()
            }
            is Payload.ResetDone -> {
                applyResetDone(e.sender, p)
                Applied()
            }
            is Payload.GroupUpdate -> Applied(releaseGroup = groups.applyUpdate(me, e.sender, p.state))
            is Payload.SenderKey ->
                Applied(releaseGroup = if (groups.applySenderKey(e.sender, p)) listOf(e.sender) else emptyList())
            is Payload.GroupJoin -> {
                groups.applyJoin(me, e.sender, p.g)
                Applied()
            }
            is Payload.GroupDecline -> {
                groups.applyDecline(me, e.sender, p.g)
                Applied()
            }
            is Payload.GroupLeave -> {
                groups.applyLeave(me, e.sender, p.g)
                Applied()
            }
            Payload.Typing, null -> Applied() // misplaced or unknown: drop
        }
    }

    /**
     * A text or media message, 1:1 ([group] null) or in a group. In 1:1 chats
     * a resend replaces its placeholder in place, keeping its position.
     */
    private fun applyContent(
        conversation: ConversationId,
        e: IncomingEnvelope,
        midOrNull: String?,
        ts: Long?,
        replaces: String?,
        body: String,
        pointer: AttachmentPointer?,
        group: String?,
        stranger: Boolean = false,
    ): Applied {
        val mid = midOrNull ?: e.id
        val attachment = pointer?.let { Attachments.fromPointer(it) ?: return Applied() } // malformed: drop
        val placeholder = if (group != null) {
            null
        } else {
            sequenceOf(replaces, mid, e.id).filterNotNull()
                .firstNotNullOfOrNull { id -> dao.message(e.sender, id)?.takeIf { it.placeholder != null } }
        }
        val timestamp = if (placeholder != null) ts ?: placeholder.timestamp else e.serverTs
        val row = if (placeholder != null) {
            dao.recoverPlaceholder(placeholder.localOrder, body, timestamp)
            dao.resolveReset(e.sender, placeholder.messageId)
            placeholder.localOrder
        } else {
            dao.insertMessage(
                MessageEntity(
                    messageId = mid,
                    conversationId = conversation.value,
                    peerId = e.sender,
                    outgoing = false,
                    body = body,
                    timestamp = timestamp,
                    status = null,
                ),
            )
        }
        replaces?.let { dao.resolveReset(e.sender, it) }
        if (row != -1L && attachment != null) db.groupDao().putAttachment(attachment.copy(messageRow = row))
        if (group == null) {
            // A group member we only knew from a group now writes to us directly: a message request.
            dao.contact(e.sender)?.takeIf { it.hidden }?.let {
                dao.putContact(it.copy(hidden = false, isRequest = true))
            }
        }
        val name = dao.contact(e.sender)?.displayName ?: SignalStore.UNKNOWN_CONTACT
        val preview = if (attachment != null) Attachments.preview(attachment.kind) else body
        val title = if (group != null) "$name · $group" else name
        return Applied(notify = if (row != -1L) Triple(conversation, title, preview) else null, lookupSender = stranger)
    }

    /** One reaction per reactor per message; a newer one replaces an older one. */
    private fun applyReaction(conversation: String, reactor: String, p: Payload.Reaction) {
        val emoji = p.emoji
        if (p.target.length > MAX_ID || p.author.length > MAX_ID || (emoji != null && emoji.length > MAX_EMOJI)) return
        val gdao = db.groupDao()
        val previous = gdao.reactionTime(conversation, p.author, p.target, reactor)
        if (previous != null && previous > p.ts) return
        if (emoji == null) {
            gdao.deleteReaction(conversation, p.author, p.target, reactor)
        } else {
            gdao.putReaction(ReactionEntity(conversation, p.author, p.target, reactor, emoji, p.ts))
        }
    }

    /**
     * The request's key must match the identity the PreKey message carried
     * (now pinned via the store); a mismatch is flagged, never adopted.
     */
    private fun applyContactRequest(sender: String, p: Payload.ContactRequest) {
        val key = runCatching { Base64.getDecoder().decode(p.key) }.getOrNull()
            ?.takeIf { runCatching { IdentityKey(it) }.isSuccess } ?: return
        val contact =
            dao.contact(sender)
                ?: ContactEntity(sender, SignalStore.UNKNOWN_CONTACT, ByteArray(0), clock(), isRequest = true)
        val named = if (contact.displayName == SignalStore.UNKNOWN_CONTACT) {
            contact.copy(displayName = p.name.take(MAX_NAME).ifBlank { SignalStore.UNKNOWN_CONTACT })
        } else {
            contact
        }
        dao.putContact(if (named.identityKey.isEmpty()) named.copy(identityKey = key) else named)
        if (named.identityKey.isNotEmpty() && !named.identityKey.contentEquals(key)) dao.flagKeyChange(sender, key)
    }

    /**
     * The peer could not decrypt [failed]. Resend each one that we sent to
     * this peer and have not resent before; answer with what happened to each.
     */
    private fun answerReset(conversation: ConversationId, peer: String, failed: List<String>) {
        val resent = mutableListOf<String>()
        val control = mutableListOf<String>()
        val lost = mutableListOf<String>()
        val aliases = mutableMapOf<String, String>()
        for (id in failed) {
            val row = dao.sent(id)
            val payload = row?.plaintext?.let(PayloadCodec::decode)
            when {
                // Only messages sent to this peer: a peer can't fish for others' messages.
                row == null || row.recipientId != peer -> lost += id
                row.kind == KIND_CONTROL -> control += id
                row.autoResent || payload == null -> {
                    lost += id
                    row.mid?.takeIf { it != id }?.let { aliases[id] = it }
                }
                else -> {
                    dao.enqueue(
                        OutboxEntity(
                            messageId = UUID.randomUUID().toString(),
                            conversationId = conversation.value,
                            recipientId = peer,
                            payload = PayloadCodec.encode(payload.resendOf(id)),
                            clientTs = clock(),
                        ),
                    )
                    dao.markAutoResent(id)
                    resent += id
                }
            }
        }
        dao.enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = conversation.value,
                recipientId = peer,
                payload = PayloadCodec.encode(Payload.ResetDone(resent, control, lost, aliases)),
                clientTs = clock(),
            ),
        )
    }

    private fun applyResetDone(peer: String, p: Payload.ResetDone) {
        p.control.forEach { resolve(peer, it, delete = true) }
        for (id in p.lost) {
            val original = p.aliases[id]
            if (original != null) {
                // A resend that also failed: drop its duplicate bubble.
                resolve(peer, id, delete = true)
                finalise(peer, original)
            } else {
                finalise(peer, id)
            }
        }
        // `resent` resolves nothing: the row stays until the resend arrives.
    }

    private fun resolve(peer: String, id: String, delete: Boolean) {
        if (delete) dao.deletePlaceholder(peer, id)
        dao.resolveReset(peer, id)
    }

    private fun finalise(peer: String, id: String) {
        dao.setPlaceholderState(
            peer,
            id,
            Placeholder.Unrecoverable.name,
            listOf(Placeholder.Pending.name, Placeholder.Waiting.name),
        )
        dao.resolveReset(peer, id)
    }

    /** Names a stranger (message request) from the server and cross-checks their key. */
    private suspend fun refreshStranger(sender: String) {
        val profile = lookup(sender) ?: return
        val contactDao = db.contactDao()
        val contact = contactDao.get(sender) ?: return
        if (contact.displayName == SignalStore.UNKNOWN_CONTACT) contactDao.setName(sender, profile.displayName)
        val serverKey = runCatching { Base64.getDecoder().decode(profile.identityKey) }.getOrNull() ?: return
        if (contact.identityKey.isNotEmpty() && !contact.identityKey.contentEquals(serverKey)) {
            contactDao.flagKeyChange(sender, serverKey)
        }
    }

    companion object {
        const val KIND_ENVELOPE = "envelope"
        const val KIND_DELIVERED = "delivered"
        const val KIND_TEXT = "text"
        const val KIND_MEDIA = "media"
        const val KIND_CONTROL = "control"
        const val STATE_QUEUED = "queued"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_NAME = 64
        private const val MAX_ID = 64
        private const val MAX_EMOJI = 16

        // processGroup outcomes besides a group ID.
        private const val HOLD = "\u0000hold"
        private const val DROP = "\u0000drop"

        /**
         * The sent-log kind. Only [KIND_CONTROL] payloads are never resent
         * after a failed decryption; group control messages and sender keys
         * are resent, or a lost key would strand every later group message.
         */
        fun Payload.kind(): String = when (this) {
            is Payload.Text -> KIND_TEXT
            is Payload.Media -> KIND_MEDIA
            is Payload.Read -> "read"
            is Payload.ContactRequest -> "contact_request"
            is Payload.Reaction -> "reaction"
            is Payload.SenderKey -> "sender_key"
            is Payload.GroupUpdate, is Payload.GroupJoin, is Payload.GroupDecline, is Payload.GroupLeave -> "group"
            Payload.Typing, is Payload.SessionReset, is Payload.ResetDone -> KIND_CONTROL
        }

        /** The logical ID a sent payload is about (for the sent log). */
        fun Payload.originalId(transportId: String): String? = when (this) {
            is Payload.Text -> replaces ?: mid ?: transportId
            is Payload.Media -> replaces ?: mid ?: transportId
            else -> replacesId()
        }

        fun Payload.isResend(): Boolean = replacesId() != null

        fun Payload.replacesId(): String? = when (this) {
            is Payload.Text -> replaces
            is Payload.Media -> replaces
            is Payload.Reaction -> replaces
            is Payload.Read -> replaces
            is Payload.ContactRequest -> replaces
            is Payload.GroupUpdate -> replaces
            is Payload.SenderKey -> replaces
            is Payload.GroupJoin -> replaces
            is Payload.GroupDecline -> replaces
            is Payload.GroupLeave -> replaces
            Payload.Typing, is Payload.SessionReset, is Payload.ResetDone -> null
        }

        private fun Payload.resendOf(id: String): Payload = when (this) {
            is Payload.Text -> copy(replaces = id)
            is Payload.Media -> copy(replaces = id)
            is Payload.Reaction -> copy(replaces = id)
            is Payload.Read -> copy(replaces = id)
            is Payload.ContactRequest -> copy(replaces = id)
            is Payload.GroupUpdate -> copy(replaces = id)
            is Payload.SenderKey -> copy(replaces = id)
            is Payload.GroupJoin -> copy(replaces = id)
            is Payload.GroupDecline -> copy(replaces = id)
            is Payload.GroupLeave -> copy(replaces = id)
            Payload.Typing, is Payload.SessionReset, is Payload.ResetDone -> this
        }
    }
}
