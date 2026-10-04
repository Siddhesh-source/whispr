package dev.whispr.domain

import dev.whispr.domain.usecase.DisplayNameValidation
import dev.whispr.domain.usecase.DisplayNameValidator
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases mirror server/internal/auth/service_test.go TestValidateDisplayName. */
class DisplayNameValidatorTest {
    @Test
    fun acceptsAndTrims() {
        listOf("Ada", "José", "李小龍", "🙂 Sam", "a".repeat(64)).forEach {
            assertEquals(DisplayNameValidation.Valid(it), DisplayNameValidator.validate(it))
        }
        assertEquals(DisplayNameValidation.Valid("Ada"), DisplayNameValidator.validate("  Ada \n"))
    }

    @Test
    fun countsCodePointsNotChars() {
        // 64 emoji = 128 UTF-16 chars but 64 code points.
        assertEquals(true, DisplayNameValidator.validate("🙂".repeat(64)) is DisplayNameValidation.Valid)
        assertEquals(DisplayNameValidation.TooLong, DisplayNameValidator.validate("🙂".repeat(65)))
    }

    @Test
    fun rejects() {
        assertEquals(DisplayNameValidation.Empty, DisplayNameValidator.validate("   "))
        assertEquals(DisplayNameValidation.TooLong, DisplayNameValidator.validate("a".repeat(65)))
        listOf("A\nB", "A\u0000B", "evil‮gnp.exe", "x\uD800y").forEach {
            assertEquals(it, DisplayNameValidation.InvalidCharacters, DisplayNameValidator.validate(it))
        }
    }
}
