package dev.whispr.domain

import app.cash.turbine.test
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.UserId
import dev.whispr.domain.usecase.CompleteOnboardingUseCase
import dev.whispr.domain.usecase.DisplayNameValidation
import dev.whispr.domain.usecase.ObserveStartDestinationUseCase
import dev.whispr.domain.usecase.OnboardingResult
import dev.whispr.domain.usecase.RestoreSessionUseCase
import dev.whispr.domain.usecase.StartDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UseCasesTest {
    private val identity = FakeIdentityRepository()
    private val accounts = FakeAccountRepository()
    private val auth = FakeAuthRepository()
    private val onboard = CompleteOnboardingUseCase(identity, accounts, auth)

    @Test
    fun onboardingCreatesIdentityRegistersAndSignsIn() = runTest {
        val result = onboard("  Ada  ", AvatarSource("content://avatar"))

        assertEquals(OnboardingResult.Success, result)
        assertEquals(1, identity.createCalls)
        assertEquals(listOf("Ada"), auth.registeredNames)
        assertEquals(Account(UserId("u-1"), "Ada", "content://avatar"), accounts.state.value)
        assertEquals(1, auth.authenticateCalls)
    }

    @Test
    fun invalidNameStopsBeforeAnySideEffect() = runTest {
        val result = onboard("   ", null)

        assertEquals(OnboardingResult.InvalidName(DisplayNameValidation.Empty), result)
        assertEquals(0, identity.createCalls)
        assertNull(accounts.state.value)
    }

    @Test
    fun registrationFailureLeavesAccountUnregisteredAndRetryReusesIdentity() = runTest {
        auth.registerResult = networkError
        assertEquals(OnboardingResult.Failed(AuthError.Network), onboard("Ada", null))
        assertEquals(false, accounts.state.value!!.isRegistered)
        val firstKey = identity.key

        auth.registerResult = AuthResult.Ok(UserId("u-1"))
        assertEquals(OnboardingResult.Success, onboard("Ada", null))
        assertEquals("retry must not create a new identity", firstKey, identity.key)
    }

    @Test
    fun signInFailureAfterRegistrationStillCompletesOnboarding() = runTest {
        auth.authenticateResult = networkError
        assertEquals(OnboardingResult.Success, onboard("Ada", null))
        assertEquals(true, accounts.state.value!!.isRegistered)
    }

    @Test
    fun identityStorageFailureIsReported() = runTest {
        identity.failCreate = true
        assertEquals(OnboardingResult.Failed(AuthError.Storage), onboard("Ada", null))
        assertEquals(emptyList<String>(), auth.registeredNames)
    }

    @Test
    fun startDestinationFollowsRegistration() = runTest {
        ObserveStartDestinationUseCase(accounts)().test {
            assertEquals(StartDestination.Onboarding, awaitItem())
            accounts.state.value = Account(null, "Ada", null)
            expectNoEvents()
            accounts.state.value = Account(UserId("u-1"), "Ada", null)
            assertEquals(StartDestination.Chats, awaitItem())
        }
    }

    @Test
    fun restoreSessionOnlyAuthenticatesRegisteredAccounts() = runTest {
        val restore = RestoreSessionUseCase(accounts, auth)
        assertNull(restore())
        assertEquals(0, auth.authenticateCalls)

        accounts.state.value = Account(UserId("u-1"), "Ada", null)
        assertEquals(AuthResult.Ok(Unit), restore())
        assertEquals(1, auth.authenticateCalls)
    }
}
