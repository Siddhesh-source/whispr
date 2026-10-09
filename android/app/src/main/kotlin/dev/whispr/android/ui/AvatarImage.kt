package dev.whispr.android.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a picture we were sent (a contact's or group's), off the main thread; null if absent or unreadable. */
@Composable
fun rememberImageBytes(bytes: ByteArray?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, bytes?.contentHashCode()) {
        value = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            ?.asImageBitmap()
    }

/** Decodes the locally stored avatar off the main thread; null while loading or if absent. */
@Composable
fun rememberAvatarBitmap(path: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, path) {
    value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
}
