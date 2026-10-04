package dev.whispr.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import dev.whispr.core.designsystem.component.BubbleDirection
import dev.whispr.core.designsystem.component.BubbleGroupPosition
import dev.whispr.core.designsystem.component.ChatListRow
import dev.whispr.core.designsystem.component.DeliveryStatus
import dev.whispr.core.designsystem.component.EmptyState
import dev.whispr.core.designsystem.component.ErrorState
import dev.whispr.core.designsystem.component.LoadingState
import dev.whispr.core.designsystem.component.MessageBubble
import dev.whispr.core.designsystem.component.MessageInputBar
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.component.WhisprTopBar
import dev.whispr.core.designsystem.theme.WhisprTheme

/*
 * Review aids for the design system. Used by the debug catalog in :app and by
 * screenshot tests. Not used by product screens.
 */

/** Color roles and the type scale. */
@Composable
fun ThemeShowcase(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val w = WhisprTheme.colors
    Column(
        modifier.padding(WhisprTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
    ) {
        SectionTitle("Color")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Swatch("primary", c.primary, c.onPrimary)
            Swatch("primaryContainer", c.primaryContainer, c.onPrimaryContainer)
            Swatch("secondaryContainer", c.secondaryContainer, c.onSecondaryContainer)
            Swatch("tertiaryContainer", c.tertiaryContainer, c.onTertiaryContainer)
            Swatch("surface", c.surface, c.onSurface)
            Swatch("surfaceContainer", c.surfaceContainer, c.onSurface)
            Swatch("surfaceContainerHigh", c.surfaceContainerHigh, c.onSurfaceVariant)
            Swatch("error", c.error, c.onError)
            Swatch("errorContainer", c.errorContainer, c.onErrorContainer)
            Swatch("bubbleOutgoing", w.bubbleOutgoing, w.onBubbleOutgoing)
            Swatch("bubbleIncoming", w.bubbleIncoming, w.onBubbleIncoming)
            Swatch("banner", w.banner, w.onBanner)
        }
        SectionTitle("Type")
        val t = MaterialTheme.typography
        TypeSample("headlineSmall", t.headlineSmall)
        TypeSample("titleLarge", t.titleLarge)
        TypeSample("titleMedium", t.titleMedium)
        TypeSample("bodyLarge (messages)", t.bodyLarge)
        TypeSample("bodyMedium", t.bodyMedium)
        TypeSample("labelLarge", t.labelLarge)
        TypeSample("labelSmall", t.labelSmall)
        SectionTitle("Spacing")
        val s = WhisprTheme.spacing
        listOf(
            "xs" to s.xs,
            "sm" to s.sm,
            "md" to s.md,
            "lg" to s.lg,
            "xl" to s.xl,
            "xxl" to s.xxl,
        ).forEach { (name, dp) ->
            SpacingSample(name, dp)
        }
    }
}

/** Every component in its common states. */
@Composable
fun ComponentCatalog(modifier: Modifier = Modifier) {
    val spacing = WhisprTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        WhisprTopBar(title = "Chats")
        WhisprTopBar(title = "Ada Lovelace", onNavigateBack = {})
        OfflineBanner(visible = true)

        Column(Modifier.padding(horizontal = spacing.lg)) { SectionTitle("Avatar") }
        Row(
            Modifier.padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WhisprAvatar("", size = WhisprTheme.sizes.avatarSmall)
            WhisprAvatar("Ada Lovelace", size = WhisprTheme.sizes.avatarSmall)
            WhisprAvatar("Grace Hopper")
            WhisprAvatar("Alan Turing", size = WhisprTheme.sizes.avatarLarge)
        }

        Column(Modifier.padding(horizontal = spacing.lg)) { SectionTitle("Chat list row") }
        Column {
            ChatListRow("Ada Lovelace", "See you at the lake on Saturday!", "10:42", onClick = {}, unreadCount = 2)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ChatListRow("Grace Hopper", "Thanks, that worked.", "Yesterday", onClick = {})
        }

        Column(Modifier.padding(horizontal = spacing.lg)) { SectionTitle("Message bubble") }
        Column(Modifier.padding(horizontal = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            MessageBubble(
                "Hey! Did you get the photos?",
                "10:41",
                BubbleDirection.Incoming,
                groupPosition = BubbleGroupPosition.First,
            )
            MessageBubble(
                "The lake ones came out great.",
                "10:41",
                BubbleDirection.Incoming,
                groupPosition = BubbleGroupPosition.Last,
            )
            MessageBubble("Yes, thank you!", "10:42", BubbleDirection.Outgoing, status = DeliveryStatus.Delivered)
            MessageBubble(
                "This one failed.",
                "10:44",
                BubbleDirection.Outgoing,
                status = DeliveryStatus.Failed,
                onRetry = {},
            )
        }

        Column(Modifier.padding(horizontal = spacing.lg)) { SectionTitle("Input bar") }
        MessageInputBar(value = "", onValueChange = {}, onSend = {})

        Column(Modifier.padding(horizontal = spacing.lg)) {
            SectionTitle("Primary button")
            WhisprPrimaryButton("Continue", onClick = {})
        }

        Column(Modifier.padding(horizontal = spacing.lg)) { SectionTitle("States") }
        Box(Modifier.fillMaxWidth().height(WhisprTheme.sizes.contentMaxWidth / 2)) {
            EmptyState(title = "No conversations yet", message = "Scan a friend's QR code to start a private chat.")
        }
        Box(Modifier.fillMaxWidth().height(WhisprTheme.sizes.contentMaxWidth / 2)) {
            ErrorState(title = "Couldn't load chats", message = "Check your connection and try again.", onRetry = {})
        }
        Box(Modifier.fillMaxWidth().height(WhisprTheme.sizes.contentMaxWidth / 4)) { LoadingState() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Swatch(name: String, background: Color, foreground: Color) {
    Box(
        Modifier
            .width(WhisprTheme.sizes.avatarXLarge + WhisprTheme.sizes.avatarLarge)
            .background(background, MaterialTheme.shapes.medium)
            .border(
                WhisprTheme.sizes.progressStroke / 3,
                MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .padding(WhisprTheme.spacing.md),
    ) {
        Text(name, color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TypeSample(name: String, style: TextStyle) {
    Text("$name — Private by default", style = style, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SpacingSample(name: String, size: Dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(WhisprTheme.sizes.avatarMedium),
        )
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary))
    }
}
