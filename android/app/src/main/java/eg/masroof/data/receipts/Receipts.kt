package eg.masroof.data.receipts

import java.io.File

/** A receipt file attached to a transaction (metadata only; no file name is ever stored). */
data class AttachmentInfo(
    val id: String,
    val contentType: String,
    val sizeBytes: Long,
) {
    val isPdf: Boolean
        get() = contentType == CONTENT_TYPE_PDF
}

/** One purchased item as the server holds it. Money is minor units; the quantity is a decimal string, never a float. */
data class ItemData(
    val name: String,
    val qty: String?,
    val totalMinor: Long?,
) {
    // Item names and amounts are private: never printed.
    override fun toString() = "ItemData"
}

const val CONTENT_TYPE_JPEG = "image/jpeg"
const val CONTENT_TYPE_PDF = "application/pdf"

/** The server's upload ceiling (REQ-RCPT-021), checked on the phone before a PDF is sent. */
const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024

/** At most this many attachments per transaction (server limit); used to disable "Attach" without a round trip. */
const val MAX_ATTACHMENTS = 5

/** The seam over the four attachment operations and the items read, so view models are testable offline. */
interface ReceiptRemote {
    /** The expense's purchased items. @throws IOException when unreachable, `HttpFailure` on a non-2xx answer. */
    suspend fun items(expenseId: String): List<ItemData>

    suspend fun list(expenseId: String): List<AttachmentInfo>

    /** Uploads [file] (already a JPEG or a PDF within the limits). */
    suspend fun upload(
        expenseId: String,
        file: File,
        contentType: String,
    ): AttachmentInfo

    /** Streams the stored bytes into [dest]; at most [MAX_DOWNLOAD_BYTES]. */
    suspend fun download(
        expenseId: String,
        attachmentId: String,
        dest: File,
    )

    /** 404 is surfaced as `HttpFailure(404)`: the caller treats it as already removed. */
    suspend fun delete(
        expenseId: String,
        attachmentId: String,
    )
}

/** A stored file larger than this is not downloaded (the server cap is 8 MiB; a little slack for a future change). */
const val MAX_DOWNLOAD_BYTES = 9L * 1024 * 1024

/** What preparing a picked file for upload produced. */
sealed interface Prepared {
    /** A temp file in the app cache, ready to upload; deleted by the caller after the upload. */
    data class Ready(
        val file: File,
        val contentType: String,
    ) : Prepared

    data object TooLarge : Prepared

    data object Unreadable : Prepared
}

/** Everything that touches the device (content resolver, bitmaps, cache dir) so the view model stays JVM-testable. */
interface ReceiptFiles {
    /** A fresh cache file exposed through the FileProvider for the system camera to write into. */
    fun newCameraTarget(): android.net.Uri

    /** The photo the camera wrote to [cameraTarget] (or a gallery [source]): orientation applied, scaled, JPEG q85. */
    suspend fun prepareImage(source: android.net.Uri): Prepared

    /** Copies the picked PDF into the cache, refusing more than [MAX_UPLOAD_BYTES]. */
    suspend fun preparePdf(source: android.net.Uri): Prepared

    /** A new empty temp file in the cache for a download. */
    fun newTempFile(suffix: String): File

    /** Deletes the camera target and any leftover temp files from earlier runs. */
    fun cleanUp()
}
