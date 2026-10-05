package dev.whispr.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.whispr.android.navigation.GroupChatDestination
import dev.whispr.android.navigation.GroupInfoDestination
import dev.whispr.android.notifications.ActiveConversation
import dev.whispr.android.ui.chat.AttachmentActions
import dev.whispr.android.ui.chat.ChatContent
import dev.whispr.android.ui.chat.ChatError
import dev.whispr.android.ui.chat.ChatScreen
import dev.whispr.android.ui.chat.ChatUiState
import dev.whispr.android.ui.chat.ChatViewModel
import dev.whispr.android.ui.chat.group
import dev.whispr.android.ui.chats.ChatsContent
import dev.whispr.android.ui.chats.ChatsScreen
import dev.whispr.android.ui.chats.ChatsUiState
import dev.whispr.android.ui.groups.GroupInfoScreen
import dev.whispr.android.ui.groups.GroupInfoUiState
import dev.whispr.android.ui.groups.GroupInfoViewModel
import dev.whispr.android.ui.groups.NewGroupViewModel
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.Attachment
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.Group
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupMember
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.GroupSummary
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.Reaction
import dev.whispr.domain.model.SendResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GroupsUiTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val gid = GroupId("22222222-2222-4222-8222-222222222222")
    private val me = registered.userId!!
    private val sam = UserId("00000000-0000-0000-0000-00000000005a")
    private val alex = Contact(UserId("00000000-0000-0000-0000-0000000000a1"), "Alex", ByteArray(33))

    private fun groupOf(status: GroupStatus = GroupStatus.Active, admin: Boolean = true) = Group(
        gid,
        "Hikers",
        null,
        listOf(
            GroupMember(me, "Ada", if (admin) GroupRole.Admin else GroupRole.Member, isMe = true),
            GroupMember(sam, "Sam", if (admin) GroupRole.Member else GroupRole.Admin),
        ),
        status,
    )

    private fun msg(
        id: String,
        outgoing: Boolean = false,
        text: String = id,
        author: UserId? = sam,
        authorName: String? = "Sam",
        attachment: Attachment? = null,
        reactions: List<Reaction> = emptyList(),
        system: Boolean = false,
    ) = Message(
        id,
        gid.conversation,
        outgoing,
        text,
        Instant.parse("2026-01-01T10:00:00Z"),
        null,
        author = if (outgoing) null else author,
        authorName = if (outgoing) null else authorName,
        attachment = attachment,
        reactions = reactions,
        system = system,
    )

    private fun groupVm(messaging: FakeMessaging, groups: FakeGroups) = ChatViewModel(
        SavedStateHandle(route = GroupChatDestination(gid.value)),
        FakeContacts(),
        FakeAccounts(registered),
        FakeConnectivity(),
        messaging,
        groups,
        ActiveConversation(),
    )

    // ---- ViewModels ----

    @Test
    fun groupChatSendsToTheGroupOnlyWhileActive() = runTest(dispatcher) {
        val messaging = FakeMessaging()
        val groups = FakeGroups().apply { this.groups.value = mapOf(gid to groupOf()) }
        val vm = groupVm(messaging, groups)
        vm.state.test {
            val s = expectMostRecentItem()
            assertTrue(s.isGroup)
            assertEquals(2, s.memberCount)
            assertTrue(s.canCompose)
            vm.onInput(" hi all ")
            vm.send()
            assertEquals(listOf(gid to "hi all"), messaging.groupSent)
            assertEquals("no typing indicators in groups", 0, messaging.typingCalls)

            groups.groups.value = mapOf(gid to groupOf(GroupStatus.Removed))
            assertFalse(expectMostRecentItem().canCompose)
            vm.onInput("still here?")
            vm.send()
            assertEquals(1, messaging.groupSent.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mediaSendErrorsAreShownNotSwallowed() = runTest(dispatcher) {
        val messaging = FakeMessaging().apply { mediaResult = SendResult.TooLarge }
        val groups = FakeGroups().apply { this.groups.value = mapOf(gid to groupOf()) }
        val vm = groupVm(messaging, groups)
        vm.state.test {
            expectMostRecentItem()
            vm.sendMedia("content://big", AttachmentKind.File)
            assertEquals(ChatError.TooLarge, expectMostRecentItem().error)
            vm.dismissError()
            assertEquals(null, expectMostRecentItem().error)
        }
        assertEquals("content://big", messaging.media.single().uri)
    }

    @Test
    fun invitationsAreAnsweredThroughTheRepository() = runTest(dispatcher) {
        val groups = FakeGroups().apply { this.groups.value = mapOf(gid to groupOf(GroupStatus.Invited)) }
        val vm = groupVm(FakeMessaging(), groups)
        vm.state.test {
            assertFalse(expectMostRecentItem().canCompose)
            vm.acceptInvite()
            var left = false
            vm.declineInvite { left = true }
            assertTrue(left)
        }
        assertEquals(listOf("accept", "decline"), groups.calls)
    }

    @Test
    fun newGroupNeedsANameAndMembers() = runTest(dispatcher) {
        val groups = FakeGroups()
        val contacts = FakeContacts().apply {
            contacts.value = listOf(alex, alex.copy(userId = UserId("r"), displayName = "Req", isRequest = true))
        }
        val vm = NewGroupViewModel(contacts, groups)
        vm.state.test {
            val first = expectMostRecentItem()
            assertEquals("requests can't be added", listOf(alex), first.contacts)
            assertFalse(first.canCreate)
            vm.onName("Hikers")
            assertFalse(expectMostRecentItem().canCreate)
            vm.toggle(alex.userId)
            assertTrue(expectMostRecentItem().canCreate)
            var created: GroupId? = null
            vm.create { created = it }
            assertEquals(GroupId("11111111-1111-4111-8111-111111111111"), created)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("create:Hikers:1"), groups.calls)
    }

    @Test
    fun groupInfoOffersOnlyContactsNotYetInTheGroup() = runTest(dispatcher) {
        val groups = FakeGroups().apply {
            this.groups.value = mapOf(gid to groupOf())
            result = GroupResult.NotAllowed
        }
        val contacts = FakeContacts().apply {
            contacts.value = listOf(alex, Contact(sam, "Sam", ByteArray(33)))
        }
        val vm = GroupInfoViewModel(SavedStateHandle(route = GroupInfoDestination(gid.value)), contacts, groups)
        vm.state.test {
            assertEquals(listOf(alex), expectMostRecentItem().candidates)
            vm.remove(sam)
            assertEquals(dev.whispr.android.ui.groups.GroupError.NotAllowed, expectMostRecentItem().error)
        }
    }

    @Test
    fun runsBreakWhenTheAuthorChanges() {
        val items = group(listOf(msg("a"), msg("b"), msg("c", author = UserId("x")), msg("s", system = true)))
        assertEquals(
            listOf("First", "Last", "Single", "Single"),
            items.map { it.position.name },
        )
    }

    // ---- Screens ----

    private fun chat(
        state: ChatUiState,
        actions: AttachmentActions = AttachmentActions.None,
        onReact: (String, String?) -> Unit = {
                _,
                _,
            ->
        },
    ) = rule.setContent {
        WhisprTheme {
            ChatScreen(state, onBack = {
            }, onInput = {}, onSend = {}, onRetry = {}, onReact = onReact, attachments = actions)
        }
    }

    private fun groupState(vararg messages: Message, status: GroupStatus = GroupStatus.Active) = ChatUiState(
        peerName = "Hikers",
        content = ChatContent.Messages(group(messages.toList())),
        isGroup = true,
        groupStatus = status,
        memberCount = 3,
    )

    @Test
    fun groupChatShowsAuthorsEventsAndReactions() {
        chat(
            groupState(
                msg("sys", text = "Sam added Alex", system = true, author = null),
                msg("m1", text = "morning", reactions = listOf(Reaction("👍", 2, mine = true))),
            ),
        )
        rule.onNodeWithText("Sam added Alex").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sam, ", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("👍, 2, including yours").assertIsDisplayed()
        rule.onNodeWithText("3 members").assertIsDisplayed()
        rule.onNodeWithContentDescription("Group info").assertIsDisplayed()
    }

    @Test
    fun longPressOpensTheReactionPicker() {
        val picked = mutableListOf<Pair<String, String?>>()
        chat(groupState(msg("m1", text = "react to me")), onReact = { id, e -> picked += id to e })
        rule.onNodeWithContentDescription("react to me", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("❤️").performClick()
        assertEquals(listOf("m1" to "❤️"), picked)
    }

    @Test
    fun removedOrInvitedGroupsCannotBeWrittenTo() {
        chat(groupState(msg("m1"), status = GroupStatus.Removed))
        rule.onNodeWithText("You're no longer in this group.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertDoesNotExist()
    }

    @Test
    fun invitationShowsAcceptAndDecline() {
        chat(groupState(status = GroupStatus.Invited))
        rule.onNodeWithText("You're invited to Hikers", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Accept").assertIsDisplayed()
    }

    @Test
    fun remoteImagesDownloadAndStatesAreVisible() {
        val downloads = mutableListOf<String>()
        val actions = object : AttachmentActions {
            override fun download(messageId: String) {
                downloads += messageId
            }
            override suspend fun bytes(messageId: String): ByteArray? = null
            override suspend fun export(messageId: String): String? = null
        }
        fun att(kind: AttachmentKind, state: AttachmentState, name: String? = null) =
            Attachment(kind, "x/y", name, 2048, durationMs = 61_000, state = state)
        chat(
            groupState(
                msg("img", text = "", attachment = att(AttachmentKind.Image, AttachmentState.Remote)),
                msg("old", text = "", attachment = att(AttachmentKind.File, AttachmentState.Expired, "a.pdf")),
                msg("bad", text = "", attachment = att(AttachmentKind.File, AttachmentState.Corrupt, "b.pdf")),
                msg("voice", text = "", attachment = att(AttachmentKind.Voice, AttachmentState.Remote)),
            ),
            actions,
        )
        rule.waitForIdle()
        assertEquals("images and voice download when shown; files on demand", setOf("img", "voice"), downloads.toSet())
        rule.onNodeWithText("No longer available").assertIsDisplayed()
        rule.onNodeWithText("Couldn't verify this file").assertIsDisplayed()
        rule.onNodeWithText("Voice message, 1:01").assertIsDisplayed()
        rule.onNodeWithText("a.pdf").assertIsDisplayed()
    }

    @Test
    fun mediaErrorDialog() {
        chat(groupState(msg("m")).copy(error = ChatError.TooLarge))
        rule.onNodeWithText("That file is larger than 25 MB.").assertIsDisplayed()
    }

    @Test
    fun chatListShowsGroupsAndPutsInvitesWithRequests() {
        val last = msg("m", text = "see you", author = sam, authorName = "Sam")
        rule.setContent {
            WhisprTheme {
                ChatsScreen(
                    ChatsUiState(
                        content = ChatsContent.Conversations(
                            listOf(
                                ConversationSummary(
                                    gid.conversation,
                                    null,
                                    last,
                                    2,
                                    GroupSummary(gid, "Hikers", null, GroupStatus.Active),
                                ),
                                ConversationSummary(
                                    GroupId("33333333-3333-4333-8333-333333333333").conversation,
                                    null,
                                    null,
                                    0,
                                    GroupSummary(
                                        GroupId("33333333-3333-4333-8333-333333333333"),
                                        "Book club",
                                        null,
                                        GroupStatus.Invited,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithContentDescription(
            "Hikers. 2 unread messages. Last message: Sam: see you",
            substring = true,
        ).assertIsDisplayed()
        rule.onNodeWithText("Requests").assertIsDisplayed()
        rule.onNodeWithContentDescription(
            "Last message: Invited you to the group",
            substring = true,
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription("New group").assertIsDisplayed()
    }

    @Test
    fun groupInfoShowsAdminControlsOnlyToAdmins() {
        var left = 0
        rule.setContent {
            WhisprTheme {
                GroupInfoScreen(
                    GroupInfoUiState(
                        groupOf(admin = true),
                        loading = false,
                        candidates = listOf(alex),
                    ),
                    onBack = {
                    },
                    onLeave = { left++ },
                )
            }
        }
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Add members").assertIsEnabled()
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Manage Sam"))
        rule.onNodeWithContentDescription("Manage Sam").assertIsDisplayed()
        rule.onNodeWithText("The name, picture and member list are end-to-end encrypted.").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Leave group"))
        rule.onNodeWithText("Leave group").performClick()
        rule.onNodeWithText("Leave this group?").assertIsDisplayed()
    }

    @Test
    fun membersSeeNoAdminControls() {
        rule.setContent {
            WhisprTheme { GroupInfoScreen(GroupInfoUiState(groupOf(admin = false), loading = false), onBack = {}) }
        }
        rule.onNodeWithText("Rename").assertDoesNotExist()
        rule.onNodeWithText("Add members").assertDoesNotExist()
        rule.onNodeWithContentDescription("Manage Sam").assertDoesNotExist()
        rule.onNodeWithText("Admin").assertIsDisplayed()
    }

    @Test
    fun addMembersIsDisabledWithNobodyToAdd() {
        rule.setContent {
            WhisprTheme {
                GroupInfoScreen(GroupInfoUiState(groupOf(), loading = false, candidates = emptyList()), onBack = {})
            }
        }
        rule.onNodeWithText("Add members").assertIsNotEnabled()
    }
}
