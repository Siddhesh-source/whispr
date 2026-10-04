package dev.whispr.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.QrCodeImage
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class MyCodeUiState(val code: String? = null, val name: String = "", val username: String? = null)

@HiltViewModel
class MyCodeViewModel @Inject constructor(contacts: ContactsRepository, profile: ProfileRepository) : ViewModel() {
    val state: StateFlow<MyCodeUiState> = combine(
        flow {
            emit(contacts.myContactCode())
        },
        profile.observeProfile(),
    ) { code, p ->
        MyCodeUiState(code, p?.displayName.orEmpty(), p?.username)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MyCodeUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun MyCodeRoute(onBack: () -> Unit, viewModel: MyCodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyCodeScreen(state, onBack)
}

@Composable
fun MyCodeScreen(state: MyCodeUiState, onBack: () -> Unit) {
    val spacing = WhisprTheme.spacing
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.my_code_title), onNavigateBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val code = state.code
            if (code == null) {
                LoadingState()
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    Text(
                        state.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    state.username?.let {
                        Text(
                            stringResource(R.string.my_code_username, it),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    QrCodeImage(
                        code,
                        contentDescription = stringResource(R.string.my_code_description),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.my_code_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
