package eg.masroof.data

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.categories.CUSTOM_ID_PREFIX
import eg.masroof.data.categories.CategoryNameKey
import eg.masroof.data.categories.filedByRule
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.MemberEntity
import eg.masroof.ingest.CaptureQueueConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
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
    /** Optional "paid by" label (ADR-0009 D1); null = not set. */
    val paidByMemberId: String? = null,
    /** A receipt that waited unattached (REQ-RCPT-023): attached in the same write as the entry, or not at all. */
    val receiptId: String? = null,
    /** "EXPENSE" (default) or "INCOME"; income is listed but never counts as spending (ADR-0009 D2). */
    val type: String = "EXPENSE",
    /**
     * Shop or payee (e.g. from a spoken line); null = none. With no [categoryId] a merchant rule may file the entry
     * (a chosen category always wins).
     */
    val merchant: String? = null,
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

    /** The household members for the "Paid by" choice and the row chip. */
    fun observeMembers(): Flow<List<MemberEntity>> = flowOf(emptyList())

    /** Saves the entry and its `MANUAL` source in one transaction; throws, with nothing written, if either insert fails. */
    suspend fun add(entry: NewExpense): String

    /** Turns entries still waiting in the old upload queue into local entries; returns how many were converted. */
    suspend fun convertQueuedEntries(): Int
}

const val CHANNEL_MANUAL = "MANUAL"

private val CURRENCY_CODE = Regex("[A-Z]{3}")

@Singleton
class DefaultExpenseRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
        private val eraseLock: EraseLock = EraseLock(),
    ) : ExpenseRepository {
        private val converter = OutboxConverter(db, clock, ids)
        private val captureConverter = CaptureQueueConverter(db, clock, ids, eraseLock)

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

        override fun observeAccounts() = db.accountBalances().observeActiveAccounts()

        override fun observeMembers() = db.lookups().observeMembers()

        override suspend fun add(entry: NewExpense): String {
            require(entry.type == "EXPENSE" || entry.type == "INCOME") { "Unknown entry type" }
            require(CURRENCY_CODE.matches(entry.amount.currency)) { "Currency must be a 3-letter ISO code" }
            val now = clock.millis()
            val id = ids.next()
            val sourceId = ids.next()
            // Today: the save instant. Another day: Cairo noon of that day, and the time is not known (REQ-EXP-043).
            val today = entry.date == CairoTime.today(clock.instant())
            val occurredAt = if (today) now else CairoTime.noonOf(entry.date).toEpochMilli()
            // Waits for a running erase or start-up recovery, so the entry lands after it and is never wiped by it.
            eraseLock.mutex.withLock {
                db.withTransaction {
                    db.expenses().insert(
                        ExpenseEntity(
                            id = id,
                            amountMinor = entry.amount.amountMinor,
                            currency = entry.amount.currency,
                            occurredAtMillis = occurredAt,
                            merchant = entry.merchant?.trim()?.takeIf { it.isNotEmpty() },
                            type = entry.type,
                            categoryId = entry.categoryId,
                            accountId = entry.accountId,
                            notes = entry.notes,
                            paidByMemberId = entry.paidByMemberId,
                            pending = false,
                            source = CHANNEL_MANUAL,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            timeKnown = today,
                        ).filedByRule(db),
                    )
                    entry.receiptId?.let { db.receipts().attachWaiting(it, id) }
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
            }
            signal.changed()
            return id
        }

        override suspend fun convertQueuedEntries(): Int {
            val converted = converter.convertAll() + captureConverter.convertAll()
            if (converted > 0) signal.changed()
            return converted
        }

        /**
         * Only a server copy of a category the phone already has (same normalised name) is dropped. A custom category is never
         * dropped, so "Fuel" under Dining out stays even though "Fuel" exists under Car.
         */
        private fun oneChipPerName(all: List<CategoryEntity>): List<CategoryEntity> {
            val systemNames = all.filter { it.system }.mapTo(HashSet()) { CategoryNameKey.of(it.nameEn) }
            // the phone's own and the user's custom categories are always kept; only server copies can be duplicates
            val own = all.filter { it.system || it.id.startsWith(CUSTOM_ID_PREFIX) }
            val servers =
                all
                    .filter { !it.system && !it.id.startsWith(CUSTOM_ID_PREFIX) && CategoryNameKey.of(it.nameEn) !in systemNames }
                    .distinctBy { CategoryNameKey.of(it.nameEn) }
            return (own + servers).sortedWith(compareBy({ it.sortOrder }, { it.nameEn }))
        }
    }
