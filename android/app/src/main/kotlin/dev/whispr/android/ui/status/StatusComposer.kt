package dev.whispr.android.ui.status

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.android.ui.chat.CameraProblem
import dev.whispr.android.ui.chat.rememberCameraCapture
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.StatusRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writing a status: large text on a palette color (tap the palette to cycle),
 * or a photo from the camera or gallery with an optional caption.
 */
@Composable
fun StatusComposeRoute(start: StatusStart, onClose: () -> Unit, viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.posted) { if (state.posted) onClose() }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var started by rememberSaveable { mutableStateOf(false) }
    val takePhoto = rememberCameraCapture(
        onPhoto = { photo = it },
        onProblem = {
            viewModel.report(if (it == CameraProblem.Denied) StatusError.CameraDenied else StatusError.NoCamera)
        },
    )
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { photo = it.toString() }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(start) {
        if (!started) {
            started = true
            when (start) {
                StatusStart.Camera -> takePhoto()
                StatusStart.Gallery -> pickPhoto()
                StatusStart.Text -> Unit
            }
        }
    }
    val current = photo
    if (current != null) {
        PhotoComposer(
            uri = current,
            onCancel = {
                discardCapture(current)
                photo = null
            },
            onSend = { caption -> viewModel.postImage(current, caption, deleteAfter = true) },
        )
    } else {
        TextComposer(
            onClose = onClose,
            onCamera = takePhoto,
            onGallery = pickPhoto,
            onSend = viewModel::postText,
        )
    }
    state.error?.let { e ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.chat_error_ok)) }
            },
            text = { Text(stringResource(e.message())) },
        )
    }
}

@Composable
private fun TextComposer(
    onClose: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onSend: (String, Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var background by rememberSaveable { mutableIntStateOf(0) }
    val colors = WhisprTheme.colors
    val on = colors.onStatus
    val fieldLabel = stringResource(R.string.status_text_label)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.statusBackgrounds[background])
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(WhisprTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(WhisprIcons.Close, contentDescription = stringResource(R.string.status_close), tint = on)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { background = (background + 1) % StatusRules.BACKGROUNDS }) {
                Icon(WhisprIcons.Status, contentDescription = stringResource(R.string.status_change_color), tint = on)
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = WhisprTheme.spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            val style = MaterialTheme.typography.headlineLarge.copy(color = on, textAlign = TextAlign.Center)
            if (text.isEmpty()) {
                Text(stringResource(R.string.status_text_hint), style = style.copy(color = on.copy(alpha = HINT_ALPHA)))
            }
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= StatusRules.MAX_TEXT) text = it },
                textStyle = style,
                cursorBrush = SolidColor(on),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(WhisprTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
        ) {
            IconButton(onClick = onCamera) {
                Icon(WhisprIcons.Camera, contentDescription = stringResource(R.string.status_new_photo), tint = on)
            }
            IconButton(onClick = onGallery) {
                Icon(WhisprIcons.Image, contentDescription = stringResource(R.string.status_from_gallery), tint = on)
            }
            Spacer(Modifier.weight(1f))
            if (text.length > StatusRules.MAX_TEXT - COUNTER_FROM) {
                Text(
                    "${text.length}/${StatusRules.MAX_TEXT}",
                    style = MaterialTheme.typography.labelMedium,
                    color = on,
                )
            }
            SendButton(enabled = text.isNotBlank()) { onSend(text, background) }
        }
    }
}

@Composable
private fun PhotoComposer(uri: String, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri.toUri())?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply {
                            inSampleSize = PREVIEW_SAMPLE
                        },
                    )
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val colors = WhisprTheme.colors
    val on = colors.onCallGround
    val captionLabel = stringResource(R.string.status_caption_label)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.callGround)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(WhisprTheme.spacing.xs)) {
            IconButton(onClick = onCancel) {
                Icon(WhisprIcons.Close, contentDescription = stringResource(R.string.status_discard_photo), tint = on)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
        }
        Row(
            Modifier.fillMaxWidth().padding(WhisprTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(colors.callControl, MaterialTheme.shapes.large)
                    .padding(horizontal = WhisprTheme.spacing.md, vertical = WhisprTheme.spacing.md),
            ) {
                if (caption.isEmpty()) {
                    Text(
                        stringResource(R.string.status_caption_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = on.copy(alpha = HINT_ALPHA),
                    )
                }
                BasicTextField(
                    value = caption,
                    onValueChange = { if (it.length <= StatusRules.MAX_CAPTION) caption = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = on),
                    cursorBrush = SolidColor(on),
                    maxLines = CAPTION_LINES,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = captionLabel },
                )
            }
            SendButton(enabled = preview != null) { onSend(caption) }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = WhisprTheme.colors.onStatus,
            contentColor = WhisprTheme.colors.statusBackgrounds.first(),
            disabledContainerColor = WhisprTheme.colors.onStatus.copy(alpha = HINT_ALPHA),
            disabledContentColor = WhisprTheme.colors.statusBackgrounds.first(),
        ),
        modifier = Modifier.size(WhisprTheme.sizes.sendButton),
    ) {
        Icon(WhisprIcons.Send, contentDescription = stringResource(R.string.status_post))
    }
}

/** A camera capture we no longer need (the user discarded it). Picked photos aren't ours to delete. */
private fun discardCapture(uri: String) {
    if (uri.startsWith("file:")) runCatching { java.io.File(java.net.URI(uri)).delete() }
}

fun StatusError.message(): Int = when (this) {
    StatusError.TooLarge -> R.string.chat_error_too_large
    StatusError.Unreadable -> R.string.chat_error_unreadable
    StatusError.NotPosted -> R.string.status_error_not_posted
    StatusError.CameraDenied -> R.string.chat_error_camera_denied
    StatusError.NoCamera -> R.string.chat_error_no_camera
}

private const val HINT_ALPHA = 0.6f
private const val COUNTER_FROM = 100
private const val CAPTION_LINES = 4
private const val PREVIEW_SAMPLE = 2
