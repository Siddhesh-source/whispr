package dev.whispr.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * A QR code for [content], dark modules on a light quiet zone in both themes
 * (scanners are most reliable that way). Rendered at one pixel per module and
 * scaled without smoothing, so it stays sharp at any size.
 */
@Composable
fun QrCodeImage(content: String, contentDescription: String, modifier: Modifier = Modifier) {
    val fg = WhisprTheme.colors.qrForeground.toArgb()
    val bg = WhisprTheme.colors.qrBackground.toArgb()
    val bitmap = remember(content, fg, bg) { renderQr(content, fg, bg).asImageBitmap() }
    Box(
        modifier
            .widthIn(max = WhisprTheme.sizes.contentMaxWidth / 2)
            .aspectRatio(1f)
            .background(WhisprTheme.colors.qrBackground, MaterialTheme.shapes.large)
            .padding(WhisprTheme.spacing.lg),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
    }
}

private fun renderQr(content: String, fg: Int, bg: Int): Bitmap {
    // Quiet zone comes from the padded background above, so margin 0 here.
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels =
        IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) fg else bg }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * A prominent warning that needs a decision, e.g. a contact's safety number
 * changed. One primary action; an optional secondary one.
 */
@Composable
fun WarningCard(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = WhisprTheme.colors
    Surface(
        color = colors.dangerSoft,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
            ) {
                Icon(
                    WhisprIcons.Warning,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(WhisprTheme.sizes.icon),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.danger,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onDangerSoft)
            Row(
                Modifier.fillMaxWidth().padding(top = WhisprTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WhisprPrimaryButton(text = primaryLabel, onClick = onPrimary, fillWidth = false)
                if (secondaryLabel != null && onSecondary != null) {
                    WhisprSecondaryButton(text = secondaryLabel, onClick = onSecondary)
                }
            }
        }
    }
}

@ComponentPreviews
@Composable
private fun QrAndWarningPreview() {
    PreviewSurface {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg),
        ) {
            QrCodeImage("whispr:AQAAAAAAAAAAAAAAAAAAAAAAAA", contentDescription = "Your contact code")
            WarningCard(
                title = "Safety number changed",
                message = "Ada's safety number is different from before.",
                primaryLabel = "Accept",
                onPrimary = {},
                secondaryLabel = "Not now",
                onSecondary = {},
            )
        }
    }
}
