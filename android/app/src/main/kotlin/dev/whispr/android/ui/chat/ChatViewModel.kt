package dev.whispr.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.notifications.ActiveConversation
import dev.whispr.core.designsystem.component.BubbleGroupPosition
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageRules
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.GroupsRepository
import dev.whispr.domain.repository.MessagingRepository
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BubbleItem(val message: Message, val position: BubbleGroupPosition)

sealed interface ChatContent {
    data object Loading : ChatContent
    data object Missing : ChatContent
    data class Messages(val items: List<BubbleItem>) : ChatContent
}

/** Why a send, forward or delete was refused, shown once as a message. */
enum class ChatError { TooLarge, Unreadable, NotAllowed, DeleteFailed }

data class ChatUiState(
    val peerName: String = "",
    val content: ChatContent = ChatContent.Loading,
    val peerTyping: Boolean = false,
    val offline: Boolean = false,
    val input: String = "",
    val trust: TrustState = TrustState.Unverified,
    /** They added us and we have not accepted yet. */
    val isRequest: Boolean = false,
    val isGroup: Boolean = false,
    /** Groups only. */
    val groupStatus: GroupStatus? = null,
    val memberCount: Int = 0,
    val error: ChatError? = null,
    /** The message the next send replies to. */
    val replyingTo: Message? = null,
    /** Disappearing-message timer in seconds; 0 is off. */
    val timerSeconds: Long = 0,
) {
    /** Sending is only possible for accepted contacts with no unacknowledged key change, or active groups. */
    val canCompose: Boolean
        get() = if (isGroup) groupStatus == GroupStatus.Active else !isRequest && trust != TrustState.KeyChanged
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val contacts: ContactsRepository,
    accounts: AccountRepository,
    connectivity: ConnectivityRepository,
    private val messaging: MessagingRepository,
    private val groups: GroupsRepository,
    private val active: ActiveConversation,
) : ViewModel() {

    private val peer = savedState.get<String>("peerId")?.takeIf { it.isNotEmpty() }?.let(::UserId)
    private val groupId = savedState.get<String>("groupId")?.takeIf { it.isNotEmpty() }?.let(::GroupId)
    private val input = MutableStateFlow("")
    private val error = MutableStateFlow<ChatError?>(null)
    private val replyTo = MutableStateFlow<Message?>(null)

    private val conversation: StateFlow<ConversationId?> = (
        groupId?.let { flowOf(it.conversation) }
            ?: flow { accounts.getAccount()?.userId?.let { me -> peer?.let { emit(ConversationId.direct(me, it)) } } }
        ).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Header(
        val name: String,
        val exists: Boolean,
        val trust: TrustState,
        val isRequest: Boolean,
        val groupStatus: GroupStatus?,
        val members: Int,
    )

    private val header = if (groupId != null) {
        groups.observeGroup(groupId).map { g ->
            Header(
                g?.name.orEmpty(),
                g != null,
                TrustState.Unverified,
                false,
                g?.status,
                g?.members?.count { !it.invited } ?: 0,
            )
        }
    } else {
        contacts.observeContacts().map { list ->
            val c = list.firstOrNull { it.userId == peer }
            Header(
                c?.displayName.orEmpty(),
                c != null,
                c?.trust ?: TrustState.Unverified,
                c?.isRequest == true,
                null,
                0,
            )
        }
    }

    private val messages = conversation.filterNotNull().flatMapLatest { messaging.observeMessages(it) }
    private val typing = conversation.filterNotNull().flatMapLatest { messaging.observePeerTyping(it) }
    private val timer = conversation.filterNotNull().flatMapLatest { messaging.observeTimer(it) }

    /** Where a message can be forwarded: every other conversation we can write to. */
    val forwardTargets: StateFlow<List<ConversationSummary>> = combine(
        messaging.observeConversations(),
        conversation,
    ) { all, current ->
        all.filter { c ->
            val writable = c.group?.let { it.status == GroupStatus.Active }
                ?: c.peer?.let { !it.isRequest && it.trust != TrustState.KeyChanged }
                ?: false
            c.id != current && writable
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val state: StateFlow<ChatUiState> = combine(
        header,
        messages,
        typing,
        combine(connectivity.isOnline, error) { online, e -> online to e },
        combine(input, replyTo, timer) { text, reply, seconds -> Triple(text, reply, seconds) },
    ) { h, msgs, isTyping, (online, e), (text, reply, seconds) ->
        ChatUiState(
            peerName = h.name,
            content = if (!h.exists) ChatContent.Missing else ChatContent.Messages(group(msgs)),
            peerTyping = isTyping && groupId == null,
            offline = !online,
            input = text,
            trust = h.trust,
            isRequest = h.isRequest,
            isGroup = groupId != null,
            groupStatus = h.groupStatus,
            memberCount = h.members,
            error = e,
            replyingTo = reply,
            timerSeconds = seconds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatUiState(isGroup = groupId != null))

    fun onInput(text: String) {
        input.value = text
        val p = peer
        if (text.isNotBlank() && p != null) viewModelScope.launch { messaging.onTyping(p) }
    }

    init {
        // Every time the chat opens, compare the pinned key with what the server reports now.
        peer?.let { viewModelScope.launch { contacts.refreshKey(it) } }
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty() || !state.value.canCompose) return
        viewModelScope.launch {
            // Keep the draft if sending is refused (e.g. a key change arrived meanwhile).
            val quoted = replyTo.value?.id
            val ok = when {
                groupId != null -> messaging.sendGroupText(groupId, text, quoted)
                peer != null -> messaging.sendText(peer, text, quoted)
                else -> false
            }
            if (ok) {
                input.value = ""
                replyTo.value = null
            }
        }
    }

    /** Sends a picked or recorded file; images are re-encoded (metadata stripped) before encryption. */
    fun sendMedia(uri: String, kind: AttachmentKind, fileName: String? = null, durationMs: Long? = null) {
        val id = conversation.value ?: return
        if (!state.value.canCompose) return
        viewModelScope.launch {
            error.value = when (messaging.sendMedia(id, MediaSource(uri, kind, fileName, durationMs = durationMs))) {
                SendResult.Ok -> null
                SendResult.TooLarge -> ChatError.TooLarge
                SendResult.Unreadable -> ChatError.Unreadable
                SendResult.NotAllowed -> ChatError.NotAllowed
            }
            // A voice recording is our own plaintext temp file: gone once encrypted (or refused).
            if (kind == AttachmentKind.Voice && uri.startsWith("file:")) {
                runCatching { java.io.File(java.net.URI(uri)).delete() }
            }
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun replyTo(message: Message?) {
        replyTo.value = message?.takeIf { MessageRules.isContent(it) }
    }

    fun forward(messageId: String, to: ConversationId) {
        val from = conversation.value ?: return
        viewModelScope.launch {
            error.value = when (messaging.forward(from, messageId, to)) {
                SendResult.Ok -> null
                SendResult.TooLarge -> ChatError.TooLarge
                SendResult.Unreadable -> ChatError.Unreadable
                SendResult.NotAllowed -> ChatError.NotAllowed
            }
        }
    }

    fun deleteForMe(messageId: String) {
        val id = conversation.value ?: return
        if (replyTo.value?.id == messageId) replyTo.value = null
        viewModelScope.launch { messaging.deleteForMe(id, messageId) }
    }

    fun deleteForEveryone(messageId: String) {
        val id = conversation.value ?: return
        if (replyTo.value?.id == messageId) replyTo.value = null
        viewModelScope.launch {
            if (!messaging.deleteForEveryone(id, messageId)) error.value = ChatError.DeleteFailed
        }
    }

    fun canDeleteForEveryone(message: Message): Boolean =
        state.value.canCompose && MessageRules.canDeleteForEveryone(message, Instant.now())

    fun setTimer(seconds: Long) {
        val id = conversation.value ?: return
        viewModelScope.launch { if (!messaging.setTimer(id, seconds)) error.value = ChatError.NotAllowed }
    }

    fun react(messageId: String, emoji: String?) {
        val id = conversation.value ?: return
        viewModelScope.launch { messaging.react(id, messageId, emoji) }
    }

    fun download(messageId: String) {
        val id = conversation.value ?: return
        viewModelScope.launch { messaging.download(id, messageId) }
    }

    suspend fun attachmentBytes(messageId: String): ByteArray? =
        conversation.value?.let { messaging.attachmentBytes(it, messageId) }

    suspend fun exportAttachment(messageId: String): String? =
        conversation.value?.let { messaging.exportAttachment(it, messageId) }

    fun acceptRequest() {
        peer?.let { viewModelScope.launch { contacts.acceptRequest(it) } }
    }

    fun declineRequest(onDone: () -> Unit) {
        val p = peer ?: return
        viewModelScope.launch {
            contacts.declineRequest(p)
            onDone()
        }
    }

    fun acceptInvite() {
        groupId?.let { viewModelScope.launch { groups.acceptInvite(it) } }
    }

    fun declineInvite(onDone: () -> Unit) {
        val g = groupId ?: return
        viewModelScope.launch {
            groups.declineInvite(g)
            onDone()
        }
    }

    fun acknowledgeKeyChange() {
        peer?.let { viewModelScope.launch { contacts.acknowledgeKeyChange(it) } }
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

/** Consecutive messages from the same author form a visual group; group events stand alone. */
internal fun group(messages: List<Message>): List<BubbleItem> = messages.mapIndexed { i, m ->
    fun same(o: Message?) = o != null && !o.system && !m.system && o.outgoing == m.outgoing && o.author == m.author
    val sameAsPrev = same(messages.getOrNull(i - 1))
    val sameAsNext = same(messages.getOrNull(i + 1))
    val position = when {
        sameAsPrev && sameAsNext -> BubbleGroupPosition.Middle
        sameAsPrev -> BubbleGroupPosition.Last
        sameAsNext -> BubbleGroupPosition.First
        else -> BubbleGroupPosition.Single
    }
    BubbleItem(m, position)
}
