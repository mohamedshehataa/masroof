package eg.masroof.data.receipts

import eg.masroof.api.models.Attachment
import eg.masroof.data.edit.fieldOf
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.apiJson
import eg.masroof.data.remote.withDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import retrofit2.Response as RetrofitResponse

/**
 * [ReceiptRemote] over the generated `ExpensesApi` for the reads and the delete, and over the app's authenticated OkHttp client
 * for the multipart upload and the binary download: the generator renders those two as `@Part String` / `Response<String>`.
 */
@Singleton
class ApiReceiptRemote
    @Inject
    constructor(
        private val source: ApiRemoteSource,
    ) : ReceiptRemote {
        private fun <T> RetrofitResponse<T>.checked(): T {
            if (!isSuccessful) throw HttpFailure(code(), fieldOf(errorBody()?.string()))
            return body() ?: throw HttpFailure(code())
        }

        override suspend fun items(expenseId: String): List<ItemData> =
            withDeadline {
                source
                    .expensesApi()
                    .getExpense(UUID.fromString(expenseId))
                    .checked()
                    .items
                    .map { ItemData(it.name, it.qty, it.total?.amountMinor) }
            }

        override suspend fun list(expenseId: String): List<AttachmentInfo> =
            withDeadline {
                source
                    .expensesApi()
                    .listExpenseAttachments(UUID.fromString(expenseId))
                    .checked()
                    .items
                    .map { it.toInfo() }
            }

        override suspend fun upload(
            expenseId: String,
            file: File,
            contentType: String,
        ): AttachmentInfo {
            val id = UUID.fromString(expenseId)
            val call =
                source.authenticatedCall { base ->
                    // The part name is fixed and generic: the server ignores it and the original file name is never sent.
                    val part =
                        MultipartBody.Part.createFormData(
                            "file",
                            if (contentType ==
                                CONTENT_TYPE_PDF
                            ) {
                                "receipt.pdf"
                            } else {
                                "receipt.jpg"
                            },
                            file.asRequestBody(contentType.toMediaType()),
                        )
                    Request
                        .Builder()
                        .url(base.newBuilder().addPathSegments("api/expenses/$id/attachments").build())
                        .post(
                            MultipartBody
                                .Builder()
                                .setType(MultipartBody.FORM)
                                .addPart(part)
                                .build(),
                        ).build()
                }
            return call.await().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response.code)
                val text = response.body.string()
                try {
                    apiJson.decodeFromString(Attachment.serializer(), text).toInfo()
                } catch (_: SerializationException) {
                    throw HttpFailure(response.code)
                }
            }
        }

        override suspend fun download(
            expenseId: String,
            attachmentId: String,
            dest: File,
        ) {
            val e = UUID.fromString(expenseId)
            val a = UUID.fromString(attachmentId)
            val call =
                source.authenticatedCall { base ->
                    Request
                        .Builder()
                        .url(base.newBuilder().addPathSegments("api/expenses/$e/attachments/$a").build())
                        .get()
                        .build()
                }
            call.await().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response.code)
                withContext(Dispatchers.IO) {
                    var total = 0L
                    response.body.byteStream().use { input ->
                        dest.outputStream().use { out ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_DOWNLOAD_BYTES) throw IOException("Attachment larger than expected")
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
        }

        override suspend fun delete(
            expenseId: String,
            attachmentId: String,
        ) {
            val response =
                withDeadline {
                    source.expensesApi().deleteExpenseAttachment(UUID.fromString(expenseId), UUID.fromString(attachmentId))
                }
            if (!response.isSuccessful) throw HttpFailure(response.code())
        }

        private fun Attachment.toInfo() = AttachmentInfo(id.toString(), contentType.value, sizeBytes)

        private companion object {
            const val COPY_BUFFER = 16 * 1024
        }
    }

/** Suspends until the call answers; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }
