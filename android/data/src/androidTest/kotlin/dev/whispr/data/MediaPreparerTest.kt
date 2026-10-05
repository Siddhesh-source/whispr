package dev.whispr.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.data.media.AndroidMediaPreparer
import dev.whispr.data.media.Prepared
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.MediaSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: photos are re-encoded, which drops EXIF (GPS, camera, timestamps). */
@RunWith(AndroidJUnit4::class)
class MediaPreparerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun photoWithGps(): File {
        val file = File(context.cacheDir, "gps-test.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setLatLong(48.8584, 2.2945)
            setAttribute(ExifInterface.TAG_MAKE, "WhisprTestCam")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:05 09:00:00")
            saveAttributes()
        }
        assertNotNull("fixture has GPS", ExifInterface(file.path).latLong)
        return file
    }

    @Test
    fun imagesLoseLocationAndCameraMetadata() = runTest {
        val file = photoWithGps()
        val result = AndroidMediaPreparer(context.contentResolver)
            .prepare(
                MediaSource(
                    android.net.Uri.fromFile(file).toString(),
                    AttachmentKind.Image,
                    fileName = "IMG_secret.jpg",
                ),
            )
        val media = (result as Prepared.Ok).media
        val exif = ExifInterface(ByteArrayInputStream(media.bytes))
        assertNull("GPS survived re-encoding", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertFalse(String(media.bytes, Charsets.ISO_8859_1).contains("WhisprTestCam"))
        assertEquals("image/jpeg", media.contentType)
        assertNull("camera file names are not sent", media.fileName)
        assertEquals(64, media.width)
        assertTrue("thumbnail fits inline", (media.thumbnail?.size ?: Int.MAX_VALUE) <= 16 * 1024)
        file.delete()
    }

    @Test
    fun groupPicturesFitTheGroupState() = runTest {
        val file = photoWithGps()
        val avatar = AndroidMediaPreparer(context.contentResolver)
            .groupAvatar(android.net.Uri.fromFile(file).toString(), 24 * 1024)
        assertNotNull(avatar)
        assertNull(ExifInterface(ByteArrayInputStream(avatar)).latLong)
        file.delete()
    }
}
