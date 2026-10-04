package dev.whispr.android.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.MessagingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChatsContent {
    data object Loading : ChatsContent
    data object Empty : ChatsContent
    data class Conversations(val items: List<ConversationSummary>) : ChatsContent

    /** The server refuses our identity; nothing will work until this is resolved. */
    data object SignInRejected : ChatsContent
}

data class ChatsUiState(
    val content: ChatsContent = ChatsContent.Loading,
    val offline: Boolean = false,
    /** Online, but the server is failing; non-blocking because the list is local. */
    val serverUnreachable: Boolean = false,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    messaging: MessagingRepository,
    private val auth: AuthRepository,
    connectivity: ConnectivityRepository,
) : ViewModel() {

    val state: StateFlow<ChatsUiState> = combine(
        messaging.observeConversations(),
        auth.session,
        connectivity.isOnline,
    ) { conversations, session, online ->
        val unavailable = (session as? SessionState.Unavailable)?.error
        ChatsUiState(
            content = when {
                unavailable == AuthError.Rejected -> ChatsContent.SignInRejected
                conversations.isEmpty() -> ChatsContent.Empty
                else -> ChatsContent.Conversations(conversations)
            },
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
