package dev.whispr.android.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.navigation.ContactProfileDestination
import dev.whispr.android.ui.chat.AttachmentActions
import dev.whispr.android.ui.chat.saveAttachment
import dev.whispr.android.ui.chats.previewRes
import dev.whispr.android.ui.formatTimestamp
import dev.whispr.android.ui.rememberImageBytes
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ListDivider
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.MessagingRepository
import dev.whispr.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactProfileUiState(
    val loading: Boolean = true,
    val contact: Contact? = null,
    /** Disappearing-message timer in seconds; 0 is off. */
    val timerSeconds: Long = 0,
    /** Every photo, voice message and file in the chat, newest first. */
    val media: List<Message> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactProfileViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contacts: ContactsRepository,
    accounts: AccountRepository,
    private val messaging: MessagingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val peer = UserId(savedState.toRoute<ContactProfileDestination>().peerId)

    private val conversation = flow { accounts.getAccount()?.userId?.let { emit(ConversationId.direct(it, peer)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ContactProfileUiState> = combine(
        contacts.observeContact(peer),
        conversation.filterNotNull().flatMapLatest { messaging.observeTimer(it) },
        conversation.flatMapLatest { c ->
            c?.let { messaging.observeMessages(it) }?.map { list ->
                list.filter { it.attachment != null && !it.deleted }.reversed()
            } ?: emptyFlow()
        },
    ) { c, timer, media ->
        ContactProfileUiState(loading = false, contact = c, timerSeconds = timer, media = media)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContactProfileUiState())

    val attachments = object : AttachmentActions {
        override fun download(messageId: String) {
            val c = conversation.value ?: return
            viewModelScope.launch { messaging.download(c, messageId) }
        }

        override suspend fun bytes(messageId: String) =
            conversation.value?.let { messaging.attachmentBytes(it, messageId) }

        override suspend fun export(messageId: String) =
            conversation.value?.let { messaging.exportAttachment(it, messageId) }

        override suspend fun saveFolder() = settings.observePrivacy().first().saveFolder
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ContactProfileRoute(
    onBack: () -> Unit,
    onVerify: () -> Unit,
    viewModel: ContactProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContactProfileScreen(state, onBack, onVerify, viewModel.attachments)
}

@Composable
fun ContactProfileScreen(
    state: ContactProfileUiState,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    attachments: AttachmentActions = AttachmentActions.None,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.profile_contact_title), onNavigateBack = onBack) },
    ) { padding ->
        val c = state.contact
        if (!state.loading && c == null) {
            EmptyState(
                title = stringResource(R.string.chat_missing_title),
                message = stringResource(R.string.chat_missing_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (c != null) item(key = "header") { Header(c, state.timerSeconds, onVerify) }
            item(key = "media-header") {
                Text(
                    pluralStringResource(R.plurals.profile_media_count, state.media.size, state.media.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(WhisprTheme.spacing.lg).semantics { heading() },
                )
            }
            items(state.media, key = { it.id }) { m ->
                MediaRow(m, onSave = { saveAttachment(context, scope, attachments, m) }) {
                    attachments.download(m.id)
                }
                ListDivider()
            }
        }
    }
}

@Composable
private fun Header(c: Contact, timerSeconds: Long, onVerify: () -> Unit) {
    val picture by rememberImageBytes(c.avatar)
    Column(
        Modifier.fillMaxWidth().padding(WhisprTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
    ) {
        WhisprAvatar(c.displayName, image = picture, size = WhisprTheme.sizes.avatarXLarge)
        Text(c.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(
                when {
                    c.isRequest -> R.string.profile_contact_wants_to_connect
                    c.awaitingAccept -> R.string.chats_request_sent_preview
                    c.trust == TrustState.Verified -> R.string.profile_contact_verified
                    c.trust == TrustState.KeyChanged -> R.string.chats_key_changed_preview
                    else -> R.string.profile_contact_connected
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (timerSeconds > 0) {
            Text(
                stringResource(R.string.profile_contact_timer_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onVerify) { Text(stringResource(R.string.chat_verify)) }
    }
}

@Composable
private fun MediaRow(m: Message, onSave: () -> Unit, onDownload: () -> Unit) {
    val a = m.attachment ?: return
    val thumb by rememberImageBytes(a.thumbnail)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
    ) {
        Box(Modifier.size(WhisprTheme.sizes.avatarMedium), contentAlignment = Alignment.Center) {
            val image = thumb
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(WhisprTheme.sizes.avatarMedium)
                        .clip(RoundedCornerShape(WhisprTheme.spacing.sm)),
                )
            } else {
                Icon(
                    when (a.kind) {
                        AttachmentKind.Voice -> WhisprIcons.Mic
                        AttachmentKind.Image -> WhisprIcons.Image
                        AttachmentKind.File -> WhisprIcons.File
                    },
                    contentDescription = null,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                a.fileName ?: stringResource(a.kind.previewRes()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                formatTimestamp(m.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (a.state) {
            AttachmentState.Ready -> TextButton(onClick = onSave) { Text(stringResource(R.string.chat_action_save)) }
            AttachmentState.Remote, AttachmentState.Failed -> TextButton(onClick = onDownload) {
                Text(stringResource(R.string.chat_attachment_download))
            }
            else -> Unit
        }
    }
}
