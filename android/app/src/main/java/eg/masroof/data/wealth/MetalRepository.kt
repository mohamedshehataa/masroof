package eg.masroof.data.wealth

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MetalPriceEntity
import eg.masroof.data.db.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class HoldingDraft(
    val kind: String,
    val name: String,
    val karat: Int,
)

data class LotDraft(
    val side: String,
    val quantityMilli: Long,
    val totalMinor: Long,
    val atMillis: Long,
    val expenseId: String? = null,
    val note: String? = null,
)

/** A holding as its detail screen shows it. */
data class HoldingDetail(
    val asset: AssetEntity,
    /** Newest first. */
    val lots: List<AssetLotEntity>,
    val valuation: MetalMath.Valuation,
    val goalName: String?,
    val today: LocalDate,
)

sealed interface MetalOutcome {
    data class Saved(
        val id: String,
    ) : MetalOutcome

    data object InvalidName : MetalOutcome

    data object InvalidKarat : MetalOutcome

    data object InvalidQuantity : MetalOutcome

    data object InvalidAmount : MetalOutcome

    data object TooLarge : MetalOutcome

    /** A date after today. */
    data object InvalidDate : MetalOutcome

    /** A sale of more than is held at that moment. */
    data class OnlyHold(
        val heldMilli: Long,
    ) : MetalOutcome

    data object NotFound : MetalOutcome

    /** An archived goal, or one in another currency. */
    data object NotEligible : MetalOutcome

    /** The holding already backs a goal; unlink it first. */
    data object AlreadyLinked : MetalOutcome
}

/** What the lot and price forms make of typed text. */
object MetalInput {
    private const val MAX_MILLI = 1_000_000_000L
    private const val MAX_DECIMALS = 3

    /** Grams typed as `30`, `7.5`, `0.125` (Western or Arabic-Indic digits) as milligrams; null when it is not a positive amount. */
    fun parseGrams(text: String): Long? {
        // A comma (or Arabic thousands mark) is a thousands separator as in money: 1,250 is 1250 g. The decimal mark is . or ٫.
        val typed = WealthValidation.western(text)
        val whole = typed.substringBefore('.')
        if (',' in typed && !GROUPED.matches(whole)) return null
        val t = typed.replace(",", "")
        val parts = t.split('.')
        val digits = parts.all { p -> p.all { it in '0'..'9' } }
        val ok = parts.size in 1..2 && digits && (parts[0].isNotEmpty() || parts.getOrNull(1).isNullOrEmpty().not())
        if (!ok || parts[0].length > MAX_INT_DIGITS) return null
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > MAX_DECIMALS) return null
        val milli = parts[0].ifEmpty { "0" }.toLong() * MetalMath.MILLI_PER_GRAM + fraction.padEnd(MAX_DECIMALS, '0').toLong()
        return milli.takeIf { it in 1..MAX_MILLI }
    }

    private const val MAX_INT_DIGITS = 7
    private val GROUPED = Regex("""\d{1,3}(,\d{3})+""")

    fun parseMoney(text: String): Long? = (MoneyFormat.parseEntry(text) as? MoneyFormat.EntryAmount.Valid)?.minor

    enum class Field { NAME, GRAMS, AMOUNT, DATE }

    /** A lot form: grams and amount must be positive, a blank date is [today], a date after today is wrong. */
    fun lot(
        side: String,
        grams: String,
        amount: String,
        date: String,
        today: LocalDate,
        expenseId: String? = null,
        note: String? = null,
    ): Checked<LotDraft, Field> {
        val errors = mutableSetOf<Field>()
        val milli = parseGrams(grams)
        if (milli == null) errors += Field.GRAMS
        val total = parseMoney(amount)
        if (total == null) errors += Field.AMOUNT
        val day = if (date.isBlank()) today else WealthValidation.parseDate(date)
        if (day == null || day.isAfter(today)) errors += Field.DATE
        if (errors.isNotEmpty()) return Checked(null, errors)
        if (milli == null || total == null || day == null) return Checked(null, errors)
        return Checked(LotDraft(side, milli, total, CairoTime.noonOf(day).toEpochMilli(), expenseId, note), emptySet())
    }

    /** Today's price per gram typed for one purity: blank is skipped (null), anything else must be a positive amount. */
    fun price(text: String): Long? = parseMoney(text)
}

/**
 * Gold and silver on Room only (REQ-EXP-063, REQ-AN-173, REQ-AN-174). A holding is an `asset` of kind GOLD or SILVER whose value comes
 * from its lots and the price the user typed; linking a lot to an entry only flips that entry's excludedFromTotals flag.
 */
@Singleton
class MetalRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val ids: IdFactory,
        private val clock: Clock,
        private val eraseLock: EraseLock,
        private val wealth: WealthRepository,
        private val answers: eg.masroof.data.answers.AnswerPrefs = eg.masroof.data.answers.NoAnswerPrefs,
    ) {
        fun observeHolding(id: String): Flow<HoldingDetail?> =
            combine(
                db.assets().observeById(id),
                db.assetLots().observeForAsset(id),
                db.metalPrices().observeAll(),
                db.savingsGoals().observeAll(),
            ) { asset, lots, prices, goals ->
                asset?.let {
                    val today = wealth.today()
                    HoldingDetail(
                        it,
                        lots,
                        MetalMath.valuationOf(it, lots, prices, today, CairoTime::dateOf),
                        goals.firstOrNull { g -> g.id == it.goalId }?.name,
                        today,
                    )
                }
            }

        /** Open goals in the holding's currency a holding may back. */
        fun observeLinkableGoals(): Flow<List<SavingsGoalEntity>> =
            db.savingsGoals().observeAll().map { goals ->
                // A goal may be backed by several holdings; only a holding is limited to one goal.
                goals.filter { it.archivedAtMillis == null && it.currency == Money.EGP }
            }

        fun observePrices(): Flow<List<MetalPriceEntity>> = db.metalPrices().observeAll()

        suspend fun entryCandidates(): List<ExpenseEntity> = db.expenses().metalLinkCandidates(CANDIDATES)

        /** Creates the holding with its first buy in one transaction. */
        suspend fun createHolding(
            draft: HoldingDraft,
            firstLot: LotDraft,
        ): MetalOutcome =
            eraseLock.mutex.withLock {
                val problem =
                    when {
                        !MetalMath.isMetal(
                            draft.kind,
                        ) ||
                            draft.name.isBlank() ||
                            draft.name.trim().length > MAX_NAME -> MetalOutcome.InvalidName
                        draft.karat !in MetalMath.karatsOf(draft.kind) -> MetalOutcome.InvalidKarat
                        firstLot.side != MetalMath.BUY -> MetalOutcome.InvalidQuantity
                        else -> check(firstLot, emptyList())
                    }
                problem ?: db
                    .withTransaction {
                        val now = clock.millis()
                        val id = ids.next()
                        db.assets().insert(
                            AssetEntity(
                                id = id,
                                kind = draft.kind,
                                name = draft.name.trim(),
                                currency = Money.EGP,
                                valueMinor = null,
                                karat = draft.karat,
                                principalMinor = null,
                                annualRateBp = null,
                                startAtMillis = null,
                                maturityAtMillis = null,
                                goalId = null,
                                createdAtMillis = now,
                                updatedAtMillis = now,
                            ),
                        )
                        insertLot(id, firstLot)
                        MetalOutcome.Saved(id)
                    }.also { refresh() }
            }

        suspend fun addLot(
            assetId: String,
            draft: LotDraft,
        ): MetalOutcome =
            eraseLock.mutex.withLock {
                db
                    .withTransaction {
                        db.assets().find(assetId) ?: return@withTransaction MetalOutcome.NotFound
                        check(draft, db.assetLots().forAsset(assetId)) ?: insertLot(assetId, draft)
                    }.also { refresh() }
            }

        suspend fun updateLot(
            lotId: String,
            draft: LotDraft,
        ): MetalOutcome =
            eraseLock.mutex.withLock {
                db
                    .withTransaction {
                        val old = db.assetLots().find(lotId) ?: return@withTransaction MetalOutcome.NotFound
                        val others = db.assetLots().forAsset(old.assetId).filter { it.id != lotId }
                        check(draft, others)?.let { return@withTransaction it }
                        val entry =
                            if (old.expenseId == draft.expenseId) {
                                old.expenseId
                            } else {
                                old.expenseId?.let { release(it, lotId) }
                                draft.expenseId?.takeIf { claim(it) }
                            }
                        db.assetLots().update(
                            old.copy(
                                side = draft.side,
                                quantityMilli = draft.quantityMilli,
                                totalMinor = draft.totalMinor,
                                atMillis = draft.atMillis,
                                expenseId = entry,
                                note =
                                    draft.note
                                        ?.trim()
                                        ?.take(MAX_NOTE)
                                        ?.ifBlank { null },
                            ),
                        )
                        MetalOutcome.Saved(lotId)
                    }.also { refresh() }
            }

        /** Deleting a buy that a later sale depends on is refused with the grams actually held at that sale. */
        suspend fun deleteLot(lotId: String): MetalOutcome =
            eraseLock.mutex.withLock {
                db
                    .withTransaction {
                        val old = db.assetLots().find(lotId) ?: return@withTransaction MetalOutcome.NotFound
                        val rest = db.assetLots().forAsset(old.assetId).filter { it.id != lotId }
                        MetalMath.firstOversell(rest.map(MetalMath::lotOf))?.let { return@withTransaction MetalOutcome.OnlyHold(it) }
                        old.expenseId?.let { release(it, lotId) }
                        db.assetLots().delete(old)
                        MetalOutcome.Saved(lotId)
                    }.also { refresh() }
            }

        /** Undo of "excluded from spending": the lot forgets the entry and the entry counts as spending again. */
        suspend fun detachEntry(lotId: String) {
            eraseLock.mutex.withLock {
                db.withTransaction {
                    val old = db.assetLots().find(lotId) ?: return@withTransaction
                    old.expenseId?.let { release(it, lotId) }
                    db.assetLots().update(old.copy(expenseId = null))
                }
            }
        }

        /** Types today's price per gram for a purity; the older prices stay as history. */
        suspend fun setPrice(
            metal: String,
            karat: Int,
            pricePerGramMinor: Long,
        ): MetalOutcome =
            eraseLock.mutex.withLock {
                when {
                    !MetalMath.isMetal(metal) || karat !in MetalMath.karatsOf(metal) -> MetalOutcome.InvalidKarat
                    pricePerGramMinor <= 0L -> MetalOutcome.InvalidAmount
                    pricePerGramMinor > MoneyFormat.MAX_ENTRY_MINOR -> MetalOutcome.TooLarge
                    else -> {
                        val id = ids.next()
                        db.metalPrices().insert(MetalPriceEntity(id, metal, karat, pricePerGramMinor, Money.EGP, clock.millis()))
                        refresh()
                        MetalOutcome.Saved(id)
                    }
                }
            }

        /** Undo of a link change: the lot goes back to [previousExpenseId] (null = no entry) and the entries' counting follows. */
        suspend fun restoreEntry(
            lotId: String,
            previousExpenseId: String?,
        ) {
            eraseLock.mutex.withLock {
                db.withTransaction {
                    val old = db.assetLots().find(lotId) ?: return@withTransaction
                    old.expenseId?.let { release(it, lotId) }
                    val back = previousExpenseId?.takeIf { claim(it) }
                    db.assetLots().update(old.copy(expenseId = back))
                }
            }
        }

        suspend fun entryOf(lotId: String): String? = db.assetLots().find(lotId)?.expenseId

        /** Deletes the holding and its lots under the erase lock; entries only its lots linked count as spending again. */
        suspend fun deleteHolding(assetId: String) {
            eraseLock.mutex.withLock {
                val asset = db.assets().find(assetId) ?: return@withLock
                wealth.deleteAsset(asset)
            }
        }

        suspend fun linkGoal(
            assetId: String,
            goalId: String,
        ): MetalOutcome =
            eraseLock.mutex.withLock {
                db
                    .withTransaction {
                        val asset = db.assets().find(assetId) ?: return@withTransaction MetalOutcome.NotFound
                        val goal = db.savingsGoals().find(goalId) ?: return@withTransaction MetalOutcome.NotFound
                        if (goal.archivedAtMillis != null ||
                            goal.currency != asset.currency
                        ) {
                            return@withTransaction MetalOutcome.NotEligible
                        }
                        if (asset.goalId != null && asset.goalId != goal.id) return@withTransaction MetalOutcome.AlreadyLinked
                        db.assets().setGoal(assetId, goal.id, clock.millis())
                        MetalOutcome.Saved(assetId)
                    }.also { refresh() }
            }

        suspend fun unlinkGoal(assetId: String) {
            eraseLock.mutex.withLock { db.assets().setGoal(assetId, null, clock.millis()) }
            refresh()
        }

        private suspend fun refresh() {
            runCatching { wealth.snapshotToday() }
        }

        private suspend fun insertLot(
            assetId: String,
            draft: LotDraft,
        ): MetalOutcome {
            val entry = draft.expenseId?.takeIf { claim(it) }
            val id = ids.next()
            db.assetLots().insert(
                AssetLotEntity(
                    id = id,
                    assetId = assetId,
                    side = draft.side,
                    quantityMilli = draft.quantityMilli,
                    totalMinor = draft.totalMinor,
                    currency = Money.EGP,
                    atMillis = draft.atMillis,
                    expenseId = entry,
                    note =
                        draft.note
                            ?.trim()
                            ?.take(MAX_NOTE)
                            ?.ifBlank { null },
                ),
            )
            return MetalOutcome.Saved(id)
        }

        /** Excludes the entry from totals; false when the entry is gone. */
        private suspend fun claim(expenseId: String): Boolean =
            // an entry that an answer linked (transfer pair, refund) cannot be claimed: it would leave its other side netting alone
            expenseId !in answers.current().linkedIds() && db.expenses().claimExcluded(expenseId, clock.millis()) > 0

        /** The entry counts as spending again unless another lot still points at it. */
        private suspend fun release(
            expenseId: String,
            exceptLotId: String,
        ) {
            if (db.assetLots().countForExpense(expenseId, exceptLotId) == 0) db.expenses().setExcluded(expenseId, false, clock.millis())
        }

        // Validates one lot against the lots already there (never itself); null when it is fine.
        private fun check(
            draft: LotDraft,
            others: List<AssetLotEntity>,
        ): MetalOutcome? =
            when {
                draft.side != MetalMath.BUY && draft.side != MetalMath.SELL -> MetalOutcome.InvalidQuantity
                draft.quantityMilli <= 0L -> MetalOutcome.InvalidQuantity
                draft.totalMinor <= 0L -> MetalOutcome.InvalidAmount
                draft.totalMinor > MoneyFormat.MAX_ENTRY_MINOR || draft.quantityMilli > MAX_MILLI -> MetalOutcome.TooLarge
                CairoTime.dateOf(draft.atMillis).isAfter(wealth.today()) -> MetalOutcome.InvalidDate
                else -> {
                    val all =
                        others.map(MetalMath::lotOf) +
                            MetalMath.Lot("new", draft.side, draft.quantityMilli, draft.totalMinor, draft.atMillis)
                    MetalMath.firstOversell(all)?.let { MetalOutcome.OnlyHold(it) }
                }
            }

        private companion object {
            const val MAX_NAME = 60
            const val MAX_NOTE = 200
            const val MAX_MILLI = 1_000_000_000L
            const val CANDIDATES = 30
        }
    }
