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

/** Stable RFC 9457 problem `type` URIs (contracts/openapi.yaml, Problem.type). Clients switch on these, not on titles. */
private const val PROBLEM_PREFIX = "https://masroof.app/problems/"
private const val TYPE_IN_PROGRESS = "${PROBLEM_PREFIX}idempotency-in-progress"
private const val TYPE_KEY_REUSED = "${PROBLEM_PREFIX}idempotency-key-reused"
private const val TYPE_EXPENSE_DELETED = "${PROBLEM_PREFIX}idempotency-expense-deleted"
private const val TYPE_RATE_LIMITED = "${PROBLEM_PREFIX}rate-limited"
private const val TYPE_BLANK = "about:blank"

/** Fallback only for a response whose type is `about:blank` (older server or framework-generated). */
private const val PERMANENT_CONFLICT_TITLE = "Idempotency key reused"

/** The only two fields of an error body the app reads. The body itself is never kept or logged. */
data class ProblemInfo(
    val type: String?,
    val title: String?,
)

fun parseProblem(body: String?): ProblemInfo? =
    try {
        val obj = body?.let { Json.parseToJsonElement(it) }?.jsonObject
        obj?.let {
            ProblemInfo(
                type = it["type"]?.jsonPrimitive?.contentOrNull,
                title = it["title"]?.jsonPrimitive?.contentOrNull,
            )
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun transient(retryAfterSeconds: Long?): UploadResult =
    if (retryAfterSeconds != null && retryAfterSeconds > 0) UploadResult.RetryAfter(retryAfterSeconds) else UploadResult.Retry

/**
 * Outcome of a non-created upload response. Pure so the status table is unit tested.
 * Decided by the problem `type` first; the `title` is used only when the type is absent or `about:blank`.
 * 5xx, 408, 429 and `rate-limited` are transient ([UploadResult.Retry], or [UploadResult.RetryAfter] when the server
 * says how long) and are never capped. Only the ambiguous 409 ([UploadResult.RetryLimited]) counts toward MAX_ATTEMPTS.
 */
fun classifyUploadStatus(
    code: Int,
    successful: Boolean,
    problem: ProblemInfo? = null,
    retryAfterSeconds: Long? = null,
): UploadResult {
    val type = problem?.type?.trim()
    val untyped = type.isNullOrEmpty() || type == TYPE_BLANK
    return when {
        successful -> UploadResult.Unexpected
        code == UNAUTHORIZED -> UploadResult.Unauthorized
        type == TYPE_RATE_LIMITED -> transient(retryAfterSeconds)
        type == TYPE_KEY_REUSED || type == TYPE_EXPENSE_DELETED -> UploadResult.Rejected
        type == TYPE_IN_PROGRESS -> UploadResult.RetryLimited
        code == CONFLICT ->
            if (untyped && problem?.title == PERMANENT_CONFLICT_TITLE) UploadResult.Rejected else UploadResult.RetryLimited
        // An outage (VM reboot, proxy 502) can outlast any attempt cap: these never fail an entry.
        code in RETRYABLE || code >= SERVER_ERROR -> transient(retryAfterSeconds)
        else -> UploadResult.Rejected
    }
}

/** The SMS upload view of [classifyUploadStatus] for a non-2xx answer. */
fun classifySmsStatus(
    code: Int,
    problem: ProblemInfo? = null,
    retryAfterSeconds: Long? = null,
): SmsUploadResult =
    when (val r = classifyUploadStatus(code, false, problem, retryAfterSeconds)) {
        UploadResult.Retry -> SmsUploadResult.Retry(null)
        is UploadResult.RetryAfter -> SmsUploadResult.Retry(r.seconds)
        UploadResult.RetryLimited, UploadResult.Unexpected -> SmsUploadResult.RetryLimited
        UploadResult.Unauthorized -> SmsUploadResult.Unauthorized
        UploadResult.Rejected, is UploadResult.Created -> SmsUploadResult.Rejected
    }
