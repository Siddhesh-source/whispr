package dev.whispr.android

import dev.whispr.android.calls.CallManager
import dev.whispr.android.calls.CallMedia
import dev.whispr.android.calls.CallPhase
import dev.whispr.android.calls.CallProblem
import dev.whispr.android.calls.MediaState
import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.CallSignal
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.HangupReason
import dev.whispr.domain.model.IceCandidate
import dev.whispr.domain.model.IceServer
import dev.whispr.domain.model.IncomingCallSignal
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.CallLogRepository
import dev.whispr.domain.repository.CallSignalingRepository
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call state machine against fake media and signaling, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {
    private val bob = UserId("bob")

    private class FakeMedia : CallMedia {
        override val state = MutableStateFlow(MediaState.New)
        val candidates = Channel<IceCandidate>(Channel.UNLIMITED)
        override val localCandidates = candidates.receiveAsFlow()
        val remote = mutableListOf<IceCandidate>()
        var answered: String? = null
        var closed = 0
        var muted = false
        var failOffer = false

        override suspend fun createOffer(video: Boolean, servers: List<IceServer>, relayOnly: Boolean): String {
            if (failOffer) error("no media")
            return "offer-sdp video=$video relay=$relayOnly"
        }

        override suspend fun answer(offer: String, video: Boolean, servers: List<IceServer>, relayOnly: Boolean) =
            "answer-to:$offer"

        override suspend fun applyAnswer(answer: String) {
            answered = answer
        }

        override fun addRemoteCandidates(candidates: List<IceCandidate>) {
            remote += candidates
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            this.muted = muted
        }

        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun close() {
            closed++
        }
    }

    private class FakeSignaling : CallSignalingRepository {
        override val incoming = MutableSharedFlow<IncomingCallSignal>(extraBufferCapacity = 16)
        val sent = mutableListOf<Pair<UserId, CallSignal>>()
        var allowed = true
        var active = false
        override suspend fun send(peer: UserId, signal: CallSignal): Boolean {
            if (allowed) sent += peer to signal
            return allowed
        }
        override suspend fun iceServers() = listOf(IceServer(listOf("turn:example")))
        override fun setCallActive(active: Boolean) {
            this.active = active
        }
    }

    private class FakeLog : CallLogRepository {
        val entries = mutableListOf<Pair<String, CallOutcome>>()
        override fun observe() = flowOf(emptyList<CallLogEntry>())
        override suspend fun record(
            callId: String,
            peer: UserId,
            outgoing: Boolean,
            video: Boolean,
            startedAt: Instant,
            connectedAt: Instant?,
            endedAt: Instant,
            outcome: CallOutcome,
        ) {
            entries += callId to outcome
        }
        override suspend fun clear() = entries.clear()
    }

    private val signaling = FakeSignaling()
    private val log = FakeLog()
    private val medias = mutableListOf<FakeMedia>()
    private val settings = FakeSettings()
    private val contacts = FakeContacts().apply {
        contacts.value = listOf(Contact(bob, "Bob", ByteArray(33)))
    }

    private fun TestScope.manager(): CallManager = CallManager(
        signaling,
        log,
        contacts,
        settings,
        { FakeMedia().also { medias += it } },
        backgroundScope,
        clock = { Instant.ofEpochMilli(testScheduler.currentTime) },
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.now() = Instant.ofEpochMilli(testScheduler.currentTime)

    private suspend fun TestScope.receive(signal: CallSignal, serverTime: Instant = now()) {
        signaling.incoming.emit(IncomingCallSignal(bob, signal, serverTime))
        runCurrent()
    }

    @Test
    fun outgoingCallConnectsAndHangsUpWithALogEntry() = runTest {
        val m = manager()
        m.startCall(bob, video = true)
        runCurrent()
        val offer = signaling.sent.single().second as CallSignal.Offer
        assertEquals(CallPhase.Dialing, m.call.value!!.phase)
        assertEquals("Bob", m.call.value!!.peerName)
        assertTrue(signaling.active)

        receive(CallSignal.Answer(offer.callId, "their answer"))
        assertEquals("their answer", medias.single().answered)
        assertEquals(CallPhase.Connecting, m.call.value!!.phase)

        medias.single().state.value = MediaState.Connected
        runCurrent()
        assertEquals(CallPhase.Connected, m.call.value!!.phase)

        advanceTimeBy(60_000)
        m.hangup()
        runCurrent()
        assertEquals(CallSignal.Hangup(offer.callId, HangupReason.Hangup), signaling.sent.last().second)
        assertEquals(CallPhase.Ended, m.call.value!!.phase)
        assertEquals(listOf(offer.callId to CallOutcome.Completed), log.entries)
        assertEquals(1, medias.single().closed)
        assertTrue(!signaling.active)
        advanceTimeBy(2_000)
        assertNull("back to idle after showing the end", m.call.value)
    }

    @Test
    fun localCandidatesAreBatchedAndRemoteOnesApplied() = runTest {
        val m = manager()
        m.startCall(bob, video = false)
        runCurrent()
        val id = (signaling.sent.single().second as CallSignal.Offer).callId
        receive(CallSignal.Answer(id, "a"))
        repeat(3) { medias.single().candidates.send(IceCandidate("0", 0, "c$it")) }
        runCurrent()
        advanceTimeBy(150)
        val ice = signaling.sent.map { it.second }.filterIsInstance<CallSignal.Ice>()
        assertEquals(listOf("c0", "c1", "c2"), ice.single().candidates.map { it.sdp })
        receive(CallSignal.Ice(id, listOf(IceCandidate("0", 0, "theirs"))))
        assertEquals("theirs", medias.single().remote.single().sdp)
        // Candidates for another call are ignored.
        receive(CallSignal.Ice("other", listOf(IceCandidate("0", 0, "x"))))
        assertEquals(1, medias.single().remote.size)
    }

    @Test
    fun unansweredOutgoingCallTimesOut() = runTest {
        val m = manager()
        m.startCall(bob, video = false)
        runCurrent()
        val id = (signaling.sent.single().second as CallSignal.Offer).callId
        advanceTimeBy(46_000)
        assertEquals(CallSignal.Hangup(id, HangupReason.Timeout), signaling.sent.last().second)
        assertEquals(CallOutcome.NoAnswer, m.call.value!!.outcome)
        assertEquals(listOf(id to CallOutcome.NoAnswer), log.entries)
    }

    @Test
    fun declinedAndBusyCallsEndWithThatOutcome() = runTest {
        val m = manager()
        m.startCall(bob, video = false)
        runCurrent()
        val first = (signaling.sent.last().second as CallSignal.Offer).callId
        receive(CallSignal.Hangup(first, HangupReason.Decline))
        assertEquals(CallOutcome.Declined, m.call.value!!.outcome)
        advanceTimeBy(2_000)

        m.startCall(bob, video = false)
        runCurrent()
        val second = (signaling.sent.last().second as CallSignal.Offer).callId
        receive(CallSignal.Hangup(second, HangupReason.Busy))
        assertEquals(CallOutcome.Busy, m.call.value!!.outcome)
    }

    @Test
    fun incomingCallRingsAndCanBeAccepted() = runTest {
        val m = manager()
        receive(CallSignal.Offer("c1", "their offer", video = false))
        assertEquals(CallPhase.Ringing, m.call.value!!.phase)
        assertTrue(!m.call.value!!.outgoing)
        // Candidates that arrive while ringing are kept for the media.
        receive(CallSignal.Ice("c1", listOf(IceCandidate("0", 0, "early"))))
        m.accept()
        runCurrent()
        assertEquals(CallSignal.Answer("c1", "answer-to:their offer"), signaling.sent.single().second)
        assertEquals("early", medias.single().remote.single().sdp)
        medias.single().state.value = MediaState.Connected
        runCurrent()
        assertEquals(CallPhase.Connected, m.call.value!!.phase)
        receive(CallSignal.Hangup("c1", HangupReason.Hangup))
        assertEquals(CallOutcome.Completed, m.call.value!!.outcome)
    }

    @Test
    fun incomingCallDeclinedOrUnansweredOrHungUpWhileRinging() = runTest {
        val m = manager()
        receive(CallSignal.Offer("c1", "o", video = false))
        m.decline()
        runCurrent()
        assertEquals(CallSignal.Hangup("c1", HangupReason.Decline), signaling.sent.last().second)
        assertEquals("c1" to CallOutcome.Declined, log.entries.last())
        advanceTimeBy(2_000)

        receive(CallSignal.Offer("c2", "o", video = false))
        advanceTimeBy(46_000)
        assertEquals("c2" to CallOutcome.Missed, log.entries.last())
        advanceTimeBy(2_000)

        receive(CallSignal.Offer("c3", "o", video = false))
        receive(CallSignal.Hangup("c3", HangupReason.Hangup))
        assertEquals("c3" to CallOutcome.Missed, log.entries.last())
    }

    @Test
    fun staleOfferIsLoggedAsMissedWithoutRinging() = runTest {
        val m = manager()
        advanceTimeBy(10 * 60_000)
        receive(CallSignal.Offer("old", "o", video = true), serverTime = now().minusSeconds(120))
        assertNull(m.call.value)
        assertEquals(listOf("old" to CallOutcome.Missed), log.entries)
        assertTrue(medias.isEmpty())
    }

    @Test
    fun secondCallerHearsBusy() = runTest {
        val m = manager()
        receive(CallSignal.Offer("c1", "o", video = false))
        val carol = UserId("carol")
        signaling.incoming.emit(IncomingCallSignal(carol, CallSignal.Offer("c2", "o", video = false), now()))
        runCurrent()
        assertEquals(carol to CallSignal.Hangup("c2", HangupReason.Busy), signaling.sent.last())
        assertEquals("c1", m.call.value!!.callId)
        assertEquals("c2" to CallOutcome.Missed, log.entries.last())
    }

    @Test
    fun glareKeepsTheLowerCallIdOnBothSides() = runTest {
        val m = manager()
        m.startCall(bob, video = false)
        runCurrent()
        val mine = (signaling.sent.single().second as CallSignal.Offer).callId
        // Their ID sorts before any UUID, so theirs wins: we answer it.
        receive(CallSignal.Offer("0", "their offer", video = false))
        runCurrent()
        assertEquals("0", m.call.value!!.callId)
        assertEquals(CallSignal.Answer("0", "answer-to:their offer"), signaling.sent.last().second)
        assertEquals(1, medias.first().closed)
        advanceTimeBy(2_000)
        m.hangup()
        runCurrent()
        advanceTimeBy(2_000)

        // Theirs sorts after ours: ignored, we keep dialing.
        m.startCall(bob, video = false)
        runCurrent()
        val ours = (signaling.sent.last().second as CallSignal.Offer).callId
        receive(CallSignal.Offer("~", "late offer", video = false))
        assertEquals(ours, m.call.value!!.callId)
        assertEquals(CallPhase.Dialing, m.call.value!!.phase)
        assertTrue(mine != ours)
    }

    @Test
    fun failuresEndTheCallAndSaySo() = runTest {
        val m = manager()
        signaling.allowed = false
        m.startCall(bob, video = false)
        runCurrent()
        assertEquals(CallProblem.NotAllowed, m.call.value!!.problem)
        assertEquals(CallOutcome.Failed, m.call.value!!.outcome)
        advanceTimeBy(2_000)

        signaling.allowed = true
        m.startCall(bob, video = false)
        runCurrent()
        val id = (signaling.sent.last().second as CallSignal.Offer).callId
        receive(CallSignal.Answer(id, "a"))
        medias.last().state.value = MediaState.Failed
        runCurrent()
        assertEquals(CallProblem.MediaFailed, m.call.value!!.problem)
        assertEquals(CallSignal.Hangup(id, HangupReason.Error), signaling.sent.last().second)
        advanceTimeBy(2_000)

        // Answered but media never connects: gives up after the connect timeout.
        m.startCall(bob, video = false)
        runCurrent()
        val third = (signaling.sent.last().second as CallSignal.Offer).callId
        receive(CallSignal.Answer(third, "a"))
        advanceTimeBy(31_000)
        assertEquals(CallOutcome.Failed, m.call.value!!.outcome)
    }

    @Test
    fun relaySettingAndMuteReachTheMedia() = runTest {
        settings.setRelayCalls(true)
        val m = manager()
        m.startCall(bob, video = false)
        runCurrent()
        assertTrue((signaling.sent.single().second as CallSignal.Offer).sdp.contains("relay=true"))
        m.setMuted(true)
        runCurrent()
        assertTrue(medias.single().muted)
        assertTrue(m.call.value!!.muted)
    }
}
