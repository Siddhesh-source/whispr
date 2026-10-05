package dev.whispr.android.ui.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.Group
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.GroupsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a group change was refused, shown once. */
enum class GroupError { NotAllowed, Invalid, KeyChanged }

internal fun GroupResult.error(): GroupError? = when (this) {
    is GroupResult.Ok -> null
    GroupResult.NotAllowed -> GroupError.NotAllowed
    GroupResult.Invalid -> GroupError.Invalid
    GroupResult.KeyChanged -> GroupError.KeyChanged
}

/** Contacts that can be put in a group: accepted, with a usable pinned key. */
internal fun List<Contact>.pickable() = filter { !it.isRequest && it.trust != TrustState.KeyChanged }

data class NewGroupUiState(
    val name: String = "",
    val contacts: List<Contact> = emptyList(),
    val selected: Set<UserId> = emptySet(),
    val avatar: AvatarSource? = null,
    val creating: Boolean = false,
    val error: GroupError? = null,
) {
    val canCreate: Boolean get() = name.isNotBlank() &&
        name.trim().length <= MAX_NAME &&
        selected.isNotEmpty() &&
        !creating

    companion object {
        const val MAX_NAME = 64
    }
}

@HiltViewModel
class NewGroupViewModel @Inject constructor(contacts: ContactsRepository, private val groups: GroupsRepository) :
    ViewModel() {
    private val form = MutableStateFlow(NewGroupUiState())

    val state: StateFlow<NewGroupUiState> = combine(form, contacts.observeContacts()) { f, list ->
        f.copy(contacts = list.pickable())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NewGroupUiState())

    fun onName(name: String) {
        form.value = form.value.copy(name = name.take(NewGroupUiState.MAX_NAME))
    }

    fun toggle(user: UserId) {
        val s = form.value.selected
        form.value = form.value.copy(selected = if (user in s) s - user else s + user)
    }

    fun onAvatar(source: AvatarSource?) {
        form.value = form.value.copy(avatar = source)
    }

    fun create(onCreated: (GroupId) -> Unit) {
        val f = form.value
        if (!state.value.canCreate) return
        form.value = f.copy(creating = true, error = null)
        viewModelScope.launch {
            when (val r = groups.create(f.name.trim(), f.selected.toList(), f.avatar)) {
                is GroupResult.Ok -> onCreated(r.group)
                else -> form.value = form.value.copy(creating = false, error = r.error())
            }
        }
    }

    fun dismissError() {
        form.value = form.value.copy(error = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class GroupInfoUiState(
    val group: Group? = null,
    val loading: Boolean = true,
    /** Contacts not yet in the group (for add / invite). */
    val candidates: List<Contact> = emptyList(),
    val error: GroupError? = null,
)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contacts: ContactsRepository,
    private val groups: GroupsRepository,
) : ViewModel() {
    private val id = GroupId(checkNotNull(savedState.get<String>("groupId")))
    private val error = MutableStateFlow<GroupError?>(null)

    val state: StateFlow<GroupInfoUiState> = combine(
        groups.observeGroup(id),
        contacts.observeContacts(),
        error,
    ) { g, list, e ->
        val inGroup = g?.members?.map { it.userId }?.toSet().orEmpty()
        GroupInfoUiState(g, loading = false, candidates = list.pickable().filter { it.userId !in inGroup }, error = e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupInfoUiState())

    private fun run(op: suspend () -> GroupResult) {
        viewModelScope.launch { error.value = op().error() }
    }

    fun rename(name: String) = run { groups.rename(id, name) }
    fun setAvatar(source: AvatarSource?) = run { groups.setAvatar(id, source) }
    fun add(users: List<UserId>) = run { groups.addMembers(id, users) }
    fun invite(users: List<UserId>) = run { groups.invite(id, users) }
    fun remove(user: UserId) = run { groups.removeMember(id, user) }
    fun setAdmin(user: UserId, admin: Boolean) =
        run { groups.setRole(id, user, if (admin) GroupRole.Admin else GroupRole.Member) }

    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            val r = groups.leave(id)
            error.value = r.error()
            if (r is GroupResult.Ok) onLeft()
        }
    }

    fun dismissError() {
        error.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
