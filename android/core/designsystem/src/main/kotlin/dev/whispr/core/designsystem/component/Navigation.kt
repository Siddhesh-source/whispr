package dev.whispr.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/** One destination in [WhisprBottomBar]. */
class BottomBarTab(
    val label: String,
    val icon: ImageVector,
    /** Unread count shown in the amber badge (chats only); 0 hides it. */
    val badgeCount: Int = 0,
    /** A small moss dot: something new that isn't a count (unseen statuses). */
    val dot: Boolean = false,
)

/**
 * The app's three top-level places (Chats, Status, Calls). Flat surface with
 * a hairline above it, moss for the selected icon on a mint indicator, muted
 * for the rest. The amber badge keeps its one meaning: unread messages.
 */
@Composable
fun WhisprBottomBar(tabs: List<BottomBarTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = WhisprTheme.colors
    Column(modifier) {
        HorizontalDivider(color = colors.hairline)
        NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, tab ->
                NavigationBarItem(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    icon = {
                        BadgedBox(
                            badge = {
                                when {
                                    tab.badgeCount > 0 -> Badge(
                                        containerColor = colors.unreadBadge,
                                        contentColor = colors.onUnreadBadge,
                                    ) { Text(if (tab.badgeCount > MAX_BADGE) "$MAX_BADGE+" else "${tab.badgeCount}") }
                                    tab.dot -> Badge(containerColor = scheme.primary)
                                }
                            },
                        ) { Icon(tab.icon, contentDescription = null) }
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.primary,
                        selectedTextColor = scheme.onSurface,
                        indicatorColor = scheme.primaryContainer,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * An avatar inside a status ring: moss while there is something unseen,
 * hairline once everything was viewed, none when [ring] is null. [add]
 * puts a small moss "+" badge on it (your own status, nothing posted yet).
 */
@Composable
fun StatusAvatar(
    name: String,
    ring: StatusRing?,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    size: Dp = WhisprTheme.sizes.avatarMedium,
    add: Boolean = false,
) {
    val colors = WhisprTheme.colors
    val gap = WhisprTheme.spacing.xxs
    val stroke = WhisprTheme.spacing.xxs
    val ringColor = when (ring) {
        StatusRing.Unseen -> MaterialTheme.colorScheme.primary
        StatusRing.Seen -> colors.hairline
        null -> null
    }
    Box(modifier.size(size + (gap + stroke) * 2), contentAlignment = Alignment.Center) {
        if (ringColor != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(stroke, ringColor, CircleShape),
            )
        }
        WhisprAvatar(name, image = image, size = size)
        if (add) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .border(stroke, colors.surface, CircleShape),
            ) {
                Icon(
                    WhisprIcons.Add,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(WhisprTheme.spacing.xxs / 2)
                        .size(WhisprTheme.sizes.iconSmall),
                )
            }
        }
    }
}

enum class StatusRing { Unseen, Seen }

private fun Modifier.matchParentSize() = this.then(Modifier.padding(WhisprTheme_ZERO))

private val WhisprTheme_ZERO = Dp(0f)

private const val MAX_BADGE = 99
