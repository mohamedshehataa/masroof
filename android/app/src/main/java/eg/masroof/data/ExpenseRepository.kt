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
}

@Singleton
class DefaultExpenseRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: RemoteSource,
        private val clock: Clock,
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
            } catch (_: HttpFailure) {
                RefreshOutcome.Failed
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
            return id
        }

        override suspend fun syncOutbox(): SyncReport {
            var uploaded = 0
            var rejected = 0
            var retry = false
            for (item in db.outbox().pending()) {
                when (val result = remote.upload(item)) {
                    is UploadResult.Created -> {
                        // Swap the local pending row for the server's canonical row, then drop the outbox entry.
                        db.expenses().delete(item.clientId)
                        db.expenses().upsert(result.expense)
                        db.outbox().delete(item.clientId)
                        uploaded++
                    }
                    UploadResult.Retry -> {
                        db.outbox().bumpAttempts(item.clientId)
                        retry = true
                        break // server unreachable: later items would fail the same way, and order is preserved
                    }
                    UploadResult.Rejected -> {
                        db.outbox().markFailed(item.clientId)
                        rejected++
                    }
                }
            }
            return SyncReport(uploaded, rejected, retry)
        }
    }
