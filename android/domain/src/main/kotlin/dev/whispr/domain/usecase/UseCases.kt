package dev.whispr.domain.usecase

import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.IdentityRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

sealed interface OnboardingResult {
    data object Success : OnboardingResult
    data class InvalidName(val reason: DisplayNameValidation) : OnboardingResult
    data class Failed(val error: AuthError) : OnboardingResult
}

/**
 * First-launch flow: create the identity, save the profile, register, then
 * sign in. Each step is safe to repeat, so a retry after a crash or network
 * failure picks up where the last attempt stopped.
 */
class CompleteOnboardingUseCase @Inject constructor(
    private val identity: IdentityRepository,
    private val accounts: AccountRepository,
    private val auth: AuthRepository,
) {
    suspend operator fun invoke(displayName: String, avatar: AvatarSource?): OnboardingResult {
        val name = when (val v = DisplayNameValidator.validate(displayName)) {
            is DisplayNameValidation.Valid -> v.name
            else -> return OnboardingResult.InvalidName(v)
        }
        try {
            identity.getOrCreatePublicKey()
            accounts.saveProfile(name, avatar)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return OnboardingResult.Failed(AuthError.Storage)
        }
        val userId = when (val r = auth.register(name)) {
            is AuthResult.Ok -> r.value
            is AuthResult.Err -> return OnboardingResult.Failed(r.error)
        }
        accounts.markRegistered(userId)
        // Registration succeeded, so the account exists. If sign-in fails now
        // (e.g. connection dropped), the session layer retries later; the user
        // is not sent back to onboarding.
        auth.authenticate()
        return OnboardingResult.Success
    }
}

enum class StartDestination { Onboarding, Chats }

/** Registered users go straight to their chats; everyone else onboards. */
class ObserveStartDestinationUseCase @Inject constructor(private val accounts: AccountRepository) {
    operator fun invoke(): Flow<StartDestination> = accounts.observeAccount()
        .map { if (it?.isRegistered == true) StartDestination.Chats else StartDestination.Onboarding }
        .distinctUntilChanged()
}

/**
 * Re-establishes a server session after app start. Tokens live only in
 * memory, so "staying signed in" means silently repeating challenge-response
 * with the stored identity key.
 */
class RestoreSessionUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val auth: AuthRepository,
) {
    suspend operator fun invoke(): AuthResult<Unit>? {
        if (accounts.getAccount()?.isRegistered != true) return null
        return auth.authenticate()
    }
}
