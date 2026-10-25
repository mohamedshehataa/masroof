package eg.masroof.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val SERVER_ERROR = 500
private const val UNAUTHORIZED = 401
private const val CONFLICT = 409
private val RETRYABLE = setOf(408, 429)

/**
 * The backend's two permanent createExpense 409s share this problem title: the Idempotency-Key was reused with a
 * different request, or the entry created with that key was deleted. The concurrent-request 409 is a plain "Conflict".
 * No machine-readable problem `type` exists yet (every problem is about:blank), so the title is the only stable field.
 */
private const val PERMANENT_CONFLICT_TITLE = "Idempotency key reused"

/** Reads only the RFC 9457 `title` of an error body; the body itself is never kept or logged. */
fun problemTitle(body: String?): String? =
    try {
        body
            ?.let { Json.parseToJsonElement(it) }
            ?.jsonObject
            ?.get("title")
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Outcome of a non-created upload response. Pure so the status table is unit tested.
 * [UploadResult.RetryLimited] = the server answered but cannot take it now (counts toward MAX_ATTEMPTS, then the
 * entry is marked failed); plain [UploadResult.Retry] is reserved for an unreachable network.
 */
fun classifyUploadStatus(
    code: Int,
    successful: Boolean,
    title: String? = null,
): UploadResult =
    when {
        successful -> UploadResult.Unexpected
        code == UNAUTHORIZED -> UploadResult.Unauthorized
        code == CONFLICT -> if (title == PERMANENT_CONFLICT_TITLE) UploadResult.Rejected else UploadResult.RetryLimited
        code in RETRYABLE -> UploadResult.RetryLimited
        code >= SERVER_ERROR -> UploadResult.RetryLimited
        else -> UploadResult.Rejected
    }
