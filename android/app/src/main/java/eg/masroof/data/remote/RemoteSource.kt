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

    /** Network unreachable. Keep in the outbox and back off, with no attempt cap. */
    data object Retry : UploadResult

    /** The server answered but cannot take it now (409 concurrent key, 408/429, 5xx). Backed off; failed after MAX_ATTEMPTS. */
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
