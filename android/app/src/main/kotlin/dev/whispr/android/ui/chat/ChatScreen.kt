package dev.whispr.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import dev.whispr.core.designsystem.component.WarningCard
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.TrustState

@Composable
fun ChatRoute(onBack: () -> Unit, onVerify: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val count = (state.content as? ChatContent.Messages)?.items?.size ?: 0
    // Only while actually on screen: mark read (sends a receipt if enabled)
    // and suppress notifications for this conversation.
    LifecycleResumeEffect(count) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
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
    )
}

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
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(
                title = state.peerName,
                onNavigateBack = onBack,
                subtitle = if (state.peerTyping) stringResource(R.string.chat_typing) else null,
                titleBadge = if (state.trust == TrustState.Verified) WhisprIcons.Verified else null,
                titleBadgeDescription = stringResource(R.string.chat_verified_badge),
                actions = {
                    if (state.content !is ChatContent.Missing) {
                        IconButton(onClick = onVerify) {
                            Icon(WhisprIcons.Verified, contentDescription = stringResource(R.string.chat_verify))
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
                            message = stringResource(R.string.chat_empty_message),
                        )
                    } else {
                        MessageList(content.items, state.peerName, onRetry)
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
                    state.isRequest -> RequestBar(state.peerName, onAccept, onDecline)
                    else -> MessageInputBar(
                        value = state.input,
                        onValueChange = onInput,
                        onSend = onSend,
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(items: List<BubbleItem>, peerName: String, onRetry: (String) -> Unit) {
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
        items(items, key = { it.message.id }) { item ->
            val m = item.message
            MessageBubble(
                text = m.notice?.let { stringResource(it.labelRes()) } ?: m.text,
                time = formatTime(m.timestamp),
                direction = if (m.outgoing) BubbleDirection.Outgoing else BubbleDirection.Incoming,
                senderName = if (m.outgoing) null else peerName,
                groupPosition = item.position,
                status = m.status?.toDeliveryStatus(),
                onRetry = { onRetry(m.id) },
                notice = m.notice != null,
            )
        }
    }
}

@Composable
private fun RequestBar(name: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Text(
                stringResource(R.string.chat_request_message, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
