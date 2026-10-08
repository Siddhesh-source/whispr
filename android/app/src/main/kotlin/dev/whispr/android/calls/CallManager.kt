package dev.whispr.android.calls

import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.CallRules
import dev.whispr.domain.model.CallSignal
import dev.whispr.domain.model.HangupReason
import dev.whispr.domain.model.IceCandidate
import dev.whispr.domain.model.IncomingCallSignal
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.CallLogRepository
import dev.whispr.domain.repository.CallSignalingRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.SettingsRepository
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a call is, as the call screen shows it. */
enum class CallPhase {
    /** Ours, waiting for them to answer. */
    Dialing,

    /** Theirs, ringing here. */
    Ringing,

    /** Answered; media is connecting. */
    Connecting,
    Connected,
    Ended,
}

/** Why a call could not start; shown on the call screen. */
enum class CallProblem { NotAllowed, MediaFailed }

data class CallUi(
    val callId: String,
    val peer: UserId,
    val peerName: String,
    val video: Boolean,
    val outgoing: Boolean,
    val phase: CallPhase,
    val startedAt: Instant,
    val connectedAt: Instant? = null,
    val muted: Boolean = false,
    val speaker: Boolean = video,
    val cameraOn: Boolean = video,
    /** Set once [phase] is [CallPhase.Ended]. */
    val outcome: CallOutcome? = null,
    val problem: CallProblem? = null,
)

/**
 * One call at a time, end to end: signaling over the encrypted outbox,
 * media through [CallMedia], and the local call log. Every event (user
 * action, signal, media change, timeout) is handled under one lock, so the
 * state machine never sees two at once.
 *
 * ```
 *  start ─▶ Dialing ──answer──▶ Connecting ──media up──▶ Connected ──hangup──▶ Ended
 *  offer ─▶ Ringing ──accept──▶ Connecting ─┘                 any ──timeout/fail──▶ Ended
 * ```
 */
class CallManager(
    private val signaling: CallSignalingRepository,
    private val log: CallLogRepository,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
    private val media: CallMediaFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val endedVisibleMs: Long = ENDED_VISIBLE_MS,
) {
    private val ui = MutableStateFlow<CallUi?>(null)

    /** The current call, or null when idle. */
    val call: StateFlow<CallUi?> = ui.asStateFlow()

    private val lock = Mutex()
    private var session: Session? = null

    private class Session(val media: CallMedia) {
        var offer: String? = null
        val jobs = mutableListOf<Job>()
        var timeout: Job? = null
    }

    /** Starts listening for calls; called once at app start. */
    fun start() {
        scope.launch { signaling.incoming.collect { onSignal(it) } }
    }

    /** Places a call. The UI has already obtained the microphone (and camera) permission. */
    fun startCall(peer: UserId, video: Boolean) = scope.launch {
        lock.withLock {
            if (ui.value?.phase.let { it != null && it != CallPhase.Ended }) return@withLock
            val callId = UUID.randomUUID().toString()
            val s = newSession()
            ui.value = CallUi(callId, peer, nameOf(peer), video, outgoing = true, CallPhase.Dialing, clock())
            signaling.setCallActive(true)
            val offer = try {
                s.media.createOffer(video, signaling.iceServers(), relayOnly())
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                return@withLock end(CallOutcome.Failed, CallProblem.MediaFailed)
            }
            if (!signaling.send(peer, CallSignal.Offer(callId, offer, video))) {
                return@withLock end(CallOutcome.Failed, CallProblem.NotAllowed)
            }
            watchMedia(s, callId)
            s.timeout = timeout(callId, CallRules.RING_TIMEOUT.toMillis()) {
                signaling.send(peer, CallSignal.Hangup(callId, HangupReason.Timeout))
                end(CallOutcome.NoAnswer)
            }
        }
    }

    /** Answers the ringing call. */
    fun accept() = scope.launch {
        lock.withLock {
            val c = ui.value ?: return@withLock
            val s = session ?: return@withLock
            val offer = s.offer ?: return@withLock
            if (c.phase != CallPhase.Ringing) return@withLock
            ui.value = c.copy(phase = CallPhase.Connecting)
            val answer = try {
                s.media.answer(offer, c.video, signaling.iceServers(), relayOnly())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Error))
                return@withLock end(CallOutcome.Failed, CallProblem.MediaFailed)
            }
            signaling.send(c.peer, CallSignal.Answer(c.callId, answer))
            watchMedia(s, c.callId)
            s.timeout?.cancel()
            s.timeout = timeout(c.callId, CallRules.CONNECT_TIMEOUT.toMillis()) {
                signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Error))
                end(CallOutcome.Failed, CallProblem.MediaFailed)
            }
        }
    }

    fun decline() = scope.launch {
        lock.withLock {
            val c = ui.value ?: return@withLock
            if (c.phase != CallPhase.Ringing) return@withLock
            signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Decline))
            end(CallOutcome.Declined)
        }
    }

    fun hangup() = scope.launch {
        lock.withLock {
            val c = ui.value ?: return@withLock
            when (c.phase) {
                CallPhase.Ended -> return@withLock
                CallPhase.Ringing -> {
                    signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Decline))
                    end(CallOutcome.Declined)
                }
                CallPhase.Dialing -> {
                    signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Hangup))
                    end(CallOutcome.Cancelled)
                }
                CallPhase.Connecting, CallPhase.Connected -> {
                    signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Hangup))
                    end(if (c.connectedAt != null) CallOutcome.Completed else CallOutcome.Cancelled)
                }
            }
        }
    }

    fun setMuted(muted: Boolean) = update { c, s ->
        s.media.setMicrophoneMuted(muted)
        c.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) = update { c, _ -> c.copy(speaker = on) }

    fun setCamera(on: Boolean) = update { c, s ->
        s.media.setCameraEnabled(on)
        c.copy(cameraOn = on)
    }

    fun switchCamera() = update { c, s ->
        s.media.switchCamera()
        c
    }

    private fun update(change: (CallUi, Session) -> CallUi) = scope.launch {
        lock.withLock {
            val c = ui.value ?: return@withLock
            val s = session ?: return@withLock
            if (c.phase != CallPhase.Ended) ui.value = change(c, s)
        }
    }

    private suspend fun onSignal(incoming: IncomingCallSignal) = lock.withLock {
        val signal = incoming.signal
        val c = ui.value?.takeIf { it.phase != CallPhase.Ended }
        when (signal) {
            is CallSignal.Offer -> onOffer(incoming, signal, c)
            is CallSignal.Answer -> if (c != null &&
                c.callId == signal.callId &&
                c.peer == incoming.peer &&
                c.phase == CallPhase.Dialing
            ) {
                ui.value = c.copy(phase = CallPhase.Connecting)
                val s = session ?: return@withLock
                try {
                    s.media.applyAnswer(signal.sdp)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Error))
                    return@withLock end(CallOutcome.Failed, CallProblem.MediaFailed)
                }
                s.timeout?.cancel()
                s.timeout = timeout(c.callId, CallRules.CONNECT_TIMEOUT.toMillis()) {
                    signaling.send(c.peer, CallSignal.Hangup(c.callId, HangupReason.Error))
                    end(CallOutcome.Failed, CallProblem.MediaFailed)
                }
            }
            is CallSignal.Ice -> if (c != null && c.callId == signal.callId && c.peer == incoming.peer) {
                session?.media?.addRemoteCandidates(signal.candidates)
            }
            is CallSignal.Hangup -> if (c != null && c.callId == signal.callId && c.peer == incoming.peer) {
                end(
                    when {
                        signal.reason == HangupReason.Busy -> CallOutcome.Busy
                        c.phase == CallPhase.Ringing -> CallOutcome.Missed
                        c.phase == CallPhase.Dialing && signal.reason == HangupReason.Decline -> CallOutcome.Declined
                        c.phase == CallPhase.Dialing -> CallOutcome.NoAnswer
                        c.connectedAt != null -> CallOutcome.Completed
                        else -> CallOutcome.Failed
                    },
                )
            }
        }
    }

    private suspend fun onOffer(incoming: IncomingCallSignal, offer: CallSignal.Offer, current: CallUi?) {
        val now = clock()
        if (!CallRules.ringable(incoming.serverTime, now)) {
            // It reached us after the caller gave up: a missed call, no ring.
            record(offer.callId, incoming.peer, false, offer.video, incoming.serverTime, null, CallOutcome.Missed)
            return
        }
        if (current != null) {
            val glare = current.peer == incoming.peer && current.phase == CallPhase.Dialing
            if (glare && CallRules.glareWinner(current.callId, offer.callId) == current.callId) return
            if (glare) {
                // We both called; theirs wins. Drop ours and answer theirs, as both meant to talk.
                session?.let { closeSession(it) }
                session = null
                ui.value = null
                ring(incoming, offer)
                accept() // queued behind this lock
                return
            }
            // In another call: they hear busy, we log it as missed.
            signaling.send(incoming.peer, CallSignal.Hangup(offer.callId, HangupReason.Busy))
            record(offer.callId, incoming.peer, false, offer.video, now, null, CallOutcome.Missed)
            return
        }
        ring(incoming, offer)
    }

    private suspend fun ring(incoming: IncomingCallSignal, offer: CallSignal.Offer) {
        val s = newSession()
        s.offer = offer.sdp
        ui.value =
            CallUi(
                offer.callId,
                incoming.peer,
                nameOf(incoming.peer),
                offer.video,
                outgoing = false,
                CallPhase.Ringing,
                clock(),
            )
        signaling.setCallActive(true)
        s.timeout = timeout(offer.callId, CallRules.RING_TIMEOUT.toMillis()) {
            signaling.send(incoming.peer, CallSignal.Hangup(offer.callId, HangupReason.Timeout))
            end(CallOutcome.Missed)
        }
    }

    private fun newSession(): Session {
        session?.let { closeSession(it) }
        return Session(media.create()).also { session = it }
    }

    /** Forwards our candidates in small batches, and follows the media connection. */
    private fun watchMedia(s: Session, callId: String) {
        s.jobs += scope.launch {
            val batch = mutableListOf<IceCandidate>()
            var flush: Job? = null
            s.media.localCandidates.collect { candidate ->
                synchronized(batch) { batch += candidate }
                if (flush?.isActive != true) {
                    flush = scope.launch {
                        delay(ICE_BATCH_MS)
                        val out = synchronized(batch) { batch.toList().also { batch.clear() } }
                        val peer = ui.value?.takeIf { it.callId == callId }?.peer ?: return@launch
                        if (out.isNotEmpty()) signaling.send(peer, CallSignal.Ice(callId, out))
                    }
                }
            }
        }
        s.jobs += scope.launch {
            s.media.state.collect { state ->
                lock.withLock {
                    val c = ui.value?.takeIf { it.callId == callId && it.phase != CallPhase.Ended } ?: return@withLock
                    when (state) {
                        MediaState.Connected -> {
                            s.timeout?.cancel()
                            ui.value = c.copy(phase = CallPhase.Connected, connectedAt = c.connectedAt ?: clock())
                        }
                        MediaState.Failed -> {
                            signaling.send(c.peer, CallSignal.Hangup(callId, HangupReason.Error))
                            end(
                                if (c.connectedAt !=
                                    null
                                ) {
                                    CallOutcome.Completed
                                } else {
                                    CallOutcome.Failed
                                },
                                CallProblem.MediaFailed,
                            )
                        }
                        // A brief network change: WebRTC recovers or reports Failed.
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun timeout(callId: String, ms: Long, onTimeout: suspend () -> Unit): Job = scope.launch {
        delay(ms)
        lock.withLock { if (ui.value?.callId == callId && ui.value?.phase != CallPhase.Ended) onTimeout() }
    }

    /** Ends the current call: media closed, logged, "Call ended" shown briefly, then idle. */
    private suspend fun end(outcome: CallOutcome, problem: CallProblem? = null) {
        val c = ui.value ?: return
        val s = session
        session = null
        signaling.setCallActive(false)
        ui.value = c.copy(phase = CallPhase.Ended, outcome = outcome, problem = problem)
        record(c.callId, c.peer, c.outgoing, c.video, c.startedAt, c.connectedAt, outcome)
        scope.launch {
            delay(endedVisibleMs)
            lock.withLock { if (ui.value?.callId == c.callId && ui.value?.phase == CallPhase.Ended) ui.value = null }
        }
        // Last: this may run inside one of the session's own jobs, which this cancels.
        s?.let { closeSession(it) }
    }

    private fun closeSession(s: Session) {
        s.timeout?.cancel()
        s.jobs.forEach { it.cancel() }
        s.media.close()
    }

    private suspend fun record(
        callId: String,
        peer: UserId,
        outgoing: Boolean,
        video: Boolean,
        startedAt: Instant,
        connectedAt: Instant?,
        outcome: CallOutcome,
    ) = log.record(callId, peer, outgoing, video, startedAt, connectedAt, clock(), outcome)

    private suspend fun nameOf(peer: UserId) = contacts.contact(peer)?.displayName.orEmpty()

    private suspend fun relayOnly() = settings.observePrivacy().first().relayCalls

    private companion object {
        const val ICE_BATCH_MS = 100L
        const val ENDED_VISIBLE_MS = 1_500L
    }
}
