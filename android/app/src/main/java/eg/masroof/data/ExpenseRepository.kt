package eg.masroof.data

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
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

/** A manual entry. [date] is the Cairo calendar day the user picked; the instant is derived by the repository. */
data class NewExpense(
    val amount: Money,
    val date: LocalDate,
    val categoryId: String?,
    val accountId: String?,
    val notes: String?,
)

/** New ids for rows. A seam so a test can make a write fail deterministically. */
interface IdFactory {
    fun next(): String
}

@Singleton
class UuidIdFactory
    @Inject
    constructor() : IdFactory {
        override fun next(): String = UUID.randomUUID().toString()
    }

/** Everything the screens read and write about expenses. Room only: nothing here touches the network (ADR-0009). */
interface ExpenseRepository {
    fun observeAll(): Flow<List<ExpenseEntity>>

    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    fun observeMonthTotal(): Flow<Money>

    /** The categories the server knows (budgets, edit). The phone's own defaults are not in this list. */
    fun observeCategories(): Flow<List<CategoryEntity>>

    /** Every category, archived ones too: names for the rows already saved. */
    fun observeLabelCategories(): Flow<List<CategoryEntity>>

    /** What the Add sheet offers: the phone's defaults plus any server ones, one chip per name (the default wins). */
    fun observePickerCategories(): Flow<List<CategoryEntity>>

    fun observeAccounts(): Flow<List<AccountEntity>>

    /** Saves the entry and its `MANUAL` source in one transaction; throws, with nothing written, if either insert fails. */
    suspend fun add(entry: NewExpense): String

    /** Turns entries still waiting in the old upload queue into local entries; returns how many were converted. */
    suspend fun convertQueuedEntries(): Int
}

const val CHANNEL_MANUAL = "MANUAL"

@Singleton
class DefaultExpenseRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
    ) : ExpenseRepository {
        private val converter = OutboxConverter(db, clock, ids)

        override fun observeAll() = db.expenses().observeAll()

        override fun observeRecent(limit: Int) = db.expenses().observeRecent(limit)

        // The month window is Cairo-calendar based and evaluated on subscription.
        override fun observeMonthTotal(): Flow<Money> =
            flow {
                val (from, to) = CairoTime.monthRangeMillis(clock.instant())
                emitAll(db.expenses().observeTotal(from, to).map { Money(it, Money.EGP) })
            }

        override fun observeCategories() = db.lookups().observeCategories()

        override fun observeLabelCategories() = db.lookups().observeLabelCategories()

        override fun observePickerCategories(): Flow<List<CategoryEntity>> = db.lookups().observeAllCategories().map(::oneChipPerName)

        override fun observeAccounts() = db.lookups().observeAccounts()

        override suspend fun add(entry: NewExpense): String {
            val now = clock.millis()
            val id = ids.next()
            val sourceId = ids.next()
            // Today: the save instant. Another day: Cairo noon of that day, and the time is not known (REQ-EXP-043).
            val today = entry.date == CairoTime.today(clock.instant())
            val occurredAt = if (today) now else CairoTime.noonOf(entry.date).toEpochMilli()
            db.withTransaction {
                db.expenses().insert(
                    ExpenseEntity(
                        id = id,
                        amountMinor = entry.amount.amountMinor,
                        currency = entry.amount.currency,
                        occurredAtMillis = occurredAt,
                        merchant = null,
                        categoryId = entry.categoryId,
                        accountId = entry.accountId,
                        notes = entry.notes,
                        pending = false,
                        source = CHANNEL_MANUAL,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                        timeKnown = today,
                    ),
                )
                db.sources().insert(
                    ExpenseSourceEntity(
                        id = sourceId,
                        expenseId = id,
                        channel = CHANNEL_MANUAL,
                        localSourceId = id,
                        receivedAtMillis = now,
                        senderId = null,
                        instrumentLast4 = null,
                    ),
                )
            }
            signal.changed()
            return id
        }

        override suspend fun convertQueuedEntries(): Int = converter.convertAll().also { if (it > 0) signal.changed() }

        /**
         * Two chips with the same name would confuse the user. The phone's own default wins over a server category of the
         * same English name (case-insensitive); among equals the first by sort order stays.
         */
        private fun oneChipPerName(all: List<CategoryEntity>): List<CategoryEntity> =
            all
                .sortedByDescending { it.system }
                .distinctBy { it.nameEn.trim().lowercase() }
                .sortedWith(compareBy({ it.sortOrder }, { it.nameEn }))
    }
