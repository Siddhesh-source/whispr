package dev.whispr.android.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.whispr.android.R
import dev.whispr.core.designsystem.component.WhisprAvatar
import dev.whispr.core.designsystem.icon.WhisprIcons
import dev.whispr.core.designsystem.theme.WhisprTheme
import dev.whispr.domain.model.CallOutcome
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** What the call screen draws video with; absent in previews and tests. */
class CallVideo(val egl: EglBase.Context, val local: VideoTrack?, val remote: VideoTrack?, val mirrored: Boolean)

/**
 * The call, full screen and always dark (DESIGN.md "Calls"): their video or
 * their initials, the state in one line, and the controls. Ringing shows
 * only Decline and Accept.
 */
@Composable
fun CallScreen(
    call: CallUi,
    video: CallVideo?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onMinimize: () -> Unit,
) {
    val colors = WhisprTheme.colors
    val on = colors.onCallGround
    val showRemote = call.video && video?.remote != null && call.phase == CallPhase.Connected
    Box(Modifier.fillMaxSize().background(colors.callGround)) {
        if (showRemote) {
            VideoView(video!!.egl, video.remote!!, mirror = false, overlay = false, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(WhisprTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (call.phase != CallPhase.Ringing) {
                    IconButton(onClick = onMinimize) {
                        Icon(WhisprIcons.Back, contentDescription = stringResource(R.string.call_minimize), tint = on)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        WhisprIcons.Shield,
                        contentDescription = null,
                        tint = on,
                        modifier = Modifier.size(WhisprTheme.sizes.iconSmall),
                    )
                    Text(
                        stringResource(R.string.call_encrypted),
                        style = MaterialTheme.typography.labelMedium,
                        color = on,
                        modifier = Modifier.padding(start = WhisprTheme.spacing.xs),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (call.phase != CallPhase.Ringing) Spacer(Modifier.width(WhisprTheme.sizes.minTouchTarget))
            }
            Spacer(Modifier.height(WhisprTheme.spacing.xxl))
            if (!showRemote) {
                WhisprAvatar(call.peerName, size = WhisprTheme.sizes.avatarXLarge)
                Spacer(Modifier.height(WhisprTheme.spacing.lg))
            }
            Text(
                call.peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = on,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                callStatus(call),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = if (call.phase == CallPhase.Ended && call.outcome != CallOutcome.Completed) {
                    colors.danger
                } else {
                    on
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = WhisprTheme.spacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (call.problem == CallProblem.NotAllowed) {
                Text(
                    stringResource(R.string.call_not_allowed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = on,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = WhisprTheme.spacing.md),
                )
            }
            Spacer(Modifier.weight(1f))
            when (call.phase) {
                CallPhase.Ringing -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundAction(WhisprIcons.CallEnd, stringResource(R.string.call_decline), colors.danger, onDecline)
                    RoundAction(
                        if (call.video) WhisprIcons.Video else WhisprIcons.Call,
                        stringResource(R.string.call_accept),
                        MaterialTheme.colorScheme.primary,
                        onAccept,
                    )
                }
                CallPhase.Ended -> Unit
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WhisprTheme.spacing.lg)) {
                        Toggle(
                            if (call.muted) WhisprIcons.MicOff else WhisprIcons.Mic,
                            stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute),
                            active = call.muted,
                        ) { onMute(!call.muted) }
                        Toggle(
                            WhisprIcons.Speaker,
                            stringResource(if (call.speaker) R.string.call_speaker_off else R.string.call_speaker_on),
                            active = call.speaker,
                        ) { onSpeaker(!call.speaker) }
                        if (call.video) {
                            Toggle(
                                if (call.cameraOn) WhisprIcons.Video else WhisprIcons.VideoOff,
                                stringResource(
                                    if (call.cameraOn) R.string.call_camera_off else R.string.call_camera_on,
                                ),
                                active = !call.cameraOn,
                            ) { onCamera(!call.cameraOn) }
                            Toggle(
                                WhisprIcons.CameraSwitch,
                                stringResource(R.string.call_switch_camera),
                                false,
                                onSwitchCamera,
                            )
                        }
                    }
                    Spacer(Modifier.height(WhisprTheme.spacing.xl))
                    RoundAction(WhisprIcons.CallEnd, stringResource(R.string.call_hang_up), colors.danger, onHangUp)
                }
            }
            Spacer(Modifier.height(WhisprTheme.spacing.lg))
        }
        val local = video?.local
        if (call.video && call.cameraOn && local != null && call.phase != CallPhase.Ended) {
            VideoView(
                video.egl,
                local,
                mirror = video.mirrored,
                overlay = true,
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(
                        top = WhisprTheme.sizes.minTouchTarget + WhisprTheme.spacing.md,
                        end = WhisprTheme.spacing.lg,
                    )
                    .size(width = WhisprTheme.sizes.callPreviewWidth, height = WhisprTheme.sizes.callPreviewHeight)
                    .clip(MaterialTheme.shapes.large),
            )
        }
    }
}

@Composable
private fun callStatus(call: CallUi): String = when (call.phase) {
    CallPhase.Dialing -> stringResource(R.string.call_calling)
    CallPhase.Ringing -> stringResource(if (call.video) R.string.call_ringing_video else R.string.call_ringing_voice)
    CallPhase.Connecting -> stringResource(R.string.call_connecting)
    CallPhase.Connected -> {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(TICK_MS)
                now = System.currentTimeMillis()
            }
        }
        val seconds = ((now - (call.connectedAt?.toEpochMilli() ?: now)) / MS).coerceAtLeast(0)
        formatCallTime(seconds)
    }
    CallPhase.Ended -> stringResource(
        when (call.outcome) {
            CallOutcome.Declined -> R.string.call_ended_declined
            CallOutcome.Busy -> R.string.call_ended_busy
            CallOutcome.NoAnswer -> R.string.call_ended_no_answer
            CallOutcome.Missed -> R.string.call_ended_missed
            CallOutcome.Failed -> R.string.call_ended_failed
            else -> R.string.call_ended
        },
    )
}

fun formatCallTime(seconds: Long): String = if (seconds >= HOUR) {
    "%d:%02d:%02d".format(seconds / HOUR, seconds % HOUR / MINUTE, seconds % MINUTE)
} else {
    "%d:%02d".format(seconds / MINUTE, seconds % MINUTE)
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = color,
                contentColor = WhisprTheme.colors.onStatus,
            ),
            modifier = Modifier.size(WhisprTheme.sizes.callAction),
        ) { Icon(icon, contentDescription = label) }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = WhisprTheme.colors.onCallGround,
            modifier = Modifier.padding(top = WhisprTheme.spacing.sm),
        )
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = WhisprTheme.colors
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) colors.onCallGround else colors.callControl,
            contentColor = if (active) colors.callGround else colors.onCallGround,
        ),
        modifier = Modifier.size(WhisprTheme.sizes.callControl),
    ) { Icon(icon, contentDescription = label) }
}

/** A WebRTC renderer for [track], released when it leaves the screen. */
@Composable
private fun VideoView(egl: EglBase.Context, track: VideoTrack, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
    DisposableEffect(Unit) { onDispose { renderer.release() } }
    AndroidView(factory = { renderer }, update = { it.setMirror(mirror) }, modifier = modifier)
}

/** The bar shown over the app while a call continues behind it. */
@Composable
fun ReturnToCallBar(call: CallUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.call_return, call.peerName),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .statusBarsPadding()
                .padding(vertical = WhisprTheme.spacing.sm, horizontal = WhisprTheme.spacing.lg),
        )
    }
}

private const val TICK_MS = 1_000L
private const val MS = 1_000L
private const val MINUTE = 60L
private const val HOUR = 3_600L
