package dev.whispr.data.messaging

import dev.whispr.data.crypto.DecryptResult
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.DecryptAttemptEntity
import dev.whispr.data.db.HeldEnvelopeEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.PendingResetEntity
import dev.whispr.data.db.Placeholder
import dev.whispr.data.db.SeenEnvelopeEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.network.UserResponse
import dev.whispr.domain.model.ConversationId
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: CryptoDao get() = db.cryptoDao()

    suspend fun process(me: String, e: IncomingEnvelope, released: Boolean = false): Boolean {
        if (e.kind == KIND_DELIVERED) {
            e.refId?.let { ref -> crypto.transaction { delivered(ref) } }
            return true
        }
        if (!released && crypto.transaction { dao.seen(e.sender, e.id) }) return true
        val result = try {
            crypto.decrypt(e.sender, e.payload) { plaintext -> apply(me, e, plaintext) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            // Unexpected (e.g. storage) failure: don't ack, so the envelope is
            // redelivered. After 3 attempts, even across restarts, give up on it.
            val count = crypto.transaction {
                ((dao.attempts(e.sender, e.id) ?: 0) + 1).also {
                    dao.putAttempts(DecryptAttemptEntity(e.sender, e.id, it))
                }
            }
            if (count < MAX_ATTEMPTS) throw ex
            DecryptResult.Failed("poison")
        }
        when (result) {
            is DecryptResult.Ok -> {
                if (released) crypto.transaction { dao.releaseHeld(e.sender, e.id) }
                result.value.notify?.let { (conv, name, body) -> events.onText(conv, name, body) }
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

    private fun delivered(ref: String) {
        val sent = dao.sent(ref)
        when {
            sent == null -> dao.markDelivered(ref)
            sent.kind == KIND_TEXT -> dao.markDelivered(sent.mid ?: ref)
        }
        dao.resetDelivered(ref, clock())
    }

    /** What to do after the decrypt transaction committed. */
    class Applied(val notify: Triple<ConversationId, String, String>? = null, val lookupSender: Boolean = false)

    /** Runs inside the decrypt transaction on the crypto thread. */
    private fun apply(me: String, e: IncomingEnvelope, plaintext: ByteArray): Applied {
        dao.markSeen(SeenEnvelopeEntity(e.sender, e.id, clock()))
        dao.clearAttempts(e.sender, e.id)
        val conversation = ConversationId.direct(UserId(me), UserId(e.sender))
        val stranger = dao.contact(e.sender)?.displayName == SignalStore.UNKNOWN_CONTACT
        val p = PayloadCodec.decode(plaintext)
        // A held envelope that turned out not to be text leaves no bubble.
        if (p !is Payload.Text) dao.deletePlaceholder(e.sender, e.id)
        return when (p) {
            is Payload.Text -> applyText(conversation, e, p, stranger)
            is Payload.Read -> {
                dao.markReadByPeer(p.ids, e.sender)
                p.replaces?.let { resolve(e.sender, it, delete = true) }
                Applied()
            }
            is Payload.ContactRequest -> {
                applyContactRequest(e.sender, p)
                p.replaces?.let { resolve(e.sender, it, delete = true) }
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
            Payload.Typing, null -> Applied() // misplaced or unknown: drop
        }
    }

    private fun applyText(
        conversation: ConversationId,
        e: IncomingEnvelope,
        p: Payload.Text,
        stranger: Boolean,
    ): Applied {
        val mid = p.mid ?: e.id
        // A resend replaces its placeholder in place, keeping its position.
        val placeholder = sequenceOf(p.replaces, mid, e.id).filterNotNull()
            .firstNotNullOfOrNull { id -> dao.message(e.sender, id)?.takeIf { it.placeholder != null } }
        val timestamp = if (placeholder != null) p.ts ?: placeholder.timestamp else e.serverTs
        val shown = if (placeholder != null) {
            dao.recoverPlaceholder(placeholder.localOrder, p.body, timestamp)
            dao.resolveReset(e.sender, placeholder.messageId)
            true
        } else {
            dao.insertMessage(
                MessageEntity(
                    messageId = mid,
                    conversationId = conversation.value,
                    peerId = e.sender,
                    outgoing = false,
                    body = p.body,
                    timestamp = timestamp,
                    status = null,
                ),
            ) != -1L
        }
        p.replaces?.let { dao.resolveReset(e.sender, it) }
        val name = dao.contact(e.sender)?.displayName ?: SignalStore.UNKNOWN_CONTACT
        return Applied(notify = if (shown) Triple(conversation, name, p.body) else null, lookupSender = stranger)
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
        const val KIND_CONTROL = "control"
        const val STATE_QUEUED = "queued"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_NAME = 64

        fun Payload.kind(): String = when (this) {
            is Payload.Text -> KIND_TEXT
            is Payload.Read -> "read"
            is Payload.ContactRequest -> "contact_request"
            else -> KIND_CONTROL
        }

        /** The logical ID a sent payload is about (for the sent log). */
        fun Payload.originalId(transportId: String): String? = when (this) {
            is Payload.Text -> replaces ?: mid ?: transportId
            is Payload.Read -> replaces
            is Payload.ContactRequest -> replaces
            else -> null
        }

        fun Payload.isResend(): Boolean = when (this) {
            is Payload.Text -> replaces != null
            is Payload.Read -> replaces != null
            is Payload.ContactRequest -> replaces != null
            else -> false
        }

        private fun Payload.resendOf(id: String): Payload = when (this) {
            is Payload.Text -> copy(replaces = id)
            is Payload.Read -> copy(replaces = id)
            is Payload.ContactRequest -> copy(replaces = id)
            else -> this
        }
    }
}
