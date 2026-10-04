package dev.whispr.android.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes the locally stored avatar off the main thread; null while loading or if absent. */
@Composable
fun rememberAvatarBitmap(path: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, path) {
    value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
}
