package dev.whispr.android.ui.chat

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import dev.whispr.android.R
import dev.whispr.core.designsystem.theme.WhisprTheme
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays a decrypted GIF or animated WebP from memory (the plaintext never
 * touches disk). Animates on API 28+ while the system's animations are on;
 * otherwise, or if the file can't be decoded, [fallback] (the first frame)
 * is shown.
 */
@Composable
fun AnimatedImage(bytes: ByteArray, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val animationsOn =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    val drawable by produceState<Drawable?>(null, bytes, animationsOn) {
        value = if (animationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeAnimated(bytes) else null
    }
    val d = drawable
    if (d == null) {
        fallback()
        return
    }
    Box(modifier) {
        AndroidView(
            factory = { ImageView(it).apply { adjustViewBounds = true } },
            update = { view ->
                view.setImageDrawable(d)
                (d as? Animatable)?.start()
            },
            onRelease = { view ->
                (d as? Animatable)?.stop()
                view.setImageDrawable(null)
            },
        )
        GifLabel(Modifier.align(Alignment.BottomStart))
    }
}

/** The small "GIF" tag on an animated image, so a still frame isn't mistaken for a photo. */
@Composable
fun GifLabel(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = WhisprTheme.colors.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(WhisprTheme.spacing.xs)
            .clearAndSetSemantics { },
    ) {
        Text(
            stringResource(R.string.chat_gif_label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = WhisprTheme.spacing.xs),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private suspend fun decodeAnimated(bytes: ByteArray): Drawable? = withContext(Dispatchers.Default) {
    try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
