package dev.whispr.android.ui.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onEditProfile: () -> Unit = {},
    onMyCode: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onReadReceipts = viewModel::setReadReceipts,
        onTypingIndicators = viewModel::setTypingIndicators,
        onEditProfile = onEditProfile,
        onMyCode = onMyCode,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onReadReceipts: (Boolean) -> Unit = {},
    onTypingIndicators: (Boolean) -> Unit = {},
    onEditProfile: () -> Unit = {},
    onMyCode: () -> Unit = {},
) {
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
                is SettingsUiState.Content -> SettingsContent(
                    state,
                    onReadReceipts,
                    onTypingIndicators,
                    onEditProfile,
                    onMyCode,
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState.Content,
    onReadReceipts: (Boolean) -> Unit,
    onTypingIndicators: (Boolean) -> Unit,
    onEditProfile: () -> Unit,
    onMyCode: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
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
                NavRow(stringResource(R.string.settings_edit_profile), WhisprIcons.Edit, onEditProfile)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(stringResource(R.string.settings_my_code), WhisprIcons.QrCode, onMyCode)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_connection), connectionText(state.connection))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SettingRow(stringResource(R.string.settings_account_id), state.userId) }
                    IconButton(onClick = {
                        clipScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("account ID", state.userId)))
                        }
                    }) {
                        Icon(WhisprIcons.Copy, contentDescription = stringResource(R.string.settings_copy_id))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    stringResource(R.string.settings_read_receipts),
                    stringResource(R.string.settings_read_receipts_body),
                    state.readReceipts,
                    onReadReceipts,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    stringResource(R.string.settings_typing),
                    stringResource(R.string.settings_typing_body),
                    state.typingIndicators,
                    onTypingIndicators,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    stringResource(R.string.settings_privacy),
                    stringResource(
                        if (state.keysRegistered) R.string.settings_privacy_body else R.string.settings_keys_pending,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_version), state.version)
            }
        }
    }
}

/** A row that opens another screen. */
@Composable
private fun NavRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A switch whose whole row is the touch target, announced as one toggle. */
@Composable
private fun ToggleRow(label: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
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
