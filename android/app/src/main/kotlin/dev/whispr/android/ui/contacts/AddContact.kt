package dev.whispr.android.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprFields
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.ContactsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddContactError { InvalidUsername, NotFound, Self, InvalidCode, OtherServer, KeyMismatch, Network, Server }

data class AddContactUiState(
    val username: String = "",
    val submitting: Boolean = false,
    val error: AddContactError? = null,
    val offline: Boolean = false,
    val added: UserId? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && !submitting
}

/** Maps a repository result to UI: the added contact, or an error to show. */
fun AddContactResult.toUi(): Pair<UserId?, AddContactError?> = when (this) {
    is AddContactResult.Added -> contact.userId to null
    AddContactResult.InvalidId -> null to AddContactError.InvalidUsername
    AddContactResult.NotFound -> null to AddContactError.NotFound
    AddContactResult.IsSelf -> null to AddContactError.Self
    AddContactResult.InvalidCode -> null to AddContactError.InvalidCode
    AddContactResult.DifferentServer -> null to AddContactError.OtherServer
    AddContactResult.KeyMismatch -> null to AddContactError.KeyMismatch
    is AddContactResult.Failed ->
        null to
            if (error == AuthError.Network) AddContactError.Network else AddContactError.Server
}

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val contacts: ContactsRepository,
    connectivity: ConnectivityRepository,
) : ViewModel() {
    private val form = MutableStateFlow(AddContactUiState())

    init {
        // Opened from a shared link: send the request right away.
        ContactLinks.pending.getAndUpdate { null }?.let(::addFromCode)
    }

    /** A pasted link or code. */
    fun addFromCode(text: String?) {
        val code = ContactLinks.codeFrom(text)
        if (code == null) {
            form.update { it.copy(error = AddContactError.InvalidCode) }
            return
        }
        form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val (added, error) = contacts.addFromCode(code).toUi()
            form.update { it.copy(submitting = false, added = added, error = error) }
        }
    }

    val state: StateFlow<AddContactUiState> = combine(form, connectivity.isOnline) { f, online ->
        f.copy(offline = !online)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddContactUiState())

    fun onUsername(value: String) = form.update { it.copy(username = value, error = null) }

    fun submit() {
        val current = form.value
        if (!current.canSubmit) return
        form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val (added, error) = contacts.addByUsername(current.username).toUi()
            form.update { it.copy(submitting = false, added = added, error = error) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun AddContactRoute(
    onBack: () -> Unit,
    onAdded: (UserId) -> Unit,
    onScan: () -> Unit,
    onMyCode: () -> Unit,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { state.added?.let(onAdded) }
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    AddContactScreen(state, onBack, viewModel::onUsername, viewModel::submit, onScan, onMyCode) {
        viewModel.addFromCode(clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
    }
}

@Composable
fun AddContactScreen(
    state: AddContactUiState,
    onBack: () -> Unit,
    onUsername: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
    onMyCode: () -> Unit,
    onPaste: () -> Unit = {},
) {
    val spacing = WhisprTheme.spacing
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
                        stringResource(R.string.new_chat_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The one primary action: scanning gives an out-of-band key.
                    WhisprPrimaryButton(text = stringResource(R.string.new_chat_scan), onClick = onScan)
                    TextButton(onClick = onMyCode, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(WhisprIcons.QrCode, contentDescription = null)
                        Text(stringResource(R.string.new_chat_my_code), modifier = Modifier.padding(start = spacing.sm))
                    }
                    OutlinedButton(
                        onClick = onPaste,
                        enabled = !state.submitting,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text(stringResource(R.string.new_chat_paste_code)) }
                    Text(
                        stringResource(R.string.new_chat_or),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedTextField(
                            shape = WhisprFields.shape,
                            colors = WhisprFields.colors(),
                            value = state.username,
                            onValueChange = onUsername,
                            label = { Text(stringResource(R.string.new_chat_username_label)) },
                            singleLine = true,
                            enabled = !state.submitting,
                            isError = state.error != null,
                            supportingText = state.error?.let { e ->
                                {
                                    Text(
                                        addErrorText(e),
                                        modifier = Modifier.semantics {
                                            liveRegion =
                                                LiveRegionMode.Polite
                                        },
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onSubmit,
                            enabled = state.canSubmit,
                            modifier = Modifier.padding(top = spacing.sm),
                        ) { Text(stringResource(R.string.new_chat_username_action)) }
                    }
                }
            }
        }
    }
}

@Composable
fun addErrorText(e: AddContactError) = stringResource(
    when (e) {
        AddContactError.InvalidUsername -> R.string.add_error_invalid_username
        AddContactError.NotFound -> R.string.add_error_not_found_username
        AddContactError.Self -> R.string.add_contact_self
        AddContactError.InvalidCode -> R.string.add_error_invalid_code
        AddContactError.OtherServer -> R.string.add_error_other_server
        AddContactError.KeyMismatch -> R.string.add_error_key_mismatch
        AddContactError.Network -> R.string.add_contact_network
        AddContactError.Server -> R.string.add_contact_server
    },
)
