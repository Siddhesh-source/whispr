package dev.whispr.data

import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadCodecTest {
    @Test
    fun roundTrips() {
        listOf(Payload.Text("héllo 🙂"), Payload.Read(listOf("a", "b")), Payload.Typing).forEach {
            assertEquals(it, PayloadCodec.decode(PayloadCodec.encode(it)))
        }
    }

    @Test
    fun wireFormatIsStable() {
        assertEquals("""{"t":"text","body":"hi"}""", PayloadCodec.encode(Payload.Text("hi")).decodeToString())
    }

    @Test
    fun unknownOrGarbageDecodesToNull() {
        assertNull(PayloadCodec.decode("""{"t":"sticker","id":1}""".toByteArray()))
        assertNull(PayloadCodec.decode(byteArrayOf(-1, 0, 1)))
        assertEquals(
            "unknown fields ignored",
            Payload.Text("x"),
            PayloadCodec.decode("""{"t":"text","body":"x","extra":1}""".toByteArray()),
        )
    }
}
