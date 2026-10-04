package dev.whispr.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme

/**
 * Text entry with one primary action: send. Send is disabled until there is
 * non-blank text. The Enter key inserts a newline; sending is always an
 * explicit tap, which avoids accidental sends.
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
) {
    val canSend = enabled && value.isNotBlank()
    val fieldLabel = stringResource(R.string.ds_input_label)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = WhisprTheme.spacing.sm, vertical = WhisprTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text(placeholder) },
                maxLines = MAX_INPUT_LINES,
                shape = MaterialTheme.shapes.extraLarge,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = WhisprTheme.sizes.inputBarMinHeight)
                    .semantics { contentDescription = fieldLabel },
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.size(WhisprTheme.sizes.inputBarMinHeight),
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
        Column(verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm)) {
            var text by remember { mutableStateOf("") }
            MessageInputBar(value = text, onValueChange = { text = it }, onSend = {})
            MessageInputBar(value = "Running late, be there in 10", onValueChange = {}, onSend = {})
        }
    }
}
