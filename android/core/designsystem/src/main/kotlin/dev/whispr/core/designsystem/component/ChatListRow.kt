package dev.whispr.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * One conversation in the chat list. The whole row is a single touch target
 * and a single screen-reader announcement. There is deliberately no presence,
 * "typing", or "last seen" indicator: those leak metadata.
 *
 * @param time a pre-formatted, localized timestamp of the last message.
 */
@Composable
fun ChatListRow(
    name: String,
    lastMessage: String,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: ImageBitmap? = null,
    unreadCount: Int = 0,
    /** Shows the amber seal after the name: the safety number was compared. */
    verified: Boolean = false,
    /** The preview is a warning (e.g. safety number changed) and is shown in the danger color. */
    alert: Boolean = false,
) {
    val unread = unreadCount > 0
    val unreadText = if (unread) {
        pluralStringResource(R.plurals.ds_unread_messages, unreadCount, unreadCount)
    } else {
        stringResource(R.string.ds_chat_row_no_unread)
    }
    val description = stringResource(R.string.ds_chat_row_description, name, unreadText, lastMessage, time)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WhisprTheme.sizes.chatRowMinHeight)
            .clickable(onClickLabel = stringResource(R.string.ds_open_chat), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhisprAvatar(name = name, image = avatar)
        Spacer(Modifier.width(WhisprTheme.spacing.md + WhisprTheme.spacing.xxs))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (verified) {
                        Spacer(Modifier.width(WhisprTheme.spacing.xs))
                        Icon(
                            WhisprIcons.Verified,
                            contentDescription = null,
                            tint = WhisprTheme.colors.seal,
                            modifier = Modifier.size(WhisprTheme.sizes.seal),
                        )
                    }
                }
                Spacer(Modifier.width(WhisprTheme.spacing.sm))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
            Row(
                Modifier.padding(top = WhisprTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            ) {
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alert) WhisprTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) UnreadBadge(unreadCount)
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val colors = WhisprTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = WhisprTheme.sizes.unreadBadgeMin, minHeight = WhisprTheme.sizes.unreadBadgeMin)
            .background(colors.unreadBadge, CircleShape)
            .padding(horizontal = WhisprTheme.spacing.sm - WhisprTheme.spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BADGE_COUNT) stringResource(R.string.ds_unread_overflow) else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.onUnreadBadge,
        )
    }
}

/** The hairline between list rows; it starts at the text column, past the avatar. */
@Composable
fun ListDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    HorizontalDivider(
        modifier = modifier.padding(start = if (inset) WhisprTheme.sizes.listDividerInset else WhisprTheme.spacing.lg),
        thickness = WhisprTheme.sizes.hairline,
        color = WhisprTheme.colors.hairline,
    )
}

private const val MAX_BADGE_COUNT = 99

@ComponentPreviews
@Composable
private fun ChatListRowPreview() {
    PreviewSurface {
        Column {
            ChatListRow("Ada Lovelace", "See you at the lake on Saturday!", "10:42", onClick = {
            }, unreadCount = 2, verified = true)
            ListDivider()
            ChatListRow("Grace Hopper", "Safety number changed", "Yesterday", onClick = {}, alert = true)
            ListDivider()
            ChatListRow(
                "A very long display name that will not fit",
                "A long message preview that should be truncated with an ellipsis",
                "Mon",
                onClick = {},
                unreadCount = 120,
            )
        }
    }
}
