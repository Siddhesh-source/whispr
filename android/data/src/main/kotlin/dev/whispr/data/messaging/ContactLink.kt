package dev.whispr.data.messaging

import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.OutboxEntity
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Who may reach whom. Accounts are private: adding someone only sends a
 * request, and nothing else flows in either direction until they accept.
 * Every function runs inside a database transaction (sync DAO calls).
 */
internal object ContactLink {
    /** Accepted both ways: messages, calls, statuses and profiles flow (their key may still be on its way). */
    fun connected(c: ContactEntity?): Boolean = c != null && !c.isRequest && !c.hidden && !c.awaitingAccept

    /** Like [connected], with a key pinned that we still trust (calls, statuses). */
    fun trusted(c: ContactEntity?): Boolean =
        connected(c) && c!!.identityKey.isNotEmpty() && c.trust != TrustState.KeyChanged.name

    /** We accept [peer]: answer, and send them our profile. */
    fun accept(dao: CryptoDao, peer: String, clock: () -> Long) {
        val account = dao.account() ?: return
        val me = account.userId ?: return
        enqueue(dao, me, peer, Payload.ContactAccept(account.displayName), clock)
        sendProfile(dao, peer, clock)
    }

    fun sendProfile(dao: CryptoDao, peer: String, clock: () -> Long) {
        val me = dao.account()?.userId ?: return
        profile(dao, clock)?.let { enqueue(dao, me, peer, it, clock) }
    }

    /** Sends our current profile to every accepted contact. */
    fun broadcastProfile(dao: CryptoDao, clock: () -> Long) {
        val me = dao.account()?.userId ?: return
        val p = profile(dao, clock) ?: return
        dao.connectedIds().filter { it != me }.forEach { enqueue(dao, me, it, p, clock) }
    }

    private fun profile(dao: CryptoDao, clock: () -> Long): Payload.Profile? {
        val account = dao.account() ?: return null
        val avatar = account.avatarPath?.let { shareable(it) }
        return Payload.Profile(account.displayName, avatar?.let { Base64.getEncoder().encodeToString(it) }, clock())
    }

    /** The small copy of our avatar that goes to contacts (written next to it on import). */
    fun shareable(avatarPath: String): ByteArray? = File("$avatarPath$SHARE_SUFFIX").takeIf { it.isFile }
        ?.readBytes()?.takeIf { it.size <= Payload.Profile.MAX_AVATAR_BYTES }

    fun enqueue(dao: CryptoDao, me: String, peer: String, payload: Payload, clock: () -> Long) {
        dao.enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = ConversationId.direct(UserId(me), UserId(peer)).value,
                recipientId = peer,
                payload = PayloadCodec.encode(payload),
                clientTs = clock(),
            ),
        )
    }

    const val SHARE_SUFFIX = ".share"
}
