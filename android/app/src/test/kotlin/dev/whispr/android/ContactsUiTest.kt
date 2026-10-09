package dev.whispr.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.whispr.android.navigation.VerifyDestination
import dev.whispr.android.ui.chat.ChatContent
import dev.whispr.android.ui.chat.ChatScreen
import dev.whispr.android.ui.chat.ChatUiState
import dev.whispr.android.ui.contacts.AddContactError
import dev.whispr.android.ui.contacts.AddContactScreen
import dev.whispr.android.ui.contacts.AddContactUiState
import dev.whispr.android.ui.contacts.AddContactViewModel
import dev.whispr.android.ui.profile.ContactProfileScreen
import dev.whispr.android.ui.profile.ContactProfileUiState
import dev.whispr.android.ui.profile.EditProfileViewModel
import dev.whispr.android.ui.profile.ProfileMessage
import dev.whispr.android.ui.scan.ScanContactViewModel
import dev.whispr.android.ui.verify.VerifyContent
import dev.whispr.android.ui.verify.VerifyViewModel
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.ProfileResult
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
import dev.whispr.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ContactsUiTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val peer = Contact(UserId("00000000-0000-0000-0000-0000000000b0"), "Bob", ByteArray(33))

    @Test
    fun usernameLookupMapsResults() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val vm = AddContactViewModel(contacts, FakeConnectivity())
        vm.state.test {
            awaitItem()
            vm.onUsername("bob")
            contacts.result = AddContactResult.InvalidId
            vm.submit()
            assertEquals(AddContactError.InvalidUsername, expectMostRecentItem().error)
            contacts.result = AddContactResult.Added(peer)
            vm.submit()
            assertEquals(peer.userId, expectMostRecentItem().added)
        }
    }

    @Test
    fun scanAddsOnceAndIgnoresRepeatedFramesOfARejectedCode() = runTest(dispatcher) {
        val contacts = FakeContacts().apply { result = AddContactResult.DifferentServer }
        val vm = ScanContactViewModel(contacts)
        vm.onCode("whispr:EVIL")
        vm.onCode("whispr:EVIL") // the camera sees the same code many times a second
        assertEquals(AddContactError.OtherServer, vm.state.value.error)
        assertEquals(1, contacts.calls.count { it == "code:whispr:EVIL" })

        contacts.result = AddContactResult.Added(peer)
        vm.onCode("whispr:GOOD")
        assertEquals(peer.userId, vm.state.value.added)
        vm.onCode("whispr:OTHER")
        assertEquals("no further adds after success", 2, contacts.calls.size)
    }

    @Test
    fun scanFromImageWithoutCodeShowsMessage() = runTest(dispatcher) {
        val vm = ScanContactViewModel(FakeContacts())
        vm.onCode("")
        assertTrue(vm.state.value.noCodeInImage)
    }

    @Test
    fun keyMismatchFromScanIsReported() = runTest(dispatcher) {
        val vm = ScanContactViewModel(FakeContacts().apply { result = AddContactResult.KeyMismatch })
        vm.onCode("whispr:X")
        assertEquals(AddContactError.KeyMismatch, vm.state.value.error)
    }

    private fun verifyVm(contacts: FakeContacts) =
        VerifyViewModel(SavedStateHandle(route = VerifyDestination(peer.userId.value)), contacts)

    @Test
    fun verifyByScanMarksVerified() = runTest(dispatcher) {
        val contacts = FakeContacts().apply { this.contacts.value = listOf(peer) }
        val vm = verifyVm(contacts)
        vm.state.test {
            val ready = expectMostRecentItem().content as VerifyContent.Ready
            assertEquals(60, ready.number.digits.length)
            vm.startScan()
            vm.onScanned("whispr-sn:THEIRS")
            val after = expectMostRecentItem()
            assertEquals(VerifyResult.Match, after.lastResult)
            assertTrue(!after.scanning)
        }
    }

    @Test
    fun verifyUnavailableWhileKeyChanged() = runTest(dispatcher) {
        val contacts = FakeContacts().apply { this.contacts.value = listOf(peer.copy(trust = TrustState.KeyChanged)) }
        val vm = verifyVm(contacts)
        vm.state.test { assertEquals(VerifyContent.Unavailable, expectMostRecentItem().content) }
    }

    @Test
    fun editProfileSavesAndReportsErrors() = runTest(dispatcher) {
        val repo = object : ProfileRepository {
            val profile = MutableStateFlow<MyProfile?>(MyProfile(peer.userId, "Ada", null, null))
            var claimResult: ProfileResult = ProfileResult.Unavailable
            override fun observeProfile() = profile
            override suspend fun setDisplayName(name: String) = ProfileResult.Ok.also {
                profile.value =
                    profile.value!!.copy(displayName = name)
            }
            override suspend fun setAvatar(avatar: AvatarSource?) = dev.whispr.domain.model.ProfileResult.Ok
            override suspend fun claimUsername(nickname: String) = claimResult
            override suspend fun clearUsername() = ProfileResult.Ok
        }
        val vm = EditProfileViewModel(repo)
        vm.state.test {
            awaitItem()
            vm.onName("Ada L")
            vm.saveName()
            assertEquals(ProfileMessage.Saved, expectMostRecentItem().message)
            vm.onNickname("ada")
            vm.claimUsername()
            assertEquals(ProfileMessage.Unavailable, expectMostRecentItem().message)
            repo.claimResult = ProfileResult.InvalidInput
            vm.claimUsername()
            assertEquals(ProfileMessage.InvalidNickname, expectMostRecentItem().message)
        }
    }

    // ---- screens ----

    @Test
    fun keyChangeWarningReplacesInputAndNeedsAcknowledgement() {
        var acked = 0
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(
                        peerName = "Bob",
                        content = ChatContent.Messages(emptyList()),
                        trust = TrustState.KeyChanged,
                    ),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                    onAcknowledgeKeyChange = { acked++ },
                )
            }
        }
        rule.onNodeWithText("Safety number changed").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Send message")).assertDoesNotExist()
        rule.onNodeWithText("Accept new number").performClick()
        assertEquals(1, acked)
    }

    @Test
    fun requestShowsAcceptAndDeclineInsteadOfInput() {
        var accepted = 0
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(peerName = "Bob", content = ChatContent.Messages(emptyList()), isRequest = true),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                    onAccept = { accepted++ },
                )
            }
        }
        rule.onNodeWithText("Bob wants to connect.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        rule.onNodeWithText("Accept").performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun verifiedBadgeAndVerifyActionInChat() {
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(
                        peerName = "Bob",
                        content = ChatContent.Messages(emptyList()),
                        trust = TrustState.Verified,
                    ),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Verified").assertIsDisplayed()
        rule.onNodeWithContentDescription("Verify safety number").assertIsDisplayed()
    }

    @Test
    fun newChatScreenOffersScanFirstAndShowsErrors() {
        var scans = 0
        rule.setContent {
            WhisprTheme {
                AddContactScreen(
                    AddContactUiState(username = "x", error = AddContactError.NotFound),
                    onBack = {},
                    onUsername = {},
                    onSubmit = {},
                    onScan = { scans++ },
                    onMyCode = {},
                )
            }
        }
        rule.onNodeWithText("No one has that username.").assertIsDisplayed()
        rule.onNodeWithText("Show my code").assertIsDisplayed()
        rule.onNodeWithText("Scan QR code").performClick()
        assertEquals(1, scans)
    }

    @Test
    fun aSentRequestBlocksWritingUntilAccepted() {
        rule.setContent {
            WhisprTheme {
                ChatScreen(
                    ChatUiState(peerName = "Bob", content = ChatContent.Messages(emptyList()), awaitingAccept = true),
                    onBack = {},
                    onInput = {},
                    onSend = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithText("Request sent. You can chat with Bob once they accept.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertDoesNotExist()
        rule.onNodeWithContentDescription("Voice call").assertDoesNotExist()
    }

    @Test
    fun contactProfileListsMediaWithSave() {
        val photo = dev.whispr.domain.model.Message(
            "m1",
            dev.whispr.domain.model.ConversationId("c"),
            false,
            "",
            java.time.Instant.now(),
            null,
            attachment = dev.whispr.domain.model.Attachment(
                kind = dev.whispr.domain.model.AttachmentKind.File,
                contentType = "application/pdf",
                fileName = "tickets.pdf",
                size = 2048,
                state = dev.whispr.domain.model.AttachmentState.Ready,
            ),
        )
        rule.setContent {
            WhisprTheme {
                ContactProfileScreen(
                    ContactProfileUiState(
                        loading = false,
                        contact = Contact(UserId("b"), "Bob", ByteArray(33)),
                        media = listOf(photo),
                    ),
                    onBack = {},
                    onVerify = {},
                )
            }
        }
        rule.onNodeWithText("Bob").assertIsDisplayed()
        rule.onNodeWithText("Connected · end-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithText("tickets.pdf").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }
}
