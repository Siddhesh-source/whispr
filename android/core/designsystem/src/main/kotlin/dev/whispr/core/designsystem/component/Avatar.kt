package dev.whispr.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import java.text.BreakIterator
import kotlin.math.absoluteValue

/**
 * A circular avatar: the user's local image if present, otherwise initials on
 * a color derived from the name, or a person icon when there is no name yet.
 * Avatars are never fetched from the network.
 *
 * @param contentDescription null when the avatar is decorative (e.g. inside a
 *   row that already announces the name).
 */
@Composable
fun WhisprAvatar(
    name: String,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    size: Dp = WhisprTheme.sizes.avatarMedium,
    contentDescription: String? = null,
) {
    val a11y = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    val boxModifier = modifier.size(size).clip(CircleShape).then(a11y)

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = boxModifier,
        )
        return
    }

    val palette = WhisprTheme.colors.avatarPalette
    val (background, foreground) = palette[name.hashCode().absoluteValue % palette.size]
    val initials = remember(name) { initialsOf(name) }
    // Initials scale with the avatar, not with the font-scale setting, so they
    // never overflow the circle. The name is still readable in adjacent text.
    val fontSize = with(LocalDensity.current) { (size * INITIALS_SIZE_RATIO).toSp() }
    Box(boxModifier.background(background), contentAlignment = Alignment.Center) {
        if (initials.isEmpty()) {
            // No name yet (e.g. onboarding): a neutral person glyph instead of a placeholder character.
            Icon(
                imageVector = WhisprIcons.Person,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(size * PLACEHOLDER_ICON_RATIO),
            )
        } else {
            Text(
                text = initials,
                color = foreground,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

private const val INITIALS_SIZE_RATIO = 0.4f
private const val PLACEHOLDER_ICON_RATIO = 0.5f

/** Up to two user-perceived characters (grapheme clusters), from the first two words. */
internal fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { firstGrapheme(it) }.uppercase()
}

private fun firstGrapheme(word: String): String {
    val it = BreakIterator.getCharacterInstance()
    it.setText(word)
    val end = it.next()
    return if (end == BreakIterator.DONE) word else word.substring(0, end)
}

@ComponentPreviews
@Composable
private fun AvatarPreview() {
    PreviewSurface {
        Row(
            Modifier.padding(WhisprTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WhisprAvatar("", size = WhisprTheme.sizes.avatarSmall)
            WhisprAvatar("Ada Lovelace", size = WhisprTheme.sizes.avatarSmall)
            WhisprAvatar("Grace Hopper")
            WhisprAvatar("Alan Turing", size = WhisprTheme.sizes.avatarLarge)
            WhisprAvatar("李小龍", size = WhisprTheme.sizes.avatarXLarge)
        }
    }
}
