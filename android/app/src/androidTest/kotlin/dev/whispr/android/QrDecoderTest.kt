package dev.whispr.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.whispr.android.ui.scan.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The on-device decoder (zxing-cpp native) reads what our renderer (zxing core) writes. */
@RunWith(AndroidJUnit4::class)
class QrDecoderTest {
    private fun render(text: String, size: Int = 600): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun roundTripsAContactCode() {
        val code = "whispr:AQAAESIzRFVmd4iZqrvM3e7_BQ" + "A".repeat(43) + "FWh0dHBzOi8vY2hhdC5leGFtcGxlLm9yZw"
        assertEquals(code, QrDecoder.decode(render(code)))
    }

    @Test
    fun readsInvertedCodes() {
        val code = "whispr-sn:CAESIAAA"
        val normal = render(code)
        val inverted = Bitmap.createBitmap(normal.width, normal.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until normal.width) {
            for (y in 0 until normal.height) {
                inverted.setPixel(x, y, if (normal.getPixel(x, y) == Color.BLACK) Color.WHITE else Color.BLACK)
            }
        }
        assertEquals(code, QrDecoder.decode(inverted))
    }

    @Test
    fun blankImageYieldsNothing() {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull(QrDecoder.decode(blank))
    }

    @Test
    fun foreignQrDecodesAsTextForTheParserToReject() {
        // The decoder does not judge content; ContactQr.parse does (tested in :data).
        assertEquals("https://example.com/not-whispr", QrDecoder.decode(render("https://example.com/not-whispr")))
    }
}
