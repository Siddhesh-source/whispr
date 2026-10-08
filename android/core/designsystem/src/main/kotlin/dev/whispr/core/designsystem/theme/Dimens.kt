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
    val avatarMedium: Dp = 46.dp,
    val avatarLarge: Dp = 64.dp,
    val avatarXLarge: Dp = 112.dp,
    val chatRowMinHeight: Dp = 72.dp,
    val unreadBadgeMin: Dp = 22.dp,
    /** The amber verified seal after a contact's name. */
    val seal: Dp = 15.dp,
    /** Hairline dividers and borders. */
    val hairline: Dp = 1.dp,
    /** List dividers start at the text column: gutter + avatar + gap. */
    val listDividerInset: Dp = 76.dp,
    val sendButton: Dp = 44.dp,
    val buttonHeight: Dp = 52.dp,
    val inputBarMinHeight: Dp = 56.dp,
    val primaryButtonHeight: Dp = 56.dp,
    val progressStroke: Dp = 3.dp,
    val progressSmall: Dp = 20.dp,
    /** Keeps line length readable on tablets and foldables. */
    val contentMaxWidth: Dp = 640.dp,
    /** Bubbles never exceed this fraction of the available width. */
    val bubbleMaxWidthFraction: Float = 0.78f,
    /** Largest side of an image preview inside a bubble. */
    val mediaPreviewMax: Dp = 240.dp,
    /** Call screen: accept, decline and hang up. */
    val callAction: Dp = 68.dp,
    /** Call screen: mute, speaker, camera toggles. */
    val callControl: Dp = 56.dp,
    /** Our own camera preview during a video call. */
    val callPreviewWidth: Dp = 112.dp,
    val callPreviewHeight: Dp = 160.dp,
)

internal val LocalWhisprSpacing = staticCompositionLocalOf { WhisprSpacing() }
internal val LocalWhisprSizes = staticCompositionLocalOf { WhisprSizes() }
