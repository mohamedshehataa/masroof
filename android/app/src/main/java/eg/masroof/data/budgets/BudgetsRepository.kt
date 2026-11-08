package eg.masroof.data.budgets

import android.database.SQLException
import androidx.room.withTransaction
import eg.masroof.core.Money
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetSpec
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Budgets stored on the phone (STORY-181). Room only: nothing here touches the network (ADR-0009). */
interface BudgetsRepository {
    /** The stored budgets, oldest first; emits again after every create, edit and delete. */
    fun observe(): Flow<List<BudgetEntity>>

    /** Every category, archived ones too, so a budget can tell an archived category from a live one. */
    fun observeCategories(): Flow<List<CategoryEntity>>

    /** Creates a budget; [MutationOutcome.Duplicate] when its category and period already have one (nothing is overwritten). */
    suspend fun create(draft: BudgetDraft): MutationOutcome

    /** Changes the amount and rollover only: the category and period are fixed after create. */
    suspend fun update(
        id: String,
        amountMinor: Long,
        rollover: Boolean,
    ): MutationOutcome

    suspend fun delete(id: String): MutationOutcome
}

@Singleton
class RoomBudgetsRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val eraseLock: EraseLock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : BudgetsRepository {
        override fun observe(): Flow<List<BudgetEntity>> = db.budgets().observeAll().flowOn(io)

        override fun observeCategories(): Flow<List<CategoryEntity>> = db.lookups().observeEveryCategory().flowOn(io)

        override suspend fun create(draft: BudgetDraft): MutationOutcome =
            guarded {
                if (draft.amountMinor !in 1..MAX_BUDGET_MINOR) return@guarded MutationOutcome.Invalid
                db.withTransaction {
                    val dao = db.budgets()
                    val category = draft.categoryId
                    when {
                        category != null && dao.liveCategoryCount(category) == 0 -> MutationOutcome.UnknownCategory
                        dao.countInScope(category, draft.period.name) > 0 -> MutationOutcome.Duplicate
                        else -> {
                            val now = clock.millis()
                            dao.insert(
                                BudgetEntity(
                                    id = ids.next(),
                                    categoryId = category,
                                    period = draft.period.name,
                                    amountMinor = draft.amountMinor,
                                    currency = Money.EGP,
                                    rollover = draft.rollover,
                                    createdAtMillis = now,
                                    updatedAtMillis = now,
                                ),
                            )
                            MutationOutcome.Ok
                        }
                    }
                }
            }

        override suspend fun update(
            id: String,
            amountMinor: Long,
            rollover: Boolean,
        ): MutationOutcome =
            guarded {
                if (amountMinor !in 1..MAX_BUDGET_MINOR) return@guarded MutationOutcome.Invalid
                db.withTransaction {
                    val row = db.budgets().byId(id)
                    if (row == null) {
                        MutationOutcome.NotFound
                    } else {
                        db.budgets().update(row.copy(amountMinor = amountMinor, rollover = rollover, updatedAtMillis = clock.millis()))
                        MutationOutcome.Ok
                    }
                }
            }

        override suspend fun delete(id: String): MutationOutcome =
            guarded {
                db.withTransaction {
                    val row = db.budgets().byId(id)
                    if (row == null) {
                        MutationOutcome.NotFound
                    } else {
                        db.budgets().delete(row)
                        MutationOutcome.Ok
                    }
                }
            }

        private suspend fun guarded(block: suspend () -> MutationOutcome): MutationOutcome =
            // Waits for a running erase or start-up recovery, so the write lands after it and is never half wiped.
            withContext(io) {
                try {
                    eraseLock.mutex.withLock { block() }
                } catch (
                    @Suppress("SwallowedException") _: SQLException,
                ) {
                    MutationOutcome.Failed
                }
            }
    }

/** The server's rule: a threshold is a percentage from 1 to 200, and each fires once. */
private const val MAX_THRESHOLD_PERCENT = 200

/** The calculator's view of a stored budget; null when its period is not one the app writes or its currency is not EGP. */
internal fun BudgetEntity.toSpec(): BudgetSpec? {
    val kind = BudgetPeriodKind.entries.firstOrNull { it.name == period } ?: return null
    if (currency != Money.EGP) return null
    val thresholds =
        alertThresholds
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..MAX_THRESHOLD_PERCENT }
            .distinct()
            .sorted()
            .ifEmpty { BudgetSpec.DEFAULT_THRESHOLDS }
    return BudgetSpec(id, categoryId, kind, amountMinor, rollover, thresholds, createdAtMillis)
}
