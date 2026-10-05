package dev.whispr.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp-based spacing scale. Screens use these instead of literal dp values. */
@Immutable
data class WhisprSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

/** Component and layout sizes. */
@Immutable
data class WhisprSizes(
    /** Minimum interactive size (Material and WCAG 2.5.5 guidance). */
    val minTouchTarget: Dp = 48.dp,
    val icon: Dp = 24.dp,
    val iconSmall: Dp = 16.dp,
    val stateIllustration: Dp = 64.dp,
    val avatarSmall: Dp = 32.dp,
    val avatarMedium: Dp = 48.dp,
    val avatarLarge: Dp = 64.dp,
    val avatarXLarge: Dp = 112.dp,
    val chatRowMinHeight: Dp = 72.dp,
    val unreadBadgeMin: Dp = 20.dp,
    val inputBarMinHeight: Dp = 56.dp,
    val primaryButtonHeight: Dp = 56.dp,
    val progressStroke: Dp = 3.dp,
    val progressSmall: Dp = 20.dp,
    /** Keeps line length readable on tablets and foldables. */
    val contentMaxWidth: Dp = 600.dp,
    /** Bubbles never exceed this fraction of the available width. */
    val bubbleMaxWidthFraction: Float = 0.8f,
    /** Largest side of an image preview inside a bubble. */
    val mediaPreviewMax: Dp = 240.dp,
)

internal val LocalWhisprSpacing = staticCompositionLocalOf { WhisprSpacing() }
internal val LocalWhisprSizes = staticCompositionLocalOf { WhisprSizes() }
