package dev.whispr.android.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.FilterObscuredTouches
import dev.whispr.android.ui.rememberAvatarBitmap
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprFonts
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
    val deletion by viewModel.deletion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Everything local is gone: start over from onboarding in a fresh process.
    LaunchedEffect(deletion) { if (deletion == Deletion.Done) restartApp(context) }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onReadReceipts = viewModel::setReadReceipts,
        onTypingIndicators = viewModel::setTypingIndicators,
        onEditProfile = onEditProfile,
        onMyCode = onMyCode,
        onScreenSecurity = viewModel::setScreenSecurity,
        deletion = deletion,
        onDeleteAccount = viewModel::deleteAccount,
        onDismissDeletion = viewModel::dismissDeletionError,
    )
}

private fun restartApp(context: android.content.Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    Runtime.getRuntime().exit(0)
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onReadReceipts: (Boolean) -> Unit = {},
    onTypingIndicators: (Boolean) -> Unit = {},
    onEditProfile: () -> Unit = {},
    onMyCode: () -> Unit = {},
    onScreenSecurity: (Boolean) -> Unit = {},
    deletion: Deletion = Deletion.Idle,
    onDeleteAccount: () -> Unit = {},
    onDismissDeletion: () -> Unit = {},
) {
    var confirming by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(title = stringResource(R.string.settings_title), onNavigateBack = onBack, large = true)
        },
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
                    onScreenSecurity,
                    onDelete = { confirming = true },
                )
            }
        }
    }
    if (confirming) {
        DeleteAccountDialog(
            onConfirm = {
                confirming = false
                onDeleteAccount()
            },
            onDismiss = { confirming = false },
        )
    }
    when (deletion) {
        Deletion.Idle -> Unit
        Deletion.Working, Deletion.Done -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.settings_delete_working))
                }
            },
        )
        Deletion.FailedNetwork, Deletion.Failed, Deletion.FailedLocal -> AlertDialog(
            onDismissRequest = onDismissDeletion,
            confirmButton = {
                TextButton(onClick = onDismissDeletion) { Text(stringResource(R.string.chat_error_ok)) }
            },
            text = {
                Text(
                    stringResource(
                        when (deletion) {
                            Deletion.FailedNetwork -> R.string.settings_delete_failed_network
                            Deletion.FailedLocal -> R.string.settings_delete_failed_local
                            else -> R.string.settings_delete_failed
                        },
                    ),
                )
            },
        )
    }
}

@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_title)) },
        text = {
            // Ignore taps while another app draws over this dialog (tapjacking).
            FilterObscuredTouches()
            Text(stringResource(R.string.settings_delete_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_delete_cancel)) }
        },
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState.Content,
    onReadReceipts: (Boolean) -> Unit,
    onTypingIndicators: (Boolean) -> Unit,
    onEditProfile: () -> Unit,
    onMyCode: () -> Unit,
    onScreenSecurity: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val spacing = WhisprTheme.spacing
    val avatar by rememberAvatarBitmap(state.avatarPath)
    val accountIdLabel = stringResource(R.string.settings_account_id)
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(visible = state.connection == ConnectionStatus.Offline)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = WhisprTheme.sizes.contentMaxWidth)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    WhisprAvatar(name = state.displayName, image = avatar, size = WhisprTheme.sizes.avatarLarge)
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            state.userId,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = WhisprFonts.Mono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.semantics {
                                contentDescription = "${'$'}{accountIdLabel}: ${'$'}{state.userId}"
                            },
                        )
                    }
                    IconButton(onClick = {
                        clipScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("account ID", state.userId)))
                        }
                    }) {
                        Icon(
                            WhisprIcons.Copy,
                            contentDescription = stringResource(R.string.settings_copy_id),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionLabel(stringResource(R.string.settings_section_account))
                SettingsGroup {
                    NavRow(stringResource(R.string.settings_edit_profile), WhisprIcons.Edit, onEditProfile)
                    GroupDivider()
                    NavRow(stringResource(R.string.settings_my_code), WhisprIcons.QrCode, onMyCode)
                }

                SectionLabel(stringResource(R.string.settings_privacy))
                SettingsGroup {
                    ToggleRow(
                        stringResource(R.string.settings_screen_security),
                        stringResource(R.string.settings_screen_security_body),
                        state.screenSecurity,
                        onScreenSecurity,
                    )
                    GroupDivider()
                    ToggleRow(
                        stringResource(R.string.settings_read_receipts),
                        stringResource(R.string.settings_read_receipts_body),
                        state.readReceipts,
                        onReadReceipts,
                    )
                    GroupDivider()
                    ToggleRow(
                        stringResource(R.string.settings_typing),
                        stringResource(R.string.settings_typing_body),
                        state.typingIndicators,
                        onTypingIndicators,
                    )
                }
                Text(
                    stringResource(
                        if (state.keysRegistered) R.string.settings_privacy_body else R.string.settings_keys_pending,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.sm),
                )

                SectionLabel(stringResource(R.string.settings_section_about))
                SettingsGroup {
                    SettingRow(stringResource(R.string.settings_connection), connectionText(state.connection))
                    GroupDivider()
                    SettingRow(stringResource(R.string.settings_version), state.version)
                }

                Spacer(Modifier.height(spacing.xl))
                SettingsGroup {
                    NavRow(
                        stringResource(R.string.settings_delete_account),
                        WhisprIcons.Delete,
                        onDelete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Small muted label above a settings group. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(
                start = WhisprTheme.spacing.xl,
                end = WhisprTheme.spacing.xl,
                top = WhisprTheme.spacing.xl,
                bottom = WhisprTheme.spacing.sm,
            )
            .semantics { heading() },
    )
}

/** A raised panel holding related rows: surface, 16dp corners, hairline border. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = WhisprTheme.colors.surface,
        border = BorderStroke(WhisprTheme.sizes.hairline, WhisprTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhisprTheme.spacing.lg),
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = WhisprTheme.spacing.lg),
        thickness = WhisprTheme.sizes.hairline,
        color = WhisprTheme.colors.hairline,
    )
}

/** A row that opens another screen. */
@Composable
private fun NavRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onSurface)
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
