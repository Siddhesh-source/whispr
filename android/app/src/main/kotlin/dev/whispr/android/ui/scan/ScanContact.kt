package dev.whispr.android.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.ui.contacts.AddContactError
import dev.whispr.android.ui.contacts.addErrorText
import dev.whispr.android.ui.contacts.toUi
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.ContactsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanUiState(
    val adding: Boolean = false,
    val error: AddContactError? = null,
    val noCodeInImage: Boolean = false,
    val added: UserId? = null,
)

@HiltViewModel
class ScanContactViewModel @Inject constructor(private val contacts: ContactsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = mutable.asStateFlow()
    private var lastRejected: String? = null

    /** Called for every decoded frame; the text is untrusted and handled by the repository's parser. */
    fun onCode(text: String) {
        val s = mutable.value
        // The camera reports the same code many times a second; act on it once.
        if (s.adding || s.added != null || text == lastRejected) return
        if (text.isEmpty()) {
            mutable.update { it.copy(noCodeInImage = true, error = null) }
            return
        }
        mutable.update { it.copy(adding = true, error = null, noCodeInImage = false) }
        viewModelScope.launch {
            val (added, error) = contacts.addFromCode(text).toUi()
            if (error != null) lastRejected = text
            mutable.update { it.copy(adding = false, added = added, error = error) }
        }
    }
}

@Composable
fun ScanContactRoute(onBack: () -> Unit, onAdded: (UserId) -> Unit, viewModel: ScanContactViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { state.added?.let(onAdded) }
    ScanContactScreen(state, onBack, viewModel::onCode)
}

@Composable
fun ScanContactScreen(state: ScanUiState, onBack: () -> Unit, onCode: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.scan_title), onNavigateBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.adding) {
                LoadingState(label = stringResource(R.string.scan_adding))
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WhisprTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
                ) {
                    QrScanner(onCode = onCode)
                    val message = when {
                        state.error != null -> addErrorText(state.error)
                        state.noCodeInImage -> stringResource(R.string.scan_no_code_in_image)
                        else -> null
                    }
                    if (message != null) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}
