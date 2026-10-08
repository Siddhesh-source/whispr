package dev.whispr.android.ui.onboarding

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.OfflineBanner
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.component.WhisprFields
import dev.whispr.core.designsystem.component.WhisprPrimaryButton
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.usecase.DisplayNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OnboardingRoute(onCompleted: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) { if (state.completed) onCompleted() }

    // System photo picker: no storage permission, user picks exactly one image.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onAvatarPicked(uri?.toString())
    }
    OnboardingScreen(
        state = state,
        onNameChange = viewModel::onNameChange,
        onPickAvatar = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSubmit = viewModel::submit,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onNameChange: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onSubmit: () -> Unit,
    avatarPreview: ImageBitmap? = rememberUriBitmap(state.avatarUri),
) {
    val spacing = WhisprTheme.spacing
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            OfflineBanner(visible = state.offline)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = WhisprTheme.sizes.contentMaxWidth)
                        .padding(horizontal = spacing.xl, vertical = spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    Text(
                        stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.onboarding_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    AvatarPicker(state, avatarPreview, onPickAvatar)
                    NameField(state, onNameChange, onSubmit)
                    state.error?.takeUnless { state.isNameError }?.let { error ->
                        Text(
                            text = errorText(error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    WhisprPrimaryButton(
                        text = stringResource(R.string.onboarding_continue),
                        onClick = onSubmit,
                        enabled = state.canSubmit,
                        loading = state.submitting,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarPicker(state: OnboardingUiState, preview: ImageBitmap?, onPickAvatar: () -> Unit) {
    val label = stringResource(
        if (state.avatarUri ==
            null
        ) {
            R.string.onboarding_avatar_add
        } else {
            R.string.onboarding_avatar_change
        },
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.sm),
    ) {
        WhisprAvatar(
            name = state.name,
            image = preview,
            size = WhisprTheme.sizes.avatarXLarge,
            contentDescription = label,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    enabled = !state.submitting,
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onPickAvatar,
                ),
        )
        Text(
            stringResource(R.string.onboarding_avatar_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NameField(state: OnboardingUiState, onNameChange: (String) -> Unit, onSubmit: () -> Unit) {
    OutlinedTextField(
        shape = WhisprFields.shape,
        colors = WhisprFields.colors(),
        value = state.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.onboarding_name_label)) },
        singleLine = true,
        enabled = !state.submitting,
        isError = state.isNameError,
        supportingText = state.error?.takeIf { state.isNameError }?.let { error -> { Text(errorText(error)) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun errorText(error: OnboardingError): String = when (error) {
    OnboardingError.NameEmpty -> stringResource(R.string.onboarding_name_empty)
    OnboardingError.NameTooLong -> stringResource(R.string.onboarding_name_too_long, DisplayNameValidator.MAX_LENGTH)
    OnboardingError.NameInvalid -> stringResource(R.string.onboarding_name_invalid)
    OnboardingError.Network -> stringResource(R.string.onboarding_error_network)
    OnboardingError.Server -> stringResource(R.string.onboarding_error_server)
    OnboardingError.Storage -> stringResource(R.string.onboarding_error_storage)
    OnboardingError.Rejected -> stringResource(R.string.onboarding_error_rejected)
}

/** Small preview of the picked image; the stored copy is processed by the data layer. */
@Composable
private fun rememberUriBitmap(uri: String?): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = PREVIEW_SAMPLE_SIZE }
                    resolver.openInputStream(it.toUri())?.use { s ->
                        BitmapFactory.decodeStream(s, null, opts)
                    }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

private const val PREVIEW_SAMPLE_SIZE = 4
