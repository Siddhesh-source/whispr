package dev.whispr.domain.model

import java.time.Duration
import java.time.Instant

/** How a call ended, as the call log shows it. */
enum class CallOutcome {
    /** Connected, then hung up by either side. */
    Completed,

    /** Incoming, not answered (or it reached us too late to ring). */
    Missed,

    /** We or they declined it. */
    Declined,

    /** The other side was in another call. */
    Busy,

    /** Outgoing, nobody answered within the ring time. */
    NoAnswer,

    /** We hung up before it connected. */
    Cancelled,

    /** Media never connected, or the call broke. */
    Failed,
}

data class CallLogEntry(
    val id: String,
    val peer: UserId,
    val peerName: String,
    val outgoing: Boolean,
    val video: Boolean,
    val startedAt: Instant,
    /** Talk time; null if it never connected. */
    val duration: Duration?,
    val outcome: CallOutcome,
)

/**
 * Call signaling. Every signal travels as an end-to-end encrypted payload
 * between pinned identities; the SDP inside carries the DTLS fingerprints
 * that authenticate the media connection.
 */
sealed interface CallSignal {
    val callId: String

    data class Offer(override val callId: String, val sdp: String, val video: Boolean) : CallSignal

    data class Answer(override val callId: String, val sdp: String) : CallSignal

    data class Ice(override val callId: String, val candidates: List<IceCandidate>) : CallSignal

    data class Hangup(override val callId: String, val reason: HangupReason) : CallSignal
}

data class IceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

enum class HangupReason { Hangup, Decline, Busy, Timeout, Error }

/** A signal from [peer]; [serverTime] is when the server accepted it (not the sender's clock). */
data class IncomingCallSignal(val peer: UserId, val signal: CallSignal, val serverTime: Instant)

/** A STUN or TURN server for one call. */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

object CallRules {
    /** An offer rings only if the server accepted it this recently; older ones are logged as missed. */
    val RING_FRESHNESS: Duration = Duration.ofSeconds(45)

    /** How long an incoming call rings, and an outgoing one waits for an answer. */
    val RING_TIMEOUT: Duration = Duration.ofSeconds(45)

    /** How long we wait for media to connect after an answer. */
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

    const val MAX_SDP = 32 * 1024
    const val MAX_CANDIDATES = 32
    const val MAX_CANDIDATE_LEN = 512

    /** Whether an offer accepted by the server at [serverTime] may still ring at [now]. */
    fun ringable(serverTime: Instant, now: Instant): Boolean =
        !serverTime.isBefore(now.minus(RING_FRESHNESS)) && !serverTime.isAfter(now.plus(RING_FRESHNESS))

    /**
     * Two people called each other at once: the call with the lower ID wins,
     * so both sides pick the same one without talking.
     */
    fun glareWinner(mine: String, theirs: String): String = minOf(mine, theirs)
}
