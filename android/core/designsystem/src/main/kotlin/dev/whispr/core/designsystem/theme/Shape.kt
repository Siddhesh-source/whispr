package dev.whispr.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

internal val WhisprShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Bubble corners (topStart, topEnd, bottomEnd, bottomStart). Consecutive
 * bubbles from one sender tighten the corners on the sender's side so a run
 * reads as a single group.
 */
@Immutable
data class BubbleShapes(
    val outgoingSingle: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
    val outgoingFirst: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
    val outgoingMiddle: RoundedCornerShape = RoundedCornerShape(20.dp, 6.dp, 6.dp, 20.dp),
    val outgoingLast: RoundedCornerShape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
    val incomingSingle: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
    val incomingFirst: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
    val incomingMiddle: RoundedCornerShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 6.dp),
    val incomingLast: RoundedCornerShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
)

internal val LocalBubbleShapes = staticCompositionLocalOf { BubbleShapes() }
