package dev.whispr.android.session

import dev.whispr.android.di.ApplicationScope
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.usecase.RestoreSessionUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the user signed in. While the device is online and the account is
 * registered, it runs challenge-response whenever there is no session (app
 * start, network regained), retrying transient failures with backoff.
 *
 * Only connectivity and registration restart this loop. Session state must
 * not: signing in changes the state to Authenticating, and restarting on that
 * would cancel the very sign-in that caused it.
 *
 * A rejected identity is not retried automatically (hammering the server with
 * a key it refuses helps nobody); the user can retry from the chat screen.
 */
@Singleton
class SessionKeeper @Inject constructor(
    private val accounts: AccountRepository,
    private val auth: AuthRepository,
    private val connectivity: ConnectivityRepository,
    private val restoreSession: RestoreSessionUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            combine(connectivity.isOnline, accounts.observeAccount()) { online, account ->
                online &&
                    account?.isRegistered == true
            }
                .distinctUntilChanged()
                .collectLatest { eligible -> if (eligible) keepSignedIn() }
        }
    }

    private suspend fun keepSignedIn() {
        var backoff = INITIAL_BACKOFF
        while (true) {
            when (val result = restoreSession()) {
                is AuthResult.Ok, null -> {
                    backoff = INITIAL_BACKOFF
                    // Token expiry is handled on demand by TokenSource; we only
                    // act again if something marks the session unavailable.
                    auth.session.first { it is SessionState.Unavailable || it == SessionState.Idle }
                }
                is AuthResult.Err -> if (result.error == AuthError.Rejected) {
                    auth.session.first { !(it is SessionState.Unavailable && it.error == AuthError.Rejected) }
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                }
            }
        }
    }

    internal companion object {
        val INITIAL_BACKOFF: Duration = 2.seconds
        val MAX_BACKOFF: Duration = 60.seconds
    }
}
