package dev.whispr.android.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.formatTimestamp
import dev.whispr.android.ui.rememberImageBytes
import dev.whispr.core.designsystem.component.ChatListRow
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.ListDivider
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId

@Composable
fun ChatsRoute(
    onOpenSettings: () -> Unit,
    onMyCode: () -> Unit,
    onOpenChat: (UserId) -> Unit,
    onOpenGroup: (GroupId) -> Unit,
    onNewGroup: () -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RequestNotificationPermissionOnce(enabled = state.content is ChatsContent.Conversations)
    ChatsScreen(
        state = state,
        onOpenSettings = onOpenSettings,
        onMyCode = onMyCode,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onOpenGroup = onOpenGroup,
        onNewGroup = onNewGroup,
        onRetry = viewModel::retrySignIn,
        onSearch = onSearch,
    )
}

@Composable
fun ChatsScreen(
    state: ChatsUiState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenChat: (UserId) -> Unit = {},
    onNewChat: () -> Unit = {},
    onMyCode: () -> Unit = {},
    onOpenGroup: (GroupId) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val newChat = stringResource(R.string.chats_new_chat)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(
                title = stringResource(R.string.chats_title),
                large = true,
                actions = {
                    if (state.content is ChatsContent.Conversations) {
                        IconButton(onClick = onSearch) {
                            Icon(WhisprIcons.Search, contentDescription = stringResource(R.string.chats_search))
                        }
                    }
                    IconButton(onClick = onNewGroup) {
                        Icon(WhisprIcons.Group, contentDescription = stringResource(R.string.chats_new_group))
                    }
                    IconButton(onClick = onMyCode) {
                        Icon(WhisprIcons.QrCode, contentDescription = stringResource(R.string.chats_my_code))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(WhisprIcons.Settings, contentDescription = stringResource(R.string.chats_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            // The one primary action, when there is a list to act on.
            if (state.content is ChatsContent.Conversations) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(WhisprIcons.PersonAdd, contentDescription = null) },
                    text = { Text(newChat) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(visible = state.offline)
            OfflineBanner(
                visible = state.serverUnreachable,
                message = stringResource(R.string.chats_server_unreachable),
            )
            Box(Modifier.fillMaxSize()) {
                when (val content = state.content) {
                    ChatsContent.Loading -> LoadingState(label = stringResource(R.string.chats_loading))
                    ChatsContent.Empty -> EmptyState(
                        title = stringResource(R.string.chats_empty_title),
                        message = stringResource(R.string.chats_empty_message),
                        action = { WhisprPrimaryButton(text = newChat, onClick = onNewChat, fillWidth = false) },
                    )
                    ChatsContent.SignInRejected -> ErrorState(
                        title = stringResource(R.string.chats_error_rejected_title),
                        message = stringResource(R.string.chats_error_rejected_message),
                        onRetry = onRetry,
                    )
                    is ChatsContent.Conversations -> ConversationList(content.items) { item ->
                        item.group?.let { onOpenGroup(it.id) } ?: item.peer?.let { onOpenChat(it.userId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationList(items: List<ConversationSummary>, onOpen: (ConversationSummary) -> Unit) {
    // Requests and group invites first, under their own heading; accepted chats below.
    val (requests, chats) = items.partition {
        it.peer?.isRequest == true || it.group?.status == GroupStatus.Invited
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (requests.isNotEmpty()) {
            item(key = "requests-header") {
                Text(
                    stringResource(R.string.chats_requests),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(
                            start = WhisprTheme.spacing.lg,
                            end = WhisprTheme.spacing.lg,
                            top = WhisprTheme.spacing.md,
                            bottom = WhisprTheme.spacing.xs,
                        )
                        .semantics { heading() },
                )
            }
            itemsIndexed(requests, key = { _, it -> "r-" + it.id.value }) { i, it ->
                ConversationRow(it, onOpen, divider = i < requests.lastIndex)
            }
            item(key = "requests-divider") { ListDivider(inset = false) }
        }
        itemsIndexed(chats, key = { _, it -> it.id.value }) { i, it ->
            ConversationRow(it, onOpen, divider = i < chats.lastIndex)
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationSummary, onOpen: (ConversationSummary) -> Unit, divider: Boolean) {
    val last = item.lastMessage
    val body = when {
        last == null -> ""
        last.attachment?.animated == true -> stringResource(R.string.chat_attachment_gif)
        last.attachment != null -> stringResource(last.attachment!!.kind.previewRes())
        else -> last.text
    }
    val preview = when {
        item.peer?.trust == TrustState.KeyChanged -> stringResource(R.string.chats_key_changed_preview)
        item.group?.status == GroupStatus.Invited -> stringResource(R.string.chats_invite_preview)
        item.group?.status == GroupStatus.Removed || item.group?.status == GroupStatus.Left ->
            stringResource(R.string.chats_removed_preview)
        last == null && item.peer?.isRequest == true -> stringResource(R.string.chats_request_preview)
        item.peer?.awaitingAccept == true -> stringResource(R.string.chats_request_sent_preview)
        last == null -> ""
        last.system -> body
        last.outgoing -> stringResource(R.string.chats_you_prefix, body)
        last.authorName != null -> stringResource(R.string.chats_author_prefix, last.authorName!!, body)
        else -> body
    }
    val avatar by rememberImageBytes(item.group?.avatar ?: item.peer?.avatar)
    ChatListRow(
        name = item.title,
        avatar = avatar,
        lastMessage = preview,
        time = last?.let { formatTimestamp(it.timestamp) }.orEmpty(),
        onClick = { onOpen(item) },
        unreadCount = item.unreadCount,
        verified = item.peer?.trust == TrustState.Verified,
        alert = item.peer?.trust == TrustState.KeyChanged,
    )
    if (divider) ListDivider()
}

internal fun AttachmentKind.previewRes() = when (this) {
    AttachmentKind.Image -> R.string.chats_preview_photo
    AttachmentKind.File -> R.string.chats_preview_file
    AttachmentKind.Voice -> R.string.chats_preview_voice
}

/** Asks for notification permission (Android 13+) once there are chats to notify about. */
@Composable
private fun RequestNotificationPermissionOnce(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enabled) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (enabled && !asked && !granted) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
