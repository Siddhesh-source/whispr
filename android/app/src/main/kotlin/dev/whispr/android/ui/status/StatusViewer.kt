package dev.whispr.android.ui.status

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.StatusKind
import dev.whispr.domain.model.StatusSendState
import dev.whispr.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays one person's statuses, oldest first: 5 s each, tap the right side
 * for the next, the left for the previous, hold to pause. Seeing an item
 * marks it viewed here only; nothing is sent to its author.
 */
@Composable
fun StatusViewerRoute(author: UserId, onClose: () -> Unit, viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) return
    val mine = author == state.myId
    val items = if (mine) {
        state.feed.mine
    } else {
        (state.feed.recent + state.feed.viewed).firstOrNull { it.author == author }?.items.orEmpty()
    }
    val name = if (mine) {
        stringResource(R.string.status_mine)
    } else {
        (state.feed.recent + state.feed.viewed).firstOrNull { it.author == author }?.name.orEmpty()
    }
    // Start at the first unseen item.
    var index by rememberSaveable(author) { mutableIntStateOf(items.indexOfFirst { !it.viewed }.coerceAtLeast(0)) }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val shown = index.coerceIn(0, items.lastIndex)
    var confirmDelete by remember { mutableStateOf<StatusItem?>(null) }
    StatusViewer(
        name = name,
        items = items,
        index = shown,
        onIndex = { next -> if (next in items.indices) index = next else onClose() },
        onClose = onClose,
        onSeen = viewModel::markViewed,
        loadImage = viewModel::imageBytes,
        onDelete = if (mine) ({ confirmDelete = it }) else null,
        onRetry = if (mine) viewModel::retry else null,
    )
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.status_delete_title)) },
            text = { Text(stringResource(R.string.status_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.delete(item)
                }) { Text(stringResource(R.string.status_delete), color = WhisprTheme.colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.status_keep)) }
            },
        )
    }
}

@Composable
fun StatusViewer(
    name: String,
    items: List<StatusItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onSeen: (StatusItem) -> Unit,
    loadImage: suspend (StatusItem) -> ByteArray?,
    onDelete: ((StatusItem) -> Unit)? = null,
    onRetry: ((StatusItem) -> Unit)? = null,
) {
    val item = items[index]
    val colors = WhisprTheme.colors
    val progress = remember(item.id) { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    // A photo starts its clock only once it is on screen.
    val image by produceState<ImageBitmap?>(null, item.id) {
        value = if (item.kind == StatusKind.Image) {
            loadImage(item)?.let { bytes ->
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        } else {
            null
        }
    }
    val ready = item.kind == StatusKind.Text || image != null
    LaunchedEffect(item.id, ready) { if (ready) onSeen(item) }
    LaunchedEffect(item.id, paused, ready) {
        if (!ready || paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * ITEM_MS).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        onIndex(index + 1)
    }
    val background = if (item.kind == StatusKind.Text) {
        colors.statusBackgrounds.getOrElse(item.background) { colors.statusBackgrounds.first() }
    } else {
        colors.callGround
    }
    val on = colors.onStatus
    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(index) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { offset -> onIndex(if (offset.x < size.width / 3) index - 1 else index + 1) },
                )
            },
    ) {
        when (item.kind) {
            StatusKind.Text -> Text(
                item.text,
                style = MaterialTheme.typography.headlineLarge,
                color = on,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(WhisprTheme.spacing.xl),
            )
            StatusKind.Image -> {
                val photo = image
                if (photo != null) {
                    Image(
                        photo,
                        contentDescription = item.text.ifEmpty { null },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(color = on, modifier = Modifier.align(Alignment.Center))
                }
                if (item.text.isNotEmpty()) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = on,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(colors.callGround.copy(alpha = CAPTION_SCRIM))
                            .navigationBarsPadding()
                            .padding(WhisprTheme.spacing.lg),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = WhisprTheme.spacing.sm, vertical = WhisprTheme.spacing.xs),
        ) {
            ProgressSegments(count = items.size, current = index, progress = progress.value)
            Row(
                Modifier.fillMaxWidth().padding(top = WhisprTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            ) {
                WhisprAvatar(name, size = WhisprTheme.sizes.avatarSmall)
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = on,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (item.sendState) {
                            StatusSendState.Failed -> stringResource(R.string.status_not_sent)
                            StatusSendState.Sending -> stringResource(R.string.status_sending)
                            StatusSendState.Sent -> relativeTime(item.createdAt)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = on,
                    )
                }
                if (onRetry != null && item.sendState == StatusSendState.Failed) {
                    TextButton(onClick = { onRetry(item) }) { Text(stringResource(R.string.status_retry), color = on) }
                }
                if (onDelete != null) {
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(WhisprIcons.Delete, contentDescription = stringResource(R.string.status_delete), tint = on)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(WhisprIcons.Close, contentDescription = stringResource(R.string.status_close), tint = on)
                }
            }
        }
    }
}

@Composable
private fun ProgressSegments(count: Int, current: Int, progress: Float) {
    val on = WhisprTheme.colors.onStatus
    val label = stringResource(R.string.status_position, current + 1, count)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
    ) {
        repeat(count) { i ->
            LinearProgressIndicator(
                progress = {
                    when {
                        i < current -> 1f
                        i == current -> progress
                        else -> 0f
                    }
                },
                color = on,
                trackColor = on.copy(alpha = TRACK_ALPHA),
                drawStopIndicator = {},
                gapSize = Dp.Hairline,
                modifier = Modifier
                    .weight(1f)
                    .height(WhisprTheme.spacing.xxs + WhisprTheme.spacing.xxs / 2),
            )
        }
    }
}

private const val ITEM_MS = 5_000
private const val TRACK_ALPHA = 0.35f
private const val CAPTION_SCRIM = 0.6f
