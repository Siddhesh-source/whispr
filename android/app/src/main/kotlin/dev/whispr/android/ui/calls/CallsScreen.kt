package dev.whispr.android.ui.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.ui.formatTimestamp
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ListDivider
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.CallLogEntry
import dev.whispr.domain.model.CallOutcome
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.CallLogRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CallsUiState(val loading: Boolean = true, val calls: List<CallLogEntry> = emptyList())

@HiltViewModel
class CallsViewModel @Inject constructor(private val log: CallLogRepository) : ViewModel() {
    val state: StateFlow<CallsUiState> = log.observe()
        .map { CallsUiState(loading = false, calls = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CallsUiState())

    fun clear() {
        viewModelScope.launch { log.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun CallsRoute(onCall: (UserId, Boolean) -> Unit, viewModel: CallsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallsScreen(state, onCall = onCall, onClear = viewModel::clear)
}

@Composable
fun CallsScreen(state: CallsUiState, onCall: (UserId, Boolean) -> Unit, onClear: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WhisprTopBar(
                title = stringResource(R.string.calls_title),
                large = true,
                actions = {
                    if (state.calls.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(WhisprIcons.Delete, contentDescription = stringResource(R.string.calls_clear))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.calls_clear)) },
                                    onClick = {
                                        menu = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.calls.isEmpty() -> EmptyState(
                title = stringResource(R.string.calls_empty_title),
                message = stringResource(R.string.calls_empty_body),
                icon = WhisprIcons.Call,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding()),
            ) {
                items(state.calls, key = { it.id }) { call ->
                    CallRow(call, onCall)
                    ListDivider()
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.calls_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.calls_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.status_keep)) }
            },
        )
    }
}

@Composable
private fun CallRow(call: CallLogEntry, onCall: (UserId, Boolean) -> Unit) {
    val missed = call.outcome == CallOutcome.Missed
    val name = call.peerName.ifEmpty { stringResource(R.string.calls_unknown) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.chatRowMinHeight)
            .clickable(role = Role.Button) { onCall(call.peer, call.video) }
            .padding(start = WhisprTheme.spacing.lg, end = WhisprTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
    ) {
        WhisprAvatar(name)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) WhisprTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
            ) {
                Icon(
                    when {
                        missed -> WhisprIcons.CallMissed
                        call.outgoing -> WhisprIcons.CallOutgoing
                        else -> WhisprIcons.CallIncoming
                    },
                    contentDescription = null,
                    tint = if (missed) WhisprTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(WhisprTheme.sizes.iconSmall),
                )
                Text(
                    describe(call) + " · " + formatTimestamp(call.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { onCall(call.peer, call.video) }) {
            Icon(
                if (call.video) WhisprIcons.Video else WhisprIcons.Call,
                contentDescription = stringResource(
                    if (call.video) R.string.calls_video_back else R.string.calls_voice_back,
                    name,
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun describe(call: CallLogEntry): String {
    val kind = stringResource(if (call.video) R.string.calls_kind_video else R.string.calls_kind_voice)
    return when (call.outcome) {
        CallOutcome.Completed -> {
            val seconds = call.duration?.seconds ?: 0
            // Rounded talk time, so it never reads like the clock time beside it.
            val length = if (seconds < SECONDS) {
                stringResource(R.string.calls_duration_seconds, seconds)
            } else {
                stringResource(R.string.calls_duration_minutes, (seconds + SECONDS / 2) / SECONDS)
            }
            stringResource(R.string.calls_completed, kind, length)
        }
        CallOutcome.Missed -> stringResource(R.string.calls_missed, kind)
        CallOutcome.Declined -> stringResource(R.string.calls_declined, kind)
        CallOutcome.Busy -> stringResource(R.string.calls_busy, kind)
        CallOutcome.NoAnswer -> stringResource(R.string.calls_no_answer, kind)
        CallOutcome.Cancelled -> stringResource(R.string.calls_cancelled, kind)
        CallOutcome.Failed -> stringResource(R.string.calls_failed, kind)
    }
}

private const val SECONDS = 60L
