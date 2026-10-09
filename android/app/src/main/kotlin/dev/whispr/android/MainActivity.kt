package dev.whispr.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.calls.CallOverlay
import dev.whispr.android.calls.CallRequests
import dev.whispr.android.navigation.WhisprNavHost
import dev.whispr.android.ui.contacts.ContactLinks
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.repository.SettingsRepository
import dev.whispr.domain.usecase.ObserveStartDestinationUseCase
import dev.whispr.domain.usecase.StartDestination
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var callRequests: CallRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        // Secure until the setting says otherwise, so nothing leaks before it loads.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val secure by viewModel.screenSecurity.collectAsStateWithLifecycle()
            LaunchedEffect(secure) {
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            WhisprTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val destination by viewModel.startDestination.collectAsStateWithLifecycle()
                    // Pick the start destination once; later changes (onboarding
                    // completing) are handled by navigation, not by rebuilding the graph.
                    var start by rememberSaveable { mutableStateOf<StartDestination?>(null) }
                    if (start == null) start = destination
                    when (val s = start) {
                        null -> LoadingState()
                        else -> CallOverlay { startCall, openCallsTab, onCallsTabOpened ->
                            WhisprNavHost(
                                s,
                                onCall = startCall,
                                openCallsTab = openCallsTab,
                                onCallsTabOpened = onCallsTabOpened,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Requests from call notifications, and shared contact links. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> ContactLinks.codeFrom(intent.dataString)?.let { ContactLinks.pending.value = it }
            ACTION_SHOW_CALL -> callRequests.show.value = true
            ACTION_ACCEPT_CALL -> {
                callRequests.show.value = true
                callRequests.accept.value = true
            }
            ACTION_SHOW_CALLS -> callRequests.openCallsTab.value = true
        }
    }

    companion object {
        const val ACTION_SHOW_CALL = "dev.whispr.android.SHOW_CALL"
        const val ACTION_ACCEPT_CALL = "dev.whispr.android.ACCEPT_CALL"
        const val ACTION_SHOW_CALLS = "dev.whispr.android.SHOW_CALLS"
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    observeStartDestination: ObserveStartDestinationUseCase,
    settings: SettingsRepository,
) : ViewModel() {
    val startDestination: StateFlow<StartDestination?> =
        observeStartDestination().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** FLAG_SECURE: no screenshots, screen recording or recents thumbnail. */
    val screenSecurity: StateFlow<Boolean> =
        settings.observePrivacy().map { it.screenSecurity }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
}
