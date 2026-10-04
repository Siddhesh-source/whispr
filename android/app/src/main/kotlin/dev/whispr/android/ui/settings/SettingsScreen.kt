package dev.whispr.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.rememberAvatarBitmap
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.theme.WhisprTheme

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onBack = onBack)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.settings_title), onNavigateBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                SettingsUiState.Loading -> LoadingState(label = stringResource(R.string.settings_loading))
                // Local data only: there is nothing useful to retry, so the action just goes back.
                SettingsUiState.Error -> ErrorState(
                    title = stringResource(R.string.settings_error_title),
                    message = stringResource(R.string.settings_error_message),
                    onRetry = onBack,
                    retryLabel = stringResource(R.string.settings_error_action),
                )
                is SettingsUiState.Content -> SettingsContent(state)
            }
        }
    }
}

@Composable
private fun SettingsContent(state: SettingsUiState.Content) {
    val spacing = WhisprTheme.spacing
    val avatar by rememberAvatarBitmap(state.avatarPath)
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(visible = state.connection == ConnectionStatus.Offline)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WhisprAvatar(name = state.displayName, image = avatar, size = WhisprTheme.sizes.avatarXLarge)
            Text(
                state.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.lg, start = spacing.lg, end = spacing.lg).semantics {
                    heading()
                },
            )
            Column(Modifier.widthIn(max = WhisprTheme.sizes.contentMaxWidth).padding(top = spacing.xl)) {
                SettingRow(stringResource(R.string.settings_connection), connectionText(state.connection))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_account_id), state.userId)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_body))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_version), state.version)
            }
        }
    }
}

/** A read-only label/value pair, announced as one item. */
@Composable
private fun SettingRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun connectionText(status: ConnectionStatus) = stringResource(
    when (status) {
        ConnectionStatus.Active -> R.string.settings_connection_active
        ConnectionStatus.Connecting -> R.string.settings_connection_connecting
        ConnectionStatus.Offline -> R.string.settings_connection_offline
        ConnectionStatus.Unavailable -> R.string.settings_connection_unavailable
    },
)
