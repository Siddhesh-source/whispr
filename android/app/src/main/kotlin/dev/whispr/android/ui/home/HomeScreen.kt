package dev.whispr.android.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.update.UpdateBanner
import dev.whispr.android.update.UpdateViewModel
import dev.whispr.core.designsystem.component.BottomBarTab
import dev.whispr.core.designsystem.component.WhisprBottomBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.domain.repository.MessagingRepository
import dev.whispr.domain.repository.StatusRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The three top-level places. */
enum class HomeTab { Chats, Status, Calls }

data class HomeBadges(val unread: Int = 0, val unseenStatus: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(messaging: MessagingRepository, statuses: StatusRepository) : ViewModel() {
    val badges: StateFlow<HomeBadges> = combine(messaging.observeConversations(), statuses.observeFeed()) {
            chats,
            feed,
        ->
        HomeBadges(unread = chats.sumOf { it.unreadCount }, unseenStatus = feed.recent.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeBadges())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Chats, Status and Calls behind one bottom bar. The selected tab survives
 * opening a chat and coming back. Each tab keeps its own top bar and action.
 */
@Composable
fun HomeRoute(
    chats: @Composable () -> Unit,
    status: @Composable () -> Unit,
    calls: @Composable () -> Unit,
    openCallsTab: Boolean = false,
    onCallsTabOpened: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    updates: UpdateViewModel = hiltViewModel(),
) {
    val badges by viewModel.badges.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    HomeScreen(
        badges,
        chats,
        status,
        calls,
        openCallsTab = openCallsTab,
        onCallsTabOpened = onCallsTabOpened,
        banner = { UpdateBanner(update, updates::update, updates::dismiss, updates::permissionIntent) },
    )
}

@Composable
fun HomeScreen(
    badges: HomeBadges,
    chats: @Composable () -> Unit,
    status: @Composable () -> Unit,
    calls: @Composable () -> Unit,
    initial: HomeTab = HomeTab.Chats,
    openCallsTab: Boolean = false,
    onCallsTabOpened: () -> Unit = {},
    banner: @Composable () -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(initial.ordinal) }
    LaunchedEffect(openCallsTab) {
        if (openCallsTab) {
            selected = HomeTab.Calls.ordinal
            onCallsTabOpened()
        }
    }
    val tabs = listOf(
        BottomBarTab(stringResource(R.string.home_tab_chats), WhisprIcons.Chat, badgeCount = badges.unread),
        BottomBarTab(stringResource(R.string.home_tab_status), WhisprIcons.Status, dot = badges.unseenStatus),
        BottomBarTab(stringResource(R.string.home_tab_calls), WhisprIcons.Call),
    )
    Column(Modifier.fillMaxSize()) {
        // The bar below owns the navigation-bar inset; the tab's own Scaffold must not add it again.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
            when (HomeTab.entries[selected]) {
                HomeTab.Chats -> chats()
                HomeTab.Status -> status()
                HomeTab.Calls -> calls()
            }
        }
        banner()
        WhisprBottomBar(tabs = tabs, selected = selected, onSelect = { selected = it })
    }
}
