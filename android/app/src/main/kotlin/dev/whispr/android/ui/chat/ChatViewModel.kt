package dev.whispr.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.navigation.ChatDestination
import dev.whispr.android.notifications.ActiveConversation
import dev.whispr.core.designsystem.component.BubbleGroupPosition
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.MessagingRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BubbleItem(val message: Message, val position: BubbleGroupPosition)

sealed interface ChatContent {
    data object Loading : ChatContent
    data object Missing : ChatContent
    data class Messages(val items: List<BubbleItem>) : ChatContent
}

data class ChatUiState(
    val peerName: String = "",
    val content: ChatContent = ChatContent.Loading,
    val peerTyping: Boolean = false,
    val offline: Boolean = false,
    val input: String = "",
    val trust: TrustState = TrustState.Unverified,
    /** They added us and we have not accepted yet. */
    val isRequest: Boolean = false,
) {
    /** Sending is only possible for accepted contacts with no unacknowledged key change. */
    val canCompose: Boolean get() = !isRequest && trust != TrustState.KeyChanged
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val contacts: ContactsRepository,
    accounts: AccountRepository,
    connectivity: ConnectivityRepository,
    private val messaging: MessagingRepository,
    private val active: ActiveConversation,
) : ViewModel() {

    private val peer = UserId(savedState.toRoute<ChatDestination>().peerId)
    private val input = MutableStateFlow("")

    private val conversation = flow { accounts.getAccount()?.userId?.let { emit(ConversationId.direct(it, peer)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val contact = contacts.observeContacts().map { list -> list.firstOrNull { it.userId == peer } }

    private val messages = conversation.filterNotNull().flatMapLatest { messaging.observeMessages(it) }
    private val typing = conversation.filterNotNull().flatMapLatest { messaging.observePeerTyping(it) }

    val state: StateFlow<ChatUiState> = combine(contact, messages, typing, connectivity.isOnline, input) {
            c,
            msgs,
            isTyping,
            online,
            text,
        ->
        ChatUiState(
            peerName = c?.displayName.orEmpty(),
            content = if (c == null) ChatContent.Missing else ChatContent.Messages(group(msgs)),
            peerTyping = isTyping,
            offline = !online,
            input = text,
            trust = c?.trust ?: TrustState.Unverified,
            isRequest = c?.isRequest == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatUiState())

    fun onInput(text: String) {
        input.value = text
        if (text.isNotBlank()) viewModelScope.launch { messaging.onTyping(peer) }
    }

    init {
        // Every time the chat opens, compare the pinned key with what the server reports now.
        viewModelScope.launch { contacts.refreshKey(peer) }
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty() || !state.value.canCompose) return
        viewModelScope.launch {
            // Keep the draft if sending is refused (e.g. a key change arrived meanwhile).
            if (messaging.sendText(peer, text)) input.value = ""
        }
    }

    fun acceptRequest() {
        viewModelScope.launch { contacts.acceptRequest(peer) }
    }

    fun declineRequest(onDone: () -> Unit) {
        viewModelScope.launch {
            contacts.declineRequest(peer)
            onDone()
        }
    }

    fun acknowledgeKeyChange() {
        viewModelScope.launch { contacts.acknowledgeKeyChange(peer) }
    }

    fun retry(messageId: String) {
        viewModelScope.launch { messaging.retry(messageId) }
    }

    /** The screen is visible: mark incoming read and suppress notifications for it. */
    fun onVisible() {
        val id = conversation.value ?: return
        active.current.value = id
        // No read receipts for message requests: that would confirm the account is active.
        if (!state.value.isRequest) viewModelScope.launch { messaging.markRead(id) }
    }

    fun onHidden() {
        if (active.current.value == conversation.value) active.current.value = null
    }

    override fun onCleared() = onHidden()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Consecutive messages in the same direction form a visual group. */
internal fun group(messages: List<Message>): List<BubbleItem> = messages.mapIndexed { i, m ->
    val sameAsPrev = i > 0 && messages[i - 1].outgoing == m.outgoing
    val sameAsNext = i < messages.lastIndex && messages[i + 1].outgoing == m.outgoing
    val position = when {
        sameAsPrev && sameAsNext -> BubbleGroupPosition.Middle
        sameAsPrev -> BubbleGroupPosition.Last
        sameAsNext -> BubbleGroupPosition.First
        else -> BubbleGroupPosition.Single
    }
    BubbleItem(m, position)
}
