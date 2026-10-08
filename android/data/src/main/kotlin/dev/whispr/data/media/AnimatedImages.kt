package dev.whispr.data.media

/**
 * Animated images (GIF, animated WebP) are sent byte for byte, so their
 * animation survives; re-encoding to JPEG would keep only the first frame.
 * Instead their metadata is stripped structurally: everything that is not
 * needed to draw the frames is dropped, and anything malformed is rejected
 * rather than passed through.
 */
object AnimatedImages {
    /** Largest animated image we send or decode (below the 25 MiB attachment cap: frames decode into memory). */
    const val MAX_BYTES = 10 shl 20

    const val GIF = "image/gif"
    const val WEBP = "image/webp"

    class Cleaned(val bytes: ByteArray, val contentType: String, val width: Int, val height: Int)

    /** True if [head] (the first bytes of a file) starts a GIF or an animated WebP. */
    fun isAnimated(head: ByteArray): Boolean = isGif(head) || WebpSanitizer.isAnimated(head)

    fun isGif(head: ByteArray): Boolean = head.size >= GIF_MAGIC_LEN &&
        head[0] == 'G'.code.toByte() &&
        head[1] == 'I'.code.toByte() &&
        head[2] == 'F'.code.toByte() &&
        head[3] == '8'.code.toByte() &&
        (head[4] == '7'.code.toByte() || head[4] == '9'.code.toByte()) &&
        head[5] == 'a'.code.toByte()

    /** The cleaned file, or null if it is neither a well-formed GIF nor animated WebP. */
    fun clean(bytes: ByteArray): Cleaned? = when {
        bytes.size > MAX_BYTES -> null
        isGif(bytes) -> GifSanitizer.clean(bytes)?.let { (out, w, h) -> Cleaned(out, GIF, w, h) }
        WebpSanitizer.isAnimated(bytes) -> WebpSanitizer.clean(bytes)?.let { (out, w, h) -> Cleaned(out, WEBP, w, h) }
        else -> null
    }

    private const val GIF_MAGIC_LEN = 6
}

/**
 * Rewrites a GIF keeping only what draws it: header, screen descriptor,
 * color tables, graphic control extensions, image data and the looping
 * extension. Comments, plain-text blocks and every other application
 * extension (XMP, ICC, editor data) are dropped.
 */
object GifSanitizer {
    private const val HEADER = 6
    private const val SCREEN = 7
    private const val IMAGE_DESCRIPTOR = 0x2C
    private const val EXTENSION = 0x21
    private const val TRAILER = 0x3B
    private const val GRAPHIC_CONTROL = 0xF9
    private const val APPLICATION = 0xFF
    private const val APP_ID_LEN = 11
    private val LOOP_IDS = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

    /** (cleaned bytes, width, height), or null if malformed. */
    fun clean(input: ByteArray): Triple<ByteArray, Int, Int>? {
        val r = Reader(input)
        val out = java.io.ByteArrayOutputStream(input.size)
        val header = r.take(HEADER) ?: return null
        if (!AnimatedImages.isGif(header)) return null
        val screen = r.take(SCREEN) ?: return null
        val width = u16(screen, 0)
        val height = u16(screen, 2)
        if (width == 0 || height == 0) return null
        out.write(header)
        out.write(screen)
        colorTable(screen[4].toInt())?.let { out.write(r.take(it) ?: return null) }
        var images = 0
        while (true) {
            when (r.byte() ?: return null) {
                IMAGE_DESCRIPTOR -> {
                    val desc = r.take(IMAGE_DESCRIPTOR_LEN) ?: return null
                    out.write(IMAGE_DESCRIPTOR)
                    out.write(desc)
                    colorTable(desc[IMAGE_DESCRIPTOR_LEN - 1].toInt())?.let { out.write(r.take(it) ?: return null) }
                    out.write(r.byte() ?: return null) // LZW minimum code size
                    out.write(r.subBlocks() ?: return null)
                    images++
                }
                EXTENSION -> {
                    val label = r.byte() ?: return null
                    val blocks = r.subBlocks() ?: return null
                    if (keep(label, blocks)) {
                        out.write(EXTENSION)
                        out.write(label)
                        out.write(blocks)
                    }
                }
                TRAILER -> {
                    if (images == 0) return null
                    out.write(TRAILER)
                    return Triple(out.toByteArray(), width, height)
                }
                else -> return null
            }
        }
    }

    private fun keep(label: Int, blocks: ByteArray): Boolean = when (label) {
        GRAPHIC_CONTROL -> true
        APPLICATION ->
            blocks.isNotEmpty() &&
                blocks[0].toInt() == APP_ID_LEN &&
                blocks.size > APP_ID_LEN &&
                String(blocks, 1, APP_ID_LEN, Charsets.US_ASCII) in LOOP_IDS
        else -> false // comment (0xFE), plain text (0x01), unknown
    }

    /** Size in bytes of the color table a packed field announces, or null if none. */
    private fun colorTable(packed: Int): Int? =
        if (packed and FLAG_TABLE != 0) COLOR_BYTES * (1 shl ((packed and SIZE_MASK) + 1)) else null

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and BYTE) or ((b[at + 1].toInt() and BYTE) shl BITS)

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun byte(): Int? = if (pos < b.size) b[pos++].toInt() and BYTE else null

        fun take(n: Int): ByteArray? {
            if (n < 0 || pos + n > b.size) return null
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        /** Data sub-blocks up to and including the zero terminator, verbatim. */
        fun subBlocks(): ByteArray? {
            val start = pos
            while (true) {
                val len = byte() ?: return null
                if (len == 0) return b.copyOfRange(start, pos)
                if (pos + len > b.size) return null
                pos += len
            }
        }
    }

    private const val IMAGE_DESCRIPTOR_LEN = 9
    private const val FLAG_TABLE = 0x80
    private const val SIZE_MASK = 0x07
    private const val COLOR_BYTES = 3
    private const val BYTE = 0xFF
    private const val BITS = 8
}

/**
 * Rewrites an animated WebP without its `EXIF` and `XMP ` chunks (and clears
 * their flags), keeping every chunk needed to draw it.
 */
object WebpSanitizer {
    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val VP8X_LEN = 10
    private const val FLAG_EXIF = 0x08
    private const val FLAG_XMP = 0x04
    private const val FLAG_ANIMATION = 0x02
    private val DROPPED = setOf("EXIF", "XMP ")

    fun isAnimated(head: ByteArray): Boolean {
        if (head.size < RIFF_HEADER + CHUNK_HEADER + 1) return false
        if (ascii(head, 0) != "RIFF" || ascii(head, 8) != "WEBP" || ascii(head, RIFF_HEADER) != "VP8X") return false
        return head[RIFF_HEADER + CHUNK_HEADER].toInt() and FLAG_ANIMATION != 0
    }

    /** (cleaned bytes, width, height), or null if malformed. */
    fun clean(input: ByteArray): Triple<ByteArray, Int, Int>? {
        if (!isAnimated(input)) return null
        val riffEnd = RIFF_HEADER - 4 + le32(input, 4)
        if (riffEnd > input.size || riffEnd < RIFF_HEADER) return null
        val body = java.io.ByteArrayOutputStream(input.size)
        var pos = RIFF_HEADER
        var width = 0
        var height = 0
        var frames = 0
        while (pos < riffEnd) {
            if (pos + CHUNK_HEADER > riffEnd) return null
            val fourcc = ascii(input, pos)
            val size = le32(input, pos + 4)
            val padded = size + (size and 1)
            if (size < 0 || pos + CHUNK_HEADER + padded > riffEnd) return null
            val chunk = input.copyOfRange(pos, pos + CHUNK_HEADER + padded)
            when (fourcc) {
                in DROPPED -> Unit
                "VP8X" -> {
                    if (size < VP8X_LEN) return null
                    chunk[CHUNK_HEADER] = (chunk[CHUNK_HEADER].toInt() and (FLAG_EXIF or FLAG_XMP).inv()).toByte()
                    width = le24(chunk, CHUNK_HEADER + 4) + 1
                    height = le24(chunk, CHUNK_HEADER + 7) + 1
                    body.write(chunk)
                }
                else -> {
                    if (fourcc == "ANMF") frames++
                    body.write(chunk)
                }
            }
            pos += CHUNK_HEADER + padded
        }
        if (frames == 0 || width <= 0 || height <= 0) return null
        val payload = body.toByteArray()
        val out = java.io.ByteArrayOutputStream(RIFF_HEADER + payload.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32Bytes(4 + payload.size))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return Triple(out.toByteArray(), width, height)
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun le32(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun le32Bytes(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
