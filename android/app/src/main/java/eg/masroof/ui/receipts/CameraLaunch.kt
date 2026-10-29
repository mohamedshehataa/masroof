package eg.masroof.ui.receipts

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eg.masroof.data.camera.CameraFiles
import java.io.File

/**
 * REQ-RCPT-021. Returns the function that opens the system camera, or null when no camera app exists (the option is then hidden).
 * [onPhoto] gets the temp file and must delete it when done; a backed-out camera deletes it here and calls nothing.
 */
@Composable
fun rememberCameraLaunch(onPhoto: (File) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val available = remember { CameraFiles.available(context) }
    // The name survives process death while the camera app is in front.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val file = pending?.let { CameraFiles.fileNamed(context, it) }
            pending = null
            if (file == null) return@rememberLauncherForActivityResult
            if (taken) onPhoto(file) else file.delete()
        }
    // The camera app was disabled or removed while this screen was open: the option disappears.
    var broken by remember { mutableStateOf(false) }
    if (!available || broken) return null
    return {
        CameraFiles.clean(context, CameraFiles.STALE_MILLIS)
        val file = CameraFiles.newTarget(context)
        pending = file.name
        try {
            launcher.launch(CameraFiles.uriOf(context, file))
        } catch (_: ActivityNotFoundException) {
            file.delete()
            pending = null
            broken = true
        }
    }
}
