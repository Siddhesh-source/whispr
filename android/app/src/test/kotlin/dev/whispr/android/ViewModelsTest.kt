package dev.whispr.android

import app.cash.turbine.test
import dev.whispr.android.session.SessionKeeper
import dev.whispr.android.ui.chats.ChatsContent
import dev.whispr.android.ui.chats.ChatsViewModel
import dev.whispr.android.ui.onboarding.OnboardingError
import dev.whispr.android.ui.onboarding.OnboardingViewModel
import dev.whispr.android.ui.settings.ConnectionStatus
import dev.whispr.android.ui.settings.SettingsUiState
import dev.whispr.android.ui.settings.SettingsViewModel
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.usecase.CompleteOnboardingUseCase
import dev.whispr.domain.usecase.RestoreSessionUseCase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val accounts = FakeAccounts()
    private val auth = FakeAuth()
    private val connectivity = FakeConnectivity()

    private fun onboardingVm() =
        OnboardingViewModel(CompleteOnboardingUseCase(FakeIdentity(), accounts, auth), connectivity)

    @Test
    fun onboardingSuccessMarksCompleted() = runTest(dispatcher) {
        val vm = onboardingVm()
        vm.state.test {
            awaitItem()
            vm.onNameChange("Ada")
            vm.submit()
            dispatcher.scheduler.advanceUntilIdle() // simulated network round trip
            val final = expectMostRecentItem()
            assertTrue(final.completed)
            assertFalse(final.submitting)
        }
        assertTrue(accounts.state.value!!.isRegistered)
    }

    @Test
    fun onboardingNetworkFailureShowsErrorAndAllowsRetry() = runTest(dispatcher) {
        auth.registerResult = AuthResult.Err(AuthError.Network)
        val vm = onboardingVm()
        vm.state.test {
            awaitItem()
            vm.onNameChange("Ada")
            vm.submit()
            dispatcher.scheduler.advanceUntilIdle()
            val failed = expectMostRecentItem()
            assertEquals(OnboardingError.Network, failed.error)
            assertTrue(failed.canSubmit)
            vm.onNameChange("Ada ")
            assertEquals(null, awaitItem().error)
        }
    }

    @Test
    fun onboardingRejectsInvalidNameWithoutCallingServer() = runTest(dispatcher) {
        val vm = onboardingVm()
        vm.state.test {
            awaitItem()
            vm.onNameChange("bad‮name")
            vm.submit()
            assertEquals(OnboardingError.NameInvalid, expectMostRecentItem().error)
        }
        assertEquals(null, accounts.state.value)
    }

    @Test
    fun onboardingReflectsConnectivity() = runTest(dispatcher) {
        val vm = onboardingVm()
        vm.state.test {
            assertFalse(awaitItem().offline)
            connectivity.online.value = false
            assertTrue(awaitItem().offline)
        }
    }

    @Test
    fun chatsStates() = runTest(dispatcher) {
        accounts.state.value = registered
        val messaging = FakeMessaging()
        val vm = ChatsViewModel(messaging, auth, connectivity)
        vm.state.test {
            // The initial Loading value is replaced immediately under an unconfined dispatcher;
            // ScreensTest covers how Loading renders.
            assertEquals(ChatsContent.Empty, awaitItem().content)

            val summary = dev.whispr.domain.model.ConversationSummary(
                dev.whispr.domain.model.ConversationId("c"),
                dev.whispr.domain.model.Contact(registered.userId!!, "Bob", ByteArray(0)),
                null,
                0,
            )
            messaging.conversations.value = listOf(summary)
            assertEquals(ChatsContent.Conversations(listOf(summary)), awaitItem().content)

            auth.session.value = SessionState.Unavailable(AuthError.Server)
            assertTrue(awaitItem().serverUnreachable)

            connectivity.online.value = false
            val offline = awaitItem()
            assertTrue(offline.offline)
            assertFalse("offline banner replaces the server banner", offline.serverUnreachable)

            auth.session.value = SessionState.Unavailable(AuthError.Rejected)
            assertEquals(ChatsContent.SignInRejected, awaitItem().content)
        }
        vm.retrySignIn()
        assertEquals(1, auth.authenticateCalls)
    }

    @Test
    fun settingsShowsAccountAndConnection() = runTest(dispatcher) {
        accounts.state.value = registered
        auth.session.value = SessionState.Active(Instant.MAX)
        val settings = FakeSettings()
        val vm = SettingsViewModel(accounts, auth, connectivity, settings)
        vm.state.test {
            val content = awaitItem() as SettingsUiState.Content
            assertEquals(registered.userId!!.value, content.userId)
            assertEquals(ConnectionStatus.Active, content.connection)
            connectivity.online.value = false
            assertEquals(ConnectionStatus.Offline, (awaitItem() as SettingsUiState.Content).connection)
            assertFalse("privacy toggles default off", content.readReceipts || content.typingIndicators)
            vm.setReadReceipts(true)
            assertTrue((awaitItem() as SettingsUiState.Content).readReceipts)
        }
    }

    @Test
    fun sessionKeeperSignsInWhenOnlineAndRegistered() = runTest(dispatcher) {
        keeper().start()
        advanceTimeBy(1_000)
        assertEquals("not registered yet", 0, auth.authenticateCalls)

        accounts.state.value = registered
        advanceTimeBy(1_000)
        // Regression: the keeper used to cancel its own sign-in when the
        // session moved to Authenticating. The attempt must run to completion.
        assertEquals(1, auth.completedAuthentications)
        assertTrue(auth.session.value is SessionState.Active)
    }

    @Test
    fun sessionKeeperRetriesTransientFailuresWithBackoff() = runTest(dispatcher) {
        accounts.state.value = registered
        auth.authenticateResult = AuthResult.Err(AuthError.Network)
        keeper().start()
        advanceTimeBy(1_000)
        assertEquals(1, auth.completedAuthentications)
        advanceTimeBy(2_000) // first backoff: 2s
        assertEquals(2, auth.completedAuthentications)

        auth.authenticateResult = AuthResult.Ok(Unit)
        advanceTimeBy(5_000) // second backoff: 4s
        assertEquals(3, auth.completedAuthentications)
        assertTrue(auth.session.value is SessionState.Active)
        advanceTimeBy(600_000)
        assertEquals("no further attempts once signed in", 3, auth.completedAuthentications)
    }

    @Test
    fun sessionKeeperWaitsForNetworkAndDoesNotRetryRejection() = runTest(dispatcher) {
        accounts.state.value = registered
        connectivity.online.value = false
        keeper().start()
        advanceTimeBy(10_000)
        assertEquals(0, auth.authenticateCalls)

        auth.authenticateResult = AuthResult.Err(AuthError.Rejected)
        connectivity.online.value = true
        advanceTimeBy(600_000)
        assertEquals("rejected identities are not retried automatically", 1, auth.authenticateCalls)
    }

    private fun TestScope.keeper() =
        SessionKeeper(accounts, auth, connectivity, RestoreSessionUseCase(accounts, auth), backgroundScope)
}
