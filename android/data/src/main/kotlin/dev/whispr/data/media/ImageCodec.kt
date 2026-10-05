package dev.whispr.data.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Decodes a picked image, applying its EXIF orientation and scaling it down,
 * and re-encodes it as JPEG. Re-encoding writes pixels only, so EXIF data
 * (GPS location, camera, timestamps) and any other embedded metadata are gone.
 */
object ImageCodec {
    fun decodeScaled(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = maxSide.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(resolver, uri, maxSide)
        }

    private fun decodeLegacy(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("cannot decode image")
        val rotation = resolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).rotationDegrees }
        val rotated = if (rotation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply {
                    postRotate(rotation.toFloat())
                },
                true,
            )
        }
        return scaleDown(rotated, maxSide)
    }

    /** Scales [bitmap] so its longer side is at most [maxSide] (never up). */
    fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "encode failed" }
        it.toByteArray()
    }

    /** The best-quality JPEG of [bitmap] (scaled to [maxSide]) that fits in [maxBytes], or null. */
    fun jpegUnder(bitmap: Bitmap, maxSide: Int, maxBytes: Int): ByteArray? {
        var side = maxSide
        while (side >= MIN_SIDE) {
            val scaled = scaleDown(bitmap, side)
            for (quality in QUALITIES) {
                val bytes = jpeg(scaled, quality)
                if (bytes.size <= maxBytes) return bytes
            }
            side /= 2
        }
        return null
    }

    private const val MIN_SIDE = 32
    private val QUALITIES = intArrayOf(80, 65, 50, 35)
}
