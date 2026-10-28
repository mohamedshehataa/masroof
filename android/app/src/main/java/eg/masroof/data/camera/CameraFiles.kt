package eg.masroof.data.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * The camera hand-over (REQ-RCPT-021): the system camera app writes into `cache/camera/` through a non-exported [FileProvider]
 * (the URI grant goes only to the camera intent). The temp file is deleted as soon as the receipt is stored or the camera is
 * backed out of. Masroof declares no CAMERA permission: the camera app takes the photo.
 */
object CameraFiles {
    const val DIR = "camera"

    fun authority(context: Context) = "${context.packageName}.receipts.camera"

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /** An empty temp file for the camera app to fill. */
    fun newTarget(context: Context): File {
        val d = dir(context)
        d.mkdirs()
        return File(d, "${UUID.randomUUID()}.jpg").also { it.createNewFile() }
    }

    fun uriOf(
        context: Context,
        file: File,
    ): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The temp file behind a name saved across process death; only a plain name inside the camera directory resolves. */
    fun fileNamed(
        context: Context,
        name: String,
    ): File? = File(dir(context), name).takeIf { name.isNotEmpty() && name == it.name }

    /** Deletes leftovers (a camera session killed with the process). */
    fun clean(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    /** False when no app can take a picture: the Camera option is then not shown at all. */
    fun available(context: Context): Boolean =
        context.packageManager.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0).isNotEmpty()
}
