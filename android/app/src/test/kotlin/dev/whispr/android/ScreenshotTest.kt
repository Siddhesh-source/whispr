package dev.whispr.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.whispr.android.calls.CallPhase
import dev.whispr.android.calls.CallScreen
import dev.whispr.android.calls.CallUi
import dev.whispr.android.ui.calls.CallsScreen
import dev.whispr.android.ui.calls.CallsUiState
import dev.whispr.android.ui.chat.ChatContent
import dev.whispr.android.ui.chat.ChatScreen
import dev.whispr.android.ui.chat.ChatUiState
import dev.whispr.android.ui.chat.group
import dev.whispr.android.ui.chats.ChatsContent
import dev.whispr.android.ui.chats.ChatsScreen
import dev.whispr.android.ui.chats.ChatsUiState
import dev.whispr.android.ui.contacts.AddContactScreen
import dev.whispr.android.ui.contacts.AddContactUiState
import dev.whispr.android.ui.groups.GroupInfoScreen
import dev.whispr.android.ui.groups.GroupInfoUiState
import dev.whispr.android.ui.groups.NewGroupScreen
import dev.whispr.android.ui.groups.NewGroupUiState
import dev.whispr.android.ui.home.HomeBadges
import dev.whispr.android.ui.home.HomeScreen
import dev.whispr.android.ui.onboarding.OnboardingScreen
import dev.whispr.android.ui.onboarding.OnboardingUiState
import dev.whispr.android.ui.profile.EditProfileScreen
import dev.whispr.android.ui.profile.EditProfileUiState
import dev.whispr.android.ui.profile.MyCodeScreen
import dev.whispr.android.ui.profile.MyCodeUiState
import dev.whispr.android.ui.search.SearchScreen
import dev.whispr.android.ui.search.SearchUiState
import dev.whispr.android.ui.settings.ConnectionStatus
import dev.whispr.android.ui.settings.SettingsScreen
import dev.whispr.android.ui.settings.SettingsUiState
import dev.whispr.android.ui.status.StatusScreen
import dev.whispr.android.ui.status.StatusUiState
import dev.whispr.android.ui.status.StatusViewer
import dev.whispr.android.ui.verify.VerifyContent
import dev.whispr.android.ui.verify.VerifyScreen
import dev.whispr.android.ui.verify.VerifyUiState
import dev.whispr.core.designsystem.component.RecordingBar
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.Group
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupMember
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.Quote
import dev.whispr.domain.model.Reaction
import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.SearchHit
import dev.whispr.domain.model.StatusAuthor
import dev.whispr.domain.model.StatusFeed
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.StatusKind
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import java.time.Duration
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with sample content to PNGs under app/screenshots
 * for design review. Run: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {

    @Test fun chatsLight() = capture("chats_light") { chats() }

    @Test fun chatsDark() = capture("chats_dark", dark = true) { chats() }

    @Test fun chatLight() = capture("chat_light") { chat(timer = 0) }

    @Test fun chatDark() = capture("chat_dark", dark = true) { chat(timer = 0) }

    @Test fun chatDisappearing() = capture("chat_disappearing_light") { chat(timer = DAY) }

    @Test fun recordingLight() = capture("recording_light") {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(WhisprTheme.spacing.md)) {
            RecordingBar(
                elapsedMs = 7_400,
                levels = List(48) { i -> ((i * 37) % 11) / 10f },
                onCancel = {},
                onSend = {},
            )
        }
    }

    @Test fun homeLight() = capture("home_light") {
        HomeScreen(HomeBadges(unread = 3, unseenStatus = true), chats = { chats() }, status = {}, calls = {})
    }

    @Test fun homeDark() = capture("home_dark", dark = true) {
        HomeScreen(HomeBadges(unread = 3, unseenStatus = true), chats = { chats() }, status = {}, calls = {})
    }

    @Test fun statusLight() = capture("status_light") { statusTab() }

    @Test fun statusDark() = capture("status_dark", dark = true) { statusTab() }

    @Test fun statusViewerLight() = capture("status_viewer") {
        StatusViewer(
            name = "Maya Chen",
            items = listOf(
                statusItem("1", "Maya", "Summit at dawn. Worth the 4am start.", 0),
                statusItem("2", "Maya", "x", 2),
            ),
            index = 0,
            onIndex = {},
            onClose = {},
            onSeen = {},
            loadImage = { null },
        )
    }

    @Test fun callsLight() = capture("calls_light") { callsTab() }

    @Test fun callsDark() = capture("calls_dark", dark = true) { callsTab() }

    @Test fun callRinging() = capture("call_ringing") { callScreen(CallPhase.Ringing) }

    @Test fun callConnected() = capture("call_connected") { callScreen(CallPhase.Connected) }

    @Test fun settingsLight() = capture("settings_light") { settings() }

    @Test fun settingsDark() = capture("settings_dark", dark = true) { settings() }

    @Test fun onboardingLight() = capture("onboarding_light") { onboarding() }

    @Test fun onboardingDark() = capture("onboarding_dark", dark = true) { onboarding() }

    @Test fun verifyLight() = capture("verify_light") { verify() }

    @Test fun addContactLight() = capture("add_contact_light") {
        AddContactScreen(
            AddContactUiState(username = "maya.42"),
            onBack = {},
            onUsername = {},
            onSubmit = {},
            onScan = {},
            onMyCode = {},
        )
    }

    @Test fun myCodeLight() = capture("my_code_light") {
        MyCodeScreen(
            MyCodeUiState(
                code = "whispr:AQAAAAAAAAAAAAAAAAAAAAAAAA",
                name = "Ada Lovelace",
                username = "ada.42",
            ),
            onBack = {
            },
        )
    }

    @Test fun editProfileLight() = capture("edit_profile_light") {
        EditProfileScreen(
            EditProfileUiState(profile = MyProfile(me, "Ada Lovelace", "ada.42", null)),
            onBack = {},
            onName = {},
            onSaveName = {},
            onNickname = {},
            onClaim = {},
            onClear = {},
            onPickPhoto = {},
        )
    }

    @Test fun newGroupLight() = capture("new_group_light") {
        NewGroupScreen(
            NewGroupUiState(
                name = "Climbing Saturday",
                contacts = listOf(
                    contact(1, "Maya Chen", TrustState.Verified),
                    contact(2, "Amara Okafor"),
                    contact(3, "Jonas Weber"),
                ),
                selected = setOf(UserId("00000000-0000-0000-0000-000000000001")),
            ),
            onBack = {},
            onName = {},
            onToggle = {},
            onCreate = {},
        )
    }

    @Test fun groupInfoLight() = capture("group_info_light") {
        GroupInfoScreen(
            GroupInfoUiState(
                group = Group(
                    GroupId("11111111-1111-4111-8111-111111111111"),
                    "Climbing Saturday",
                    null,
                    listOf(
                        GroupMember(me, "Ada Lovelace", GroupRole.Admin, isMe = true),
                        GroupMember(UserId("00000000-0000-0000-0000-000000000001"), "Maya Chen", GroupRole.Member),
                        GroupMember(
                            UserId("00000000-0000-0000-0000-000000000002"),
                            "Jonas Weber",
                            GroupRole.Member,
                            invited = true,
                        ),
                    ),
                    GroupStatus.Active,
                ),
                loading = false,
            ),
            onBack = {},
        )
    }

    @Test fun searchLight() = capture("search_light") {
        SearchScreen(
            SearchUiState(
                "rope",
                listOf(
                    SearchHit(
                        ConversationId("1"),
                        "Maya Chen",
                        msg("2", "Yes. Leaving at 7, I'll bring the rope.", true, 7),
                        UserId("00000000-0000-0000-0000-000000000001"),
                        null,
                    ),
                ),
            ),
            onBack = {},
            onQuery = {},
            onOpen = {},
        )
    }

    @Test fun emptyChatsLight() = capture("chats_empty_light") {
        ChatsScreen(ChatsUiState(content = ChatsContent.Empty), onOpenSettings = {}, onRetry = {})
    }

    private val now = Instant.parse("2026-10-08T09:21:00Z")
    private val me = UserId("00000000-0000-0000-0000-0000000000a0")

    private fun contact(id: Int, name: String, trust: TrustState = TrustState.Unverified, request: Boolean = false) =
        Contact(UserId("00000000-0000-0000-0000-00000000000$id"), name, ByteArray(0), trust, request)

    private fun msg(
        id: String,
        text: String,
        outgoing: Boolean,
        minutesAgo: Long,
        status: MessageStatus? = if (outgoing) MessageStatus.Read else null,
    ) = Message(id, ConversationId("c"), outgoing, text, now.minusSeconds(minutesAgo * 60), status)

    @Composable
    private fun chats() {
        val maya = contact(1, "Maya Chen", TrustState.Verified)
        val items = listOf(
            ConversationSummary(ConversationId("1"), maya, msg("a", "That route looks perfect", false, 1), 2),
            ConversationSummary(
                ConversationId("2"),
                contact(2, "Amara Okafor"),
                msg("b", "Sent the contract draft", true, 40),
                0,
            ),
            ConversationSummary(
                ConversationId("3"),
                contact(3, "Lukas Petrov", TrustState.KeyChanged),
                msg("c", "ok", false, 60 * 26),
                0,
            ),
            ConversationSummary(
                ConversationId("4"),
                contact(4, "Noor Saleh", TrustState.Verified),
                msg("d", "See you at the lake on Saturday!", false, 60 * 50),
                14,
            ),
            ConversationSummary(
                ConversationId("5"),
                contact(5, "Jonas Weber"),
                msg("e", "Thanks, that worked.", true, 60 * 80),
                0,
            ),
        )
        ChatsScreen(ChatsUiState(content = ChatsContent.Conversations(items)), onOpenSettings = {}, onRetry = {})
    }

    @Composable
    private fun chat(timer: Long) {
        val expiring = if (timer > 0) Duration.ofSeconds(timer) else null
        val messages = listOf(
            msg("1", "Are we still on for the climb on Saturday?", false, 9),
            msg("2", "Yes. Leaving at 7, I'll bring the rope.", true, 7),
            msg("3", "Coffee first?", true, 7, MessageStatus.Delivered),
            msg("4", "Absolutely, the place by the station opens at 6:30", false, 5).copy(
                reactions = listOf(Reaction("👍", 1, mine = true)),
            ),
            msg("5", "That route looks perfect", true, 1, MessageStatus.Sent).copy(
                quote = Quote(
                    "4",
                    outgoing = false,
                    authorName = null,
                    text = "Absolutely, the place by the station opens at 6:30",
                    attachmentKind = null,
                    found = true,
                ),
            ),
            msg("6", "Bring the blue chalk bag?", false, 0).copy(expiresIn = expiring),
        )
        ChatScreen(
            ChatUiState(
                peerName = "Maya Chen",
                content = ChatContent.Messages(group(messages)),
                trust = TrustState.Verified,
                timerSeconds = timer,
            ),
            onBack = {},
            onInput = {},
            onSend = {},
            onRetry = {},
        )
    }

    @Composable
    private fun settings() = SettingsScreen(
        SettingsUiState.Content(
            displayName = "Ada Lovelace",
            avatarPath = null,
            userId = me.value,
            connection = ConnectionStatus.Active,
            version = "0.1.0-beta.1",
            screenSecurity = true,
        ),
        onBack = {},
    )

    @Composable
    private fun onboarding() = OnboardingScreen(
        OnboardingUiState(name = "Ada"),
        onNameChange = {},
        onPickAvatar = {},
        onSubmit = {},
        avatarPreview = null,
    )

    @Composable
    private fun verify() = VerifyScreen(
        VerifyUiState(
            name = "Lukas Petrov",
            content = VerifyContent.Ready(
                SafetyNumber("371849025166403128905512708316944723106570938265418301249176", "whispr-sn:AAAA"),
                verified = false,
            ),
        ),
        onBack = {},
        onStartScan = {},
        onScanned = {},
        onSetVerified = {},
    )

    private fun statusItem(
        id: String,
        author: String,
        text: String,
        bg: Int,
        viewed: Boolean = false,
        hoursAgo: Long = 2,
    ) = StatusItem(
        id = id,
        author = UserId(author),
        mine = author == "me",
        kind = StatusKind.Text,
        text = text,
        background = bg,
        image = null,
        createdAt = Instant.now().minus(Duration.ofHours(hoursAgo)),
        expiresAt = Instant.now().plus(Duration.ofHours(20)),
        viewed = viewed,
    )

    @Composable
    private fun statusTab() = StatusScreen(
        StatusUiState(
            loading = false,
            myName = "Ada Lovelace",
            myId = UserId("me"),
            feed = StatusFeed(
                mine = listOf(statusItem("m", "me", "Back Monday", 3, viewed = true, hoursAgo = 1)),
                recent = listOf(
                    StatusAuthor(UserId("maya"), "Maya Chen", listOf(statusItem("1", "maya", "a", 0, hoursAgo = 1))),
                    StatusAuthor(
                        UserId("lukas"),
                        "Lukas Petrov",
                        listOf(statusItem("2", "lukas", "b", 1, hoursAgo = 3)),
                    ),
                ),
                viewed = listOf(
                    StatusAuthor(
                        UserId("sam"),
                        "Sam Okafor",
                        listOf(statusItem("3", "sam", "c", 2, viewed = true, hoursAgo = 9)),
                    ),
                ),
            ),
        ),
        onCompose = {},
        onOpen = {},
    )

    @Composable
    private fun callsTab() {
        fun e(id: String, name: String, outcome: CallOutcome, video: Boolean, outgoing: Boolean, minutesAgo: Long) =
            CallLogEntry(
                id,
                UserId(name),
                name,
                outgoing,
                video,
                Instant.now().minus(Duration.ofMinutes(minutesAgo)),
                if (outcome == CallOutcome.Completed) Duration.ofSeconds(754) else null,
                outcome,
            )
        CallsScreen(
            CallsUiState(
                loading = false,
                calls = listOf(
                    e("1", "Maya Chen", CallOutcome.Completed, video = true, outgoing = true, minutesAgo = 12),
                    e("2", "Lukas Petrov", CallOutcome.Missed, video = false, outgoing = false, minutesAgo = 95),
                    e("3", "Sam Okafor", CallOutcome.Completed, video = false, outgoing = false, minutesAgo = 300),
                    e("4", "Maya Chen", CallOutcome.NoAnswer, video = false, outgoing = true, minutesAgo = 2000),
                ),
            ),
            onCall = { _, _ -> },
        )
    }

    @Composable
    private fun callScreen(phase: CallPhase) = CallScreen(
        call = CallUi(
            callId = "c",
            peer = UserId("maya"),
            peerName = "Maya Chen",
            video = false,
            outgoing = false,
            phase = phase,
            startedAt = Instant.now(),
            connectedAt = if (phase == CallPhase.Connected) Instant.now().minusSeconds(83) else null,
        ),
        video = null,
        onAccept = {},
        onDecline = {},
        onHangUp = {},
        onMute = {},
        onSpeaker = {},
        onCamera = {},
        onSwitchCamera = {},
        onMinimize = {},
    )

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        captureRoboImage("screenshots/$name.png") { WhisprTheme(darkTheme = dark) { content() } }
    }

    private companion object {
        const val DAY = 86_400L
    }
}
