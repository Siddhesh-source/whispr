package dev.whispr.data.account

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import dev.whispr.data.media.ImageCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Imports a picked image as the local avatar. The image is decoded, scaled to
 * at most [MAX_SIDE] px, and re-encoded as JPEG. Re-encoding drops all EXIF
 * metadata, including GPS location and camera details. The avatar never
 * leaves the device.
 */
class AvatarStore(private val resolver: ContentResolver, private val dir: File) {
    fun import(uri: Uri): String {
        val bitmap = ImageCodec.decodeScaled(resolver, uri, MAX_SIDE)
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

    private companion object {
        const val FILE = "avatar.jpg"
        const val MAX_SIDE = 512
        const val JPEG_QUALITY = 90
    }
}
