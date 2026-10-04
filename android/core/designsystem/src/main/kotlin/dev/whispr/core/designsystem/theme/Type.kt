package dev.whispr.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The Material 3 type scale on the system font.
 *
 * Downloadable fonts are deliberately avoided: the Google Fonts provider
 * fetches through Play Services, an avoidable third-party request. All sizes
 * are in sp, so text follows the user's font-scale setting.
 */
internal val WhisprTypography: Typography = Typography().run {
    val family = FontFamily.Default
    copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
