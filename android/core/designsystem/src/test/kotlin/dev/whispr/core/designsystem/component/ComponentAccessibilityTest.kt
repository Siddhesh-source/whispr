package dev.whispr.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.core.designsystem.theme.WhisprTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentAccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val minTouch = 48.dp

    @Test
    fun chatRowIsOneLabelledTouchTarget() {
        var clicks = 0
        rule.setContent {
            WhisprTheme { ChatListRow("Ada", "See you soon", "10:42", onClick = { clicks++ }, unreadCount = 2) }
        }
        rule.onNodeWithContentDescription("Ada. 2 unread messages. Last message: See you soon. 10:42")
            .assertHasClickAction()
            .assertTouchTargetAtLeast(minTouch)
            .assert(
                SemanticsMatcher("has open-chat label") {
                    it.config[SemanticsActions.OnClick].label == "Open chat"
                },
            )
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun sendButtonDisabledUntilTextEntered() {
        var sent = 0
        rule.setContent {
            WhisprTheme {
                var text by remember { mutableStateOf("") }
                MessageInputBar(value = text, onValueChange = { text = it }, onSend = { sent++ })
            }
        }
        val send = rule.onNodeWithContentDescription("Send message")
        send.assertIsNotEnabled().assertTouchTargetAtLeast(minTouch)

        rule.onNodeWithContentDescription("Message text").performTextInput("   ")
        send.assertIsNotEnabled()

        rule.onNodeWithContentDescription("Message text").performTextInput("hi")
        send.assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun outgoingBubbleAnnouncesStatus() {
        rule.setContent {
            WhisprTheme { MessageBubble("Hello", "10:42", BubbleDirection.Outgoing, status = DeliveryStatus.Delivered) }
        }
        rule.onNodeWithContentDescription("You, 10:42: Hello. Delivered").assertIsDisplayed()
    }

    @Test
    fun failedBubbleOffersRetryAction() {
        var retries = 0
        rule.setContent {
            WhisprTheme {
                MessageBubble("Hello", "10:42", BubbleDirection.Outgoing, status = DeliveryStatus.Failed, onRetry = {
                    retries++
                })
            }
        }
        rule.onNodeWithContentDescription("You, 10:42: Hello. Not sent")
            .assert(
                SemanticsMatcher("has retry label") {
                    it.config[SemanticsActions.OnClick].label == "Retry sending"
                },
            )
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun incomingBubbleHasNoClickAction() {
        rule.setContent {
            WhisprTheme { MessageBubble("Hi", "10:40", BubbleDirection.Incoming, senderName = "Ada") }
        }
        rule.onNodeWithContentDescription("Ada, 10:40: Hi")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun topBarBackButtonIsLabelledAndTitleIsHeading() {
        var back = 0
        rule.setContent { WhisprTheme { WhisprTopBar("Chats", onNavigateBack = { back++ }) } }
        rule.onNodeWithContentDescription("Navigate back")
            .assertTouchTargetAtLeast(minTouch)
            .performClick()
        assertEquals(1, back)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    }

    @Test
    fun loadingStateIsAnnounced() {
        rule.setContent { WhisprTheme { Box { LoadingState(label = "Loading chats") } } }
        rule.onNodeWithContentDescription("Loading chats")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun errorStateRetryWorks() {
        var retries = 0
        rule.setContent { WhisprTheme { ErrorState("Couldn't load", "Try again later", onRetry = { retries++ }) } }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Try again"))))
            .assertTouchTargetAtLeast(minTouch)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun blankNameAvatarShowsIconNotPlaceholderText() {
        rule.setContent { WhisprTheme { WhisprAvatar("", contentDescription = "Add a profile photo") } }
        rule.onNodeWithContentDescription("Add a profile photo").assertIsDisplayed()
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("no placeholder characters expected", texts.isEmpty())
    }

    @Test
    fun decorativeAvatarIsHiddenFromScreenReaders() {
        rule.setContent { WhisprTheme { WhisprAvatar("Ada Lovelace") } }
        val nodes = rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("AL"))),
        )
            .fetchSemanticsNodes()
        assertTrue("initials should not be exposed", nodes.isEmpty())
    }

    /**
     * Checks the touch area, which Compose may extend beyond a smaller visual
     * size (e.g. 40dp icon buttons get a 48dp touch target).
     */
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(min: Dp): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        val density = node.layoutInfo.density
        val width = with(density) { bounds.width.toDp() }
        val height = with(density) { bounds.height.toDp() }
        assertTrue("touch target ${width}x$height is smaller than $min", width >= min && height >= min)
        return this
    }
}
