package dev.whispr.android.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.File

/** Why the camera could not take a photo; always shown to the user. */
enum class CameraProblem { Denied, NoCameraApp }

/**
 * Takes one photo with the system camera into an app-private cache file and
 * hands back its file: URI (the caller sends it, then deletes it). Asks for
 * the CAMERA permission first: the app declares it (QR scanning, video
 * calls), so Android refuses the camera intent until it is granted.
 * Returns the function that starts a capture.
 */
@Composable
fun rememberCameraCapture(onPhoto: (String) -> Unit, onProblem: (CameraProblem) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Survives the activity being recreated while the camera app is in front.
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = capture?.let(::File)
        capture = null
        when {
            file == null -> Unit
            taken && file.length() > 0 -> onPhoto(Uri.fromFile(file).toString())
            else -> file.delete()
        }
    }
    val open = {
        val file = newCameraFile(context)
        capture = file.absolutePath
        try {
            takePicture.launch(cameraUri(context, file))
        } catch (_: ActivityNotFoundException) {
            capture = null
            file.delete()
            onProblem(CameraProblem.NoCameraApp)
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) open() else onProblem(CameraProblem.Denied)
    }
    return {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) open() else ask.launch(Manifest.permission.CAMERA)
    }
}
