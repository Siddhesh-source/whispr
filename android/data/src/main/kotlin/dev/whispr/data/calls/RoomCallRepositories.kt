package dev.whispr.data.calls

import dev.whispr.data.db.CallEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.IceCandidatePayload
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.CallSignal
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.IceServer
import dev.whispr.domain.model.IncomingCallSignal
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.CallLogRepository
import dev.whispr.domain.repository.CallSignalingRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Call signaling over the ordinary encrypted outbox, at call priority so it
 * overtakes queued messages and statuses. The SDP inside the offer and
 * answer carries the DTLS fingerprints, so the media connection is bound to
 * the libsignal-authenticated peer.
 */
class RoomCallSignalingRepository(
    private val db: WhisprDatabase,
    private val engine: MessagingEngine,
    private val api: WhisprApi,
    private val accounts: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : CallSignalingRepository {

    override val incoming: Flow<IncomingCallSignal> = engine.calls

    override suspend fun send(peer: UserId, signal: CallSignal): Boolean {
        val me = accounts.getAccount()?.userId ?: return false
        val contact = db.contactDao().get(peer.value) ?: return false
        if (contact.isRequest || contact.hidden || contact.trust == TrustState.KeyChanged.name) return false
        val now = clock()
        db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = ConversationId.direct(me, peer).value,
                recipientId = peer.value,
                payload = PayloadCodec.encode(signal.toPayload(now)),
                clientTs = now,
                priority = OutboxEntity.PRIORITY_CALL,
            ),
        )
        return true
    }

    override suspend fun iceServers(): List<IceServer> = when (val r = api.turnCredentials()) {
        is ApiResult.Success -> listOf(IceServer(r.body.urls, r.body.username, r.body.credential))
        else -> emptyList()
    }

    override fun setCallActive(active: Boolean) = engine.setCallActive(active)

    private fun CallSignal.toPayload(now: Long): Payload = when (this) {
        is CallSignal.Offer -> Payload.CallOffer(callId, sdp, video, now)
        is CallSignal.Answer -> Payload.CallAnswer(callId, sdp)
        is CallSignal.Ice -> Payload.CallIce(
            callId,
            candidates.map {
                IceCandidatePayload(it.sdpMid, it.sdpMLineIndex, it.sdp)
            },
        )
        is CallSignal.Hangup -> Payload.CallHangup(callId, reason.name.lowercase())
    }
}

class RoomCallLogRepository(private val db: WhisprDatabase) : CallLogRepository {
    override fun observe(): Flow<List<CallLogEntry>> = db.callDao().observe().map { rows ->
        rows.map {
            CallLogEntry(
                id = it.callId,
                peer = UserId(it.peerId),
                peerName = it.peerName.orEmpty(),
                outgoing = it.outgoing,
                video = it.video,
                startedAt = Instant.ofEpochMilli(it.startedAt),
                duration = it.connectedAt?.let { c -> Duration.ofMillis((it.endedAt - c).coerceAtLeast(0)) },
                outcome = CallOutcome.valueOf(it.outcome),
            )
        }
    }

    override suspend fun record(
        callId: String,
        peer: UserId,
        outgoing: Boolean,
        video: Boolean,
        startedAt: Instant,
        connectedAt: Instant?,
        endedAt: Instant,
        outcome: CallOutcome,
    ) = db.callDao().put(
        CallEntity(
            callId = callId,
            peerId = peer.value,
            outgoing = outgoing,
            video = video,
            startedAt = startedAt.toEpochMilli(),
            connectedAt = connectedAt?.toEpochMilli(),
            endedAt = endedAt.toEpochMilli(),
            outcome = outcome.name,
        ),
    )

    override suspend fun clear() = db.callDao().clear()
}
