@file:Suppress("ExifInterface") // minSdk 26: the platform class reads a stream safely; androidx.exifinterface would be a new dependency

package eg.masroof.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Longest side of an uploaded photo, in pixels. */
const val MAX_EDGE_PX = 2048
private const val JPEG_QUALITY = 85
private const val COPY_BUFFER = 16 * 1024
private const val DIR = "receipts"

/**
 * The largest power-of-two `inSampleSize` that still leaves the long edge at [maxEdge] or more, so the decoder never holds
 * more than about four times the target in memory; the final exact scale happens on the smaller bitmap.
 */
fun sampleSizeFor(
    width: Int,
    height: Int,
    maxEdge: Int = MAX_EDGE_PX,
): Int {
    var sample = 1
    val longEdge = maxOf(width, height)
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** The size that fits [maxEdge] on the long side, keeping the aspect ratio (integer maths); unchanged when already small. */
fun fitWithin(
    width: Int,
    height: Int,
    maxEdge: Int = MAX_EDGE_PX,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val w = maxOf(1, (width.toLong() * maxEdge / longEdge).toInt())
    val h = maxOf(1, (height.toLong() * maxEdge / longEdge).toInt())
    return w to h
}

/** The EXIF orientation as the matrix that makes the pixels upright, or null when they already are. */
internal fun orientationMatrix(orientation: Int): Matrix? {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f)
            m.postScale(-1f, 1f)
        }
        else -> return null
    }
    return m
}

/** Decodes [file] with a bounded `inSampleSize` so a huge image cannot exhaust memory; null when it is not an image. */
fun decodeBounded(
    file: File,
    maxEdge: Int = MAX_EDGE_PX,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge) }
    return BitmapFactory.decodeFile(file.path, options)
}

@Singleton
class AndroidReceiptFiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ReceiptFiles {
        private val dir: File
            get() = File(context.cacheDir, DIR).also { it.mkdirs() }

        override fun newCameraTarget(): Uri {
            val file = File(dir, "camera-${UUID.randomUUID()}.jpg")
            return FileProvider.getUriForFile(context, "${context.packageName}.receipts", file)
        }

        /** The file behind a camera target URI (we created it, so the name is ours). */
        private fun cameraFile(uri: Uri): File? =
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.startsWith("camera-") }
                ?.let { File(dir, it) }

        override suspend fun prepareImage(source: Uri): Prepared =
            withContext(Dispatchers.IO) {
                val camera = if (source.authority == "${context.packageName}.receipts") cameraFile(source) else null
                try {
                    val out = File(dir, "upload-${UUID.randomUUID()}.jpg")
                    if (encode(source, camera, out)) Prepared.Ready(out, CONTENT_TYPE_JPEG) else Prepared.Unreadable.also { out.delete() }
                } catch (_: IOException) {
                    Prepared.Unreadable
                } catch (_: SecurityException) {
                    Prepared.Unreadable
                } finally {
                    camera?.delete()
                }
            }

        private fun open(
            source: Uri,
            camera: File?,
        ) = if (camera !=
            null
        ) {
            camera.inputStream()
        } else {
            context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open")
        }

        private fun encode(
            source: Uri,
            camera: File?,
            out: File,
        ): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(source, camera).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val orientation =
                open(source, camera).use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
            val decoded = open(source, camera).use { BitmapFactory.decodeStream(it, null, options) } ?: return false
            val (w, h) = fitWithin(decoded.width, decoded.height)
            val scaled = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
            val upright =
                orientationMatrix(orientation)?.let { Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, it, true) } ?: scaled
            try {
                out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            } finally {
                if (upright !== scaled) upright.recycle()
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
            return true
        }

        override suspend fun preparePdf(source: Uri): Prepared =
            withContext(Dispatchers.IO) {
                val out = File(dir, "upload-${UUID.randomUUID()}.pdf")
                try {
                    val input = context.contentResolver.openInputStream(source) ?: return@withContext Prepared.Unreadable
                    val tooLarge =
                        input.use { stream ->
                            out.outputStream().use { sink -> copyAtMost(stream, sink, MAX_UPLOAD_BYTES) }
                        }
                    if (tooLarge) {
                        out.delete()
                        Prepared.TooLarge
                    } else {
                        Prepared.Ready(out, CONTENT_TYPE_PDF)
                    }
                } catch (_: IOException) {
                    out.delete()
                    Prepared.Unreadable
                } catch (_: SecurityException) {
                    out.delete()
                    Prepared.Unreadable
                }
            }

        override fun newTempFile(suffix: String) = File(dir, "view-${UUID.randomUUID()}$suffix")

        override fun cleanUp() {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

/** Copies [input] to [sink] and returns true as soon as more than [limit] bytes were seen (the copy is then incomplete). */
internal fun copyAtMost(
    input: java.io.InputStream,
    sink: java.io.OutputStream,
    limit: Long,
): Boolean {
    val buffer = ByteArray(COPY_BUFFER)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return false
        total += n
        if (total > limit) return true
        sink.write(buffer, 0, n)
    }
}
