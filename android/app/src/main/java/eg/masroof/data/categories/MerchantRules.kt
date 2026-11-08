package eg.masroof.data.categories

import androidx.room.withTransaction
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MerchantRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The category a rule files this record under, or null. Run on the structured record's merchant field only (never on message
 * text) and only when no category is set: a category the user chose always wins. Never changes amount, date, type or account.
 */
internal suspend fun ExpenseEntity.filedByRule(db: AppDatabase): ExpenseEntity {
    if (categoryId != null) return this
    val key = MerchantKey.of(merchant)
    if (key.isEmpty()) return this
    val rule = db.categoryRules().ruleFor(key) ?: return this
    return copy(categoryId = rule.categoryId)
}

/** The outcome of "Always file X under Y". */
sealed interface RuleResult {
    /** [pastEntries] = live entries with this key that are not yet in the category (what "Also change them?" would touch). */
    data class Saved(
        val ruleId: String,
        val merchantKey: String,
        val pastEntries: Int,
    ) : RuleResult

    /** The merchant is empty once normalised (or has only digits): no rule. */
    data object EmptyKey : RuleResult

    data object NoCategory : RuleResult
}

/** The entries a bulk change touched and what each had before, so Undo puts it back. */
class PastChange(
    val categoryId: String,
    val previous: Map<String, String?>,
) {
    val count: Int get() = previous.size
}

/** Merchant rules on Room only (REQ-EXP-061). */
interface MerchantRuleRepository {
    fun observe(): Flow<List<MerchantRuleEntity>>

    /** Stores one rule for the merchant's key (replacing an older one) and counts the past entries it could change. */
    suspend fun addRule(
        merchant: String,
        categoryId: String,
    ): RuleResult

    /** True when a rule already files this merchant under this category (no prompt needed). */
    suspend fun hasRule(
        merchant: String,
        categoryId: String,
    ): Boolean

    /** Moves every live, not-deleted entry with this key to the category in one transaction. */
    suspend fun applyToPast(
        merchantKey: String,
        categoryId: String,
    ): PastChange

    /** Undo of [applyToPast]; entries deleted meanwhile are left alone. */
    suspend fun undo(change: PastChange)

    suspend fun delete(ruleId: String)
}

@Singleton
class DefaultMerchantRuleRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
        private val eraseLock: EraseLock,
    ) : MerchantRuleRepository {
        private val dao get() = db.categoryRules()

        override fun observe() = db.merchantRules().observeAll()

        override suspend fun addRule(
            merchant: String,
            categoryId: String,
        ): RuleResult {
            val key = MerchantKey.of(merchant)
            if (key.isEmpty()) return RuleResult.EmptyKey
            val result =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        if (dao.findCategory(categoryId) == null) {
                            RuleResult.NoCategory
                        } else {
                            val id = ids.next()
                            dao.saveRule(MerchantRuleEntity(id, key, categoryId, clock.millis()))
                            RuleResult.Saved(id, key, pastRows(key, categoryId).size)
                        }
                    }
                }
            if (result is RuleResult.Saved) signal.changed()
            return result
        }

        override suspend fun hasRule(
            merchant: String,
            categoryId: String,
        ): Boolean {
            val key = MerchantKey.of(merchant)
            return key.isNotEmpty() && dao.ruleFor(key)?.categoryId == categoryId
        }

        override suspend fun applyToPast(
            merchantKey: String,
            categoryId: String,
        ): PastChange {
            val change =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val rows = pastRows(merchantKey, categoryId)
                        val now = clock.millis()
                        rows.forEach { dao.setEntryCategory(it.id, categoryId, now) }
                        PastChange(categoryId, rows.associate { it.id to it.categoryId })
                    }
                }
            if (change.count > 0) signal.changed()
            return change
        }

        override suspend fun undo(change: PastChange) {
            eraseLock.mutex.withLock {
                db.withTransaction {
                    val now = clock.millis()
                    change.previous.forEach { (id, old) -> dao.setEntryCategory(id, old, now) }
                }
            }
            if (change.count > 0) signal.changed()
        }

        override suspend fun delete(ruleId: String) {
            eraseLock.mutex.withLock { dao.deleteRule(ruleId) }
            signal.changed()
        }

        private suspend fun pastRows(
            key: String,
            categoryId: String,
        ) = dao.liveMerchantRows().filter { it.categoryId != categoryId && MerchantKey.of(it.merchant) == key }
    }
