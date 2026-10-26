package eg.masroof.data.remote

import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity

/** Non-2xx response. The body is never kept (it may echo user data). */
class HttpFailure(
    val code: Int,
) : Exception("HTTP $code")

sealed interface UploadResult {
    data class Created(
        val expense: ExpenseEntity,
    ) : UploadResult

    /** Transient: network down, 5xx, 408/429. Keep in the outbox and back off exponentially; never given up on. */
    data object Retry : UploadResult

    /** Transient answer with a Retry-After (429/503): like [Retry], but the next attempt waits at least this long. */
    data class RetryAfter(
        val seconds: Long,
    ) : UploadResult

    /** An ambiguous non-permanent 409 (concurrent key). Backed off, and failed after MAX_ATTEMPTS. */
    data object RetryLimited : UploadResult

    /** 2xx with an unusable body, or an unexpected local error. Retried a few times, then given up on (marked failed). */
    data object Unexpected : UploadResult

    /** 401: credentials missing/expired. Retrying cannot help until the user signs in again. */
    data object Unauthorized : UploadResult

    /** Permanent: the server rejected the payload (400/422...). Retrying cannot help. */
    data object Rejected : UploadResult
}

/** Thin seam over the generated API client so repositories and the outbox are testable offline. */
interface RemoteSource {
    /** @throws java.io.IOException when unreachable, [HttpFailure] on a non-2xx response. */
    suspend fun fetchExpenses(): List<ExpenseEntity>

    suspend fun fetchCategories(): List<CategoryEntity>

    suspend fun fetchAccounts(): List<AccountEntity>

    suspend fun upload(item: OutboxEntity): UploadResult
}

sealed interface SmsUploadResult {
    /** 2xx: the server stored (or already had) the message. [expenseId] is the expense it created, when the server says so. */
    data class Sent(
        val expenseId: String? = null,
    ) : SmsUploadResult

    /** Transient: offline, 5xx, 408/429. Backed off exponentially, never failed. */
    data class Retry(
        val afterSeconds: Long?,
    ) : SmsUploadResult

    /** Ambiguous (409, undecodable 2xx): retried a few times, then failed. */
    data object RetryLimited : SmsUploadResult

    /** 401: sign in again. */
    data object Unauthorized : SmsUploadResult

    /** The server refused the payload for good (400/422). */
    data object Rejected : SmsUploadResult
}

/** Upload of one structured on-device parse to POST /api/ingest/parsed-transactions (REQ-SMS-060). */
interface ParsedRemote {
    suspend fun uploadParsed(
        deviceId: String,
        item: eg.masroof.data.db.ParsedOutboxEntity,
    ): SmsUploadResult
}

/** DELETE /api/expenses/{id} (soft delete): what "Undo" of an automatically captured expense calls. True when it is gone. */
interface ExpenseDeleter {
    suspend fun deleteExpense(id: String): Boolean
}

/** The deleter of a build or a test that has no network: an Undo that needs the API simply does not happen. */
object NoExpenseDeleter : ExpenseDeleter {
    override suspend fun deleteExpense(id: String) = false
}
