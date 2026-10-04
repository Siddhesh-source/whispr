package dev.whispr.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialsTest {
    @Test
    fun takesFirstGraphemeOfFirstTwoWords() {
        assertEquals("AL", initialsOf("Ada Lovelace"))
        assertEquals("GM", initialsOf("grace murray hopper"))
        assertEquals("A", initialsOf("  ada  "))
        assertEquals("", initialsOf("   "))
    }

    @Test
    fun handlesNonLatinAndSurrogatePairs() {
        assertEquals("李", initialsOf("李小龍"))
        assertEquals("ÉZ", initialsOf("élodie zoë"))
        // An emoji is one grapheme even though it is two UTF-16 chars.
        assertEquals("🙂S", initialsOf("🙂 Sam"))
    }
}
