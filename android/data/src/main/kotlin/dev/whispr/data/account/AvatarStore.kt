package dev.whispr.data.account

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import dev.whispr.data.media.ImageCodec
import dev.whispr.data.messaging.ContactLink
import dev.whispr.data.messaging.Payload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Imports a picked image as the local avatar. The image is decoded, scaled to
 * at most [MAX_SIDE] px, and re-encoded as JPEG. Re-encoding drops all EXIF
 * metadata, including GPS location and camera details. A smaller copy
 * (at most [Payload.Profile.MAX_AVATAR_BYTES]) is what accepted contacts
 * receive, end to end encrypted; the server never sees either.
 */
class AvatarStore(private val resolver: ContentResolver, private val dir: File) {
    fun import(uri: Uri): String {
        val bitmap = ImageCodec.decodeScaled(resolver, uri, MAX_SIDE)
        dir.mkdirs()
        // A new name each time: the stored path changes, so every screen reloads it.
        val target = File(dir, "avatar-${System.currentTimeMillis()}.jpg")
        write(target) { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "encode failed" } }
        val small = Bitmap.createScaledBitmap(
            bitmap,
            SHARE_SIDE.coerceAtMost(bitmap.width),
            (SHARE_SIDE.coerceAtMost(bitmap.width) * bitmap.height / bitmap.width).coerceAtLeast(1),
            true,
        )
        val share = shareBytes(small)
        val shareFile = File(target.path + ContactLink.SHARE_SUFFIX)
        write(shareFile) { it.write(share) }
        dir.listFiles()?.filter { it.name.startsWith("avatar") && it != target && it != shareFile }?.forEach {
            it.delete()
        }
        return target.absolutePath
    }

    /** The highest quality that fits the profile limit. */
    private fun shareBytes(bitmap: Bitmap): ByteArray {
        var quality = JPEG_QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "encode failed" }
            if (out.size() <= Payload.Profile.MAX_AVATAR_BYTES || quality <= MIN_QUALITY) return out.toByteArray()
            quality -= QUALITY_STEP
        }
    }

    private fun write(target: File, body: (FileOutputStream) -> Unit) {
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use {
            body(it)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val MAX_SIDE = 512
        const val SHARE_SIDE = 192
        const val JPEG_QUALITY = 90
        const val MIN_QUALITY = 30
        const val QUALITY_STEP = 15
    }
}
