package dev.whispr.domain.repository

import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.CallSignal
import dev.whispr.domain.model.IceServer
import dev.whispr.domain.model.IncomingCallSignal
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.StatusFeed
import dev.whispr.domain.model.UserId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Status updates: 24-hour posts to every accepted contact. A photo is
 * encrypted and uploaded once; each contact gets its key in a pairwise
 * end-to-end encrypted message. Viewing sends nothing back.
 */
interface StatusRepository {
    fun observeFeed(): Flow<StatusFeed>

    /** Posts a text status on palette background [background]. False if it can't be posted. */
    suspend fun postText(text: String, background: Int): Boolean

    /** Posts a photo (re-encoded, metadata stripped) with an optional caption. */
    suspend fun postImage(uri: String, caption: String): SendResult

    /** Retries a photo status whose upload failed. */
    suspend fun retry(statusId: String)

    /** Deletes our status [statusId] here and asks every contact to delete their copy. */
    suspend fun delete(statusId: String)

    suspend fun markViewed(author: UserId, statusId: String)

    /** The decrypted photo of a status, downloading it first if needed; null if unavailable. */
    suspend fun imageBytes(author: UserId, statusId: String): ByteArray?
}

/** Sends and receives call signaling, and supplies the relay servers for a call. */
interface CallSignalingRepository {
    /** Signals from contacts, as they arrive (the call manager decides what rings). */
    val incoming: Flow<IncomingCallSignal>

    /** Queues [signal] to [peer] ahead of other traffic. False if we can't call them (request, key changed). */
    suspend fun send(peer: UserId, signal: CallSignal): Boolean

    /** STUN/TURN servers for a new call; empty when the server offers none (then STUN-free direct only). */
    suspend fun iceServers(): List<IceServer>

    /** Keeps the connection to the server open while a call exists. */
    fun setCallActive(active: Boolean)
}

/** The local call log (never leaves the device). */
interface CallLogRepository {
    fun observe(): Flow<List<CallLogEntry>>

    suspend fun record(
        callId: String,
        peer: UserId,
        outgoing: Boolean,
        video: Boolean,
        startedAt: Instant,
        connectedAt: Instant?,
        endedAt: Instant,
        outcome: CallOutcome,
    )

    suspend fun clear()
}
