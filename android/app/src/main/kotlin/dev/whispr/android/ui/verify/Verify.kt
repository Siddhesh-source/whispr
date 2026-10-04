package dev.whispr.android.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.navigation.VerifyDestination
import dev.whispr.android.ui.scan.QrScanner
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.QrCodeImage
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
import dev.whispr.domain.repository.ContactsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface VerifyContent {
    data object Loading : VerifyContent

    /** Key change not yet acknowledged, or no key: nothing to verify against. */
    data object Unavailable : VerifyContent
    data class Ready(val number: SafetyNumber, val verified: Boolean) : VerifyContent
}

data class VerifyUiState(
    val name: String = "",
    val content: VerifyContent = VerifyContent.Loading,
    val scanning: Boolean = false,
    val lastResult: VerifyResult? = null,
)

@HiltViewModel
class VerifyViewModel @Inject constructor(savedState: SavedStateHandle, private val contacts: ContactsRepository) :
    ViewModel() {
    private val peer = UserId(savedState.toRoute<VerifyDestination>().peerId)
    private val ui = MutableStateFlow(VerifyUiState())
    private val number = MutableStateFlow<SafetyNumber?>(null)
    private val loaded = MutableStateFlow(false)
    private var checking = false

    val state: StateFlow<VerifyUiState> = combine(ui, contacts.observeContact(peer), number, loaded) {
            u,
            c,
            n,
            isLoaded,
        ->
        u.copy(
            name = c?.displayName.orEmpty(),
            content = when {
                !isLoaded -> VerifyContent.Loading
                c == null || n == null || c.trust == TrustState.KeyChanged -> VerifyContent.Unavailable
                else -> VerifyContent.Ready(n, c.trust == TrustState.Verified)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), VerifyUiState())

    init {
        viewModelScope.launch {
            number.value = contacts.safetyNumber(peer)
            loaded.value = true
        }
    }

    fun startScan() = ui.update { it.copy(scanning = true, lastResult = null) }

    fun onScanned(text: String) {
        if (checking || !ui.value.scanning) return
        checking = true
        viewModelScope.launch {
            val result = contacts.verifyScanned(peer, text)
            // Stop scanning after any result; the user can scan again.
            ui.update { it.copy(scanning = false, lastResult = result) }
            checking = false
        }
    }

    fun setVerified(verified: Boolean) {
        viewModelScope.launch {
            contacts.setVerified(peer, verified)
            ui.update { it.copy(lastResult = null) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun VerifyRoute(onBack: () -> Unit, viewModel: VerifyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VerifyScreen(state, onBack, viewModel::startScan, viewModel::onScanned, viewModel::setVerified)
}

@Composable
fun VerifyScreen(
    state: VerifyUiState,
    onBack: () -> Unit,
    onStartScan: () -> Unit,
    onScanned: (String) -> Unit,
    onSetVerified: (Boolean) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.verify_title, state.name), onNavigateBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val c = state.content) {
                VerifyContent.Loading -> LoadingState(label = stringResource(R.string.verify_loading))
                VerifyContent.Unavailable -> ErrorState(
                    title = stringResource(R.string.chat_key_changed_title),
                    message = stringResource(R.string.verify_unavailable),
                    onRetry = onBack,
                    retryLabel = stringResource(R.string.settings_error_action),
                )
                is VerifyContent.Ready -> ReadyContent(state, c, onStartScan, onScanned, onSetVerified)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: VerifyUiState,
    c: VerifyContent.Ready,
    onStartScan: () -> Unit,
    onScanned: (String) -> Unit,
    onSetVerified: (Boolean) -> Unit,
) {
    val spacing = WhisprTheme.spacing
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        if (c.verified) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    WhisprIcons.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(WhisprTheme.sizes.icon),
                )
                Text(
                    stringResource(R.string.verify_verified),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            stringResource(R.string.verify_hint, state.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.scanning) {
            QrScanner(onCode = onScanned)
        } else {
            QrCodeImage(
                c.number.qrCode,
                contentDescription = stringResource(R.string.verify_code_description, state.name),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SafetyDigits(c.number.digits)
        state.lastResult?.let { r ->
            val (text, isError) = when (r) {
                VerifyResult.Match -> stringResource(R.string.verify_match, state.name) to false
                VerifyResult.Mismatch -> stringResource(R.string.verify_mismatch) to true
                VerifyResult.InvalidCode -> stringResource(R.string.verify_invalid) to true
            }
            Text(
                text,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!state.scanning) {
            WhisprPrimaryButton(
                text = stringResource(R.string.verify_scan_theirs),
                onClick = onStartScan,
            )
        }
        TextButton(onClick = { onSetVerified(!c.verified) }) {
            Text(stringResource(if (c.verified) R.string.verify_clear else R.string.verify_mark))
        }
    }
}

/** 60 digits as 12 groups of 5, three per row, read out as one string. */
@Composable
private fun SafetyDigits(digits: String) {
    val groups = digits.chunked(GROUP)
    Column(
        Modifier.widthIn(max = WhisprTheme.sizes.contentMaxWidth).clearAndSetSemantics {
            contentDescription =
                groups.joinToString(" ")
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
    ) {
        groups.chunked(PER_ROW).forEach { row ->
            Text(
                row.joinToString("   "),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val GROUP = 5
private const val PER_ROW = 3
