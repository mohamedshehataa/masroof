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

    /** Transient: network down, 5xx, 401/408/429. Keep in the outbox and back off. */
    data object Retry : UploadResult

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
