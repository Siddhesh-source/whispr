package dev.whispr.android.calls

import dev.whispr.domain.model.IceCandidate
import dev.whispr.domain.model.IceServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the media connection is; mirrors WebRTC's peer connection state. */
enum class MediaState { New, Connecting, Connected, Disconnected, Failed, Closed }

/**
 * One call's media: a WebRTC peer connection in the app, a fake in tests.
 * Offers and answers are SDP strings; they travel only inside end-to-end
 * encrypted signaling, which binds the DTLS fingerprints they contain to the
 * peer's verified identity.
 */
interface CallMedia {
    val state: StateFlow<MediaState>

    /** Our ICE candidates as they are gathered; the manager batches and sends them. */
    val localCandidates: Flow<IceCandidate>

    /** [relayOnly] hides our IP address from the peer by using only the server's relay. */
    suspend fun createOffer(video: Boolean, servers: List<IceServer>, relayOnly: Boolean): String

    /** Applies the peer's offer and returns our answer. */
    suspend fun answer(offer: String, video: Boolean, servers: List<IceServer>, relayOnly: Boolean): String

    suspend fun applyAnswer(answer: String)

    /** Candidates that arrive before the remote description are held until it is set. */
    fun addRemoteCandidates(candidates: List<IceCandidate>)

    fun setMicrophoneMuted(muted: Boolean)

    fun setCameraEnabled(enabled: Boolean)

    fun switchCamera()

    fun close()
}

fun interface CallMediaFactory {
    fun create(): CallMedia
}
