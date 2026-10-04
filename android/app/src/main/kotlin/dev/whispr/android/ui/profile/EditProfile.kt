package dev.whispr.android.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.ui.rememberAvatarBitmap
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.ProfileResult
import dev.whispr.domain.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileMessage { Saved, InvalidName, InvalidNickname, Unavailable, Network }

data class EditProfileUiState(
    val profile: MyProfile? = null,
    val nameInput: String? = null,
    val nicknameInput: String = "",
    val busy: Boolean = false,
    val message: ProfileMessage? = null,
) {
    val name: String get() = nameInput ?: profile?.displayName.orEmpty()
}

@HiltViewModel
class EditProfileViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    private val form = MutableStateFlow(EditProfileUiState())

    val state: StateFlow<EditProfileUiState> = combine(form, profiles.observeProfile()) { f, p -> f.copy(profile = p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EditProfileUiState())

    fun onName(v: String) = form.update { it.copy(nameInput = v, message = null) }

    fun onNickname(v: String) = form.update { it.copy(nicknameInput = v, message = null) }

    fun saveName() = run(ProfileMessage.InvalidName) { profiles.setDisplayName(state.value.name) }

    fun claimUsername() = run(ProfileMessage.InvalidNickname) { profiles.claimUsername(form.value.nicknameInput) }

    fun clearUsername() = run(ProfileMessage.InvalidNickname) { profiles.clearUsername() }

    fun setAvatar(uri: String?) {
        if (uri != null) viewModelScope.launch { profiles.setAvatar(AvatarSource(uri)) }
    }

    private fun run(invalid: ProfileMessage, op: suspend () -> ProfileResult) {
        if (form.value.busy) return
        form.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (val r = op()) {
                ProfileResult.Ok -> ProfileMessage.Saved
                ProfileResult.InvalidInput -> invalid
                ProfileResult.Unavailable -> ProfileMessage.Unavailable
                is ProfileResult.Failed -> if (r.error ==
                    AuthError.Network
                ) {
                    ProfileMessage.Network
                } else {
                    ProfileMessage.Unavailable
                }
            }
            form.update {
                it.copy(
                    busy = false,
                    message = message,
                    nicknameInput = if (message ==
                        ProfileMessage.Saved
                    ) {
                        ""
                    } else {
                        it.nicknameInput
                    },
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun EditProfileRoute(onBack: () -> Unit, viewModel: EditProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            viewModel.setAvatar(it?.toString())
        }
    EditProfileScreen(
        state = state,
        onBack = onBack,
        onName = viewModel::onName,
        onSaveName = viewModel::saveName,
        onNickname = viewModel::onNickname,
        onClaim = viewModel::claimUsername,
        onClear = viewModel::clearUsername,
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

@Composable
fun EditProfileScreen(
    state: EditProfileUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onSaveName: () -> Unit,
    onNickname: (String) -> Unit,
    onClaim: () -> Unit,
    onClear: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val spacing = WhisprTheme.spacing
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.profile_title), onNavigateBack = onBack) },
    ) { padding ->
        val profile = state.profile
        if (profile == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { LoadingState() }
            return@Scaffold
        }
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = WhisprTheme.sizes.contentMaxWidth).padding(spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                val avatar by rememberAvatarBitmap(profile.avatarPath)
                val changePhoto = stringResource(R.string.profile_change_photo)
                WhisprAvatar(
                    name = profile.displayName,
                    image = avatar,
                    size = WhisprTheme.sizes.avatarXLarge,
                    contentDescription = changePhoto,
                    modifier = Modifier.clip(
                        CircleShape,
                    ).clickable(onClickLabel = changePhoto, role = Role.Button, onClick = onPickPhoto),
                )
                Text(
                    stringResource(R.string.profile_photo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                WhisprPrimaryButton(
                    text = stringResource(R.string.profile_save_name),
                    onClick = onSaveName,
                    enabled = state.name.isNotBlank() && state.name != profile.displayName,
                    loading = state.busy,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        stringResource(R.string.profile_username_heading),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.profile_username_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val username = profile.username
                    if (username != null) {
                        Text(
                            username,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onClear, enabled = !state.busy) {
                            Text(stringResource(R.string.profile_remove_username))
                        }
                    } else {
                        OutlinedTextField(
                            value = state.nicknameInput,
                            onValueChange = onNickname,
                            label = { Text(stringResource(R.string.profile_nickname_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = onClaim, enabled = state.nicknameInput.isNotBlank() && !state.busy) {
                            Text(stringResource(R.string.profile_claim_username))
                        }
                    }
                }
                state.message?.let { m ->
                    Text(
                        stringResource(
                            when (m) {
                                ProfileMessage.Saved -> R.string.profile_saved
                                ProfileMessage.InvalidName -> R.string.profile_error_invalid_name
                                ProfileMessage.InvalidNickname -> R.string.profile_error_invalid_nickname
                                ProfileMessage.Unavailable -> R.string.profile_error_unavailable
                                ProfileMessage.Network -> R.string.profile_error_network
                            },
                        ),
                        color = if (m ==
                            ProfileMessage.Saved
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}
