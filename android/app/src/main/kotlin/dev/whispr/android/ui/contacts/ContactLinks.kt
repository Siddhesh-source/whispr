package dev.whispr.android.ui.contacts

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.qrBitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sharing your contact code over the internet. The code only lets someone
 * send you a connection request (you still accept or decline), and it
 * carries no phone number. Verifying in person (safety numbers) is unchanged.
 */
object ContactLinks {
    const val SCHEME = "whispr"
    const val HOST = "add"
    private const val LINK_PREFIX = "$SCHEME://$HOST?c="
    private const val CODE_PREFIX = "whispr:"

    /** A code that arrived through a link and waits for the Add contact screen. */
    val pending = MutableStateFlow<String?>(null)

    fun link(code: String) = LINK_PREFIX + code.removePrefix(CODE_PREFIX)

    /** A pasted link or code as the scanner would read it; null if it isn't one. */
    fun codeFrom(text: String?): String? {
        val t = text?.trim().orEmpty()
        return when {
            t.startsWith(LINK_PREFIX) -> CODE_PREFIX + t.removePrefix(LINK_PREFIX).substringBefore('&')
            t.startsWith(CODE_PREFIX) -> t
            else -> null
        }
    }

    /** Opens the share sheet with the QR as an image and the link as text. */
    fun share(context: Context, code: String, name: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "whispr-code.png")
        FileOutputStream(file).use { qrImage(code).compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val text = context.getString(R.string.my_code_share_text, name, link(code))
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.my_code_share)))
    }

    /** Dark modules on white with a quiet zone, scaled up so it survives recompression. */
    private fun qrImage(code: String): Bitmap {
        val modules = qrBitmap(code, BLACK, WHITE)
        val side = (modules.width + QUIET * 2) * SCALE
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        out.eraseColor(WHITE)
        val scaled = Bitmap.createScaledBitmap(modules, modules.width * SCALE, modules.height * SCALE, false)
        android.graphics.Canvas(out).drawBitmap(scaled, (QUIET * SCALE).toFloat(), (QUIET * SCALE).toFloat(), null)
        return out
    }

    private const val PNG_QUALITY = 100
    private const val QUIET = 4
    private const val SCALE = 12
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
