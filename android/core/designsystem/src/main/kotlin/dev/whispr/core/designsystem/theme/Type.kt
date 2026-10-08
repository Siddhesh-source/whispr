package dev.whispr.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.whispr.core.designsystem.R

/**
 * Faces from DESIGN.md "Typography", bundled as resources (SIL OFL 1.1; the
 * license texts ship in assets/licenses). No downloadable-fonts provider: it
 * would make a third-party request through Play Services. All sizes are sp,
 * so text follows the user's font scale.
 */
@OptIn(ExperimentalTextApi::class)
object WhisprFonts {
    /** UI and message text. */
    val Onest: FontFamily = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(R.font.onest, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    /** Large screen titles and the onboarding headline only; never under 22sp. */
    val Display: FontFamily = FontFamily(
        listOf(600, 700).map { w ->
            Font(
                R.font.bricolage_grotesque,
                FontWeight(w),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(w),
                    FontVariation.width(DISPLAY_WIDTH),
                    FontVariation.Setting("opsz", DISPLAY_OPTICAL_SIZE),
                ),
            )
        },
    )

    /** Safety numbers, account IDs and keys. */
    val Mono: FontFamily = FontFamily(
        listOf(400, 500).map { w ->
            Font(
                R.font.jetbrains_mono,
                FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )

    private const val DISPLAY_WIDTH = 80f
    private const val DISPLAY_OPTICAL_SIZE = 48f
}

private val display = TextStyle(fontFamily = WhisprFonts.Display, fontWeight = FontWeight.SemiBold)
private val ui = TextStyle(fontFamily = WhisprFonts.Onest)

internal val WhisprTypography = Typography(
    // Bricolage Grotesque: screen titles and onboarding.
    displayLarge = display.copy(fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-0.025).em),
    displayMedium = display.copy(fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-0.025).em),
    displaySmall = display.copy(fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em),
    headlineLarge = display.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    headlineMedium = display.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em),
    // Onest from here down.
    headlineSmall = ui.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
    ),
    titleLarge = ui.copy(
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.005).em,
    ),
    titleMedium = ui.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = ui.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = ui.copy(fontSize = 15.5.sp, lineHeight = 21.5.sp),
    bodyMedium = ui.copy(fontSize = 14.5.sp, lineHeight = 20.sp),
    bodySmall = ui.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = ui.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = ui.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = ui.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
