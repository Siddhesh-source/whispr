package dev.whispr.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * Text entry with one primary action: send. A flat field with a hairline
 * border and 12dp corners, and a square ink send button (DESIGN.md
 * "Components"). Send is disabled until there is non-blank text. Enter
 * inserts a newline; sending is always an explicit tap.
 *
 * The caller applies imePadding()/navigationBarsPadding() at screen level.
 */
@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = stringResource(R.string.ds_input_placeholder),
    /** Inside the bar, before the field (e.g. attach). */
    leading: (@Composable () -> Unit)? = null,
    /** Inside the bar, after the field, shown only while the field is empty (e.g. record). */
    trailingWhenEmpty: (@Composable () -> Unit)? = null,
) {
    val canSend = enabled && value.isNotBlank()
    val fieldLabel = stringResource(R.string.ds_input_label)
    val scheme = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    Surface(
        shape = MaterialTheme.shapes.large,
        color = WhisprTheme.colors.surface,
        border = BorderStroke(WhisprTheme.sizes.hairline, WhisprTheme.colors.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(WhisprTheme.spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            leading?.invoke()
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = WhisprTheme.sizes.sendButton)
                    .padding(
                        start = if (leading == null) WhisprTheme.spacing.md else WhisprTheme.spacing.xxs,
                        end = WhisprTheme.spacing.sm,
                        top = WhisprTheme.spacing.md,
                        bottom = WhisprTheme.spacing.md,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = text, color = scheme.onSurfaceVariant)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    maxLines = MAX_INPUT_LINES,
                    textStyle = text.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel },
                )
            }
            if (value.isEmpty()) trailingWhenEmpty?.invoke()
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                shape = MaterialTheme.shapes.small,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = WhisprTheme.colors.sunken,
                    disabledContentColor = WhisprTheme.colors.faint,
                ),
                modifier = Modifier.size(WhisprTheme.sizes.sendButton),
            ) {
                Icon(WhisprIcons.Send, contentDescription = stringResource(R.string.ds_send_message))
            }
        }
    }
}

private const val MAX_INPUT_LINES = 6

@ComponentPreviews
@Composable
private fun MessageInputBarPreview() {
    PreviewSurface {
        Column(
            Modifier.padding(WhisprTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            var text by remember { mutableStateOf("") }
            MessageInputBar(value = text, onValueChange = { text = it }, onSend = {})
            MessageInputBar(value = "Running late, be there in 10", onValueChange = {}, onSend = {})
        }
    }
}
