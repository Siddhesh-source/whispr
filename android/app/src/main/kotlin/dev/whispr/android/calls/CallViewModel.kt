package dev.whispr.android.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whispr.domain.model.UserId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a call couldn't start or be answered from this phone; shown in a dialog. */
enum class CallPermissionProblem { Microphone, Camera, AlreadyInCall }

/**
 * Requests that cross the app: a notification asking to show or answer the
 * call, or to open the Calls tab. The UI consumes them.
 */
@Singleton
class CallRequests @Inject constructor() {
    val show = MutableStateFlow(false)
    val accept = MutableStateFlow(false)
    val openCallsTab = MutableStateFlow(false)
}

@HiltViewModel
class CallViewModel @Inject constructor(
    private val manager: CallManager,
    val webRtc: WebRtcEnvironment,
    val requests: CallRequests,
) : ViewModel() {
    val call: StateFlow<CallUi?> = manager.call

    private val problem = MutableStateFlow<CallPermissionProblem?>(null)
    val permissionProblem: StateFlow<CallPermissionProblem?> = problem.asStateFlow()

    fun start(peer: UserId, video: Boolean) {
        if (manager.call.value.let { it != null && it.phase != CallPhase.Ended }) {
            problem.value = CallPermissionProblem.AlreadyInCall
            return
        }
        manager.startCall(peer, video)
    }

    fun accept() = manager.accept()
    fun decline() = manager.decline()
    fun hangup() = manager.hangup()
    fun setMuted(muted: Boolean) = manager.setMuted(muted)
    fun setSpeaker(on: Boolean) = manager.setSpeaker(on)
    fun setCamera(on: Boolean) = manager.setCamera(on)
    fun switchCamera() = manager.switchCamera()

    fun report(p: CallPermissionProblem) {
        problem.value = p
    }

    fun dismissProblem() {
        problem.value = null
    }
}

/**
 * Asks for the microphone (and, for video, the camera) and then runs
 * [proceed] with whether the camera may be used. A denied microphone stops
 * here with [onDenied]; a denied camera turns a video call into audio only.
 */
@Composable
fun rememberCallPermissions(
    onDenied: (CallPermissionProblem) -> Unit,
    proceed: (video: Boolean, cameraAllowed: Boolean) -> Unit,
): (video: Boolean) -> Unit {
    val context = LocalContext.current
    // Survives recomposition between the request and its result.
    val want = remember { BooleanArray(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: granted(context, Manifest.permission.RECORD_AUDIO)
        val camera = result[Manifest.permission.CAMERA] ?: granted(context, Manifest.permission.CAMERA)
        when {
            !mic -> onDenied(CallPermissionProblem.Microphone)
            want[0] && !camera -> {
                onDenied(CallPermissionProblem.Camera)
                proceed(true, false)
            }
            else -> proceed(want[0], camera)
        }
    }
    return { video ->
        want[0] = video
        val needed = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeUnless { granted(context, it) },
            Manifest.permission.CAMERA.takeIf { video && !granted(context, it) },
        )
        if (needed.isEmpty()) proceed(video, true) else launcher.launch(needed.toTypedArray())
    }
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
