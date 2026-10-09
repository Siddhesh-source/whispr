package dev.whispr.android.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.WhisprFields
import dev.whispr.core.designsystem.theme.WhisprFonts
import dev.whispr.core.designsystem.theme.WhisprTheme

/** Onboarding's way back in after reinstalling: pick the backup file, type the recovery key. */
@Composable
fun RestoreFromBackup(viewModel: RestoreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.picked(it) }
    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_action)) }
    LaunchedEffect(state) { if (state == RestoreUiState.Done) restart(context) }
    when (val s = state) {
        is RestoreUiState.NeedsKey -> KeyDialog(s.wrongKey, viewModel::restore, viewModel::dismiss)
        RestoreUiState.Working, RestoreUiState.Done -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.restore_working)) },
            text = { CircularProgressIndicator() },
        )
        RestoreUiState.Damaged -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.chat_error_ok)) }
            },
            title = { Text(stringResource(R.string.restore_damaged_title)) },
            text = { Text(stringResource(R.string.restore_damaged_body)) },
        )
        RestoreUiState.Idle -> Unit
    }
}

@Composable
private fun KeyDialog(wrongKey: Boolean, onRestore: (String) -> Unit, onCancel: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.restore_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md)) {
                Text(stringResource(R.string.restore_key_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    shape = WhisprFields.shape,
                    colors = WhisprFields.colors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = WhisprFonts.Mono),
                    label = { Text(stringResource(R.string.restore_key_label)) },
                    isError = wrongKey,
                    supportingText = if (wrongKey) {
                        { Text(stringResource(R.string.restore_key_wrong)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(key) }, enabled = key.isNotBlank()) {
                Text(stringResource(R.string.restore_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** The restored database is swapped in at start, so the process starts over. */
private fun restart(context: Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    Runtime.getRuntime().exit(0)
}
