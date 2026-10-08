package dev.whispr.data

import dev.whispr.data.media.AnimatedImages
import dev.whispr.data.media.GifSanitizer
import dev.whispr.data.media.WebpSanitizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GIFs and animated WebPs are sent byte for byte, so their metadata must be stripped structurally. */
class AnimatedImagesTest {

    // ---- GIF ----

    @Test
    fun gifKeepsFramesAndLoopButDropsCommentsAndXmp() {
        val (out, w, h) = GifSanitizer.clean(gif())!!
        assertEquals(1, w)
        assertEquals(1, h)
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse("comment survived", "secret comment" in text)
        assertFalse("XMP survived", "XMP DataXMP" in text)
        assertFalse("XMP body survived", "gps=51.5" in text)
        assertTrue("loop extension dropped", "NETSCAPE2.0" in text)
        assertEquals(0x3B, out.last().toInt())
        // Still a valid image.
        assertNotNull(ImageIO.read(ByteArrayInputStream(out)))
    }

    @Test
    fun gifWithoutMetadataIsUnchanged() {
        val plain = gif(comment = false, xmp = false)
        assertTrue(plain.contentEquals(GifSanitizer.clean(plain)!!.first))
    }

    @Test
    fun truncatedGifIsRejectedAtEveryLength() {
        val full = gif()
        // Every prefix that cuts off before the trailer is malformed.
        for (n in 0 until full.size - 1) assertNull("prefix $n", GifSanitizer.clean(full.copyOf(n)))
    }

    @Test
    fun gifWithoutAnyImageOrWithAnUnknownBlockIsRejected() {
        val noImage = header() + byteArrayOf(0x3B)
        assertNull(GifSanitizer.clean(noImage))
        val unknown = header() + byteArrayOf(0x55) + image() + byteArrayOf(0x3B)
        assertNull(GifSanitizer.clean(unknown))
        assertNull(GifSanitizer.clean("GIF00a".toByteArray() + gif().copyOfRange(6, gif().size)))
    }

    // ---- WebP ----

    @Test
    fun webpDropsExifAndXmpAndClearsTheirFlags() {
        val input = webp(exif = true, xmp = true)
        val (out, w, h) = WebpSanitizer.clean(input)!!
        assertEquals(320, w)
        assertEquals(240, h)
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse("EXIF" in text)
        assertFalse("XMP " in text)
        assertTrue("ANMF" in text)
        assertTrue("ANIM" in text)
        // RIFF size covers exactly the rest of the file.
        assertEquals(out.size - 8, le32(out, 4))
        // VP8X keeps the animation flag only.
        assertEquals(FLAG_ANIMATION, out[20].toInt())
    }

    @Test
    fun webpChunkPaddingIsKept() {
        val (out, _, _) = WebpSanitizer.clean(webp(exif = true, xmp = false, oddFrame = true))!!
        assertEquals(0, (out.size - 8) % 2)
        assertEquals(out.size - 8, le32(out, 4))
    }

    @Test
    fun staticOrMalformedWebpIsNotAnimated() {
        val still = webp(exif = false, xmp = false).also { it[20] = 0 }
        assertFalse(WebpSanitizer.isAnimated(still))
        assertNull(AnimatedImages.clean(still))
        val full = webp(exif = true, xmp = true)
        for (n in 21 until full.size) assertNull("prefix $n", WebpSanitizer.clean(full.copyOf(n)))
        val noFrames = riff(vp8x(FLAG_ANIMATION) + chunk("ANIM", ByteArray(6)))
        assertNull(WebpSanitizer.clean(noFrames))
    }

    @Test
    fun cleanRoutesByFormatAndCapsSize() {
        assertEquals(AnimatedImages.GIF, AnimatedImages.clean(gif())!!.contentType)
        assertEquals(AnimatedImages.WEBP, AnimatedImages.clean(webp(exif = false, xmp = false))!!.contentType)
        assertNull(AnimatedImages.clean(ByteArray(16)))
        assertNull(AnimatedImages.clean(ByteArray(AnimatedImages.MAX_BYTES + 1)))
    }

    // ---- Fixtures ----

    /** A 1x1, two-frame GIF89a with a loop extension, optional comment and XMP. */
    private fun gif(comment: Boolean = true, xmp: Boolean = true): ByteArray = ByteArrayOutputStream().apply {
        write(header())
        write(appExtension("NETSCAPE2.0", byteArrayOf(1, 0, 0)))
        if (comment) write(byteArrayOf(0x21, 0xFE.toByte()) + subBlocks("secret comment".toByteArray()))
        if (xmp) write(appExtension("XMP DataXMP", "<x gps=51.5/>".toByteArray()))
        repeat(2) {
            write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0)) // graphic control
            write(image())
        }
        write(0x3B)
    }.toByteArray()

    private fun header(): ByteArray =
        "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0) + byteArrayOf(0, 0, 0, -1, -1, -1)

    private fun image(): ByteArray = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0) + byteArrayOf(2, 2, 0x44, 0x01, 0)

    private fun appExtension(id: String, data: ByteArray): ByteArray =
        byteArrayOf(0x21, 0xFF.toByte(), 11) + id.toByteArray() + subBlocks(data)

    private fun subBlocks(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data + byteArrayOf(0)

    private fun webp(exif: Boolean, xmp: Boolean, oddFrame: Boolean = false): ByteArray {
        var flags = FLAG_ANIMATION
        if (exif) flags = flags or 0x08
        if (xmp) flags = flags or 0x04
        var body = vp8x(flags) + chunk("ANIM", ByteArray(6)) +
            chunk("ANMF", ByteArray(if (oddFrame) 17 else 16) { 7 })
        if (exif) body += chunk("EXIF", "Exif camera=Pixel".toByteArray())
        if (xmp) body += chunk("XMP ", "<xmp gps/>".toByteArray())
        return riff(body)
    }

    /** VP8X for a 320x240 canvas. */
    private fun vp8x(flags: Int): ByteArray =
        chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + le24(319) + le24(239))

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return fourcc.toByteArray() + le32Bytes(payload.size) + payload + pad
    }

    private fun riff(body: ByteArray) = "RIFF".toByteArray() + le32Bytes(4 + body.size) + "WEBP".toByteArray() + body

    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())

    private fun le32Bytes(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val FLAG_ANIMATION = 0x02
    }
}
