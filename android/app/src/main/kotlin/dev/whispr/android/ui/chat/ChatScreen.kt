package dev.whispr.android.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.formatTime
import dev.whispr.core.designsystem.component.BubbleDirection
import dev.whispr.core.designsystem.component.DeliveryStatus
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.MessageBubble
import dev.whispr.core.designsystem.component.MessageInputBar
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.QuotePreview
import dev.whispr.core.designsystem.component.ReactionChip
import dev.whispr.core.designsystem.component.SystemNotice
import dev.whispr.core.designsystem.component.WarningCard
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.Attachment
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageRules
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.Quote
import dev.whispr.domain.model.TrustState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen needs to show and open attachments; the ViewModel in the app, fakes in tests. */
interface AttachmentActions {
    fun download(messageId: String)
    suspend fun bytes(messageId: String): ByteArray?
    suspend fun export(messageId: String): String?

    companion object {
        val None = object : AttachmentActions {
            override fun download(messageId: String) = Unit
            override suspend fun bytes(messageId: String): ByteArray? = null
            override suspend fun export(messageId: String): String? = null
        }
    }
}

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onGroupInfo: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val count = (state.content as? ChatContent.Messages)?.items?.size ?: 0
    // Only while actually on screen: mark read (sends a receipt if enabled)
    // and suppress notifications for this conversation.
    LifecycleResumeEffect(count) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    val actions = remember(viewModel) {
        object : AttachmentActions {
            override fun download(messageId: String) = viewModel.download(messageId)
            override suspend fun bytes(messageId: String) = viewModel.attachmentBytes(messageId)
            override suspend fun export(messageId: String) = viewModel.exportAttachment(messageId)
        }
    }
    ChatScreen(
        state = state,
        onBack = onBack,
        onInput = viewModel::onInput,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onVerify = onVerify,
        onAccept = viewModel::acceptRequest,
        onDecline = { viewModel.declineRequest(onBack) },
        onAcknowledgeKeyChange = viewModel::acknowledgeKeyChange,
        onSendMedia = viewModel::sendMedia,
        onReact = viewModel::react,
        onDismissError = viewModel::dismissError,
        onGroupInfo = onGroupInfo,
        onAcceptInvite = viewModel::acceptInvite,
        onDeclineInvite = { viewModel.declineInvite(onBack) },
        attachments = actions,
        messageActions = MessageActions(
            onReply = viewModel::replyTo,
            onForward = viewModel::forward,
            onDeleteForMe = viewModel::deleteForMe,
            onDeleteForEveryone = viewModel::deleteForEveryone,
            canDeleteForEveryone = viewModel::canDeleteForEveryone,
            onSetTimer = viewModel::setTimer,
            forwardTargets = forwardTargets,
        ),
    )
}

/** Long-press actions on a message, and the conversation timer. */
data class MessageActions(
    val onReply: (Message?) -> Unit = {},
    val onForward: (messageId: String, to: ConversationId) -> Unit = { _, _ -> },
    val onDeleteForMe: (String) -> Unit = {},
    val onDeleteForEveryone: (String) -> Unit = {},
    val canDeleteForEveryone: (Message) -> Boolean = { false },
    val onSetTimer: (Long) -> Unit = {},
    val forwardTargets: List<ConversationSummary> = emptyList(),
)

private enum class DeleteKind { ForMe, ForEveryone }

@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onVerify: () -> Unit = {},
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onAcknowledgeKeyChange: () -> Unit = {},
    onSendMedia: (uri: String, kind: AttachmentKind, fileName: String?, durationMs: Long?) -> Unit = { _, _, _, _ -> },
    onReact: (messageId: String, emoji: String?) -> Unit = { _, _ -> },
    onDismissError: () -> Unit = {},
    onGroupInfo: () -> Unit = {},
    onAcceptInvite: () -> Unit = {},
    onDeclineInvite: () -> Unit = {},
    attachments: AttachmentActions = AttachmentActions.None,
    messageActions: MessageActions = MessageActions(),
) {
    var reactingTo by remember { mutableStateOf<Message?>(null) }
    var actingOn by remember { mutableStateOf<Message?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var deleting by remember { mutableStateOf<Pair<Message, DeleteKind>?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(
                title = state.peerName,
                onNavigateBack = onBack,
                subtitle = when {
                    state.peerTyping -> stringResource(R.string.chat_typing)
                    state.isGroup && state.memberCount > 0 -> pluralStringResource(
                        R.plurals.chat_group_members,
                        state.memberCount,
                        state.memberCount,
                    )
                    else -> null
                },
                titleBadge = if (state.trust == TrustState.Verified) WhisprIcons.Verified else null,
                titleBadgeDescription = stringResource(R.string.chat_verified_badge),
                actions = {
                    if (state.content !is ChatContent.Missing) {
                        if (state.canCompose) TimerMenu(state.timerSeconds, messageActions.onSetTimer)
                        if (state.isGroup) {
                            IconButton(onClick = onGroupInfo) {
                                Icon(WhisprIcons.Group, contentDescription = stringResource(R.string.chat_group_info))
                            }
                        } else {
                            IconButton(onClick = onVerify) {
                                Icon(WhisprIcons.Verified, contentDescription = stringResource(R.string.chat_verify))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OfflineBanner(visible = state.offline, message = stringResource(R.string.chat_offline))
            Box(Modifier.weight(1f)) {
                when (val content = state.content) {
                    ChatContent.Loading -> LoadingState(label = stringResource(R.string.chat_loading))
                    ChatContent.Missing -> ErrorState(
                        title = stringResource(R.string.chat_missing_title),
                        message = stringResource(R.string.chat_missing_message),
                        onRetry = onBack,
                        retryLabel = stringResource(R.string.settings_error_action),
                    )
                    is ChatContent.Messages -> if (content.items.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(
                                if (state.isGroup) R.string.chat_group_empty_message else R.string.chat_empty_message,
                            ),
                        )
                    } else {
                        MessageList(
                            content.items,
                            state.peerName,
                            state.isGroup,
                            onRetry,
                            onLongPress = { actingOn = it },
                            onReact = onReact,
                            attachments = attachments,
                        )
                    }
                }
            }
            if (state.content is ChatContent.Messages) {
                when {
                    // Never accepted silently: sending stays paused until the user decides.
                    state.trust == TrustState.KeyChanged -> WarningCard(
                        title = stringResource(R.string.chat_key_changed_title),
                        message = stringResource(R.string.chat_key_changed_message, state.peerName),
                        primaryLabel = stringResource(R.string.chat_key_changed_accept),
                        onPrimary = onAcknowledgeKeyChange,
                        modifier = Modifier.padding(WhisprTheme.spacing.md).navigationBarsPadding(),
                    )
                    state.isRequest -> RequestBar(
                        stringResource(R.string.chat_request_message, state.peerName),
                        onAccept,
                        onDecline,
                    )
                    state.groupStatus == GroupStatus.Invited -> RequestBar(
                        stringResource(R.string.chat_invite_message, state.peerName),
                        onAcceptInvite,
                        onDeclineInvite,
                    )
                    state.isGroup && !state.canCompose -> ClosedBar(
                        stringResource(
                            if (state.groupStatus ==
                                GroupStatus.Left
                            ) {
                                R.string.chat_group_left
                            } else {
                                R.string.chat_group_removed
                            },
                        ),
                    )
                    else -> Column {
                        state.replyingTo?.let { ReplyBar(it, state.peerName) { messageActions.onReply(null) } }
                        Composer(state, onInput, onSend, onSendMedia)
                    }
                }
            }
        }
    }
    actingOn?.let { m ->
        ActionSheet(
            message = m,
            canWrite = state.canCompose,
            canDeleteForEveryone = messageActions.canDeleteForEveryone(m),
            onDismiss = { actingOn = null },
            onReact = { reactingTo = m },
            onReply = { messageActions.onReply(m) },
            onForward = { forwarding = m },
            onDelete = { kind -> deleting = m to kind },
        )
    }
    forwarding?.let { m ->
        ForwardPicker(
            targets = messageActions.forwardTargets,
            onPick = { to ->
                messageActions.onForward(m.id, to)
                forwarding = null
            },
            onDismiss = { forwarding = null },
        )
    }
    deleting?.let { (m, kind) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(onClick = {
                    if (kind == DeleteKind.ForMe) {
                        messageActions.onDeleteForMe(m.id)
                    } else {
                        messageActions.onDeleteForEveryone(m.id)
                    }
                    deleting = null
                }) { Text(stringResource(R.string.chat_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
            text = {
                Text(
                    stringResource(
                        if (kind == DeleteKind.ForMe) {
                            R.string.chat_delete_me_message
                        } else {
                            R.string.chat_delete_everyone_message
                        },
                    ),
                )
            },
        )
    }
    reactingTo?.let { m ->
        ReactionPicker(
            mine = m.reactions.firstOrNull { it.mine }?.emoji,
            onPick = { emoji ->
                onReact(m.id, emoji)
                reactingTo = null
            },
            onDismiss = { reactingTo = null },
        )
    }
    state.error?.let { e ->
        AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.chat_error_ok)) } },
            text = {
                Text(
                    stringResource(
                        when (e) {
                            ChatError.TooLarge -> R.string.chat_error_too_large
                            ChatError.Unreadable -> R.string.chat_error_unreadable
                            ChatError.NotAllowed -> R.string.chat_error_not_allowed
                            ChatError.DeleteFailed -> R.string.chat_error_delete_failed
                        },
                    ),
                )
            },
        )
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onSendMedia: (String, AttachmentKind, String?, Long?) -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onSendMedia(it.toString(), AttachmentKind.Image, null, null) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onSendMedia(it.toString(), AttachmentKind.File, null, null) }
    }
    val startRecording = { recording = recorder.start() }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(onClick = { menu = true }, enabled = !recording) {
                Icon(WhisprIcons.Attach, contentDescription = stringResource(R.string.chat_attach))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_attach_photo)) },
                    leadingIcon = { Icon(WhisprIcons.Image, contentDescription = null) },
                    onClick = {
                        menu = false
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_attach_file)) },
                    leadingIcon = { Icon(WhisprIcons.File, contentDescription = null) },
                    onClick = {
                        menu = false
                        pickFile.launch(arrayOf("*/*"))
                    },
                )
            }
        }
        if (recording) {
            Text(
                stringResource(R.string.chat_recording),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).padding(horizontal = WhisprTheme.spacing.md),
            )
            IconButton(onClick = {
                recording = false
                recorder.stop()?.let { r -> onSendMedia(r.uri, AttachmentKind.Voice, null, r.durationMs) }
            }) {
                Icon(WhisprIcons.Stop, contentDescription = stringResource(R.string.chat_record_stop))
            }
        } else {
            MessageInputBar(
                value = state.input,
                onValueChange = onInput,
                onSend = onSend,
                modifier = Modifier.weight(1f),
            )
            if (state.input.isBlank()) {
                IconButton(onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Icon(WhisprIcons.Mic, contentDescription = stringResource(R.string.chat_record))
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    items: List<BubbleItem>,
    peerName: String,
    isGroup: Boolean,
    onRetry: (String) -> Unit,
    onLongPress: (Message) -> Unit,
    onReact: (String, String?) -> Unit,
    attachments: AttachmentActions,
) {
    val listState = rememberLazyListState()
    // Newest at the bottom; follow new messages.
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex) }
    val spacing = WhisprTheme.spacing
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        items(items, key = { it.message.id + it.message.author?.value.orEmpty() }) { item ->
            val m = item.message
            if (m.system) {
                SystemNotice(m.text)
                return@items
            }
            val attachment = m.attachment.takeIf { !m.deleted }
            MessageBubble(
                text = when {
                    m.deleted -> stringResource(R.string.chat_deleted)
                    m.notice != null -> stringResource(m.notice!!.labelRes())
                    else -> m.text
                },
                time = formatTime(m.timestamp),
                direction = if (m.outgoing) BubbleDirection.Outgoing else BubbleDirection.Incoming,
                senderName = if (m.outgoing) null else m.authorName ?: peerName,
                showSender = isGroup && item.position.startsRun(),
                groupPosition = item.position,
                status = m.status?.toDeliveryStatus(),
                onRetry = { onRetry(m.id) },
                notice = m.notice != null || m.deleted,
                attachment = attachment?.let { a -> { AttachmentView(m.id, a, attachments) } },
                reactions = m.reactions.map { ReactionChip(it.emoji, it.count, it.mine) },
                onActions = if (m.notice == null) ({ onLongPress(m) }) else null,
                quote = m.quote?.takeIf { !m.deleted }?.let { quotePreview(it, peerName) },
                forwarded = m.forwarded && !m.deleted,
                expiring = m.expiresIn != null && !m.deleted,
                onReactionClick = { emoji ->
                    val mine = m.reactions.firstOrNull { it.mine }?.emoji
                    onReact(m.id, if (mine == emoji) null else emoji)
                },
            )
        }
    }
}

private fun dev.whispr.core.designsystem.component.BubbleGroupPosition.startsRun() =
    this == dev.whispr.core.designsystem.component.BubbleGroupPosition.Single ||
        this == dev.whispr.core.designsystem.component.BubbleGroupPosition.First

@Composable
private fun AttachmentView(messageId: String, a: Attachment, actions: AttachmentActions) {
    // Images and voice download as soon as they're on screen; files on demand.
    LaunchedEffect(messageId, a.state) {
        if (a.state == AttachmentState.Remote && a.kind != AttachmentKind.File) actions.download(messageId)
    }
    when (a.kind) {
        AttachmentKind.Image -> ImageAttachment(messageId, a, actions)
        AttachmentKind.Voice -> VoiceAttachment(messageId, a, actions)
        AttachmentKind.File -> FileAttachment(messageId, a, actions)
    }
}

@Composable
private fun ImageAttachment(messageId: String, a: Attachment, actions: AttachmentActions) {
    val full by produceState<ImageBitmap?>(null, messageId, a.state) {
        value = if (a.state == AttachmentState.Ready) actions.bytes(messageId)?.let { decode(it) } else null
    }
    val thumb by produceState<ImageBitmap?>(null, a.thumbnail) { value = a.thumbnail?.let { decode(it) } }
    val preview = full ?: thumb
    var zoomed by remember { mutableStateOf(false) }
    val description = stringResource(R.string.chat_attachment_photo)
    Box(
        Modifier
            .widthIn(max = WhisprTheme.sizes.mediaPreviewMax)
            .heightIn(max = WhisprTheme.sizes.mediaPreviewMax)
            .semantics { contentDescription = description }
            .clickable(enabled = full != null) { zoomed = true },
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(preview, contentDescription = null, contentScale = ContentScale.Fit)
        } else {
            Icon(WhisprIcons.Image, contentDescription = null, modifier = Modifier.size(WhisprTheme.sizes.avatarLarge))
        }
        AttachmentStateOverlay(a.state) { actions.download(messageId) }
    }
    if (zoomed && full != null) {
        Dialog(onDismissRequest = { zoomed = false }) {
            Image(full!!, contentDescription = description, modifier = Modifier.clickable { zoomed = false })
        }
    }
}

@Composable
private fun VoiceAttachment(messageId: String, a: Attachment, actions: AttachmentActions) {
    val player = remember { VoicePlayer() }
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.stop() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = a.state == AttachmentState.Ready,
            onClick = {
                if (playing) {
                    player.stop()
                    playing = false
                } else {
                    scope.launch {
                        val bytes = actions.bytes(messageId) ?: return@launch
                        playing = true
                        player.play(bytes) { playing = false }
                    }
                }
            },
        ) {
            Icon(
                if (playing) WhisprIcons.Pause else WhisprIcons.Play,
                contentDescription = stringResource(
                    if (playing) R.string.chat_voice_pause else R.string.chat_voice_play,
                ),
            )
        }
        Text(
            stringResource(R.string.chat_voice_label, formatDuration(a.durationMs ?: 0)),
            style = MaterialTheme.typography.bodyMedium,
        )
        AttachmentStateOverlay(a.state) { actions.download(messageId) }
    }
}

@Composable
private fun FileAttachment(messageId: String, a: Attachment, actions: AttachmentActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val name = a.fileName ?: stringResource(R.string.chat_attachment_file)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
    ) {
        Icon(WhisprIcons.File, contentDescription = null)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(formatSize(a.size), style = MaterialTheme.typography.labelSmall)
        }
        when (a.state) {
            AttachmentState.Ready -> TextButton(onClick = {
                scope.launch {
                    val path = actions.export(messageId)
                    failed = path == null || !openExternally(context, path, a.contentType)
                }
            }) { Text(stringResource(R.string.chat_attachment_open)) }
            else -> AttachmentStateOverlay(a.state) { actions.download(messageId) }
        }
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            confirmButton = {
                TextButton(onClick = { failed = false }) { Text(stringResource(R.string.chat_error_ok)) }
            },
            text = { Text(stringResource(R.string.chat_attachment_no_app)) },
        )
    }
}

/** Download button, progress, or why the attachment can't be shown. */
@Composable
private fun AttachmentStateOverlay(state: AttachmentState, onDownload: () -> Unit) {
    when (state) {
        AttachmentState.Remote, AttachmentState.Failed -> IconButton(onClick = onDownload) {
            Icon(WhisprIcons.Download, contentDescription = stringResource(R.string.chat_attachment_download))
        }
        AttachmentState.Downloading, AttachmentState.Uploading -> CircularProgressIndicator(
            modifier = Modifier.size(WhisprTheme.sizes.progressSmall),
            strokeWidth = WhisprTheme.sizes.progressStroke,
        )
        AttachmentState.Expired -> Text(
            stringResource(R.string.chat_attachment_expired),
            style = MaterialTheme.typography.labelSmall,
        )
        AttachmentState.Corrupt -> Text(
            stringResource(R.string.chat_attachment_corrupt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        AttachmentState.Ready -> Unit
    }
}

@Composable
private fun quotePreview(q: Quote, peerName: String): QuotePreview {
    val author = if (q.outgoing) stringResource(R.string.chat_you) else q.authorName ?: peerName
    val text = when {
        !q.found -> stringResource(R.string.chat_quote_missing)
        q.text.isNotEmpty() -> q.text
        else -> when (q.attachmentKind) {
            AttachmentKind.Image -> stringResource(R.string.chats_preview_photo)
            AttachmentKind.Voice -> stringResource(R.string.chats_preview_voice)
            AttachmentKind.File, null -> stringResource(R.string.chats_preview_file)
        }
    }
    return QuotePreview(author, text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    message: Message,
    canWrite: Boolean,
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onReact: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onDelete: (DeleteKind) -> Unit,
) {
    val context = LocalContext.current
    val content = MessageRules.isContent(message)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        @Composable
        fun action(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) {
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { Icon(icon, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    run()
                },
            )
        }
        Column(Modifier.navigationBarsPadding()) {
            if (canWrite && content) {
                action(R.string.chat_action_react, WhisprIcons.React, onReact)
                action(R.string.chat_action_reply, WhisprIcons.Reply, onReply)
            }
            if (content && message.text.isNotEmpty()) {
                action(R.string.chat_action_copy, WhisprIcons.Copy) { copySensitive(context, message.text) }
            }
            if (content) action(R.string.chat_action_forward, WhisprIcons.Forward, onForward)
            action(R.string.chat_action_delete_me, WhisprIcons.Delete) { onDelete(DeleteKind.ForMe) }
            if (canDeleteForEveryone) {
                action(R.string.chat_action_delete_everyone, WhisprIcons.Delete) { onDelete(DeleteKind.ForEveryone) }
            }
        }
    }
}

/** Copies [text], flagged sensitive so the keyboard's clipboard preview and history hide it. */
private fun copySensitive(context: android.content.Context, text: String) {
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            },
            true,
        )
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
private fun ForwardPicker(
    targets: List<ConversationSummary>,
    onPick: (ConversationId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
        title = { Text(stringResource(R.string.chat_forward_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.chat_forward_empty))
            } else {
                LazyColumn {
                    items(targets, key = { it.id.value }) { c ->
                        ListItem(
                            headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { onPick(c.id) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ReplyBar(message: Message, peerName: String, onCancel: () -> Unit) {
    val author = if (message.outgoing) stringResource(R.string.chat_you) else message.authorName ?: peerName
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = WhisprTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WhisprIcons.Reply, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = WhisprTheme.spacing.sm)) {
                Text(
                    stringResource(R.string.chat_reply_to, author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    message.text.ifEmpty { stringResource(R.string.chats_preview_file) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(WhisprIcons.Close, contentDescription = stringResource(R.string.chat_reply_cancel))
            }
        }
    }
}

@Composable
private fun TimerMenu(seconds: Long, onSet: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                WhisprIcons.Timer,
                contentDescription = stringResource(R.string.chat_timer),
                tint = if (seconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MessageRules.TIMER_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(timerLabel(option)) },
                    trailingIcon = if (option ==
                        seconds
                    ) {
                        ({ Icon(WhisprIcons.Sent, contentDescription = null) })
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (option != seconds) onSet(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun timerLabel(seconds: Long): String = when (seconds) {
    0L -> stringResource(R.string.chat_timer_off)
    MessageRules.TIMER_OPTIONS[1] -> stringResource(R.string.chat_timer_5m)
    MessageRules.TIMER_OPTIONS[2] -> stringResource(R.string.chat_timer_1h)
    MessageRules.TIMER_OPTIONS[3] -> stringResource(R.string.chat_timer_1d)
    MessageRules.TIMER_OPTIONS[4] -> stringResource(R.string.chat_timer_1w)
    else -> stringResource(R.string.chat_timer_custom, seconds.toInt())
}

@Composable
private fun ReactionPicker(mine: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (mine != null) {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.chat_reaction_remove)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_reaction_cancel)) } },
        title = { Text(stringResource(R.string.chat_reaction_title)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs)) {
                REACTIONS.forEach { emoji ->
                    TextButton(onClick = { onPick(emoji) }) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        },
    )
}

private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@Composable
private fun RequestBar(message: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecline) { Text(stringResource(R.string.chat_request_decline)) }
                WhisprPrimaryButton(
                    text = stringResource(R.string.chat_request_accept),
                    onClick = onAccept,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun ClosedBar(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(WhisprTheme.spacing.lg).navigationBarsPadding(),
        )
    }
}

private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

private fun formatDuration(ms: Long): String {
    val s = ms / MS_PER_S
    return "%d:%02d".format(s / S_PER_MIN, s % S_PER_MIN)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= MIB -> "%.1f MB".format(bytes / MIB.toDouble())
    bytes >= KIB -> "%d KB".format(bytes / KIB)
    else -> "$bytes B"
}

private const val MS_PER_S = 1000
private const val S_PER_MIN = 60
private const val KIB = 1024L
private const val MIB = 1024L * 1024L

private fun MessageNotice.labelRes() = when (this) {
    MessageNotice.Pending -> R.string.chat_notice_pending
    MessageNotice.Waiting -> R.string.chat_notice_waiting
    MessageNotice.Unrecoverable -> R.string.chat_notice_unrecoverable
    MessageNotice.Held -> R.string.chat_notice_held
}

private fun MessageStatus.toDeliveryStatus() = when (this) {
    MessageStatus.Sending -> DeliveryStatus.Sending
    MessageStatus.Sent -> DeliveryStatus.Sent
    MessageStatus.Delivered -> DeliveryStatus.Delivered
    MessageStatus.Read -> DeliveryStatus.Read
    MessageStatus.Failed -> DeliveryStatus.Failed
}
