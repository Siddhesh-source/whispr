package dev.whispr.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * The one primary action on a screen. While [loading], the button is disabled
 * and shows progress, which prevents double submission.
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
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = WhisprTheme.sizes.primaryButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = WhisprTheme.sizes.progressStroke,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(WhisprTheme.sizes.progressSmall)
                    .semantics { contentDescription = loadingLabel },
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
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
            WhisprPrimaryButton("Continue", onClick = {})
            WhisprPrimaryButton("Continue", onClick = {}, enabled = false)
            WhisprPrimaryButton("Continue", onClick = {}, loading = true)
        }
    }
}
