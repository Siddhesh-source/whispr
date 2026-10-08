package dev.whispr.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * The standard top bar. The title is marked as a heading so screen-reader
 * users can jump to it. Pass [onNavigateBack] to show a back button.
 *
 * [large] is for top-level screens (Chats, Settings): the title is set in the
 * display face below the actions row. [divider] draws a hairline under the
 * bar, used where content scrolls beneath it (a conversation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisprTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    subtitle: String? = null,
    /** Small icon after the title, e.g. the verified seal. Announced via its description. */
    titleBadge: ImageVector? = null,
    titleBadgeDescription: String? = null,
    large: Boolean = false,
    divider: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    if (large) {
        Column(modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = WhisprTheme.spacing.lg,
                        end = WhisprTheme.spacing.xs,
                        top = WhisprTheme.spacing.md,
                        bottom = WhisprTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            WhisprIcons.Back,
                            contentDescription = stringResource(R.string.ds_navigate_back),
                            tint = scheme.onSurface,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            if (divider) HorizontalDivider(color = WhisprTheme.colors.hairline)
        }
        return
    }
    Column(modifier) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
                ) {
                    leading?.invoke()
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                            )
                            if (titleBadge != null) {
                                Icon(
                                    titleBadge,
                                    contentDescription = titleBadgeDescription,
                                    tint = WhisprTheme.colors.seal,
                                    modifier = Modifier.size(WhisprTheme.sizes.seal),
                                )
                            }
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(WhisprIcons.Back, contentDescription = stringResource(R.string.ds_navigate_back))
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = scheme.surface,
                scrolledContainerColor = scheme.surface,
                titleContentColor = scheme.onSurface,
                navigationIconContentColor = scheme.onSurface,
                actionIconContentColor = scheme.onSurface,
            ),
        )
        if (divider) HorizontalDivider(color = WhisprTheme.colors.hairline)
    }
}

@ComponentPreviews
@Composable
private fun TopBarPreview() {
    PreviewSurface {
        Column {
            WhisprTopBar(title = "Chats", large = true, actions = {
                IconButton(onClick = {}) { Icon(WhisprIcons.Search, contentDescription = "Search") }
                IconButton(onClick = {}) { Icon(WhisprIcons.Settings, contentDescription = "Settings") }
            })
            WhisprTopBar(
                title = "Maya Chen",
                subtitle = "Safety number verified",
                titleBadge = WhisprIcons.Verified,
                onNavigateBack = {},
                divider = true,
                leading = { WhisprAvatar("Maya Chen", size = WhisprTheme.sizes.avatarSmall) },
            )
        }
    }
}
