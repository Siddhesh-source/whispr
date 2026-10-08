package dev.whispr.android.calls

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whispr.android.R
import dev.whispr.domain.model.UserId
import kotlinx.coroutines.flow.flowOf

/**
 * Hosts the app with calls on top: the full-screen call while it rings or
 * when opened, otherwise a "Return to call" bar above the app. [content]
 * receives the function that starts a call (permissions first).
 */
@Composable
fun CallOverlay(
    viewModel: CallViewModel = hiltViewModel(),
    content: @Composable (
        startCall: (UserId, Boolean) -> Unit,
        openCallsTab: Boolean,
        onCallsTabOpened: () -> Unit,
    ) -> Unit,
) {
    val call by viewModel.call.collectAsStateWithLifecycle()
    val requests = viewModel.requests
    val showRequest by requests.show.collectAsStateWithLifecycle()
    val acceptRequest by requests.accept.collectAsStateWithLifecycle()
    val callsTab by requests.openCallsTab.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val current = call
    LaunchedEffect(current?.callId) { if (current != null) expanded = true }
    LaunchedEffect(showRequest) {
        if (showRequest) {
            expanded = true
            requests.show.value = false
        }
    }

    // Who a pending outgoing call is for, while its permission prompt is up.
    val pending = remember { arrayOfNulls<UserId>(1) }
    val startCall = rememberCallPermissions(onDenied = viewModel::report) { video, cameraAllowed ->
        pending[0]?.let { viewModel.start(it, video && cameraAllowed) }
        pending[0] = null
    }
    val accept = rememberCallPermissions(onDenied = viewModel::report) { _, _ -> viewModel.accept() }
    LaunchedEffect(acceptRequest, current?.phase) {
        if (acceptRequest && current?.phase == CallPhase.Ringing) {
            requests.accept.value = false
            accept(current.video)
        } else if (acceptRequest && current == null) {
            requests.accept.value = false
        }
    }
    KeepScreenForCall(current)

    val bar = current != null && !expanded && current.phase != CallPhase.Ringing
    Column {
        if (bar) ReturnToCallBar(current!!, onClick = { expanded = true })
        Box(Modifier.weight(1f).then(if (bar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            content(
                { peer, video ->
                    pending[0] = peer
                    startCall(video)
                },
                callsTab,
                { requests.openCallsTab.value = false },
            )
        }
    }
    if (current != null && (expanded || current.phase == CallPhase.Ringing)) {
        val media by viewModel.webRtc.media.collectAsStateWithLifecycle()
        val m = media
        val local by (m?.localVideo ?: flowOf(null)).collectAsStateWithLifecycle(null)
        val remote by (m?.remoteVideo ?: flowOf(null)).collectAsStateWithLifecycle(null)
        val mirrored by (m?.mirrored ?: flowOf(true)).collectAsStateWithLifecycle(true)
        CallScreen(
            call = current,
            video = m?.let { CallVideo(viewModel.webRtc.egl.eglBaseContext, local, remote, mirrored) },
            onAccept = { accept(current.video) },
            onDecline = viewModel::decline,
            onHangUp = viewModel::hangup,
            onMute = viewModel::setMuted,
            onSpeaker = viewModel::setSpeaker,
            onCamera = viewModel::setCamera,
            onSwitchCamera = viewModel::switchCamera,
            onMinimize = { expanded = false },
        )
    }
    val problem by viewModel.permissionProblem.collectAsStateWithLifecycle()
    problem?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::dismissProblem,
            confirmButton = {
                TextButton(onClick = viewModel::dismissProblem) { Text(stringResource(R.string.chat_error_ok)) }
            },
            text = {
                Text(
                    stringResource(
                        when (p) {
                            CallPermissionProblem.Microphone -> R.string.call_mic_denied
                            CallPermissionProblem.Camera -> R.string.call_camera_denied
                            CallPermissionProblem.AlreadyInCall -> R.string.call_busy_already
                        },
                    ),
                )
            },
        )
    }
}

/** While a call rings or runs: show over the lock screen, turn the screen on, keep it on. */
@Composable
private fun KeepScreenForCall(call: CallUi?) {
    val activity = LocalActivity.current ?: return
    val active = call != null && call.phase != CallPhase.Ended
    LaunchedEffect(active) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(active)
            activity.setTurnScreenOn(active)
        } else {
            @Suppress("DEPRECATION")
            val flags =
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (active) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
        }
        if (active) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
