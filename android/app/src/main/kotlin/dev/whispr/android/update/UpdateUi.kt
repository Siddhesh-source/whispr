package dev.whispr.android.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.core.designsystem.theme.WhisprTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: Updater) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state
    val enabled: Boolean get() = updater.enabled

    fun check() {
        viewModelScope.launch { updater.check() }
    }

    fun update(manifest: UpdateManifest) {
        viewModelScope.launch { updater.update(manifest) }
    }

    fun dismiss() = updater.dismiss()

    fun permissionIntent() = updater.permissionIntent()
}

/**
 * A quiet bar above the tabs while an update is available, downloading,
 * waiting for permission or failed. Nothing is shown otherwise.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: (UpdateManifest) -> Unit,
    onDismiss: () -> Unit,
    permissionIntent: () -> android.content.Intent,
) {
    val context = LocalContext.current
    val (text, action) = when (state) {
        is UpdateState.Available ->
            stringResource(R.string.update_available, state.manifest.versionName) to
                (stringResource(R.string.update_now) to { onUpdate(state.manifest) })
        is UpdateState.Downloading -> stringResource(R.string.update_downloading) to null
        is UpdateState.Installing -> stringResource(R.string.update_installing) to null
        is UpdateState.NeedsPermission ->
            stringResource(R.string.update_needs_permission) to
                (
                    stringResource(R.string.update_allow) to {
                        context.startActivity(permissionIntent())
                        onDismiss()
                    }
                    )
        is UpdateState.Failed -> stringResource(state.reason.message()) to
            state.manifest?.let { m -> stringResource(R.string.update_retry) to { onUpdate(m) } }
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = WhisprTheme.spacing.lg, end = WhisprTheme.spacing.xs)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is UpdateState.Failed) {
                    WhisprTheme.colors.danger
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f).padding(vertical = WhisprTheme.spacing.md),
            )
            action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
            if (state is UpdateState.Available || state is UpdateState.Failed) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            }
        }
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = WhisprTheme.colors.hairline)
    }
}

fun UpdateFailure.message(): Int = when (this) {
    UpdateFailure.Network -> R.string.update_failed_network
    UpdateFailure.Corrupt -> R.string.update_failed_corrupt
    UpdateFailure.InstallFailed -> R.string.update_failed_install
}
