package dev.whispr.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.whispr.android.navigation.ChatDestination
import dev.whispr.android.notifications.ActiveConversation
import dev.whispr.android.ui.chat.ChatContent
import dev.whispr.android.ui.chat.ChatScreen
import dev.whispr.android.ui.chat.ChatUiState
import dev.whispr.android.ui.chat.ChatViewModel
import dev.whispr.android.ui.chat.MessageActions
import dev.whispr.android.ui.chat.group
import dev.whispr.android.ui.search.SearchScreen
import dev.whispr.android.ui.search.SearchUiState
import dev.whispr.android.ui.search.SearchViewModel
import dev.whispr.android.ui.settings.Deletion
import dev.whispr.android.ui.settings.SettingsScreen
import dev.whispr.android.ui.settings.SettingsUiState
import dev.whispr.android.ui.settings.SettingsViewModel
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.Quote
import dev.whispr.domain.model.SearchHit
import dev.whispr.domain.model.UserId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reply, forward, delete, disappearing messages, search, screen security and account deletion. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessageActionsUiTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val peer = Contact(UserId("00000000-0000-0000-0000-0000000000b0"), "Bob", ByteArray(0))

    private fun msg(id: String, outgoing: Boolean, text: String = id, at: Instant = Instant.now()) =
        Message(id, ConversationId("c"), outgoing, text, at, if (outgoing) MessageStatus.Sent else null)

    private fun chatVm(messaging: FakeMessaging) = ChatViewModel(
        SavedStateHandle(route = ChatDestination(peer.userId.value)),
        FakeContacts().apply { contacts.value = listOf(peer) },
        FakeAccounts(registered),
        FakeConnectivity(),
        messaging,
        FakeGroups(),
        ActiveConversation(),
        FakeSettings(),
    )

    @Test
    fun replySendsTheQuotedIdAndClearsTheReply() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val vm = chatVm(messaging)
        val original = msg("m1", false, "lunch?")
        vm.state.test {
            awaitItem()
            vm.replyTo(original)
            assertEquals(original, expectMostRecentItem().replyingTo)
            vm.onInput("yes")
            vm.send()
            assertNull(expectMostRecentItem().replyingTo)
        }
        assertEquals(listOf("m1"), messaging.replies)
    }

    @Test
    fun deletedAndSystemMessagesCannotBeRepliedTo() = runTest(dispatcher) {
        val vm = chatVm(FakeMessaging())
        vm.state.test {
            awaitItem()
            vm.replyTo(msg("d", false).copy(deleted = true))
            assertNull(vm.state.value.replyingTo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteForEveryoneOnlyForOwnRecentMessages() = runTest(dispatcher) {
        val vm = chatVm(FakeMessaging())
        vm.state.test {
            awaitItem()
            assertTrue(vm.canDeleteForEveryone(msg("mine", true)))
            assertFalse(vm.canDeleteForEveryone(msg("theirs", false)))
            assertFalse(vm.canDeleteForEveryone(msg("old", true, at = Instant.now().minus(Duration.ofHours(25)))))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun timerAndDeleteGoToTheRepository() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val vm = chatVm(messaging)
        vm.state.test {
            awaitItem()
            vm.setTimer(3600)
            assertEquals(3600L, expectMostRecentItem().timerSeconds)
            cancelAndIgnoreRemainingEvents()
        }
        vm.deleteForMe("a")
        vm.deleteForEveryone("b")
        vm.forward("c", ConversationId("other"))
        assertEquals(listOf("deleteForMe:a", "deleteForEveryone:b", "forward:c:other"), messaging.actions)
    }

    // ---- screens ----

    @Test
    fun longPressOpensActionsAndReplyIsOffered() {
        var replied: Message? = null
        val m = msg("m", false, "hello there")
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(peerName = "Bob", content = ChatContent.Messages(group(listOf(m)))),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                    messageActions = MessageActions(onReply = { replied = it }),
                )
            }
        }
        rule.onNodeWithContentDescription("hello there", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithText("Copy text").assertIsDisplayed()
        rule.onNodeWithText("Forward").assertIsDisplayed()
        rule.onNodeWithText("Delete for everyone").assertDoesNotExist()
        rule.onNodeWithText("Reply").performClick()
        assertEquals(m, replied)
    }

    @Test
    fun deletedQuotedAndForwardedMessagesRender() {
        val deleted = msg("d", false, "").copy(deleted = true)
        val reply = msg("r", false, "sure").copy(
            quote = Quote(
                "q",
                outgoing = true,
                authorName = null,
                text = "coffee?",
                attachmentKind = null,
                found = true,
            ),
            forwarded = true,
            expiresIn = Duration.ofHours(1),
        )
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(
                        peerName = "Bob",
                        content = ChatContent.Messages(group(listOf(deleted, reply))),
                        replyingTo = reply,
                    ),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithContentDescription("This message was deleted", substring = true).assertExists()
        rule.onAllNodesWithContentDescription("Forwarded. Replying to You: coffee?. Bob, ", substring = true)
            .onFirst()
            .assertExists()
        rule.onAllNodesWithContentDescription("sure. Disappearing message", substring = true).onFirst().assertExists()
        rule.onNodeWithText("Replying to Bob").assertIsDisplayed()
    }

    @Test
    fun searchShowsHitsAndOpensThem() {
        var opened: SearchHit? = null
        val hit = SearchHit(ConversationId("c"), "Bob", msg("m", false, "the meeting is at 5"), peer.userId, null)
        rule.setContent {
            WhisprTheme { SearchScreen(SearchUiState("meeting", listOf(hit)), {}, {}) { opened = it } }
        }
        rule.onNodeWithText("the meeting is at 5").performClick()
        assertEquals(hit, opened)
    }

    @Test
    fun searchViewModelDebouncesAndSkipsBlankQueries() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val vm = SearchViewModel(messaging)
        vm.state.test {
            awaitItem()
            vm.onQuery("   ")
            advanceTimeBy(500)
            vm.onQuery("mee")
            vm.onQuery("meet")
            advanceTimeBy(500)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("search:meet"), messaging.actions)
    }

    @Test
    fun settingsTogglesScreenSecurityAndDeletesTheAccount() = runTest(dispatcher) {
        val accounts = FakeAccounts(registered)
        val auth = FakeAuth()
        val settings = FakeSettings()
        val vm = SettingsViewModel(accounts, auth, FakeConnectivity(), settings, FakeEncryption())
        vm.state.test {
            assertTrue("screen security defaults on", (awaitItem() as SettingsUiState.Content).screenSecurity)
            vm.setScreenSecurity(false)
            assertFalse((expectMostRecentItem() as SettingsUiState.Content).screenSecurity)
            cancelAndIgnoreRemainingEvents()
        }
        auth.deleteResult = AuthResult.Err(AuthError.Network)
        vm.deleteAccount()
        assertEquals(Deletion.FailedNetwork, vm.deletion.value)
        vm.dismissDeletionError()
        auth.deleteResult = AuthResult.Ok(Unit)
        vm.deleteAccount()
        assertEquals(Deletion.Done, vm.deletion.value)
        assertEquals(2, auth.deleteCalls)
    }

    @Test
    fun deleteAccountAsksFirst() {
        var deleted = false
        rule.setContent {
            WhisprTheme {
                SettingsScreen(
                    SettingsUiState.Content(
                        "Ada",
                        null,
                        "id",
                        dev.whispr.android.ui.settings.ConnectionStatus.Active,
                        "1",
                    ),
                    onBack = {},
                    onDeleteAccount = { deleted = true },
                )
            }
        }
        rule.onNodeWithText("Delete account").performScrollTo().performClick()
        assertFalse(deleted)
        rule.onNodeWithText("Delete everything").performClick()
        assertTrue(deleted)
    }
}
