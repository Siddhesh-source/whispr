package dev.whispr.android.ui.groups

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupMember
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.UserId

@Composable
fun NewGroupRoute(onBack: () -> Unit, onCreated: (GroupId) -> Unit, viewModel: NewGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NewGroupScreen(
        state = state,
        onBack = onBack,
        onName = viewModel::onName,
        onToggle = viewModel::toggle,
        onAvatar = viewModel::onAvatar,
        onCreate = { viewModel.create(onCreated) },
        onDismissError = viewModel::dismissError,
    )
}

@Composable
fun NewGroupScreen(
    state: NewGroupUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onToggle: (UserId) -> Unit,
    onAvatar: (AvatarSource?) -> Unit = {},
    onCreate: () -> Unit,
    onDismissError: () -> Unit = {},
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAvatar(AvatarSource(it.toString())) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.group_new_title), onNavigateBack = onBack) },
        bottomBar = {
            WhisprPrimaryButton(
                text = stringResource(R.string.group_create),
                onClick = onCreate,
                enabled = state.canCreate,
                loading = state.creating,
                modifier = Modifier.padding(WhisprTheme.spacing.lg).navigationBarsPadding(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(WhisprTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
            ) {
                IconButton(onClick = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(
                        WhisprIcons.Image,
                        contentDescription = stringResource(
                            if (state.avatar == null) R.string.group_picture_add else R.string.group_picture_change,
                        ),
                    )
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.group_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                pluralStringResource(R.plurals.group_pick_members, state.selected.size, state.selected.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = WhisprTheme.spacing.lg).semantics { heading() },
            )
            if (state.contacts.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.group_no_contacts_title),
                    message = stringResource(R.string.group_no_contacts_message),
                )
            } else {
                ContactPicker(state.contacts, state.selected, onToggle, Modifier.weight(1f))
            }
        }
    }
    state.error?.let { ErrorDialog(it, onDismissError) }
}

@Composable
private fun ContactPicker(
    contacts: List<Contact>,
    selected: Set<UserId>,
    onToggle: (UserId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(contacts, key = { it.userId.value }) { c ->
            val checked = c.userId in selected
            val stateText = stringResource(if (checked) R.string.group_selected else R.string.group_not_selected)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = WhisprTheme.sizes.minTouchTarget)
                    .clickable { onToggle(c.userId) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Checkbox
                        stateDescription = stateText
                    }
                    .padding(horizontal = WhisprTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
            ) {
                WhisprAvatar(c.displayName, size = WhisprTheme.sizes.avatarSmall)
                Text(c.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
    }
}

@Composable
fun GroupInfoRoute(onBack: () -> Unit, onLeft: () -> Unit, viewModel: GroupInfoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GroupInfoScreen(
        state = state,
        onBack = onBack,
        onRename = viewModel::rename,
        onAvatar = viewModel::setAvatar,
        onAdd = viewModel::add,
        onInvite = viewModel::invite,
        onRemove = viewModel::remove,
        onSetAdmin = viewModel::setAdmin,
        onLeave = { viewModel.leave(onLeft) },
        onDismissError = viewModel::dismissError,
    )
}

@Composable
fun GroupInfoScreen(
    state: GroupInfoUiState,
    onBack: () -> Unit,
    onRename: (String) -> Unit = {},
    onAvatar: (AvatarSource?) -> Unit = {},
    onAdd: (List<UserId>) -> Unit = {},
    onInvite: (List<UserId>) -> Unit = {},
    onRemove: (UserId) -> Unit = {},
    onSetAdmin: (UserId, Boolean) -> Unit = { _, _ -> },
    onLeave: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    val group = state.group
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = invite, false = add
    var confirmLeave by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAvatar(AvatarSource(it.toString())) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.group_info_title), onNavigateBack = onBack) },
    ) { padding ->
        when {
            state.loading -> LoadingState(
                label = stringResource(R.string.chat_loading),
                modifier = Modifier.padding(padding),
            )
            group == null -> EmptyState(
                title = stringResource(R.string.chat_missing_title),
                message = stringResource(R.string.chat_missing_message),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item(key = "header") {
                    val picture = remember(group.avatar) {
                        group.avatar?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                    GroupHeader(group.name, picture, group.isAdmin, onRename = { renaming = true }) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                item(key = "members-header") {
                    Text(
                        group.members.count {
                            !it.invited
                        }.let { pluralStringResource(R.plurals.group_members, it, it) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(WhisprTheme.spacing.lg).semantics { heading() },
                    )
                }
                if (group.isAdmin) {
                    item(key = "add") {
                        Row(Modifier.padding(horizontal = WhisprTheme.spacing.md)) {
                            TextButton(onClick = { picking = false }, enabled = state.candidates.isNotEmpty()) {
                                Text(stringResource(R.string.group_add_members))
                            }
                            TextButton(onClick = { picking = true }, enabled = state.candidates.isNotEmpty()) {
                                Text(stringResource(R.string.group_invite_members))
                            }
                        }
                    }
                }
                items(group.members, key = { it.userId.value }) { m ->
                    MemberRow(m, canManage = group.isAdmin && !m.isMe, onRemove, onSetAdmin)
                }
                if (group.status == GroupStatus.Active) {
                    item(key = "leave") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = {
                            confirmLeave = true
                        }, modifier = Modifier.padding(WhisprTheme.spacing.md)) {
                            Icon(WhisprIcons.Leave, contentDescription = null)
                            Text(
                                stringResource(R.string.group_leave),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = WhisprTheme.spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
    if (renaming && group != null) {
        RenameDialog(group.name, onDismiss = { renaming = false }) {
            renaming = false
            onRename(it)
        }
    }
    picking?.let { invite ->
        PickDialog(state.candidates, invite, onDismiss = { picking = null }) { users ->
            picking = null
            if (invite) onInvite(users) else onAdd(users)
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.group_leave_title)) },
            text = { Text(stringResource(R.string.group_leave_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text(stringResource(R.string.group_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.chat_reaction_cancel)) }
            },
        )
    }
    state.error?.let { ErrorDialog(it, onDismissError) }
}

@Composable
private fun GroupHeader(
    name: String,
    picture: ImageBitmap?,
    isAdmin: Boolean,
    onRename: () -> Unit,
    onPicture: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(WhisprTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
    ) {
        WhisprAvatar(name, image = picture, size = WhisprTheme.sizes.avatarXLarge)
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.group_encrypted_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isAdmin) {
            Row {
                TextButton(onClick = onRename) { Text(stringResource(R.string.group_rename)) }
                TextButton(onClick = onPicture) { Text(stringResource(R.string.group_picture_change)) }
            }
        }
    }
}

@Composable
private fun MemberRow(
    m: GroupMember,
    canManage: Boolean,
    onRemove: (UserId) -> Unit,
    onSetAdmin: (UserId, Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val role = when {
        m.invited -> stringResource(R.string.group_role_invited)
        m.role == GroupRole.Admin -> stringResource(R.string.group_role_admin)
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.minTouchTarget)
            .padding(horizontal = WhisprTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
    ) {
        WhisprAvatar(m.displayName, size = WhisprTheme.sizes.avatarSmall)
        Column(Modifier.weight(1f)) {
            Text(
                if (m.isMe) stringResource(R.string.group_you) else m.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (role.isNotEmpty()) Text(role, style = MaterialTheme.typography.labelSmall)
        }
        if (canManage) {
            IconButton(onClick = { menu = true }) {
                Icon(WhisprIcons.Edit, contentDescription = stringResource(R.string.group_manage_member, m.displayName))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!m.invited) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (m.role ==
                                        GroupRole.Admin
                                    ) {
                                        R.string.group_dismiss_admin
                                    } else {
                                        R.string.group_make_admin
                                    },
                                ),
                            )
                        },
                        onClick = {
                            menu = false
                            onSetAdmin(m.userId, m.role != GroupRole.Admin)
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.group_remove_member), color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menu = false
                        onRemove(m.userId)
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(NewGroupUiState.MAX_NAME) },
                label = { Text(stringResource(R.string.group_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.group_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_reaction_cancel)) } },
    )
}

@Composable
private fun PickDialog(
    candidates: List<Contact>,
    invite: Boolean,
    onDismiss: () -> Unit,
    onDone: (List<UserId>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<UserId>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (invite) R.string.group_invite_members else R.string.group_add_members)) },
        text = {
            ContactPicker(candidates, selected, { u -> selected = if (u in selected) selected - u else selected + u })
        },
        confirmButton = {
            TextButton(onClick = { onDone(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(if (invite) R.string.group_invite else R.string.group_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_reaction_cancel)) } },
    )
}

@Composable
private fun ErrorDialog(error: GroupError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_error_ok)) } },
        text = {
            Text(
                stringResource(
                    when (error) {
                        GroupError.NotAllowed -> R.string.group_error_not_allowed
                        GroupError.Invalid -> R.string.group_error_invalid
                        GroupError.KeyChanged -> R.string.group_error_key_changed
                    },
                ),
            )
        },
    )
}
