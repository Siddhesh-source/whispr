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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.formatTimestamp
import dev.whispr.core.designsystem.component.ChatListRow
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.UserId

@Composable
fun ChatsRoute(
    onOpenSettings: () -> Unit,
    onOpenChat: (UserId) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RequestNotificationPermissionOnce(enabled = state.content is ChatsContent.Conversations)
    ChatsScreen(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onRetry = viewModel::retrySignIn,
    )
}

@Composable
fun ChatsScreen(
    state: ChatsUiState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenChat: (UserId) -> Unit = {},
    onNewChat: () -> Unit = {},
) {
    val newChat = stringResource(R.string.chats_new_chat)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(
                title = stringResource(R.string.chats_title),
                actions = {
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
                    is ChatsContent.Conversations -> ConversationList(content.items, onOpenChat)
                }
            }
        }
    }
}

@Composable
private fun ConversationList(items: List<ConversationSummary>, onOpenChat: (UserId) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id.value }) { item ->
            val last = item.lastMessage
            val preview = when {
                last == null -> ""
                last.outgoing -> stringResource(R.string.chats_you_prefix, last.text)
                else -> last.text
            }
            ChatListRow(
                name = item.peer.displayName,
                lastMessage = preview,
                time = last?.let { formatTimestamp(it.timestamp) }.orEmpty(),
                onClick = { onOpenChat(item.peer.userId) },
                unreadCount = item.unreadCount,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
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
