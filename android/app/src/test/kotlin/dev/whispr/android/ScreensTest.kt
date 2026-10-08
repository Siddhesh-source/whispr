package dev.whispr.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.android.ui.chats.ChatsContent
import dev.whispr.android.ui.chats.ChatsScreen
import dev.whispr.android.ui.chats.ChatsUiState
import dev.whispr.android.ui.onboarding.OnboardingError
import dev.whispr.android.ui.onboarding.OnboardingScreen
import dev.whispr.android.ui.onboarding.OnboardingUiState
import dev.whispr.android.ui.settings.ConnectionStatus
import dev.whispr.android.ui.settings.SettingsScreen
import dev.whispr.android.ui.settings.SettingsUiState
import dev.whispr.core.designsystem.theme.WhisprTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every screen in each of its loading / empty / error / offline states. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun onboarding(state: OnboardingUiState, onSubmit: () -> Unit = {}) = rule.setContent {
        WhisprTheme {
            OnboardingScreen(state, onNameChange = {}, onPickAvatar = {}, onSubmit = onSubmit, avatarPreview = null)
        }
    }

    @Test
    fun onboardingContinueDisabledUntilNameEntered() {
        onboarding(OnboardingUiState())
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Add a profile photo").assertIsDisplayed()
    }

    @Test
    fun onboardingSubmitsWithName() {
        var submitted = 0
        onboarding(OnboardingUiState(name = "Ada"), onSubmit = { submitted++ })
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(1, submitted)
    }

    @Test
    fun onboardingShowsLoadingWhileSubmitting() {
        onboarding(OnboardingUiState(name = "Ada", submitting = true))
        rule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun onboardingShowsErrorsAndOfflineBanner() {
        onboarding(OnboardingUiState(name = "Ada", error = OnboardingError.Network, offline = true))
        rule.onNodeWithText("Couldn't reach the server. Check your connection and try again.").assertIsDisplayed()
        rule.onNodeWithText("You're offline. Messages will send when you reconnect.").assertIsDisplayed()
    }

    @Test
    fun onboardingNameErrorShownUnderField() {
        onboarding(OnboardingUiState(name = "x", error = OnboardingError.NameTooLong))
        rule.onNodeWithText("Names can be up to 64 characters.").assertIsDisplayed()
    }

    private fun chats(state: ChatsUiState, onRetry: () -> Unit = {}) = rule.setContent {
        WhisprTheme { ChatsScreen(state, onOpenSettings = {}, onRetry = onRetry) }
    }

    @Test
    fun chatsLoading() {
        chats(ChatsUiState(content = ChatsContent.Loading))
        rule.onNodeWithContentDescription("Loading chats").assertIsDisplayed()
    }

    @Test
    fun chatsEmptyWithOfflineBanner() {
        chats(ChatsUiState(content = ChatsContent.Empty, offline = true))
        rule.onNodeWithText("No conversations yet").assertIsDisplayed()
        rule.onNodeWithText("New chat").assertIsDisplayed()
        rule.onNodeWithText("You're offline. Messages will send when you reconnect.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun chatsServerUnreachableBanner() {
        chats(ChatsUiState(content = ChatsContent.Empty, serverUnreachable = true))
        rule.onNodeWithText("Can't reach the server right now. Retrying automatically.").assertIsDisplayed()
    }

    @Test
    fun chatsRejectedErrorRetries() {
        var retries = 0
        chats(ChatsUiState(content = ChatsContent.SignInRejected), onRetry = { retries++ })
        rule.onNodeWithText("Can't sign in").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    private fun settings(state: SettingsUiState) = rule.setContent {
        WhisprTheme { SettingsScreen(state, onBack = {}) }
    }

    @Test
    fun settingsLoadingAndError() {
        settings(SettingsUiState.Loading)
        rule.onNodeWithContentDescription("Loading settings").assertIsDisplayed()
    }

    @Test
    fun settingsError() {
        settings(SettingsUiState.Error)
        rule.onNodeWithText("Couldn't load your profile").assertIsDisplayed()
        rule.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun settingsContentOffline() {
        settings(SettingsUiState.Content("Ada", null, "user-123", ConnectionStatus.Offline, "0.1.0"))
        rule.onNodeWithText("user-123").assertExists() // below the fold on the small test screen
        // Connection status sits in the About group, below the fold on the small test screen.
        rule.onNodeWithText("Offline").assertExists()
        rule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
    }

    @Test
    fun settingsWarnsWhileKeysAreNotRegistered() {
        settings(SettingsUiState.Content("Ada", null, "u", ConnectionStatus.Active, "0.1.0", keysRegistered = false))
        rule.onNodeWithText("Encryption keys not set up yet, retrying", substring = true).assertExists()
    }
}
