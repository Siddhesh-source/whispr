package dev.whispr.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.whispr.android.ui.chat.group
import dev.whispr.core.designsystem.component.BubbleGroupPosition
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessagingUiTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val peer = Contact(UserId("00000000-0000-0000-0000-0000000000b0"), "Bob", ByteArray(0))

    private fun msg(id: String, outgoing: Boolean, text: String = id, status: MessageStatus? = null) =
        Message(id, ConversationId("c"), outgoing, text, Instant.parse("2026-01-01T10:00:00Z"), status)

    @Test
    fun groupingFollowsDirectionRuns() {
        val items =
            group(
                listOf(
                    msg("a", false),
                    msg("b", false),
                    msg("c", true),
                    msg("d", false),
                    msg("e", false),
                    msg("f", false),
                ),
            )
        assertEquals(
            listOf(
                BubbleGroupPosition.First,
                BubbleGroupPosition.Last,
                BubbleGroupPosition.Single,
                BubbleGroupPosition.First,
                BubbleGroupPosition.Middle,
                BubbleGroupPosition.Last,
            ),
            items.map { it.position },
        )
    }

    private fun chatVm(
        messaging: FakeMessaging,
        contacts: FakeContacts,
        active: ActiveConversation = ActiveConversation(),
    ) = ChatViewModel(
        SavedStateHandle(route = ChatDestination(peer.userId.value)),
        contacts,
        FakeAccounts(registered),
        FakeConnectivity(),
        messaging,
        active,
    )

    @Test
    fun chatViewModelSendsTrimmedTextAndClearsInput() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val vm = chatVm(messaging, FakeContacts().apply { contacts.value = listOf(peer) })
        vm.state.test {
            awaitItem()
            vm.onInput("  hello  ")
            vm.send()
            assertEquals("", expectMostRecentItem().input)
        }
        assertEquals(listOf(peer.userId to "hello"), messaging.sent)
        assertEquals("typing signal while typing", 1, messaging.typingCalls)
    }

    @Test
    fun chatViewModelIgnoresBlankSend() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val vm = chatVm(messaging, FakeContacts().apply { contacts.value = listOf(peer) })
        vm.onInput("   ")
        vm.send()
        assertTrue(messaging.sent.isEmpty())
    }

    @Test
    fun chatViewModelMissingContactIsAnError() = runTest(dispatcher) {
        val vm = chatVm(FakeMessaging(), FakeContacts())
        vm.state.test { assertEquals(ChatContent.Missing, expectMostRecentItem().content) }
    }

    @Test
    fun visibleChatMarksReadAndSuppressesNotifications() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val active = ActiveConversation()
        val vm = chatVm(messaging, FakeContacts().apply { contacts.value = listOf(peer) }, active)
        vm.onVisible()
        assertEquals(1, messaging.markedRead)
        assertEquals(ConversationId.direct(registered.userId!!, peer.userId), active.current.value)
        vm.onHidden()
        assertNull(active.current.value)
    }

    // ---- screens ----

    private fun chat(state: ChatUiState, onRetry: (String) -> Unit = {}) = rule.setContent {
        WhisprTheme { ChatScreen(state, onBack = {}, onInput = {}, onSend = {}, onRetry = onRetry) }
    }

    @Test
    fun chatLoading() {
        chat(ChatUiState(peerName = "Bob", content = ChatContent.Loading))
        rule.onNodeWithContentDescription("Loading messages").assertIsDisplayed()
    }

    @Test
    fun chatEmptyShowsInputAndNoEncryptionWarning() {
        chat(ChatUiState(peerName = "Bob", content = ChatContent.Messages(emptyList())))
        rule.onNodeWithText("Say hello").assertIsDisplayed()
        rule.onNodeWithText("Not end-to-end encrypted", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun chatShowsNoticeInPlaceOfAnUndecryptableMessage() {
        val pending = msg("p", false, "").copy(notice = MessageNotice.Pending)
        val held = msg("h", false, "").copy(notice = MessageNotice.Held)
        chat(ChatUiState(peerName = "Bob", content = ChatContent.Messages(group(listOf(pending, held)))))
        rule.onNodeWithContentDescription(
            "Couldn't decrypt this message. Asked the sender to resend.",
            substring = true,
        )
            .assertExists()
        rule.onNodeWithContentDescription(
            "Held: safety number changed. Review to read.",
            substring = true,
        ).assertExists()
    }

    @Test
    fun chatMissingContact() {
        chat(ChatUiState(content = ChatContent.Missing))
        rule.onNodeWithText("Contact not found").assertIsDisplayed()
    }

    @Test
    fun chatMessagesTypingOfflineAndRetry() {
        var retried: String? = null
        chat(
            ChatUiState(
                peerName = "Bob",
                content = ChatContent.Messages(
                    group(listOf(msg("in", false, "hi there"), msg("out", true, "not sent", MessageStatus.Failed))),
                ),
                peerTyping = true,
                offline = true,
            ),
            onRetry = { retried = it },
        )
        rule.onNodeWithText("typing…").assertIsDisplayed()
        rule.onNodeWithText("You're offline. Messages will send when you reconnect.").assertIsDisplayed()
        // Exists rather than displayed: the list follows the newest message, so
        // the first bubble may be scrolled out of Robolectric's small window.
        rule.onNodeWithContentDescription("hi there", substring = true).assertExists()
        rule.onNodeWithContentDescription("Not sent", substring = true).performClick()
        assertEquals("out", retried)
    }
}
