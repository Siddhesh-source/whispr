package dev.whispr.android.ui.contacts

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.ContactsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddContactError { Invalid, NotFound, Self, Network, Server }

data class AddContactUiState(
    val input: String = "",
    val myId: String? = null,
    val submitting: Boolean = false,
    val error: AddContactError? = null,
    val offline: Boolean = false,
    val added: UserId? = null,
) {
    val canSubmit: Boolean get() = input.isNotBlank() && !submitting
}

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val contacts: ContactsRepository,
    accounts: AccountRepository,
    connectivity: ConnectivityRepository,
) : ViewModel() {
    private val form = MutableStateFlow(AddContactUiState())

    val state: StateFlow<AddContactUiState> =
        combine(form, accounts.observeAccount(), connectivity.isOnline) { f, account, online ->
            f.copy(myId = account?.userId?.value, offline = !online)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddContactUiState())

    fun onInput(value: String) = form.update { it.copy(input = value, error = null) }

    fun submit() {
        val current = form.value
        if (!current.canSubmit) return
        form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = contacts.addById(current.input)
            form.update {
                when (result) {
                    is AddContactResult.Added -> it.copy(submitting = false, added = result.contact.userId)
                    AddContactResult.InvalidId -> it.copy(submitting = false, error = AddContactError.Invalid)
                    AddContactResult.NotFound -> it.copy(submitting = false, error = AddContactError.NotFound)
                    AddContactResult.IsSelf -> it.copy(submitting = false, error = AddContactError.Self)
                    is AddContactResult.Failed -> it.copy(
                        submitting = false,
                        error = if (result.error ==
                            AuthError.Network
                        ) {
                            AddContactError.Network
                        } else {
                            AddContactError.Server
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun AddContactRoute(onBack: () -> Unit, onAdded: (UserId) -> Unit, viewModel: AddContactViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { state.added?.let(onAdded) }
    AddContactScreen(state, onBack = onBack, onInput = viewModel::onInput, onSubmit = viewModel::submit)
}

@Composable
fun AddContactScreen(state: AddContactUiState, onBack: () -> Unit, onInput: (String) -> Unit, onSubmit: () -> Unit) {
    val spacing = WhisprTheme.spacing
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.add_contact_title), onNavigateBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OfflineBanner(visible = state.offline)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = WhisprTheme.sizes.contentMaxWidth).padding(spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    Text(
                        stringResource(R.string.add_contact_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInput,
                        label = { Text(stringResource(R.string.add_contact_id_label)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.error != null,
                        supportingText = state.error?.let { e ->
                            { Text(errorText(e), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WhisprPrimaryButton(
                        text = stringResource(R.string.add_contact_action),
                        onClick = onSubmit,
                        enabled = state.canSubmit,
                        loading = state.submitting,
                    )
                    state.myId?.let { myId ->
                        Column(
                            Modifier.padding(top = spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            Text(
                                stringResource(R.string.add_contact_my_id),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (copied) stringResource(R.string.add_contact_copied) else myId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    clipScope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("account ID", myId)))
                                    }
                                    copied = true
                                }) {
                                    Icon(
                                        WhisprIcons.Copy,
                                        contentDescription = stringResource(R.string.add_contact_copy),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun errorText(e: AddContactError) = stringResource(
    when (e) {
        AddContactError.Invalid -> R.string.add_contact_invalid
        AddContactError.NotFound -> R.string.add_contact_not_found
        AddContactError.Self -> R.string.add_contact_self
        AddContactError.Network -> R.string.add_contact_network
        AddContactError.Server -> R.string.add_contact_server
    },
)
