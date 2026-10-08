package dev.whispr.data.media

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.SendResult
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A file ready to encrypt: images are already re-encoded (metadata stripped). */
class PreparedMedia(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String?,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnail: ByteArray? = null,
)

sealed interface Prepared {
    class Ok(val media: PreparedMedia) : Prepared
    data object TooLarge : Prepared
    data object Unreadable : Prepared
}

/** Turns what the user picked into bytes to send. Platform-specific; faked in JVM tests. */
interface MediaPreparer {
    suspend fun prepare(source: MediaSource): Prepared

    /** A group picture: ≤ 256 px JPEG that fits the group state, or null if unreadable. */
    suspend fun groupAvatar(uri: String, maxBytes: Int): ByteArray?
}

class AndroidMediaPreparer(private val resolver: ContentResolver) : MediaPreparer {
    override suspend fun prepare(source: MediaSource): Prepared = try {
        val uri = Uri.parse(source.uri)
        when (source.kind) {
            AttachmentKind.Image -> image(uri)
            AttachmentKind.File, AttachmentKind.Voice -> {
                val bytes = readCapped(uri, SendResult.MAX_ATTACHMENT_BYTES) ?: return Prepared.TooLarge
                Prepared.Ok(
                    PreparedMedia(
                        bytes = bytes,
                        contentType = source.contentType ?: resolver.getType(uri) ?: DEFAULT_TYPE,
                        fileName = source.fileName ?: displayName(uri),
                        durationMs = source.durationMs,
                    ),
                )
            }
        }
    } catch (_: IOException) {
        Prepared.Unreadable
    } catch (_: IllegalStateException) {
        Prepared.Unreadable
    } catch (_: SecurityException) {
        Prepared.Unreadable
    }

    override suspend fun groupAvatar(uri: String, maxBytes: Int): ByteArray? = try {
        ImageCodec.jpegUnder(ImageCodec.decodeScaled(resolver, Uri.parse(uri), AVATAR_SIDE), AVATAR_SIDE, maxBytes)
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun image(uri: Uri): Prepared {
        if (AnimatedImages.isAnimated(head(uri))) return animated(uri)
        val bitmap = ImageCodec.decodeScaled(resolver, uri, IMAGE_SIDE)
        val bytes = ImageCodec.jpeg(bitmap, IMAGE_QUALITY)
        if (bytes.size > SendResult.MAX_ATTACHMENT_BYTES) return Prepared.TooLarge
        return Prepared.Ok(
            PreparedMedia(
                bytes = bytes,
                contentType = "image/jpeg",
                fileName = null, // a camera file name can reveal when and where; never sent for photos
                width = bitmap.width,
                height = bitmap.height,
                thumbnail = ImageCodec.jpegUnder(bitmap, THUMB_SIDE, THUMB_BYTES),
            ),
        )
    }

    /**
     * A GIF or animated WebP keeps its frames: sent byte for byte after its
     * metadata blocks are stripped ([AnimatedImages]). Malformed files are refused.
     */
    private fun animated(uri: Uri): Prepared {
        val bytes = readCapped(uri, AnimatedImages.MAX_BYTES.toLong()) ?: return Prepared.TooLarge
        val clean = AnimatedImages.clean(bytes) ?: return Prepared.Unreadable
        val first = BitmapFactory.decodeByteArray(clean.bytes, 0, clean.bytes.size) ?: return Prepared.Unreadable
        return Prepared.Ok(
            PreparedMedia(
                bytes = clean.bytes,
                contentType = clean.contentType,
                fileName = null,
                width = clean.width,
                height = clean.height,
                thumbnail = ImageCodec.jpegUnder(first, THUMB_SIDE, THUMB_BYTES),
            ),
        )
    }

    /** The first bytes of a file, enough to recognise its format. */
    private fun head(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open")
        return input.use {
            val buf = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = it.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
    }

    /** Reads at most [limit] bytes; returns null if the file is larger. */
    private fun readCapped(uri: Uri, limit: Long): ByteArray? {
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open")
        return input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        const val IMAGE_SIDE = 2048
        const val IMAGE_QUALITY = 85
        const val THUMB_SIDE = 256
        const val THUMB_BYTES = 16 * 1024
        const val AVATAR_SIDE = 256
        const val BUFFER = 64 * 1024
        const val HEAD_BYTES = 32
        const val DEFAULT_TYPE = "application/octet-stream"
    }
}
