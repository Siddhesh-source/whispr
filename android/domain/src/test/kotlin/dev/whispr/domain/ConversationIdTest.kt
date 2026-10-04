package dev.whispr.domain

import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.UserId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConversationIdTest {
    private val a = UserId("11111111-1111-1111-1111-111111111111")
    private val b = UserId("22222222-2222-2222-2222-222222222222")

    @Test
    fun bothSidesDeriveTheSameId() {
        assertEquals(ConversationId.direct(a, b), ConversationId.direct(b, a))
        assertEquals(
            "case-insensitive",
            ConversationId.direct(a, b),
            ConversationId.direct(UserId(a.value.uppercase()), b),
        )
    }

    @Test
    fun isAValidVersion8Uuid() {
        val uuid = UUID.fromString(ConversationId.direct(a, b).value)
        assertEquals(8, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun differentPairsDiffer() {
        val c = UserId("33333333-3333-3333-3333-333333333333")
        assertNotEquals(ConversationId.direct(a, b), ConversationId.direct(a, c))
    }
}
