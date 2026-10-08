package dev.whispr.core.designsystem.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.core.designsystem.theme.WhisprTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's text lives in the field; the screen's copy arrives later.
 * A late copy of an earlier keystroke must never overwrite newer typing
 * (regression: characters vanished when typing quickly on a device).
 */
@RunWith(AndroidJUnit4::class)
class MessageInputBarTest {
    @get:Rule val rule = createComposeRule()

    private var value by mutableStateOf("")
    private val emitted = mutableListOf<String>()

    private fun text() = rule.onNodeWithContentDescription("Message text")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun setUp() = rule.setContent {
        WhisprTheme { MessageInputBar(value = value, onValueChange = { emitted += it }, onSend = {}) }
    }

    @Test
    fun lateEchoesDoNotEraseNewerTyping() {
        setUp()
        val field = rule.onNodeWithContentDescription("Message text")
        field.performTextInput("a")
        field.performTextInput("b")
        field.performTextInput("c")
        rule.waitForIdle()
        assertEquals(listOf("a", "ab", "abc"), emitted)
        // The screen catches up one edit at a time, behind the typing.
        for (echo in listOf("a", "ab", "abc")) {
            value = echo
            rule.waitForIdle()
            assertEquals("abc", text())
        }
        assertEquals(listOf("a", "ab", "abc"), emitted)
    }

    @Test
    fun aValueTheFieldNeverSentIsShown() {
        setUp()
        rule.onNodeWithContentDescription("Message text").performTextInput("draft")
        rule.waitForIdle()
        value = "draft"
        rule.waitForIdle()
        // Sent: the screen clears the composer.
        value = ""
        rule.waitForIdle()
        assertEquals("", text())
        // A restored draft appears too.
        value = "restored"
        rule.waitForIdle()
        assertEquals("restored", text())
    }

    @Test
    fun typingThenDeletingBackStillReachesTheScreen() {
        setUp()
        val field = rule.onNodeWithContentDescription("Message text")
        field.performTextInput("x")
        rule.waitForIdle()
        field.performTextClearance()
        rule.waitForIdle()
        assertEquals(listOf("x", ""), emitted)
    }
}
