package eg.masroof.data

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.RemoteSource
import eg.masroof.data.remote.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why a refresh did not complete. [Offline] = unreachable; [Failed] = reachable but errored. */
sealed interface RefreshOutcome {
    data object Ok : RefreshOutcome

    data object Offline : RefreshOutcome

    data object Failed : RefreshOutcome

    /** The server answered 401: the access token is missing or expired. */
    data object SignIn : RefreshOutcome
}

data class NewExpense(
    val amount: Money,
    val occurredAtMillis: Long,
    val categoryId: String?,
    val accountId: String?,
    val notes: String?,
)

/** Result of one outbox pass; WorkManager maps [retry] to Result.retry() (exponential backoff). */
data class SyncReport(
    val uploaded: Int,
    val rejected: Int,
    val retry: Boolean,
    val unauthorized: Boolean = false,
    /** Server-requested minimum wait before the next attempt (Retry-After), if any. */
    val retryAfterSeconds: Long? = null,
)

interface ExpenseRepository {
    fun observeAll(): Flow<List<ExpenseEntity>>

    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    fun observeMonthTotal(): Flow<Money>

    fun observeCategories(): Flow<List<CategoryEntity>>

    fun observeAccounts(): Flow<List<AccountEntity>>

    fun observePendingCount(): Flow<Int>

    suspend fun refresh(): RefreshOutcome

    /** Saves locally first (Room + outbox in one transaction); upload happens in [syncOutbox]. */
    suspend fun add(entry: NewExpense): String

    suspend fun syncOutbox(): SyncReport

    /** Re-queue an entry the server rejected, e.g. after the user fixed the server side. */
    suspend fun retryFailed(id: String)

    /** Whether any queued entry can still sync (not permanently failed). */
    suspend fun hasRetryable(): Boolean = false

    /** Drop an entry that could not be synced. */
    suspend fun deleteFailed(id: String)
}

@Singleton
class DefaultExpenseRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: RemoteSource,
        private val clock: Clock,
        private val signal: LocalDataSignal = LocalDataSignal(),
    ) : ExpenseRepository {
        override fun observeAll() = db.expenses().observeAll()

        override fun observeRecent(limit: Int) = db.expenses().observeRecent(limit)

        // The month window is Cairo-calendar based and evaluated on subscription.
        override fun observeMonthTotal(): Flow<Money> =
            flow {
                val (from, to) = CairoTime.monthRangeMillis(clock.instant())
                emitAll(db.expenses().observeTotal(from, to).map { Money(it, Money.EGP) })
            }

        override fun observeCategories() = db.lookups().observeCategories()

        override fun observeAccounts() = db.lookups().observeAccounts()

        override fun observePendingCount() = db.outbox().observePendingCount()

        override suspend fun refresh(): RefreshOutcome =
            try {
                // Push anything queued first so a refresh never hides locally created rows.
                syncOutbox()
                db.expenses().replaceSynced(remote.fetchExpenses())
                db.lookups().upsertCategories(remote.fetchCategories())
                db.lookups().upsertAccounts(remote.fetchAccounts())
                RefreshOutcome.Ok
            } catch (_: IOException) {
                RefreshOutcome.Offline
            } catch (e: HttpFailure) {
                if (e.code == UNAUTHORIZED) RefreshOutcome.SignIn else RefreshOutcome.Failed
            } catch (_: kotlinx.serialization.SerializationException) {
                RefreshOutcome.Failed
            }

        override suspend fun add(entry: NewExpense): String {
            val id = UUID.randomUUID().toString()
            val now = clock.millis()
            db.enqueue(
                ExpenseEntity(
                    id = id,
                    amountMinor = entry.amount.amountMinor,
                    currency = entry.amount.currency,
                    occurredAtMillis = entry.occurredAtMillis,
                    merchant = null,
                    categoryId = entry.categoryId,
                    accountId = entry.accountId,
                    notes = entry.notes,
                    pending = true,
                    failed = false,
                ),
                OutboxEntity(
                    clientId = id,
                    amountMinor = entry.amount.amountMinor,
                    currency = entry.amount.currency,
                    occurredAtMillis = entry.occurredAtMillis,
                    categoryId = entry.categoryId,
                    accountId = entry.accountId,
                    notes = entry.notes,
                    createdAtMillis = now,
                ),
            )
            signal.changed()
            return id
        }

        // One pass at a time: refresh() and the worker both call syncOutbox, and a double POST must never happen.
        private val syncMutex = Mutex()

        override suspend fun syncOutbox(): SyncReport =
            syncMutex.withLock {
                var uploaded = 0
                var rejected = 0
                var retry = false
                var unauthorized = false
                var retryAfter: Long? = null
                for (item in db.outbox().pending()) {
                    when (val result = remote.upload(item)) {
                        is UploadResult.Created -> {
                            db.completeUpload(item.clientId, result.expense)
                            uploaded++
                        }
                        UploadResult.Retry -> {
                            db.outbox().bumpAttempts(item.clientId)
                            retry = true
                        }
                        is UploadResult.RetryAfter -> {
                            db.outbox().bumpAttempts(item.clientId)
                            retry = true
                            retryAfter = result.seconds
                        }
                        UploadResult.Unauthorized -> {
                            // Not retried by WorkManager: it needs the user to sign in again; the entry stays queued.
                            unauthorized = true
                        }
                        UploadResult.Rejected -> {
                            db.markRejected(item.clientId)
                            rejected++
                        }
                        UploadResult.Unexpected, UploadResult.RetryLimited -> {
                            // An undecodable response or a server that keeps refusing will not fix itself:
                            // give up after MAX_ATTEMPTS instead of looping.
                            if (item.attempts + 1 >= MAX_ATTEMPTS) {
                                db.markRejected(item.clientId)
                                rejected++
                            } else {
                                db.outbox().bumpAttempts(item.clientId)
                                retry = true
                            }
                        }
                    }
                    // Unreachable server or bad credentials: later items would fail the same way, and order is preserved.
                    if (retry || unauthorized) break
                }
                SyncReport(uploaded, rejected, retry, unauthorized, retryAfter)
            }

        override suspend fun hasRetryable(): Boolean = db.outbox().countRetryable() > 0

        override suspend fun retryFailed(id: String) = db.resetFailed(id)

        override suspend fun deleteFailed(id: String) {
            db.discard(id)
            signal.changed()
        }

        private companion object {
            const val UNAUTHORIZED = 401
            const val MAX_ATTEMPTS = 5
        }
    }
