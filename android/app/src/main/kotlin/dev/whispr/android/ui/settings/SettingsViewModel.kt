package dev.whispr.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.BuildConfig
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.EncryptionRepository
import dev.whispr.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        val readReceipts: Boolean = false,
        val typingIndicators: Boolean = false,
        val keysRegistered: Boolean = true,
    ) : SettingsUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    accounts: AccountRepository,
    auth: AuthRepository,
    connectivity: ConnectivityRepository,
    private val settings: SettingsRepository,
    encryption: EncryptionRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        accounts.observeAccount(),
        auth.session,
        connectivity.isOnline,
        settings.observePrivacy(),
        encryption.observeKeysRegistered(),
    ) { account, session, online, privacy, keysRegistered ->
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
            readReceipts = privacy.readReceipts,
            typingIndicators = privacy.typingIndicators,
            keysRegistered = keysRegistered,
        )
    }
        .catch { emit(SettingsUiState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState.Loading)

    fun setReadReceipts(enabled: Boolean) {
        viewModelScope.launch { settings.setReadReceipts(enabled) }
    }

    fun setTypingIndicators(enabled: Boolean) {
        viewModelScope.launch { settings.setTypingIndicators(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
