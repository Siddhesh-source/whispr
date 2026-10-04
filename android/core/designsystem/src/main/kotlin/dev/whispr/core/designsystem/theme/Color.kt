package dev.whispr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = lightPrimary,
    onPrimary = lightOnPrimary,
    primaryContainer = lightPrimaryContainer,
    onPrimaryContainer = lightOnPrimaryContainer,
    inversePrimary = lightInversePrimary,
    secondary = lightSecondary,
    onSecondary = lightOnSecondary,
    secondaryContainer = lightSecondaryContainer,
    onSecondaryContainer = lightOnSecondaryContainer,
    tertiary = lightTertiary,
    onTertiary = lightOnTertiary,
    tertiaryContainer = lightTertiaryContainer,
    onTertiaryContainer = lightOnTertiaryContainer,
    background = lightBackground,
    onBackground = lightOnBackground,
    surface = lightSurface,
    onSurface = lightOnSurface,
    surfaceVariant = lightSurfaceVariant,
    onSurfaceVariant = lightOnSurfaceVariant,
    surfaceTint = lightSurfaceTint,
    inverseSurface = lightInverseSurface,
    inverseOnSurface = lightInverseOnSurface,
    error = lightError,
    onError = lightOnError,
    errorContainer = lightErrorContainer,
    onErrorContainer = lightOnErrorContainer,
    outline = lightOutline,
    outlineVariant = lightOutlineVariant,
    scrim = lightScrim,
    surfaceBright = lightSurfaceBright,
    surfaceDim = lightSurfaceDim,
    surfaceContainer = lightSurfaceContainer,
    surfaceContainerHigh = lightSurfaceContainerHigh,
    surfaceContainerHighest = lightSurfaceContainerHighest,
    surfaceContainerLow = lightSurfaceContainerLow,
    surfaceContainerLowest = lightSurfaceContainerLowest,
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = darkPrimary,
    onPrimary = darkOnPrimary,
    primaryContainer = darkPrimaryContainer,
    onPrimaryContainer = darkOnPrimaryContainer,
    inversePrimary = darkInversePrimary,
    secondary = darkSecondary,
    onSecondary = darkOnSecondary,
    secondaryContainer = darkSecondaryContainer,
    onSecondaryContainer = darkOnSecondaryContainer,
    tertiary = darkTertiary,
    onTertiary = darkOnTertiary,
    tertiaryContainer = darkTertiaryContainer,
    onTertiaryContainer = darkOnTertiaryContainer,
    background = darkBackground,
    onBackground = darkOnBackground,
    surface = darkSurface,
    onSurface = darkOnSurface,
    surfaceVariant = darkSurfaceVariant,
    onSurfaceVariant = darkOnSurfaceVariant,
    surfaceTint = darkSurfaceTint,
    inverseSurface = darkInverseSurface,
    inverseOnSurface = darkInverseOnSurface,
    error = darkError,
    onError = darkOnError,
    errorContainer = darkErrorContainer,
    onErrorContainer = darkOnErrorContainer,
    outline = darkOutline,
    outlineVariant = darkOutlineVariant,
    scrim = darkScrim,
    surfaceBright = darkSurfaceBright,
    surfaceDim = darkSurfaceDim,
    surfaceContainer = darkSurfaceContainer,
    surfaceContainerHigh = darkSurfaceContainerHigh,
    surfaceContainerHighest = darkSurfaceContainerHighest,
    surfaceContainerLow = darkSurfaceContainerLow,
    surfaceContainerLowest = darkSurfaceContainerLowest,
)

/**
 * Messenger-specific semantic roles. Every value maps onto a Material role,
 * so the palette is still defined in exactly one place.
 */
@Immutable
data class WhisprColors(
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val bubbleFailed: Color,
    val onBubbleFailed: Color,
    val unreadBadge: Color,
    val onUnreadBadge: Color,
    val banner: Color,
    val onBanner: Color,
    /** Background/foreground pairs for initials avatars, chosen by name hash. */
    val avatarPalette: List<Pair<Color, Color>>,
)

internal fun ColorScheme.toWhisprColors() = WhisprColors(
    bubbleOutgoing = primaryContainer,
    onBubbleOutgoing = onPrimaryContainer,
    bubbleIncoming = surfaceContainerHigh,
    onBubbleIncoming = onSurface,
    bubbleFailed = errorContainer,
    onBubbleFailed = onErrorContainer,
    unreadBadge = primary,
    onUnreadBadge = onPrimary,
    banner = surfaceContainerHighest,
    onBanner = onSurfaceVariant,
    avatarPalette = listOf(
        primaryContainer to onPrimaryContainer,
        secondaryContainer to onSecondaryContainer,
        tertiaryContainer to onTertiaryContainer,
    ),
)

internal val LightWhisprColors = LightColorScheme.toWhisprColors()
internal val DarkWhisprColors = DarkColorScheme.toWhisprColors()

internal val LocalWhisprColors = staticCompositionLocalOf { LightWhisprColors }
