package dev.whispr.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.android.calls.CallPhase
import dev.whispr.android.calls.CallProblem
import dev.whispr.android.calls.CallScreen
import dev.whispr.android.calls.CallUi
import dev.whispr.android.calls.formatCallTime
import dev.whispr.android.ui.calls.CallsScreen
import dev.whispr.android.ui.calls.CallsUiState
import dev.whispr.android.ui.home.HomeBadges
import dev.whispr.android.ui.home.HomeScreen
import dev.whispr.android.ui.status.StatusScreen
import dev.whispr.android.ui.status.StatusStart
import dev.whispr.android.ui.status.StatusUiState
import dev.whispr.android.ui.status.StatusViewer
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.StatusAuthor
import dev.whispr.domain.model.StatusFeed
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.StatusKind
import dev.whispr.domain.model.StatusSendState
import dev.whispr.domain.model.UserId
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Status tab, status viewer, Calls tab, call screen and the bottom bar. */
@RunWith(AndroidJUnit4::class)
class StatusCallsUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = UserId("me")
    private val ada = UserId("ada")

    private fun item(id: String, author: UserId, mine: Boolean = false, viewed: Boolean = false, text: String = id) =
        StatusItem(
            id = id,
            author = author,
            mine = mine,
            kind = StatusKind.Text,
            text = text,
            background = 1,
            image = null,
            createdAt = Instant.now().minusSeconds(3_600),
            expiresAt = Instant.now().plusSeconds(80_000),
            viewed = viewed,
        )

    // ---- Status ----

    @Test
    fun emptyStatusTabInvitesAPostAndExplainsPrivacy() {
        val started = mutableListOf<StatusStart>()
        rule.setContent {
            WhisprTheme { StatusScreen(StatusUiState(loading = false, myName = "Me", myId = me), started::add, {}) }
        }
        rule.onNodeWithText("My status").assertIsDisplayed()
        rule.onNodeWithText("Tap to add an update. It disappears after 24 hours.").assertIsDisplayed()
        rule.onNodeWithText("nobody is told when you view theirs", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Photo status").performClick()
        rule.onNodeWithText("New status", useUnmergedTree = true).performClick()
        rule.onNodeWithText("My status").performClick()
        assertEquals(listOf(StatusStart.Camera, StatusStart.Text, StatusStart.Text), started)
    }

    @Test
    fun recentAndViewedAreSeparateAndOpenTheAuthor() {
        val opened = mutableListOf<UserId>()
        val feed = StatusFeed(
            recent = listOf(StatusAuthor(ada, "Ada", listOf(item("a1", ada)))),
            viewed = listOf(StatusAuthor(UserId("bo"), "Bo", listOf(item("b1", UserId("bo"), viewed = true)))),
        )
        rule.setContent {
            WhisprTheme {
                StatusScreen(StatusUiState(loading = false, myName = "Me", myId = me, feed = feed), {}, opened::add)
            }
        }
        rule.onNodeWithText("Recent updates").assertIsDisplayed()
        rule.onNodeWithText("Viewed updates").assertIsDisplayed()
        rule.onNodeWithText("Ada").performClick()
        rule.onNodeWithText("Bo").performClick()
        assertEquals(listOf(ada, UserId("bo")), opened)
    }

    @Test
    fun failedOwnStatusSaysSoAndRetries() {
        val retried = mutableListOf<StatusItem>()
        val failed = item("m1", me, mine = true).copy(sendState = StatusSendState.Failed, kind = StatusKind.Image)
        rule.setContent {
            WhisprTheme {
                StatusScreen(
                    StatusUiState(loading = false, myName = "Me", myId = me, feed = StatusFeed(mine = listOf(failed))),
                    {},
                    {},
                    onRetry = retried::add,
                )
            }
        }
        rule.onNodeWithText("Not sent. Tap to retry.").performClick()
        assertEquals(listOf(failed), retried)
    }

    @Test
    fun viewerShowsTheTextMarksItSeenAndMovesOnTap() {
        val seen = mutableListOf<String>()
        val moves = mutableListOf<Int>()
        rule.setContent {
            WhisprTheme {
                StatusViewer(
                    name = "Ada",
                    items = listOf(item("a1", ada, text = "Summit at dawn"), item("a2", ada)),
                    index = 0,
                    onIndex = { moves += it },
                    onClose = {},
                    onSeen = { seen += it.id },
                    loadImage = { null },
                )
            }
        }
        rule.onNodeWithText("Summit at dawn").assertIsDisplayed()
        rule.onNodeWithContentDescription("Update 1 of 2").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(listOf("a1"), seen)
        // Auto-advance after 5 s.
        rule.mainClock.advanceTimeBy(5_100)
        assertTrue(moves.contains(1))
    }

    @Test
    fun ownStatusInTheViewerCanBeDeleted() {
        val deleted = mutableListOf<String>()
        rule.setContent {
            WhisprTheme {
                StatusViewer(
                    name = "My status",
                    items = listOf(item("m1", me, mine = true, viewed = true)),
                    index = 0,
                    onIndex = {},
                    onClose = {},
                    onSeen = {},
                    loadImage = { null },
                    onDelete = { deleted += it.id },
                )
            }
        }
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf("m1"), deleted)
    }

    // ---- Calls ----

    private fun entry(id: String, outcome: CallOutcome, video: Boolean = false, outgoing: Boolean = false) =
        CallLogEntry(
            id = id,
            peer = ada,
            peerName = "Ada",
            outgoing = outgoing,
            video = video,
            startedAt = Instant.now(),
            duration = if (outcome == CallOutcome.Completed) Duration.ofSeconds(125) else null,
            outcome = outcome,
        )

    @Test
    fun callLogDescribesEachCallAndCallsBack() {
        val calls = mutableListOf<Pair<UserId, Boolean>>()
        rule.setContent {
            WhisprTheme {
                CallsScreen(
                    CallsUiState(
                        loading = false,
                        calls = listOf(entry("1", CallOutcome.Missed, video = true), entry("2", CallOutcome.Completed)),
                    ),
                    onCall = { p, v -> calls += p to v },
                )
            }
        }
        rule.onNodeWithText("Video call · Missed", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Voice call · 2 min", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Video call Ada").performClick()
        rule.onNodeWithContentDescription("Voice call Ada").performClick()
        assertEquals(listOf(ada to true, ada to false), calls)
    }

    @Test
    fun emptyCallLog() {
        rule.setContent { WhisprTheme { CallsScreen(CallsUiState(loading = false), onCall = { _, _ -> }) } }
        rule.onNodeWithText("No calls yet").assertIsDisplayed()
    }

    private fun call(phase: CallPhase, video: Boolean = false, outcome: CallOutcome? = null) = CallUi(
        callId = "c",
        peer = ada,
        peerName = "Ada Lovelace",
        video = video,
        outgoing = phase == CallPhase.Dialing,
        phase = phase,
        startedAt = Instant.now(),
        connectedAt = if (phase == CallPhase.Connected) Instant.now() else null,
        outcome = outcome,
    )

    private fun callScreen(c: CallUi, log: MutableList<String>) = rule.setContent {
        WhisprTheme {
            CallScreen(
                call = c,
                video = null,
                onAccept = { log += "accept" },
                onDecline = { log += "decline" },
                onHangUp = { log += "hangup" },
                onMute = { log += "mute:$it" },
                onSpeaker = { log += "speaker:$it" },
                onCamera = { log += "camera:$it" },
                onSwitchCamera = { log += "switch" },
                onMinimize = { log += "minimize" },
            )
        }
    }

    @Test
    fun ringingShowsOnlyAcceptAndDecline() {
        val log = mutableListOf<String>()
        callScreen(call(CallPhase.Ringing, video = true), log)
        rule.onNodeWithText("Incoming video call").assertIsDisplayed()
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mute").assertDoesNotExist()
        rule.onNodeWithContentDescription("Decline").performClick()
        rule.onNodeWithContentDescription("Accept").performClick()
        assertEquals(listOf("decline", "accept"), log)
    }

    @Test
    fun connectedCallHasControlsAndATimer() {
        val log = mutableListOf<String>()
        callScreen(call(CallPhase.Connected, video = true), log)
        rule.onNodeWithText("0:00").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mute").performClick()
        rule.onNodeWithContentDescription("Speaker off").performClick()
        rule.onNodeWithContentDescription("Turn camera off").performClick()
        rule.onNodeWithContentDescription("Switch camera").performClick()
        rule.onNodeWithContentDescription("Back to the app").performClick()
        rule.onNodeWithContentDescription("Hang up").performClick()
        assertEquals(
            listOf("mute:true", "speaker:false", "camera:false", "switch", "minimize", "hangup"),
            log,
        )
    }

    @Test
    fun endedCallSaysWhy() {
        callScreen(call(CallPhase.Ended, outcome = CallOutcome.Busy), mutableListOf())
        rule.onNodeWithText("Busy").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hang up").assertDoesNotExist()
    }

    @Test
    fun notAllowedCallExplainsItself() {
        callScreen(
            call(CallPhase.Ended, outcome = CallOutcome.Failed).copy(problem = CallProblem.NotAllowed),
            mutableListOf(),
        )
        rule.onNodeWithText("You can call this person once", substring = true).assertIsDisplayed()
    }

    @Test
    fun callTimeFormats() {
        assertEquals("0:07", formatCallTime(7))
        assertEquals("12:00", formatCallTime(720))
        assertEquals("1:02:03", formatCallTime(3_723))
    }

    // ---- Home ----

    @Test
    fun bottomBarSwitchesTabsAndShowsUnread() {
        rule.setContent {
            WhisprTheme {
                HomeScreen(
                    HomeBadges(unread = 3, unseenStatus = true),
                    chats = { androidx.compose.material3.Text("CHATS TAB") },
                    status = { androidx.compose.material3.Text("STATUS TAB") },
                    calls = { androidx.compose.material3.Text("CALLS TAB") },
                )
            }
        }
        rule.onNodeWithText("CHATS TAB").assertIsDisplayed()
        rule.onNodeWithText("3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Status").performClick()
        rule.onNodeWithText("STATUS TAB").assertIsDisplayed()
        rule.onNodeWithText("Calls").performClick()
        rule.onNodeWithText("CALLS TAB").assertIsDisplayed()
    }

    @Test
    fun missedCallNotificationOpensTheCallsTab() {
        var consumed = false
        rule.setContent {
            WhisprTheme {
                HomeScreen(
                    HomeBadges(),
                    chats = { androidx.compose.material3.Text("CHATS TAB") },
                    status = {},
                    calls = { androidx.compose.material3.Text("CALLS TAB") },
                    openCallsTab = true,
                    onCallsTabOpened = { consumed = true },
                )
            }
        }
        rule.onNodeWithText("CALLS TAB").assertIsDisplayed()
        assertTrue(consumed)
    }
}
