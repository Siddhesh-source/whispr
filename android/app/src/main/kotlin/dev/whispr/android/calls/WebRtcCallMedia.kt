package dev.whispr.android.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.whispr.domain.model.IceCandidate
import dev.whispr.domain.model.IceServer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The process-wide WebRTC setup: one factory and one EGL context (shared by
 * the encoder, decoder and the call screen's renderers), created on first
 * use. Also tracks the call in progress so the screen can show its video.
 */
class WebRtcEnvironment(private val context: Context) : CallMediaFactory {
    val egl: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule(),
            )
            .createPeerConnectionFactory()
    }

    private val current = MutableStateFlow<WebRtcCallMedia?>(null)

    /** The media of the call in progress, for its video renderers. */
    val media: StateFlow<WebRtcCallMedia?> = current.asStateFlow()

    override fun create(): CallMedia = WebRtcCallMedia(context, this).also { current.value = it }

    internal fun released(media: WebRtcCallMedia) {
        current.compareAndSet(media, null)
    }
}

/**
 * One call's WebRTC peer connection: Opus audio, VP8/H.264 video, DTLS-SRTP.
 * Every native call runs on one thread, so closing never races a callback.
 */
class WebRtcCallMedia internal constructor(private val context: Context, private val env: WebRtcEnvironment) :
    CallMedia {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "whispr-call") }
    private val mediaState = MutableStateFlow(MediaState.New)
    override val state: StateFlow<MediaState> = mediaState.asStateFlow()

    private val candidates = Channel<IceCandidate>(Channel.UNLIMITED)
    override val localCandidates: Flow<IceCandidate> = candidates.receiveAsFlow()

    private val local = MutableStateFlow<VideoTrack?>(null)
    private val remote = MutableStateFlow<VideoTrack?>(null)

    /** Our camera, while video is on. */
    val localVideo: StateFlow<VideoTrack?> = local.asStateFlow()

    /** Their camera, once it arrives. */
    val remoteVideo: StateFlow<VideoTrack?> = remote.asStateFlow()

    private val frontCamera = MutableStateFlow(true)

    /** Whether our preview should be mirrored (front camera). */
    val mirrored: StateFlow<Boolean> = frontCamera.asStateFlow()

    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    private val pending = mutableListOf<org.webrtc.IceCandidate>()
    private var closed = false

    override suspend fun createOffer(video: Boolean, servers: List<IceServer>, relayOnly: Boolean): String {
        onThread { setUp(video, servers, relayOnly) }
        val pc = requireNotNull(pc)
        val offer = pc.awaitCreate { o -> pc.createOffer(o, constraints(video)) }
        pc.awaitSet { o -> pc.setLocalDescription(o, offer) }
        return offer.description
    }

    override suspend fun answer(offer: String, video: Boolean, servers: List<IceServer>, relayOnly: Boolean): String {
        onThread { setUp(video, servers, relayOnly) }
        val pc = requireNotNull(pc)
        pc.awaitSet { o -> pc.setRemoteDescription(o, SessionDescription(SessionDescription.Type.OFFER, offer)) }
        onThread { drainPending() }
        val answer = pc.awaitCreate { o -> pc.createAnswer(o, constraints(video)) }
        pc.awaitSet { o -> pc.setLocalDescription(o, answer) }
        return answer.description
    }

    override suspend fun applyAnswer(answer: String) {
        val pc = requireNotNull(pc)
        pc.awaitSet { o -> pc.setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        onThread { drainPending() }
    }

    override fun addRemoteCandidates(candidates: List<IceCandidate>) = post {
        if (closed) return@post
        val converted = candidates.map { org.webrtc.IceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp) }
        val pc = pc
        if (pc == null || pc.remoteDescription == null) pending += converted else converted.forEach(pc::addIceCandidate)
    }

    override fun setMicrophoneMuted(muted: Boolean) = post { audioTrack?.setEnabled(!muted) }

    override fun setCameraEnabled(enabled: Boolean) = post {
        val c = capturer ?: return@post
        if (enabled) c.startCapture(WIDTH, HEIGHT, FPS) else c.stopCapture()
        local.value?.setEnabled(enabled)
    }

    override fun switchCamera() = post {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontCamera.value = isFront
            }

            override fun onCameraSwitchError(error: String?) = Unit
        })
    }

    override fun close() = post {
        if (closed) return@post
        closed = true
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        textures?.dispose()
        local.value = null
        remote.value = null
        pc?.dispose() // also disposes the tracks added to it
        videoSource?.dispose()
        audioSource?.dispose()
        pc = null
        mediaState.value = MediaState.Closed
        candidates.close()
        env.released(this)
        thread.shutdown()
    }

    private fun setUp(video: Boolean, servers: List<IceServer>, relayOnly: Boolean) {
        check(pc == null) { "already set up" }
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls)
                .apply {
                    s.username?.let(::setUsername)
                    s.credential?.let(::setPassword)
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Relay only: the peer never learns our IP address (Settings → Privacy).
            iceTransportsType =
                if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val factory = env.factory
        val pc = requireNotNull(factory.createPeerConnection(config, Observer())) { "no peer connection" }
        this.pc = pc
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack(AUDIO_ID, audioSource).also { pc.addTrack(it, listOf(STREAM_ID)) }
        if (video) startCamera(pc)
    }

    private fun startCamera(pc: PeerConnection) {
        // Camera refused: the call goes on with audio only.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: return // no camera: an audio-only call that still works
        frontCamera.value = enumerator.isFrontFacing(name)
        val capturer = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("whispr-camera", env.egl.eglBaseContext)
        val source = env.factory.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        val track = env.factory.createVideoTrack(VIDEO_ID, source)
        pc.addTrack(track, listOf(STREAM_ID))
        this.capturer = capturer
        this.textures = helper
        this.videoSource = source
        local.value = track
    }

    private fun drainPending() {
        val pc = pc ?: return
        pending.forEach(pc::addIceCandidate)
        pending.clear()
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString())
    }

    /** Runs [block] on the call thread; ignored once the call is closed. */
    private fun post(block: () -> Unit) {
        try {
            thread.execute(block)
        } catch (_: RejectedExecutionException) {
            // Closed: nothing left to change.
        }
    }

    private suspend fun onThread(block: () -> Unit) = suspendCancellableCoroutine { c ->
        thread.execute {
            try {
                block()
                c.resume(Unit)
            } catch (e: Exception) {
                c.resumeWithException(e)
            }
        }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
            candidates.trySend(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            mediaState.value = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> MediaState.New
                PeerConnection.PeerConnectionState.CONNECTING -> MediaState.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> MediaState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> MediaState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> MediaState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> MediaState.Closed
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let { remote.value = it }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        const val AUDIO_ID = "whispr-audio"
        const val VIDEO_ID = "whispr-video"
        const val STREAM_ID = "whispr"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
    }
}

private suspend fun PeerConnection.awaitCreate(start: (SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { c ->
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = c.resume(sdp)
            override fun onCreateFailure(error: String?) = c.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

private suspend fun PeerConnection.awaitSet(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { c ->
    start(object : SdpObserver {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(error: String?) = c.resumeWithException(IllegalStateException(error))
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
    })
}
