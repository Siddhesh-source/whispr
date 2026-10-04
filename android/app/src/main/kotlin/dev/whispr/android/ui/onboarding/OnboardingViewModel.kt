package dev.whispr.android.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.usecase.CompleteOnboardingUseCase
import dev.whispr.domain.usecase.DisplayNameValidation
import dev.whispr.domain.usecase.OnboardingResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingError { NameEmpty, NameTooLong, NameInvalid, Network, Server, Storage, Rejected }

data class OnboardingUiState(
    val name: String = "",
    val avatarUri: String? = null,
    val submitting: Boolean = false,
    val error: OnboardingError? = null,
    val offline: Boolean = false,
    val completed: Boolean = false,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && !submitting
    val isNameError: Boolean get() = error in
        setOf(OnboardingError.NameEmpty, OnboardingError.NameTooLong, OnboardingError.NameInvalid)
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val completeOnboarding: CompleteOnboardingUseCase,
    connectivity: ConnectivityRepository,
) : ViewModel() {

    private val form = MutableStateFlow(OnboardingUiState())

    val state: StateFlow<OnboardingUiState> = combine(form, connectivity.isOnline) { f, online ->
        f.copy(offline = !online)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnboardingUiState())

    fun onNameChange(name: String) = form.update { it.copy(name = name, error = null) }

    fun onAvatarPicked(uri: String?) {
        if (uri != null) form.update { it.copy(avatarUri = uri) }
    }

    fun submit() {
        val current = form.value
        if (!current.canSubmit) return
        form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = completeOnboarding(current.name, current.avatarUri?.let(::AvatarSource))
            form.update {
                when (result) {
                    OnboardingResult.Success -> it.copy(submitting = false, completed = true)
                    is OnboardingResult.InvalidName -> it.copy(submitting = false, error = result.reason.toError())
                    is OnboardingResult.Failed -> it.copy(submitting = false, error = result.error.toError())
                }
            }
        }
    }

    private fun DisplayNameValidation.toError() = when (this) {
        DisplayNameValidation.Empty -> OnboardingError.NameEmpty
        DisplayNameValidation.TooLong -> OnboardingError.NameTooLong
        DisplayNameValidation.InvalidCharacters, is DisplayNameValidation.Valid -> OnboardingError.NameInvalid
    }

    private fun AuthError.toError() = when (this) {
        AuthError.Network -> OnboardingError.Network
        AuthError.Server -> OnboardingError.Server
        AuthError.Storage -> OnboardingError.Storage
        AuthError.Rejected -> OnboardingError.Rejected
        AuthError.InvalidInput -> OnboardingError.NameInvalid
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
