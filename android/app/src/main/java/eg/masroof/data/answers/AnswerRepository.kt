package eg.masroof.data.answers

import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.R
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The name of an automatically created Cash account, in the app language. */
fun interface CashAccountLabel {
    fun label(): String
}

/** Everything the "Needs an answer" cards read and write. Room and the answers store only; there is no network. */
interface AnswerRepository {
    fun observeCards(): Flow<List<AnswerCard>>

    val ignoredCards: Flow<List<String>>
    val atmChoice: Flow<AtmChoice?>

    /** "Pick another": the purchases a refund may reverse. */
    suspend fun pickList(refundId: String): List<PurchaseOption>

    suspend fun linkRefund(
        refundId: String,
        purchaseId: String,
    ): AnswerUndo?

    suspend fun notRefund(entryId: String): AnswerUndo?

    suspend fun transferToOwn(
        entryId: String,
        accountId: String,
    ): AnswerUndo?

    suspend fun transferToOther(entryId: String): AnswerUndo?

    suspend fun ignoreCard(last4: String): AnswerUndo?

    suspend fun linkCard(
        last4: String,
        accountId: String,
    ): AnswerUndo?

    suspend fun trackCash(entryId: String): AnswerUndo?

    suspend fun countCash(entryId: String): AnswerUndo?

    /** Settings: Track as cash / Count as spending / Ask me. */
    suspend fun setAtmChoice(choice: AtmChoice?)

    suspend fun restoreIgnored(last4: String)

    /** Puts back exactly what the answer changed. */
    suspend fun undo(token: AnswerUndo): Boolean

    /** The other side of a linked transfer pair, deleted or not (Undo of a delete restores it with the entry). */
    suspend fun pairPartnerId(entryId: String): String?

    /** The other side of a linked transfer pair that is still there, for "Delete both?". */
    suspend fun livePartnerOf(entryId: String): String?
}

private const val CONFIRMED = "CONFIRMED"

@Suppress("TooManyFunctions", "LongParameterList") // one function per answer; one seam per concern
@Singleton
class DefaultAnswerRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val prefs: AnswerPrefs,
        private val settings: SettingsRepository,
        private val clock: Clock,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
        private val eraseLock: EraseLock,
        private val cashLabel: CashAccountLabel,
    ) : AnswerRepository {
        override fun observeCards(): Flow<List<AnswerCard>> =
            combine(
                db.expenses().observeLiveSince(AnswerCards.lookbackStart(clock.millis())),
                db.lookups().observeAccounts(),
                prefs.state,
                settings.settings.map { it.includeCashWithdrawals }.distinctUntilChanged(),
            ) { entries, accounts, state, includeCash ->
                AnswerCards.generate(entries, accounts, state, includeCash, clock.millis())
            }

        override val ignoredCards: Flow<List<String>> = prefs.state.map { it.ignoredCards.sorted() }
        override val atmChoice: Flow<AtmChoice?> = prefs.state.map { it.atmChoice }

        override suspend fun pickList(refundId: String): List<PurchaseOption> {
            val refund = db.expenses().find(refundId)?.takeIf { it.deletedAtMillis == null } ?: return emptyList()
            return AnswerCards.pickList(refund, db.expenses().liveSince(AnswerCards.lookbackStart(clock.millis())))
        }

        override suspend fun linkRefund(
            refundId: String,
            purchaseId: String,
        ): AnswerUndo? =
            answer {
                val refund = live(refundId)
                val purchase = live(purchaseId)
                if (refund == null || purchase == null || refund.type !in refundable || refund.currency != purchase.currency) {
                    return@answer null
                }
                db.expenses().update(
                    refund.copy(
                        type = ExpenseTypes.REFUND,
                        categoryId = purchase.categoryId ?: refund.categoryId,
                        status = CONFIRMED,
                        updatedAtMillis = clock.millis(),
                    ),
                )
                AnswerUndo(
                    restore = listOf(refund),
                    added = AnswerState(answered = setOf(refundId), links = setOf(EntryLink(LinkKind.REFUND, refundId, purchaseId))),
                )
            }

        override suspend fun notRefund(entryId: String): AnswerUndo? =
            answer {
                val entry = live(entryId) ?: return@answer null
                if (entry.type !in refundable) return@answer null
                // Received money that is not a reversal is income; an INCOME stays as it is.
                val restore =
                    if (entry.type == ExpenseTypes.REFUND) {
                        db.expenses().update(entry.copy(type = ExpenseTypes.INCOME, status = CONFIRMED, updatedAtMillis = clock.millis()))
                        listOf(entry)
                    } else {
                        emptyList()
                    }
                AnswerUndo(restore = restore, added = AnswerState(answered = setOf(entryId)))
            }

        override suspend fun transferToOwn(
            entryId: String,
            accountId: String,
        ): AnswerUndo? {
            val state = prefs.current()
            return answer {
                val entry = live(entryId) ?: return@answer null
                val accounts = db.accountBalances().accounts()
                val target = accounts.firstOrNull { it.id == accountId && !it.archived && it.currency == entry.currency }
                val outgoing = entry.type == ExpenseTypes.TRANSFER
                if (target == null || (!outgoing && entry.type != ExpenseTypes.INCOME)) return@answer null
                val now = clock.millis()
                val live = db.expenses().liveSince(AnswerCards.lookbackStart(now))
                val own = AnswerCards.ownerOf(accounts, entry)
                val ownLabel = accounts.firstOrNull { it.id == own }?.label
                val partner = AnswerCards.counterpart(entry, accountId, live, accounts, state, outgoing)
                val restore = mutableListOf<ExpenseEntity>()
                val inserted = mutableListOf<String>()
                val partnerId: String
                if (partner != null) {
                    restore += partner
                    partnerId = partner.id
                    val next =
                        if (outgoing) {
                            partner.copy(type = ExpenseTypes.TRANSFER_IN, accountId = accountId)
                        } else {
                            partner.copy(accountId = accountId)
                        }
                    db.expenses().update(next.copy(status = CONFIRMED, updatedAtMillis = now))
                } else {
                    val type = if (outgoing) ExpenseTypes.TRANSFER_IN else ExpenseTypes.TRANSFER
                    partnerId = AnswerWrites.insertPairSide(db, ids, entry, type, accountId, ownLabel, now)
                    inserted += partnerId
                }
                if (!outgoing) {
                    restore += entry
                    db.expenses().update(
                        entry.copy(
                            type = ExpenseTypes.TRANSFER_IN,
                            accountId = entry.accountId ?: own,
                            status = CONFIRMED,
                            updatedAtMillis = now,
                        ),
                    )
                }
                val link =
                    if (outgoing) EntryLink(LinkKind.TRANSFER, entryId, partnerId) else EntryLink(LinkKind.TRANSFER, partnerId, entryId)
                AnswerUndo(
                    restore = restore,
                    deleteExpenses = inserted,
                    added = AnswerState(answered = setOf(entryId, partnerId), links = setOf(link)),
                )
            }
        }

        override suspend fun transferToOther(entryId: String): AnswerUndo? =
            answer {
                live(entryId) ?: return@answer null
                AnswerUndo(added = AnswerState(answered = setOf(entryId)))
            }

        override suspend fun ignoreCard(last4: String): AnswerUndo? =
            if (!isLast4(last4)) null else answer { AnswerUndo(added = AnswerState(ignoredCards = setOf(last4))) }

        override suspend fun linkCard(
            last4: String,
            accountId: String,
        ): AnswerUndo? =
            if (!isLast4(last4)) {
                null
            } else {
                answer {
                    val account = db.accountBalances().accounts().firstOrNull { it.id == accountId && !it.archived } ?: return@answer null
                    val now = clock.millis()
                    // Entries already captured from this card join the account; later ones are filed by the alias at capture.
                    val rows = db.expenses().liveSince(0L).filter { it.instrumentLast4 == last4 && it.accountId == null }
                    rows.forEach { db.expenses().update(it.copy(accountId = account.id, updatedAtMillis = now)) }
                    AnswerUndo(restore = rows, added = AnswerState(aliases = mapOf(last4 to account.id)))
                }
            }

        override suspend fun trackCash(entryId: String): AnswerUndo? =
            answer {
                val entry = live(entryId)?.takeIf { it.type == ExpenseTypes.CASH_WITHDRAWAL } ?: return@answer null
                val tracked =
                    AnswerWrites.trackWithdrawal(
                        db,
                        ids,
                        entry,
                        db.accountBalances().accounts(),
                        cashLabel.label(),
                        clock.millis(),
                    )
                tracked.copy(added = tracked.added.copy(atmChoice = AtmChoice.TRACK))
            }

        override suspend fun countCash(entryId: String): AnswerUndo? {
            val before = settings.current().includeCashWithdrawals
            val undo =
                answer {
                    live(entryId)?.takeIf { it.type == ExpenseTypes.CASH_WITHDRAWAL } ?: return@answer null
                    AnswerUndo(added = AnswerState(answered = setOf(entryId), atmChoice = AtmChoice.COUNT), includeCashBefore = before)
                } ?: return null
            settings.update { it.copy(includeCashWithdrawals = true) }
            return undo
        }

        override suspend fun setAtmChoice(choice: AtmChoice?) {
            prefs.edit { it.copy(atmChoice = choice) }
            when (choice) {
                AtmChoice.TRACK -> settings.update { it.copy(includeCashWithdrawals = false) }
                AtmChoice.COUNT -> settings.update { it.copy(includeCashWithdrawals = true) }
                null -> Unit
            }
            signal.changed()
        }

        override suspend fun restoreIgnored(last4: String) {
            prefs.edit { it.copy(ignoredCards = it.ignoredCards - last4) }
            signal.changed()
        }

        override suspend fun undo(token: AnswerUndo): Boolean {
            val ok =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        token.restore.forEach { original ->
                            if (db.expenses().find(original.id) != null) db.expenses().update(original)
                        }
                        token.deleteExpenses.forEach { db.expenses().delete(it) }
                        token.deleteAccounts.forEach { db.accountBalances().deleteAccount(it) }
                        true
                    }
                }
            val a = token.added
            prefs.edit { s ->
                s.copy(
                    answered = s.answered - a.answered,
                    links = s.links - a.links,
                    ignoredCards = s.ignoredCards - a.ignoredCards,
                    aliases = s.aliases.filterNot { (k, v) -> a.aliases[k] == v },
                    atmChoice = if (a.atmChoice != null && s.atmChoice == a.atmChoice) token.atmBefore else s.atmChoice,
                )
            }
            token.includeCashBefore?.let { before -> settings.update { it.copy(includeCashWithdrawals = before) } }
            signal.changed()
            return ok
        }

        override suspend fun pairPartnerId(entryId: String): String? = prefs.current().pairPartner(entryId)

        override suspend fun livePartnerOf(entryId: String): String? {
            val partner = prefs.current().pairPartner(entryId) ?: return null
            return partner.takeIf { live(it) != null }
        }

        private suspend fun live(id: String): ExpenseEntity? = db.expenses().find(id)?.takeIf { it.deletedAtMillis == null }

        /** One Room transaction (after a running erase), then the remembered state, then the lists are told. Null = nothing to answer. */
        private suspend fun answer(block: suspend () -> AnswerUndo?): AnswerUndo? {
            val undo = eraseLock.mutex.withLock { db.withTransaction { block() } } ?: return null
            val a = undo.added
            val atmBefore = prefs.current().atmChoice
            prefs.edit { s ->
                s.copy(
                    answered = s.answered + a.answered,
                    links = s.links + a.links,
                    ignoredCards = s.ignoredCards + a.ignoredCards,
                    aliases = s.aliases + a.aliases,
                    atmChoice = a.atmChoice ?: s.atmChoice,
                )
            }
            signal.changed()
            return if (a.atmChoice != null) undo.copy(atmBefore = atmBefore) else undo
        }

        private companion object {
            val refundable = setOf(ExpenseTypes.REFUND, ExpenseTypes.INCOME)
        }
    }

/** Binds the Cash account name to the app's string resource. */
class ResourceCashAccountLabel
    @Inject
    constructor(
        @ApplicationContext private val context: android.content.Context,
    ) : CashAccountLabel {
        override fun label(): String = context.getString(R.string.answers_cash_account)
    }

/** Looks up the other side of a linked transfer pair (live or deleted). The editor deletes and restores the pair together. */
interface PairLookup {
    suspend fun partnerOf(entryId: String): String?
}

object NoPairLookup : PairLookup {
    override suspend fun partnerOf(entryId: String): String? = null
}

@Singleton
class AnswerPairLookup
    @Inject
    constructor(
        private val repository: AnswerRepository,
    ) : PairLookup {
        override suspend fun partnerOf(entryId: String): String? = repository.pairPartnerId(entryId)
    }
