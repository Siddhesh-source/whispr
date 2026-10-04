package dev.whispr.core.designsystem.component

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

enum class BubbleDirection { Incoming, Outgoing }

/** Where a bubble sits in a run of consecutive messages from one sender. */
enum class BubbleGroupPosition { Single, First, Middle, Last }

enum class DeliveryStatus { Sending, Sent, Delivered, Read, Failed }

/**
 * One chat message. Screen readers hear a single sentence: sender, time,
 * text, and (for outgoing) delivery status. Failed outgoing messages are
 * tappable to retry.
 *
 * @param time a pre-formatted, localized time string.
 * @param senderName announced for incoming messages (group chats need it).
 */
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
    val spoken = buildString {
        append(
            if (outgoing) {
                stringResource(R.string.ds_bubble_outgoing, time, text)
            } else {
                stringResource(R.string.ds_bubble_incoming, listOfNotNull(senderName, time).joinToString(", "), text)
            },
        )
        if (statusLabel != null) append(". ").append(statusLabel)
    }
    val retryLabel = stringResource(R.string.ds_retry_send)
    val retry = onRetry.takeIf { failed }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * WhisprTheme.sizes.bubbleMaxWidthFraction
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                color = container,
                contentColor = content,
                shape = bubbleShape(direction, groupPosition),
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .then(if (retry != null) Modifier.clickable(onClick = retry) else Modifier)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        if (retry != null) {
                            role = Role.Button
                            onClick(label = retryLabel) {
                                retry()
                                true
                            }
                        }
                    },
            ) {
                Column(
                    Modifier.padding(horizontal = WhisprTheme.spacing.md, vertical = WhisprTheme.spacing.sm),
                ) {
                    Text(text = text, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        Modifier.align(Alignment.End).padding(top = WhisprTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.xs),
                    ) {
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
        }
    }
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
                "This one failed.",
                "10:44",
                BubbleDirection.Outgoing,
                status = DeliveryStatus.Failed,
                onRetry = {},
            )
        }
    }
}
