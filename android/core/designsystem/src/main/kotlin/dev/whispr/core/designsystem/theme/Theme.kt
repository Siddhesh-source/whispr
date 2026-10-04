package dev.whispr.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The single entry point for styling. Screens read colors from
 * [MaterialTheme.colorScheme] or [WhisprTheme.colors], text styles from
 * [MaterialTheme.typography], and dimensions from [WhisprTheme.spacing] and
 * [WhisprTheme.sizes], never from literals.
 *
 * Dynamic (wallpaper) color is intentionally off: a fixed palette is calmer,
 * and the contrast guarantees in ColorContrastTest only cover this palette.
 */
@Composable
fun WhisprTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val whisprColors = if (darkTheme) DarkWhisprColors else LightWhisprColors
    CompositionLocalProvider(
        LocalWhisprColors provides whisprColors,
        LocalWhisprSpacing provides WhisprSpacing(),
        LocalWhisprSizes provides WhisprSizes(),
        LocalBubbleShapes provides BubbleShapes(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WhisprTypography,
            shapes = WhisprShapes,
            content = content,
        )
    }
}

object WhisprTheme {
    val colors: WhisprColors
        @Composable @ReadOnlyComposable
        get() = LocalWhisprColors.current

    val spacing: WhisprSpacing
        @Composable @ReadOnlyComposable
        get() = LocalWhisprSpacing.current

    val sizes: WhisprSizes
        @Composable @ReadOnlyComposable
        get() = LocalWhisprSizes.current

    val bubbleShapes: BubbleShapes
        @Composable @ReadOnlyComposable
        get() = LocalBubbleShapes.current
}
