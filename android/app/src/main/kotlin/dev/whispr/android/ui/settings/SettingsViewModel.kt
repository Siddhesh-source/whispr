package dev.whispr.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.BuildConfig
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class ConnectionStatus { Active, Connecting, Offline, Unavailable }

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data object Error : SettingsUiState
    data class Content(
        val displayName: String,
        val avatarPath: String?,
        val userId: String,
        val connection: ConnectionStatus,
        val version: String,
    ) : SettingsUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    accounts: AccountRepository,
    auth: AuthRepository,
    connectivity: ConnectivityRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(accounts.observeAccount(), auth.session, connectivity.isOnline) {
            account,
            session,
            online,
        ->
        val userId = account?.userId ?: return@combine SettingsUiState.Error
        SettingsUiState.Content(
            displayName = account.displayName,
            avatarPath = account.avatarPath,
            userId = userId.value,
            connection = when {
                !online -> ConnectionStatus.Offline
                session is SessionState.Active -> ConnectionStatus.Active
                session is SessionState.Unavailable -> ConnectionStatus.Unavailable
                else -> ConnectionStatus.Connecting
            },
            version = BuildConfig.VERSION_NAME,
        )
    }
        .catch { emit(SettingsUiState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
