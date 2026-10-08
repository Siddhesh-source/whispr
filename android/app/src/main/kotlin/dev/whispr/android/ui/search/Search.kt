package dev.whispr.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.android.R
import dev.whispr.android.ui.formatTimestamp
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.WhisprFields
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.SearchHit
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.MessagingRepository
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SearchUiState(val query: String = "", val hits: List<SearchHit>? = null)

/**
 * Searches messages on this device only. The database is decrypted locally;
 * neither the query nor the results ever leave the phone.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val messaging: MessagingRepository) : ViewModel() {
    private val query = MutableStateFlow("")

    private val hits = query
        .debounce(DEBOUNCE_MS)
        .mapLatest { q -> if (q.isBlank()) null else messaging.search(q.trim()) }

    val state: StateFlow<SearchUiState> = combine(query, hits) { q, h -> SearchUiState(q, h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

    fun onQuery(text: String) {
        query.value = text
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenChat: (UserId) -> Unit,
    onOpenGroup: (GroupId) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(state, onBack, viewModel::onQuery) { hit ->
        hit.group?.let(onOpenGroup) ?: hit.peer?.let(onOpenChat)
    }
}

@Composable
fun SearchScreen(state: SearchUiState, onBack: () -> Unit, onQuery: (String) -> Unit, onOpen: (SearchHit) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.search_title), onNavigateBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(
                shape = WhisprFields.shape,
                colors = WhisprFields.colors(),
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                leadingIcon = { Icon(WhisprIcons.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.sm)
                    .focusRequester(focus),
            )
            val hits = state.hits
            Box(Modifier.weight(1f)) {
                when {
                    hits == null -> EmptyState(
                        title = stringResource(R.string.search_title),
                        message = stringResource(R.string.search_local_only),
                    )
                    hits.isEmpty() -> EmptyState(
                        title = stringResource(R.string.search_none_title),
                        message = stringResource(R.string.search_none_message, state.query.trim()),
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.Top) {
                        items(hits, key = { it.conversation.value + it.message.id }) { hit ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        hit.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        if (hit.message.outgoing) {
                                            stringResource(R.string.chats_you_prefix, hit.message.text)
                                        } else {
                                            hit.message.text
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = { Text(formatTimestamp(hit.message.timestamp)) },
                                modifier = Modifier.clickable { onOpen(hit) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
