package dev.whispr.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * Text fields (DESIGN.md "Components"): a hairline border and 12dp corners on
 * the screen's own background; the border turns moss when focused. (A filled
 * container would show behind the floating label's notch.) Pass to
 * OutlinedTextField's shape and colors.
 */
object WhisprFields {
    val shape: Shape
        @Composable get() = MaterialTheme.shapes.medium

    @Composable
    fun colors(): TextFieldColors {
        val c = WhisprTheme.colors
        // Hairline is a decorative divider tone; an input's resting border needs 3:1, so use faint.
        return OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = c.faint,
            disabledBorderColor = c.hairline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        )
    }
}
