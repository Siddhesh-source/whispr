package dev.whispr.android.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChatsContent {
    data object Loading : ChatsContent
    data object Empty : ChatsContent

    /** The server refuses our identity; nothing will work until this is resolved. */
    data object SignInRejected : ChatsContent
}

data class ChatsUiState(
    val content: ChatsContent = ChatsContent.Loading,
    val offline: Boolean = false,
    /** Online, but the server is failing; non-blocking because the chat list is local. */
    val serverUnreachable: Boolean = false,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    accounts: AccountRepository,
    private val auth: AuthRepository,
    connectivity: ConnectivityRepository,
) : ViewModel() {

    // Conversations arrive with the messaging phase; until then a loaded
    // account always means an empty list.
    val state: StateFlow<ChatsUiState> = combine(accounts.observeAccount(), auth.session, connectivity.isOnline) {
            _,
            session,
            online,
        ->
        val unavailable = (session as? SessionState.Unavailable)?.error
        ChatsUiState(
            content = if (unavailable == AuthError.Rejected) ChatsContent.SignInRejected else ChatsContent.Empty,
            offline = !online,
            serverUnreachable = online && (unavailable == AuthError.Network || unavailable == AuthError.Server),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatsUiState())

    fun retrySignIn() {
        viewModelScope.launch { auth.authenticate() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
