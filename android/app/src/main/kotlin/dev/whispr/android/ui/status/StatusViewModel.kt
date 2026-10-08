package dev.whispr.android.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.StatusFeed
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.ProfileRepository
import dev.whispr.domain.repository.StatusRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What went wrong, shown in a dialog; nothing about statuses fails silently. */
enum class StatusError { TooLarge, Unreadable, NotPosted, CameraDenied, NoCamera }

data class StatusUiState(
    val loading: Boolean = true,
    val myName: String = "",
    val myId: UserId? = null,
    val feed: StatusFeed = StatusFeed(),
    val error: StatusError? = null,
    /** Set once a post was accepted; the composer closes on it. */
    val posted: Boolean = false,
)

@HiltViewModel
class StatusViewModel @Inject constructor(private val statuses: StatusRepository, profile: ProfileRepository) :
    ViewModel() {
    private val error = MutableStateFlow<StatusError?>(null)
    private val posted = MutableStateFlow(false)

    val state: StateFlow<StatusUiState> = combine(
        statuses.observeFeed(),
        profile.observeProfile(),
        error,
        posted,
    ) { feed, me, e, done ->
        StatusUiState(
            loading = false,
            myName = me?.displayName.orEmpty(),
            myId = me?.userId,
            feed = feed,
            error = e,
            posted = done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState())

    fun postText(text: String, background: Int) {
        viewModelScope.launch {
            if (statuses.postText(text, background)) posted.value = true else error.value = StatusError.NotPosted
        }
    }

    fun postImage(uri: String, caption: String, deleteAfter: Boolean) {
        viewModelScope.launch {
            val result = statuses.postImage(uri, caption)
            // A camera capture is our own plaintext temp file: gone once encrypted, or refused.
            if (deleteAfter && uri.startsWith("file:")) runCatching { java.io.File(java.net.URI(uri)).delete() }
            when (result) {
                SendResult.Ok -> posted.value = true
                SendResult.TooLarge -> error.value = StatusError.TooLarge
                SendResult.Unreadable -> error.value = StatusError.Unreadable
                SendResult.NotAllowed -> error.value = StatusError.NotPosted
            }
        }
    }

    fun delete(item: StatusItem) {
        viewModelScope.launch { statuses.delete(item.id) }
    }

    fun retry(item: StatusItem) {
        viewModelScope.launch { statuses.retry(item.id) }
    }

    fun markViewed(item: StatusItem) {
        if (item.viewed || item.mine) return
        viewModelScope.launch { statuses.markViewed(item.author, item.id) }
    }

    suspend fun imageBytes(item: StatusItem): ByteArray? = statuses.imageBytes(item.author, item.id)

    fun report(e: StatusError) {
        error.value = e
    }

    fun dismissError() {
        error.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
