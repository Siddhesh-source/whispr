package dev.whispr.data.messaging

import dev.whispr.data.crypto.ParkReason
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SessionStatus
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.PendingResetEntity
import dev.whispr.data.db.Placeholder
import dev.whispr.data.db.SettingEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.UserId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives session resets for envelopes we could not decrypt, from the
 * durable `pending_resets` queue:
 *
 * - At most one reset per peer every 5 minutes; failures in between are
 *   queued and go out together in the next one.
 * - The old session is only replaced once a usable bundle is in hand; while
 *   the peer's lane is blocked (key change, no keys) requests just wait.
 * - A request counts as unanswered only 24 h after the server confirmed the
 *   reset reached the peer's phone; after 3 such attempts, or 30 days in
 *   total, the placeholder is marked unrecoverable.
 */
class ResetCoordinator(
    private val db: WhisprDatabase,
    private val crypto: SessionCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.cryptoDao()
    private val mutex = Mutex()

    suspend fun run(me: String) = mutex.withLock {
        val now = clock()
        crypto.transaction { expire(now) }
        for (peer in crypto.transaction { dao.peersWithQueuedResets() }) {
            val last = crypto.transaction { dao.setting(lastResetKey(peer))?.toLongOrNull() }
            if (last != null && now - last < COOLDOWN_MS) continue
            val parked = crypto.transaction { dao.parked(peer) }
            val blocked =
                parked != null &&
                    (parked.reason == ParkReason.KeyChanged.name || parked.reason == ParkReason.NoKeys.name)
            if (blocked || crypto.ensureSession(peer, rebuild = true) is SessionStatus.Blocked) {
                crypto.transaction { markWaiting(peer) }
                continue
            }
            crypto.transaction {
                val ids = dao.queuedResets(peer)
                if (ids.isEmpty()) return@transaction
                val resetId = UUID.randomUUID().toString()
                dao.enqueue(
                    OutboxEntity(
                        messageId = resetId,
                        conversationId = ConversationId.direct(UserId(me), UserId(peer)).value,
                        recipientId = peer,
                        payload = PayloadCodec.encode(Payload.SessionReset(ids)),
                        clientTs = now,
                    ),
                )
                dao.markAwaiting(peer, ids, resetId)
                ids.forEach {
                    dao.setPlaceholderState(peer, it, Placeholder.Pending.name, listOf(Placeholder.Waiting.name))
                }
                dao.putSetting(SettingEntity(lastResetKey(peer), now.toString()))
            }
        }
    }

    private fun markWaiting(peer: String) {
        dao.queuedResets(peer).forEach {
            dao.setPlaceholderState(peer, it, Placeholder.Waiting.name, listOf(Placeholder.Pending.name))
        }
    }

    /** Timeouts and retention, all in one transaction. */
    private fun expire(now: Long) {
        for (row in dao.unansweredResets(now - ANSWER_WINDOW_MS)) {
            if (row.attempts >= MAX_ATTEMPTS) finalise(row) else dao.requeueReset(row.peerId, row.transportId)
        }
        dao.staleResets(now - RETENTION_MS).forEach(::finalise)
        for (held in dao.expiredHeld(now - RETENTION_MS)) {
            dao.releaseHeld(held.senderId, held.transportId)
            dao.setPlaceholderState(
                held.senderId,
                held.transportId,
                Placeholder.Unrecoverable.name,
                listOf(Placeholder.Held.name),
            )
        }
        dao.purgeSeen(now - RETENTION_MS)
        dao.purgeSent(now - RETENTION_MS)
    }

    private fun finalise(row: PendingResetEntity) {
        dao.setPlaceholderState(
            row.peerId,
            row.transportId,
            Placeholder.Unrecoverable.name,
            listOf(Placeholder.Pending.name, Placeholder.Waiting.name),
        )
        dao.resolveReset(row.peerId, row.transportId)
    }

    private fun lastResetKey(peer: String) = "e2e.resetAt.$peer"

    companion object {
        const val COOLDOWN_MS = 5 * 60 * 1000L
        const val ANSWER_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val RETENTION_MS = 30 * 24 * 60 * 60 * 1000L
        const val MAX_ATTEMPTS = 3
    }
}
