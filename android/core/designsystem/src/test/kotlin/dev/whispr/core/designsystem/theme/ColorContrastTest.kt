package dev.whispr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 contrast checks for every text/background pairing the components
 * use. 4.5:1 for text (AA), 3:1 for non-text UI such as outlines and icons.
 */
class ColorContrastTest {

    private val schemes = mapOf(
        "light" to (LightColorScheme to LightWhisprColors),
        "dark" to (DarkColorScheme to DarkWhisprColors),
    )

    @Test
    fun textPairsMeetAA() {
        schemes.forEach { (mode, pair) ->
            val (c, w) = pair
            textPairs(c, w).forEach { (name, fg, bg) ->
                val ratio = contrast(fg, bg)
                assertTrue("$mode $name contrast %.2f < 4.5".format(ratio), ratio >= TEXT_AA)
            }
        }
    }

    @Test
    fun nonTextUiMeets3to1() {
        schemes.forEach { (mode, pair) ->
            val c = pair.first
            listOf(
                Triple("outline/surface", c.outline, c.surface),
                Triple("primary/surface (icons, progress)", c.primary, c.surface),
                Triple("error/surface (error icon)", c.error, c.surface),
            ).forEach { (name, fg, bg) ->
                val ratio = contrast(fg, bg)
                assertTrue("$mode $name contrast %.2f < 3.0".format(ratio), ratio >= NON_TEXT)
            }
        }
    }

    private fun textPairs(c: ColorScheme, w: WhisprColors) = buildList {
        add(Triple("onSurface/surface", c.onSurface, c.surface))
        add(Triple("onSurfaceVariant/surface", c.onSurfaceVariant, c.surface))
        add(
            Triple(
                "onSurfaceVariant/surfaceContainerHighest (input placeholder)",
                c.onSurfaceVariant,
                c.surfaceContainerHighest,
            ),
        )
        add(Triple("onPrimary/primary", c.onPrimary, c.primary))
        add(Triple("primary/surface (unread time)", c.primary, c.surface))
        add(Triple("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer))
        add(Triple("onError/error", c.onError, c.error))
        add(Triple("onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer))
        add(Triple("onBubbleOutgoing/bubbleOutgoing", w.onBubbleOutgoing, w.bubbleOutgoing))
        add(Triple("onBubbleIncoming/bubbleIncoming", w.onBubbleIncoming, w.bubbleIncoming))
        add(Triple("onBubbleFailed/bubbleFailed", w.onBubbleFailed, w.bubbleFailed))
        add(Triple("onUnreadBadge/unreadBadge", w.onUnreadBadge, w.unreadBadge))
        add(Triple("onBanner/banner", w.onBanner, w.banner))
        w.avatarPalette.forEachIndexed { i, (bg, fg) -> add(Triple("avatar[$i]", fg, bg)) }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private companion object {
        const val TEXT_AA = 4.5
        const val NON_TEXT = 3.0
    }
}
