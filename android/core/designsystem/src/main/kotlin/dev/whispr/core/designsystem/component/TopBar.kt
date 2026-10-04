package dev.whispr.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.whispr.core.designsystem.R
import dev.whispr.core.designsystem.icon.WhisprIcons

/**
 * The standard top bar. The title is marked as a heading so screen-reader
 * users can jump to it. Pass [onNavigateBack] to show a back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisprTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(WhisprIcons.Back, contentDescription = stringResource(R.string.ds_navigate_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@ComponentPreviews
@Composable
private fun TopBarPreview() {
    PreviewSurface {
        Column {
            WhisprTopBar(title = "Chats", actions = {
                IconButton(onClick = {}) { Icon(WhisprIcons.Settings, contentDescription = "Settings") }
            })
            WhisprTopBar(title = "Ada Lovelace", onNavigateBack = {})
        }
    }
}
