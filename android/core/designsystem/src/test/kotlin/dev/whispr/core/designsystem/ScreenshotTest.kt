package dev.whispr.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.whispr.core.designsystem.catalog.ComponentCatalog
import dev.whispr.core.designsystem.catalog.ThemeShowcase
import dev.whispr.core.designsystem.component.BubbleDirection
import dev.whispr.core.designsystem.component.BubbleGroupPosition
import dev.whispr.core.designsystem.component.ChatListRow
import dev.whispr.core.designsystem.component.DeliveryStatus
import dev.whispr.core.designsystem.component.MessageBubble
import dev.whispr.core.designsystem.component.MessageInputBar
import dev.whispr.core.designsystem.theme.WhisprTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the design system to PNGs under core/designsystem/screenshots for
 * review. Run: ./gradlew :core:designsystem:recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp-xhdpi")
class ScreenshotTest {

    @Test fun themeLight() = capture("theme_light") { ThemeShowcase() }

    @Test fun themeDark() = capture("theme_dark", dark = true) { ThemeShowcase() }

    @Test fun componentsLight() = capture("components_light") { ComponentCatalog() }

    @Test fun componentsDark() = capture("components_dark", dark = true) { ComponentCatalog() }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xhdpi")
    fun largeFontScale() = capture("components_font_2x", fontScale = 2f) {
        Column(verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm)) {
            ChatListRow("Ada Lovelace", "See you at the lake on Saturday!", "10:42", onClick = {}, unreadCount = 2)
            Column(
                Modifier.padding(WhisprTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs),
            ) {
                MessageBubble(
                    "Did you get the photos?",
                    "10:41",
                    BubbleDirection.Incoming,
                    groupPosition = BubbleGroupPosition.Single,
                )
                MessageBubble("Yes, thank you!", "10:42", BubbleDirection.Outgoing, status = DeliveryStatus.Read)
            }
            MessageInputBar(value = "", onValueChange = {}, onSend = {})
        }
    }

    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        captureRoboImage("screenshots/$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WhisprTheme(darkTheme = dark) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) { content() }
                }
            }
        }
    }
}
