package dev.whispr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Material roles mapped onto the Whispr palette. Moss is "primary" (buttons,
// send, switches, focus); mint is the outgoing bubble. The seal accent is
// "tertiary" and is used only through WhisprColors for trust and attention.

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = lightMoss,
    onPrimary = lightOnMoss,
    primaryContainer = lightMint,
    onPrimaryContainer = lightOnMint,
    inversePrimary = darkMoss,
    secondary = lightMuted,
    onSecondary = lightSurface,
    secondaryContainer = lightSunken,
    onSecondaryContainer = lightInk,
    tertiary = lightSeal,
    onTertiary = lightInk,
    tertiaryContainer = lightSealSoft,
    onTertiaryContainer = lightOnSealSoft,
    background = lightGround,
    onBackground = lightInk,
    surface = lightGround,
    onSurface = lightInk,
    surfaceVariant = lightSunken,
    onSurfaceVariant = lightMuted,
    surfaceTint = lightMoss,
    inverseSurface = lightInk,
    inverseOnSurface = lightGround,
    error = lightDanger,
    onError = lightSurface,
    errorContainer = lightDangerSoft,
    onErrorContainer = lightOnDangerSoft,
    outline = lightFaint,
    outlineVariant = lightHairline,
    scrim = black,
    surfaceBright = lightSurface,
    surfaceDim = lightSunken,
    surfaceContainerLowest = lightSurface,
    surfaceContainerLow = lightRaised,
    surfaceContainer = lightSurface,
    surfaceContainerHigh = lightSurface,
    surfaceContainerHighest = lightSunken,
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = darkMoss,
    onPrimary = darkOnMoss,
    primaryContainer = darkMint,
    onPrimaryContainer = darkOnMint,
    inversePrimary = lightMoss,
    secondary = darkMuted,
    onSecondary = darkGround,
    secondaryContainer = darkSunken,
    onSecondaryContainer = darkInk,
    tertiary = darkSeal,
    onTertiary = lightInk,
    tertiaryContainer = darkSealSoft,
    onTertiaryContainer = darkOnSealSoft,
    background = darkGround,
    onBackground = darkInk,
    surface = darkGround,
    onSurface = darkInk,
    surfaceVariant = darkSunken,
    onSurfaceVariant = darkMuted,
    surfaceTint = darkMoss,
    inverseSurface = darkInk,
    inverseOnSurface = darkGround,
    error = darkDanger,
    onError = darkGround,
    errorContainer = darkDangerSoft,
    onErrorContainer = darkOnDangerSoft,
    outline = darkFaint,
    outlineVariant = darkHairline,
    scrim = black,
    surfaceBright = darkRaised,
    surfaceDim = darkGround,
    surfaceContainerLowest = darkLowest,
    surfaceContainerLow = darkLow,
    surfaceContainer = darkSurface,
    surfaceContainerHigh = darkRaised,
    surfaceContainerHighest = darkSunken,
)

/**
 * Messenger-specific semantic roles (DESIGN.md "Colors"). Amber ([seal]) is
 * only for trust and attention: verified marks, unread counts, the encryption
 * seal. [danger] is for key changes, failed sends and destructive actions.
 */
@Immutable
data class WhisprColors(
    /** Raised sheets: settings groups, incoming bubbles, inputs. */
    val surface: Color,
    /** Inset fills: search, avatars, chips. */
    val sunken: Color,
    val hairline: Color,
    /** Placeholder icons and decorative glyphs; never for text. */
    val faint: Color,
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    /** Time and ticks inside an outgoing bubble. */
    val metaOutgoing: Color,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    /** Time inside an incoming bubble. */
    val metaIncoming: Color,
    /** The double tick once a message has been read. */
    val readTick: Color,
    val bubbleFailed: Color,
    val onBubbleFailed: Color,
    val seal: Color,
    val onSeal: Color,
    val sealSoft: Color,
    val onSealSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
    val unreadBadge: Color,
    val onUnreadBadge: Color,
    val banner: Color,
    val onBanner: Color,
    /** Background/foreground pairs for initials avatars, chosen by name hash. Neutral on purpose. */
    val avatarPalette: List<Pair<Color, Color>>,
    /** QR codes are always dark-on-light in both themes, for reliable scanning. */
    val qrForeground: Color = black,
    val qrBackground: Color = white,
)

internal val LightWhisprColors = WhisprColors(
    surface = lightSurface,
    sunken = lightSunken,
    hairline = lightHairline,
    faint = lightFaint,
    bubbleOutgoing = lightMint,
    onBubbleOutgoing = lightOnMint,
    metaOutgoing = lightMintMeta,
    bubbleIncoming = lightSurface,
    onBubbleIncoming = lightInk,
    metaIncoming = lightMuted,
    readTick = lightReadTick,
    bubbleFailed = lightDangerSoft,
    onBubbleFailed = lightOnDangerSoft,
    seal = lightSeal,
    onSeal = lightInk,
    sealSoft = lightSealSoft,
    onSealSoft = lightOnSealSoft,
    danger = lightDanger,
    dangerSoft = lightDangerSoft,
    onDangerSoft = lightOnDangerSoft,
    unreadBadge = lightSeal,
    onUnreadBadge = lightInk,
    banner = lightSunken,
    onBanner = lightMuted,
    avatarPalette = listOf(lightAvatarA to lightInk, lightAvatarB to lightInk, lightAvatarC to lightInk),
)

internal val DarkWhisprColors = WhisprColors(
    surface = darkSurface,
    sunken = darkSunken,
    hairline = darkHairline,
    faint = darkFaint,
    bubbleOutgoing = darkMint,
    onBubbleOutgoing = darkOnMint,
    metaOutgoing = darkMintMeta,
    bubbleIncoming = darkSunken,
    onBubbleIncoming = darkInk,
    metaIncoming = darkMuted,
    readTick = darkReadTick,
    bubbleFailed = darkDangerSoft,
    onBubbleFailed = darkOnDangerSoft,
    seal = darkSeal,
    onSeal = lightInk,
    sealSoft = darkSealSoft,
    onSealSoft = darkOnSealSoft,
    danger = darkDanger,
    dangerSoft = darkDangerSoft,
    onDangerSoft = darkOnDangerSoft,
    unreadBadge = darkSeal,
    onUnreadBadge = lightInk,
    banner = darkSunken,
    onBanner = darkMuted,
    avatarPalette = listOf(darkAvatarA to darkInk, darkAvatarB to darkInk, darkAvatarC to darkInk),
)

internal val LocalWhisprColors = staticCompositionLocalOf { LightWhisprColors }
