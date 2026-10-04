package dev.whispr.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import dev.whispr.core.designsystem.theme.WhisprTheme

/** Light + dark, plus light at 200% font scale to catch truncation. */
@PreviewLightDark
@Preview(name = "Font 2x", fontScale = 2f)
annotation class ComponentPreviews

/** Wraps preview content in the theme and a surface of the right color. */
@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    WhisprTheme {
        Surface(color = MaterialTheme.colorScheme.surface, content = content)
    }
}
