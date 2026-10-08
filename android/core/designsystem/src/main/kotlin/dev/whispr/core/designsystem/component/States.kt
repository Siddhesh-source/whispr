package dev.whispr.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * Shared layout for full-screen states: an icon, a heading, a sentence, and at
 * most one action.
 */
@Composable
private fun StateLayout(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier,
    iconTint: Color,
    liveRegion: Boolean,
    action: (@Composable () -> Unit)?,
) {
    Box(modifier.fillMaxSize().padding(WhisprTheme.spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = WhisprTheme.sizes.contentMaxWidth)
                .then(if (liveRegion) Modifier.semantics { this.liveRegion = LiveRegionMode.Polite } else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(WhisprTheme.sizes.stateIllustration),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Box(Modifier.padding(top = WhisprTheme.spacing.sm)) { action() }
            }
        }
    }
}

/** Nothing to show yet, e.g. no conversations. Optionally one action. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = WhisprIcons.Chat,
    action: (@Composable () -> Unit)? = null,
) {
    StateLayout(
        icon = icon,
        title = title,
        message = message,
        modifier = modifier,
        iconTint = WhisprTheme.colors.faint,
        liveRegion = false,
        action = action,
    )
}

/** Something went wrong. Announced politely to screen readers. */
@Composable
fun ErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = stringResource(R.string.ds_retry),
) {
    StateLayout(
        icon = WhisprIcons.Error,
        title = title,
        message = message,
        modifier = modifier,
        iconTint = MaterialTheme.colorScheme.error,
        liveRegion = true,
        action = { WhisprPrimaryButton(text = retryLabel, onClick = onRetry, fillWidth = false) },
    )
}

/** Indeterminate loading. [label] is shown and announced. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = stringResource(R.string.ds_loading)) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = WhisprTheme.sizes.progressStroke,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A slim, non-blocking notice at the top of a screen. Announced politely to
 * screen readers when it appears.
 */
@Composable
fun NoticeBanner(visible: Boolean, message: String, icon: ImageVector, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        Surface(color = WhisprTheme.colors.banner, contentColor = WhisprTheme.colors.onBanner) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WhisprTheme.spacing.lg, vertical = WhisprTheme.spacing.sm)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(WhisprTheme.sizes.iconSmall))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Shown while offline. Content stays usable underneath; outgoing messages queue.
 */
@Composable
fun OfflineBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.ds_offline_banner),
) {
    NoticeBanner(visible = visible, message = message, icon = WhisprIcons.Offline, modifier = modifier)
}

@ComponentPreviews
@Composable
private fun EmptyStatePreview() {
    PreviewSurface {
        EmptyState(
            title = "No conversations yet",
            message = "Scan a friend's QR code to start a private chat.",
            action = { WhisprPrimaryButton(text = "Add contact", onClick = {}, fillWidth = false) },
        )
    }
}

@ComponentPreviews
@Composable
private fun ErrorStatePreview() {
    PreviewSurface {
        ErrorState(title = "Couldn't load chats", message = "Check your connection and try again.", onRetry = {})
    }
}

@ComponentPreviews
@Composable
private fun LoadingStatePreview() {
    PreviewSurface { LoadingState(Modifier.height(WhisprTheme.sizes.avatarXLarge * 2)) }
}

@ComponentPreviews
@Composable
private fun OfflineBannerPreview() {
    PreviewSurface { OfflineBanner(visible = true) }
}
