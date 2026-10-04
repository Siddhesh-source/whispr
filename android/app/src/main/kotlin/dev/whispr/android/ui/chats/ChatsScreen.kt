package dev.whispr.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons

@Composable
fun ChatsRoute(onOpenSettings: () -> Unit, viewModel: ChatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatsScreen(state = state, onOpenSettings = onOpenSettings, onRetry = viewModel::retrySignIn)
}

@Composable
fun ChatsScreen(state: ChatsUiState, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
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
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(visible = state.offline)
            OfflineBanner(
                visible = state.serverUnreachable,
                message = stringResource(R.string.chats_server_unreachable),
            )
            Box(Modifier.fillMaxSize()) {
                when (state.content) {
                    ChatsContent.Loading -> LoadingState(label = stringResource(R.string.chats_loading))
                    ChatsContent.Empty -> EmptyState(
                        title = stringResource(R.string.chats_empty_title),
                        message = stringResource(R.string.chats_empty_message),
                    )
                    ChatsContent.SignInRejected -> ErrorState(
                        title = stringResource(R.string.chats_error_rejected_title),
                        message = stringResource(R.string.chats_error_rejected_message),
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}
