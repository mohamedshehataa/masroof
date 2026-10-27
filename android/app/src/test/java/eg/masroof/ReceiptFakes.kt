package eg.masroof

import android.net.Uri
import eg.masroof.data.receipts.AttachmentInfo
import eg.masroof.data.receipts.ItemData
import eg.masroof.data.receipts.Prepared
import eg.masroof.data.receipts.ReceiptFiles
import eg.masroof.data.receipts.ReceiptRemote
import java.io.File

/** A scripted receipts server: every call is recorded; [failWith] makes the next calls throw. */
class FakeReceiptRemote : ReceiptRemote {
    var items: List<ItemData> = emptyList()
    var attachments: MutableList<AttachmentInfo> = mutableListOf()
    var itemsFailure: Throwable? = null
    var listFailure: Throwable? = null
    var uploadFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    var downloadFailure: Throwable? = null
    val uploads = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    var itemCalls = 0
    var listCalls = 0

    override suspend fun items(expenseId: String): List<ItemData> {
        itemCalls++
        itemsFailure?.let { throw it }
        return items
    }

    override suspend fun list(expenseId: String): List<AttachmentInfo> {
        listCalls++
        listFailure?.let { throw it }
        return attachments.toList()
    }

    override suspend fun upload(
        expenseId: String,
        file: File,
        contentType: String,
    ): AttachmentInfo {
        uploads += expenseId to contentType
        uploadFailure?.let { throw it }
        return AttachmentInfo("a${uploads.size}", contentType, file.length())
    }

    override suspend fun download(
        expenseId: String,
        attachmentId: String,
        dest: File,
    ) {
        downloadFailure?.let { throw it }
        dest.writeBytes(byteArrayOf(1, 2, 3))
    }

    override suspend fun delete(
        expenseId: String,
        attachmentId: String,
    ) {
        deletes += attachmentId
        deleteFailure?.let { throw it }
    }
}

/** Device seam without a device: [image] and [pdf] decide what preparing a pick returns; temp files live in [dir]. */
class FakeReceiptFiles(
    val dir: File,
) : ReceiptFiles {
    var image: Prepared? = null
    var pdf: Prepared? = null
    var cleanUps = 0

    override fun newCameraTarget(): Uri = Uri.parse("content://eg.masroof.receipts/receipts/camera-1.jpg")

    override suspend fun prepareImage(source: Uri): Prepared =
        image ?: Prepared.Ready(File(dir, "img.jpg").also { it.writeText("x") }, "image/jpeg")

    override suspend fun preparePdf(source: Uri): Prepared =
        pdf ?: Prepared.Ready(File(dir, "doc.pdf").also { it.writeText("x") }, "application/pdf")

    override fun newTempFile(suffix: String) = File(dir, "view$suffix")

    override fun cleanUp() {
        cleanUps++
    }
}
