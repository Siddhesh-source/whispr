package dev.whispr.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * The one primary action on a screen: solid ink, 12dp corners (DESIGN.md
 * "Components"). While [loading], the button is disabled and shows progress,
 * which prevents double submission.
 */
@Composable
fun WhisprPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    val loadingLabel = stringResource(R.string.ds_loading)
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
        contentPadding = PaddingValues(horizontal = WhisprTheme.spacing.xl, vertical = WhisprTheme.spacing.md),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = WhisprTheme.sizes.buttonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = WhisprTheme.sizes.progressStroke,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(WhisprTheme.sizes.progressSmall)
                    .semantics { contentDescription = loadingLabel },
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A secondary action next to a primary one: surface with a hairline border. */
@Composable
fun WhisprSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(WhisprTheme.sizes.hairline, WhisprTheme.colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WhisprTheme.colors.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = WhisprTheme.spacing.xl, vertical = WhisprTheme.spacing.md),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = WhisprTheme.sizes.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@ComponentPreviews
@Composable
private fun PrimaryButtonPreview() {
    PreviewSurface {
        Column(
            Modifier.padding(WhisprTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.md),
        ) {
            WhisprPrimaryButton("Create my key", onClick = {})
            WhisprPrimaryButton("Create my key", onClick = {}, enabled = false)
            WhisprPrimaryButton("Create my key", onClick = {}, loading = true)
            WhisprSecondaryButton("Accept", onClick = {})
        }
    }
}
