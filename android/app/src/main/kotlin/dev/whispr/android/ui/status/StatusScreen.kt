package dev.whispr.android.ui.status

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.rememberAvatarBitmap
import dev.whispr.android.ui.rememberImageBytes
import dev.whispr.core.designsystem.component.ListDivider
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.StatusAvatar
import dev.whispr.core.designsystem.component.StatusRing
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.StatusAuthor
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.StatusSendState
import dev.whispr.domain.model.UserId
import java.time.Instant

/** How a new status starts: typing, the camera, or a photo from the gallery. */
enum class StatusStart { Text, Camera, Gallery }

@Composable
fun StatusRoute(
    onCompose: (StatusStart) -> Unit,
    onOpen: (UserId) -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusScreen(state, onCompose = onCompose, onOpen = onOpen, onRetry = viewModel::retry)
}

@Composable
fun StatusScreen(
    state: StatusUiState,
    onCompose: (StatusStart) -> Unit,
    onOpen: (UserId) -> Unit,
    onRetry: (StatusItem) -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WhisprTopBar(title = stringResource(R.string.status_title), large = true) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCompose(StatusStart.Text) },
                shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(WhisprIcons.TextFields, contentDescription = null) },
                text = { Text(stringResource(R.string.status_new)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val feed = state.feed
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + WhisprTheme.sizes.minTouchTarget * 2,
            ),
        ) {
            item(key = "mine") {
                MyStatusRow(state, onCompose = onCompose, onOpen = onOpen, onRetry = onRetry)
            }
            if (feed.recent.isEmpty() && feed.viewed.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.status_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = WhisprTheme.spacing.lg,
                            vertical = WhisprTheme.spacing.xl,
                        ),
                    )
                }
            }
            if (feed.recent.isNotEmpty()) {
                item(key = "recent-label") { SectionLabel(stringResource(R.string.status_recent)) }
                items(feed.recent, key = { "r" + it.author.value }) { a ->
                    AuthorRow(a, StatusRing.Unseen) { onOpen(a.author) }
                    ListDivider()
                }
            }
            if (feed.viewed.isNotEmpty()) {
                item(key = "viewed-label") { SectionLabel(stringResource(R.string.status_viewed)) }
                items(feed.viewed, key = { "v" + it.author.value }) { a ->
                    AuthorRow(a, StatusRing.Seen) { onOpen(a.author) }
                    ListDivider()
                }
            }
        }
    }
}

@Composable
private fun MyStatusRow(
    state: StatusUiState,
    onCompose: (StatusStart) -> Unit,
    onOpen: (UserId) -> Unit,
    onRetry: (StatusItem) -> Unit,
) {
    val mine = state.feed.mine
    val failed = mine.lastOrNull { it.sendState == StatusSendState.Failed }
    val sending = mine.any { it.sendState == StatusSendState.Sending }
    val subtitle = when {
        failed != null -> stringResource(R.string.status_not_sent)
        sending -> stringResource(R.string.status_sending)
        mine.isEmpty() -> stringResource(R.string.status_tap_to_add)
        else -> pluralStringResource(R.plurals.status_updates, mine.size, mine.size) + " · " +
            relativeTime(mine.maxOf { it.createdAt })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.chatRowMinHeight)
            .clickable(role = Role.Button) {
                when {
                    failed != null -> onRetry(failed)
                    mine.isEmpty() -> onCompose(StatusStart.Text)
                    else -> state.myId?.let(onOpen)
                }
            }
            .padding(start = WhisprTheme.spacing.lg - WhisprTheme.spacing.xs, end = WhisprTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md - WhisprTheme.spacing.xs),
    ) {
        val picture by rememberAvatarBitmap(state.myAvatar)
        StatusAvatar(
            name = state.myName,
            ring = if (mine.isEmpty()) null else StatusRing.Seen,
            image = picture,
            add = mine.isEmpty(),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.status_mine),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed != null) WhisprTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onCompose(StatusStart.Camera) }) {
            Icon(
                WhisprIcons.Camera,
                contentDescription = stringResource(R.string.status_new_photo),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onCompose(StatusStart.Text) }) {
            Icon(
                WhisprIcons.TextFields,
                contentDescription = stringResource(R.string.status_new_text),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthorRow(author: StatusAuthor, ring: StatusRing, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.chatRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = WhisprTheme.spacing.lg - WhisprTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md - WhisprTheme.spacing.xs),
    ) {
        val picture by rememberImageBytes(author.avatar)
        StatusAvatar(name = author.name, ring = ring, image = picture)
        Column(Modifier.weight(1f)) {
            Text(
                author.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                relativeTime(author.latest),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(
                start = WhisprTheme.spacing.lg,
                end = WhisprTheme.spacing.lg,
                top = WhisprTheme.spacing.lg,
                bottom = WhisprTheme.spacing.xs,
            )
            .semantics { heading() },
    )
}

/** "Just now", "12 minutes ago", "2 hours ago", localized. */
@Composable
fun relativeTime(at: Instant): String {
    val now = System.currentTimeMillis()
    val then = at.toEpochMilli()
    return if (now - then < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.status_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
