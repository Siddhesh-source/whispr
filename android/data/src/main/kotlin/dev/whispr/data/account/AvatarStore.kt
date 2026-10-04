package dev.whispr.data.account

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max

/**
 * Imports a picked image as the local avatar. The image is decoded, scaled to
 * at most [MAX_SIDE] px, and re-encoded as JPEG. Re-encoding drops all EXIF
 * metadata, including GPS location and camera details. Avatars never leave
 * the device in this phase.
 */
class AvatarStore(private val resolver: ContentResolver, private val dir: File) {
    fun import(uri: Uri): String {
        val bitmap = decodeScaled(uri)
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        FileOutputStream(tmp).use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "encode failed" }
            it.fd.sync()
        }
        val target = File(dir, FILE)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return target.absolutePath
    }

    private fun decodeScaled(uri: Uri): Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies EXIF orientation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = MAX_SIDE.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        decodeLegacy(uri)
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("cannot decode image")
        val rotation = resolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).rotationDegrees }
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private companion object {
        const val FILE = "avatar.jpg"
        const val MAX_SIDE = 512
        const val JPEG_QUALITY = 90
    }
}
