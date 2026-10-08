package dev.whispr.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

enum class BubbleDirection { Incoming, Outgoing }

/** Where a bubble sits in a run of consecutive messages from one sender. */
enum class BubbleGroupPosition { Single, First, Middle, Last }

enum class DeliveryStatus { Sending, Sent, Delivered, Read, Failed }

/** One emoji under a bubble: how many reacted with it, and whether we did. */
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean)

/** The quoted message above a reply. */
data class QuotePreview(val author: String, val text: String)

/**
 * One chat message. Screen readers hear a single sentence: sender, time,
 * text, and (for outgoing) delivery status. Failed outgoing messages are
 * tappable to retry; a long press (or the "Message actions" / "Add reaction"
 * accessibility actions) opens [onActions], or reacts when only [onReact] is set.
 *
 * @param time a pre-formatted, localized time string.
 * @param senderName announced for incoming messages; shown above the text when [showSender] (groups).
 * @param notice true when [text] is a system notice standing in for the message (shown in italics).
 * @param attachment an image, file or voice row shown above the text.
 * @param quote the message this one replies to.
 * @param forwarded shows a "Forwarded" label.
 * @param expiring shows a timer next to the time (a disappearing message).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    text: String,
    time: String,
    direction: BubbleDirection,
    modifier: Modifier = Modifier,
    senderName: String? = null,
    groupPosition: BubbleGroupPosition = BubbleGroupPosition.Single,
    status: DeliveryStatus? = null,
    onRetry: (() -> Unit)? = null,
    notice: Boolean = false,
    showSender: Boolean = false,
    attachment: (@Composable () -> Unit)? = null,
    reactions: List<ReactionChip> = emptyList(),
    onReact: (() -> Unit)? = null,
    onReactionClick: ((String) -> Unit)? = null,
    onActions: (() -> Unit)? = null,
    quote: QuotePreview? = null,
    forwarded: Boolean = false,
    expiring: Boolean = false,
) {
    val outgoing = direction == BubbleDirection.Outgoing
    val failed = outgoing && status == DeliveryStatus.Failed
    val colors = WhisprTheme.colors
    val container = when {
        failed -> colors.bubbleFailed
        outgoing -> colors.bubbleOutgoing
        else -> colors.bubbleIncoming
    }
    val content = when {
        failed -> colors.onBubbleFailed
        outgoing -> colors.onBubbleOutgoing
        else -> colors.onBubbleIncoming
    }

    val statusLabel = status?.takeIf { outgoing }?.let { stringResource(it.labelRes()) }
    val reactionText = reactions.joinToString { "${it.emoji} ${it.count}" }
    val forwardedLabel = stringResource(R.string.ds_forwarded)
    val quoteLabel = quote?.let { stringResource(R.string.ds_quote, it.author, it.text) }
    val expiringLabel = stringResource(R.string.ds_disappearing)
    val spoken = buildString {
        if (forwarded) append(forwardedLabel).append(". ")
        if (quoteLabel != null) append(quoteLabel).append(". ")
        append(
            if (outgoing) {
                stringResource(R.string.ds_bubble_outgoing, time, text)
            } else {
                stringResource(R.string.ds_bubble_incoming, listOfNotNull(senderName, time).joinToString(", "), text)
            },
        )
        if (statusLabel != null) append(". ").append(statusLabel)
        if (reactions.isNotEmpty()) append(". ").append(stringResource(R.string.ds_reactions, reactionText))
        if (expiring) append(". ").append(expiringLabel)
    }
    val retryLabel = stringResource(R.string.ds_retry_send)
    val reactLabel = stringResource(R.string.ds_react)
    val actionsLabel = stringResource(R.string.ds_message_actions)
    val retry = onRetry.takeIf { failed }
    val longPress = onActions ?: onReact

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * WhisprTheme.sizes.bubbleMaxWidthFraction
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = container,
                contentColor = content,
                shape = bubbleShape(direction, groupPosition),
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .then(
                        if (retry != null || longPress != null) {
                            Modifier.combinedClickable(onClick = { retry?.invoke() }, onLongClick = longPress)
                        } else {
                            Modifier
                        },
                    )
                    // With an attachment its own controls (play, open) stay separately focusable.
                    .semantics(mergeDescendants = attachment == null) {
                        contentDescription = spoken
                        if (retry != null) {
                            role = Role.Button
                            onClick(label = retryLabel) {
                                retry()
                                true
                            }
                        }
                        customActions = listOfNotNull(
                            onActions?.let {
                                CustomAccessibilityAction(actionsLabel) {
                                    it()
                                    true
                                }
                            },
                            onReact?.let {
                                CustomAccessibilityAction(reactLabel) {
                                    it()
                                    true
                                }
                            },
                        )
                    },
            ) {
                Column(
                    Modifier.padding(horizontal = WhisprTheme.spacing.md, vertical = WhisprTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs),
                ) {
                    if (showSender && !outgoing && senderName != null) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    if (forwarded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs),
                            modifier = Modifier.clearAndSetSemantics { },
                        ) {
                            Icon(
                                WhisprIcons.Forward,
                                contentDescription = null,
                                modifier = Modifier.size(WhisprTheme.sizes.iconSmall),
                            )
                            Text(
                                forwardedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                    if (quote != null) QuoteBlock(quote)
                    attachment?.invoke()
                    if (text.isNotEmpty()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = if (notice) FontStyle.Italic else null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    Row(
                        Modifier.align(Alignment.End).clearAndSetSemantics { },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
                    ) {
                        if (expiring) {
                            Icon(
                                WhisprIcons.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(WhisprTheme.sizes.iconSmall),
                            )
                        }
                        Text(
                            text = if (failed) statusLabel.orEmpty() else time,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (statusLabel != null) {
                            Icon(
                                imageVector = status.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(WhisprTheme.sizes.iconSmall),
                            )
                        }
                    }
                }
            }
            if (reactions.isNotEmpty()) ReactionRow(reactions, onReactionClick)
        }
    }
}

@Composable
private fun ReactionRow(reactions: List<ReactionChip>, onClick: ((String) -> Unit)?) {
    Row(
        Modifier.padding(top = WhisprTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
    ) {
        reactions.forEach { r ->
            val label = if (r.mine) {
                stringResource(R.string.ds_reaction_mine, r.emoji, r.count)
            } else {
                stringResource(R.string.ds_reaction, r.emoji, r.count)
            }
            val scheme = MaterialTheme.colorScheme
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (r.mine) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (r.mine) scheme.onSecondaryContainer else scheme.onSurface,
                modifier = Modifier
                    .then(if (onClick != null) Modifier.clickable { onClick(r.emoji) } else Modifier)
                    .clearAndSetSemantics {
                        contentDescription = label
                        if (onClick != null) role = Role.Button
                    },
            ) {
                Text(
                    "${r.emoji} ${r.count}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = WhisprTheme.spacing.sm,
                        vertical = WhisprTheme.spacing.xxs,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(quote: QuotePreview) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Column(Modifier.padding(horizontal = WhisprTheme.spacing.sm, vertical = WhisprTheme.spacing.xxs)) {
            Text(quote.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(quote.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A group event ("Sam added Alex"): centred, quiet, not a bubble. */
@Composable
fun SystemNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = WhisprTheme.spacing.xs),
    )
}

@Composable
private fun bubbleShape(direction: BubbleDirection, position: BubbleGroupPosition): Shape {
    val s = WhisprTheme.bubbleShapes
    return when (direction) {
        BubbleDirection.Outgoing -> when (position) {
            BubbleGroupPosition.Single -> s.outgoingSingle
            BubbleGroupPosition.First -> s.outgoingFirst
            BubbleGroupPosition.Middle -> s.outgoingMiddle
            BubbleGroupPosition.Last -> s.outgoingLast
        }
        BubbleDirection.Incoming -> when (position) {
            BubbleGroupPosition.Single -> s.incomingSingle
            BubbleGroupPosition.First -> s.incomingFirst
            BubbleGroupPosition.Middle -> s.incomingMiddle
            BubbleGroupPosition.Last -> s.incomingLast
        }
    }
}

private fun DeliveryStatus.labelRes(): Int = when (this) {
    DeliveryStatus.Sending -> R.string.ds_status_sending
    DeliveryStatus.Sent -> R.string.ds_status_sent
    DeliveryStatus.Delivered -> R.string.ds_status_delivered
    DeliveryStatus.Read -> R.string.ds_status_read
    DeliveryStatus.Failed -> R.string.ds_status_failed
}

@Composable
private fun DeliveryStatus?.icon(): ImageVector = when (this) {
    DeliveryStatus.Sending -> WhisprIcons.Pending
    DeliveryStatus.Sent -> WhisprIcons.Sent
    DeliveryStatus.Delivered, DeliveryStatus.Read -> WhisprIcons.Delivered
    DeliveryStatus.Failed, null -> WhisprIcons.Error
}

@ComponentPreviews
@Composable
private fun MessageBubblePreview() {
    PreviewSurface {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xxs),
        ) {
            MessageBubble(
                "Hey! Did you get the photos from Saturday?",
                "10:41",
                BubbleDirection.Incoming,
                senderName = "Ada",
                groupPosition = BubbleGroupPosition.First,
            )
            MessageBubble(
                "The lake ones came out great.",
                "10:41",
                BubbleDirection.Incoming,
                groupPosition = BubbleGroupPosition.Last,
            )
            MessageBubble(
                "Yes, thank you!",
                "10:42",
                BubbleDirection.Outgoing,
                groupPosition = BubbleGroupPosition.First,
                status = DeliveryStatus.Read,
            )
            MessageBubble(
                "Sending you mine now.",
                "10:42",
                BubbleDirection.Outgoing,
                groupPosition = BubbleGroupPosition.Middle,
                status = DeliveryStatus.Delivered,
            )
            MessageBubble(
                "One more.",
                "10:43",
                BubbleDirection.Outgoing,
                groupPosition = BubbleGroupPosition.Last,
                status = DeliveryStatus.Sending,
            )
            MessageBubble(
                "Saturday works.",
                "10:43",
                BubbleDirection.Incoming,
                quote = QuotePreview("You", "Are you free this weekend?"),
                forwarded = true,
                expiring = true,
            )
            MessageBubble(
                "This one failed.",
                "10:44",
                BubbleDirection.Outgoing,
                status = DeliveryStatus.Failed,
                onRetry = {},
            )
        }
    }
}
