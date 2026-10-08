package dev.whispr.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * Replaces the composer while a voice message is being recorded, so it is
 * never ambiguous whether the microphone is live (DESIGN.md "Components"):
 * a pulsing red dot, the elapsed time, the live input level, discard, and a
 * square send that stops and sends.
 *
 * [levels] are recent input levels, 0..1, oldest first.
 */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    levels: List<Float>,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = WhisprTheme.colors
    val elapsed = formatElapsed(elapsedMs)
    val spoken = stringResource(R.string.ds_recording, elapsed)
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "rec-dot",
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(WhisprTheme.sizes.hairline, colors.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(WhisprTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    WhisprIcons.Delete,
                    contentDescription = stringResource(R.string.ds_recording_cancel),
                    tint = scheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            ) {
                Box(
                    Modifier
                        .size(WhisprTheme.spacing.sm + WhisprTheme.spacing.xxs)
                        .alpha(pulse)
                        .background(colors.danger, CircleShape),
                )
                Text(
                    elapsed,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurface,
                )
                LevelMeter(
                    levels,
                    Modifier
                        .weight(1f)
                        .height(WhisprTheme.sizes.icon),
                )
            }
            FilledIconButton(
                onClick = onSend,
                shape = MaterialTheme.shapes.small,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                modifier = Modifier
                    .padding(start = WhisprTheme.spacing.sm)
                    .size(WhisprTheme.sizes.sendButton),
            ) {
                Icon(WhisprIcons.Send, contentDescription = stringResource(R.string.ds_recording_send))
            }
        }
    }
}

/** Bars for the most recent levels, newest at the end, drawn in moss. */
@Composable
private fun LevelMeter(levels: List<Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val faint = WhisprTheme.colors.hairline
    val barWidth = WhisprTheme.spacing.xxs + WhisprTheme.spacing.xxs / 2
    Canvas(modifier) {
        val bar = barWidth.toPx()
        val gap = bar
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        val shown = levels.takeLast(count)
        val pad = count - shown.size
        for (i in 0 until count) {
            val level = if (i < pad) 0f else shown[i - pad].coerceIn(0f, 1f)
            val h = maxOf(bar, size.height * level)
            drawRoundRect(
                color = if (i < pad) faint else color,
                topLeft = Offset(i * (bar + gap), (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** m:ss, tabular. */
fun formatElapsed(ms: Long): String {
    val s = (ms / MILLIS).coerceAtLeast(0)
    return "%d:%02d".format(s / SECONDS, s % SECONDS)
}

private const val PULSE_MIN_ALPHA = 0.25f
private const val PULSE_MS = 700
private const val MILLIS = 1000L
private const val SECONDS = 60L

@ComponentPreviews
@Composable
private fun RecordingBarPreview() {
    PreviewSurface {
        RecordingBar(
            elapsedMs = 7_400,
            levels = List(40) { i -> ((i * 37) % 10) / 10f },
            onCancel = {},
            onSend = {},
            modifier = Modifier.padding(WhisprTheme.spacing.md),
        )
    }
}
