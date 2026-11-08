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

    /** The effective ATM choice: the "Count ATM withdrawals as spending" switch on always reads as COUNT. */
    val atmChoice: Flow<AtmChoice?>

    /** Linked card tails and the account each files under (an archived account's link is not listed). */
    val cardLinks: Flow<Map<String, String>>

    /** "Pick another": the purchases a refund may reverse. */
    suspend fun pickList(refundId: String): List<PurchaseOption>

    suspend fun linkRefund(
        refundId: String,
        purchaseId: String,
    ): AnswerUndo?

    /** "Yes, a refund" for a purchase that is not in Masroof: the entry stays a refund and the card closes. */
    suspend fun confirmRefund(entryId: String): AnswerUndo?

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

    /** Settings: Track as cash / Count as spending / Ask me. One source of truth with the spending switch. */
    suspend fun setAtmChoice(choice: AtmChoice?)

    suspend fun restoreIgnored(last4: String)

    suspend fun removeCardLink(last4: String)

    /** Puts back exactly what the answer changed. */
    suspend fun undo(token: AnswerUndo): Boolean

    /** The links of an entry, for "Delete both?" and for releasing a converted entry. */
    suspend fun linksOf(entryId: String): List<EntryLink>

    suspend fun unlink(entryId: String): AnswerUndo?

    suspend fun undoUnlink(token: AnswerUndo)
}

private const val CONFIRMED = "CONFIRMED"
private const val CASH_KIND = "CASH"

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

        override val atmChoice: Flow<AtmChoice?> =
            combine(prefs.state, settings.settings.map { it.includeCashWithdrawals }.distinctUntilChanged()) { s, include ->
                when {
                    include -> AtmChoice.COUNT
                    s.atmChoice == AtmChoice.TRACK -> AtmChoice.TRACK
                    else -> null
                }
            }

        override val cardLinks: Flow<Map<String, String>> =
            combine(prefs.state, db.lookups().observeAccounts()) { s, accounts ->
                val active = accounts.filter { !it.archived }.mapTo(HashSet()) { it.id }
                s.aliases.filterValues { it in active }
            }

        override suspend fun pickList(refundId: String): List<PurchaseOption> {
            val refund = live(refundId) ?: return emptyList()
            return AnswerCards.pickList(refund, db.expenses().liveSince(AnswerCards.lookbackStart(clock.millis())))
        }

        override suspend fun linkRefund(
            refundId: String,
            purchaseId: String,
        ): AnswerUndo? =
            answer(done(refundId)) { state ->
                if (live(refundId) == null) return@answer null
                val now = clock.millis()
                val old = state.links.filter { it.involves(refundId) }.toSet()
                // a new answer makes the old link final: its converted entry is back in its original type
                val settled = AnswerWrites.settle(db, old, now)
                val refund = live(refundId)
                val purchase = live(purchaseId)
                if (refund == null || purchase == null) return@answer null
                if (refund.type !in refundable || refund.currency != purchase.currency) return@answer null
                if (purchase.type != ExpenseTypes.EXPENSE || purchase.amountMinor < refund.amountMinor) return@answer null
                db.expenses().update(
                    refund.copy(
                        type = ExpenseTypes.REFUND,
                        categoryId = purchase.categoryId ?: refund.categoryId,
                        status = CONFIRMED,
                    ),
                )
                val link =
                    EntryLink(
                        LinkKind.REFUND,
                        refundId,
                        purchaseId,
                        converted = refundId,
                        origType = refund.type,
                        origStatus = refund.status,
                        origCategoryId = refund.categoryId.orEmpty(),
                    )
                AnswerUndo(
                    added = AnswerState(answered = setOf(refundId), links = setOf(link)),
                    replacedLinks = old,
                    settledRows = settled.rows,
                    settledInserted = settled.insertedDeleted,
                )
            }

        override suspend fun confirmRefund(entryId: String): AnswerUndo? =
            answer(done(entryId)) {
                val entry = live(entryId)?.takeIf { it.type == ExpenseTypes.REFUND } ?: return@answer null
                val changed = AnswerWrites.change(db, entry, entry.copy(status = CONFIRMED))
                AnswerUndo(restore = listOf(changed), added = AnswerState(answered = setOf(entryId)))
            }

        override suspend fun notRefund(entryId: String): AnswerUndo? =
            answer(done(entryId)) {
                val entry = live(entryId) ?: return@answer null
                if (entry.type !in refundable) return@answer null
                // Received money that is not a reversal is income; an INCOME stays as it is.
                val restore =
                    if (entry.type == ExpenseTypes.REFUND) {
                        listOf(
                            AnswerWrites.change(
                                db,
                                entry,
                                entry.copy(type = ExpenseTypes.INCOME, status = CONFIRMED),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                AnswerUndo(restore = restore, added = AnswerState(answered = setOf(entryId)))
            }

        override suspend fun transferToOwn(
            entryId: String,
            accountId: String,
        ): AnswerUndo? =
            answer(done(entryId)) { state ->
                val first = live(entryId) ?: return@answer null
                val accounts = db.accountBalances().accounts()
                // The Room rows are the truth: a pair side this entry already has (the answer was cut short before the remembered
                // state was written) is adopted again, never added a second time.
                val existingSide = db.expenses().find(AnswerWrites.pairSideId(entryId))?.takeIf { it.deletedAtMillis == null }
                if (existingSide != null) return@answer adoptedPair(first, existingSide, accounts)
                val outgoing = first.type == ExpenseTypes.TRANSFER
                if (!canAnswer(first, accountId, accounts)) return@answer null
                // Both sides must be known, or one balance moves alone and net worth changes.
                val own = AnswerCards.ownerOf(accounts, first, state.aliases)
                if (own == null || own == accountId) return@answer null
                val now = clock.millis()
                val candidate =
                    AnswerCards.counterpart(
                        first,
                        accountId,
                        db.expenses().liveSince(AnswerCards.lookbackStart(now)),
                        accounts,
                        state,
                        outgoing,
                    )
                // a new answer makes every older link of these entries final (their converted entries are back in their original type)
                val old = state.links.filter { it.involves(entryId) || (candidate != null && it.involves(candidate.id)) }.toSet()
                val settled = AnswerWrites.settle(db, old, now)
                val entry = live(entryId) ?: return@answer null
                val partner = candidate?.let { live(it.id) }
                val ownLabel = accounts.firstOrNull { it.id == own }?.label
                val confirmed = entry.copy(status = CONFIRMED)
                val inserted = mutableListOf<String>()
                var converted: ExpenseEntity? = null
                var pairAccount = ""
                val partnerId: String
                if (partner != null) {
                    partnerId = partner.id
                    if (outgoing) {
                        // the other bank's own entry, attributed to the chosen account and arriving after the send
                        converted = partner
                        pairAccount = accountId
                        db.expenses().update(partner.copy(type = ExpenseTypes.TRANSFER_IN, accountId = accountId, status = CONFIRMED))
                    }
                } else {
                    val type = if (outgoing) ExpenseTypes.TRANSFER_IN else ExpenseTypes.TRANSFER
                    partnerId = AnswerWrites.insertPairSide(db, confirmed, type, accountId, ownLabel, now)
                    inserted += partnerId
                }
                if (!outgoing) {
                    converted = entry
                    pairAccount = entry.accountId ?: own
                    db.expenses().update(entry.copy(type = ExpenseTypes.TRANSFER_IN, accountId = pairAccount, status = CONFIRMED))
                }
                val (out, inn) = if (outgoing) entryId to partnerId else partnerId to entryId
                val link = transferLink(out, inn, inserted.firstOrNull().orEmpty(), converted, pairAccount)
                AnswerUndo(
                    deleteExpenses = inserted,
                    added = AnswerState(answered = setOf(entryId, partnerId), links = setOf(link)),
                    replacedLinks = old,
                    settledRows = settled.rows,
                    settledInserted = settled.insertedDeleted,
                )
            }

        /** The chosen account exists, is active and in the entry's currency; the entry is a transfer out or received money. */
        private fun canAnswer(
            entry: ExpenseEntity,
            accountId: String,
            accounts: List<eg.masroof.data.db.AccountEntity>,
        ): Boolean {
            val target = accounts.firstOrNull { it.id == accountId && !it.archived && it.currency == entry.currency }
            return target != null && (entry.type == ExpenseTypes.TRANSFER || entry.type == ExpenseTypes.INCOME)
        }

        private fun transferLink(
            out: String,
            inn: String,
            inserted: String,
            converted: ExpenseEntity?,
            pairAccount: String,
        ) = EntryLink(
            LinkKind.TRANSFER,
            out,
            inn,
            inserted = inserted,
            converted = converted?.id.orEmpty(),
            origType = converted?.let { originalTypeOf(it) }.orEmpty(),
            origAccountId = converted?.let { if (it.type == ExpenseTypes.TRANSFER_IN) "" else it.accountId }.orEmpty(),
            pairType = if (converted == null) "" else ExpenseTypes.TRANSFER_IN,
            pairAccountId = pairAccount,
            origStatus = converted?.status.orEmpty(),
        )

        /** A captured entry that is already a TRANSFER_IN was converted by an answer that lost its link: it was an INCOME. */
        private fun originalTypeOf(e: ExpenseEntity) = if (e.type == ExpenseTypes.TRANSFER_IN) ExpenseTypes.INCOME else e.type

        private fun adoptedPair(
            entry: ExpenseEntity,
            side: ExpenseEntity,
            accounts: List<eg.masroof.data.db.AccountEntity>,
        ): AnswerUndo {
            val mirror = side.type == ExpenseTypes.TRANSFER
            val cash = accounts.firstOrNull { it.id == side.accountId }?.kind == CASH_KIND
            val converted = mirror || cash
            val (out, inn) = if (mirror) side.id to entry.id else entry.id to side.id
            val link =
                EntryLink(
                    LinkKind.TRANSFER,
                    out,
                    inn,
                    inserted = side.id,
                    converted = if (converted) entry.id else "",
                    origType =
                        if (mirror) {
                            ExpenseTypes.INCOME
                        } else if (cash) {
                            ExpenseTypes.CASH_WITHDRAWAL
                        } else {
                            ""
                        },
                    origAccountId = if (mirror) "" else entry.accountId.orEmpty(),
                    pairType =
                        if (mirror) {
                            ExpenseTypes.TRANSFER_IN
                        } else if (cash) {
                            ExpenseTypes.TRANSFER
                        } else {
                            ""
                        },
                    pairAccountId = entry.accountId.orEmpty(),
                    origStatus = entry.status,
                )
            return AnswerUndo(added = AnswerState(answered = setOf(entry.id, side.id), links = setOf(link)))
        }

        override suspend fun transferToOther(entryId: String): AnswerUndo? =
            answer(done(entryId)) {
                live(entryId) ?: return@answer null
                AnswerUndo(added = AnswerState(answered = setOf(entryId)))
            }

        override suspend fun ignoreCard(last4: String): AnswerUndo? =
            if (!isLast4(last4)) {
                null
            } else {
                answer({ last4 in it.ignoredCards }) { AnswerUndo(added = AnswerState(ignoredCards = setOf(last4))) }
            }

        override suspend fun linkCard(
            last4: String,
            accountId: String,
        ): AnswerUndo? =
            if (!isLast4(last4)) {
                null
            } else {
                answer({ last4 in it.aliases }) {
                    val account = db.accountBalances().accounts().firstOrNull { it.id == accountId && !it.archived } ?: return@answer null
                    val now = clock.millis()
                    // Entries already captured from this card join the account; later ones are filed by the alias at capture.
                    val rows = db.expenses().liveSince(0L).filter { it.instrumentLast4 == last4 && it.accountId == null }
                    val changed = rows.map { AnswerWrites.change(db, it, it.copy(accountId = account.id)) }
                    AnswerUndo(restore = changed, added = AnswerState(aliases = mapOf(last4 to account.id)))
                }
            }

        override suspend fun trackCash(entryId: String): AnswerUndo? {
            val before = settings.current().includeCashWithdrawals
            val undo =
                answer(done(entryId)) {
                    val entry =
                        live(entryId)?.takeIf { it.type == ExpenseTypes.CASH_WITHDRAWAL && it.currency == "EGP" } ?: return@answer null
                    val tracked =
                        AnswerWrites.trackWithdrawal(db, ids, entry, db.accountBalances().accounts(), cashLabel.label(), clock.millis())
                    tracked.copy(added = tracked.added.copy(atmChoice = AtmChoice.TRACK), includeCashBefore = before)
                } ?: return null
            // one source of truth: Track it turns the "Count ATM withdrawals as spending" switch off
            settings.update { it.copy(includeCashWithdrawals = false) }
            return undo
        }

        override suspend fun countCash(entryId: String): AnswerUndo? {
            val before = settings.current().includeCashWithdrawals
            val undo =
                answer(done(entryId)) {
                    live(entryId)?.takeIf { it.type == ExpenseTypes.CASH_WITHDRAWAL } ?: return@answer null
                    AnswerUndo(added = AnswerState(answered = setOf(entryId), atmChoice = AtmChoice.COUNT), includeCashBefore = before)
                } ?: return null
            settings.update { it.copy(includeCashWithdrawals = true) }
            return undo
        }

        /** An entry whose card was already answered (or that a pair already links) cannot be answered again. */
        private fun done(entryId: String): suspend (AnswerState) -> Boolean =
            { s -> entryId in s.settledIds(db.expenses().liveSince(AnswerCards.lookbackStart(clock.millis())).mapTo(HashSet()) { it.id }) }

        override suspend fun setAtmChoice(choice: AtmChoice?) {
            eraseLock.mutex.withLock {
                prefs.edit { it.copy(atmChoice = choice) }
                settings.update { it.copy(includeCashWithdrawals = choice == AtmChoice.COUNT) }
            }
            signal.changed()
        }

        override suspend fun restoreIgnored(last4: String) {
            eraseLock.mutex.withLock { prefs.edit { it.copy(ignoredCards = it.ignoredCards - last4) } }
            signal.changed()
        }

        override suspend fun removeCardLink(last4: String) {
            eraseLock.mutex.withLock { prefs.edit { it.copy(aliases = it.aliases - last4) } }
            signal.changed()
        }

        override suspend fun undo(token: AnswerUndo): Boolean {
            val ok =
                eraseLock.mutex.withLock {
                    // an answer that a newer one replaced (or that was unlinked) can no longer be undone: its rows belong to the new link
                    if (!prefs.current().links.containsAll(token.added.links)) return@withLock false
                    val done =
                        db.withTransaction {
                            val now = clock.millis()
                            token.restore.forEach { AnswerWrites.revert(db, it) }
                            token.added.links.forEach { AnswerWrites.unlink(db, it) }
                            token.deleteExpenses.forEach { db.expenses().delete(it) }
                            token.deleteAccounts.forEach { db.accountBalances().deleteAccount(it) }
                            // the links this answer replaced come back exactly as they were
                            restoreSettled(token, now)
                            true
                        }
                    val a = token.added
                    val replacedIds = token.replacedLinks.flatMapTo(HashSet()) { listOf(it.first, it.second) }
                    prefs.edit { s ->
                        s.copy(
                            answered = s.answered - (a.answered - replacedIds),
                            links = s.links - a.links + token.replacedLinks,
                            ignoredCards = s.ignoredCards - a.ignoredCards,
                            aliases = s.aliases.filterNot { (k, v) -> a.aliases[k] == v },
                            atmChoice = if (a.atmChoice != null && s.atmChoice == a.atmChoice) token.atmBefore else s.atmChoice,
                        )
                    }
                    done
                }
            if (ok) token.includeCashBefore?.let { before -> settings.update { it.copy(includeCashWithdrawals = before) } }
            signal.changed()
            return ok
        }

        /** Puts back what settling links changed (the converted rows, the inserted rows) and re-applies the links to them. */
        private suspend fun restoreSettled(
            token: AnswerUndo,
            now: Long,
        ) {
            token.settledInserted.forEach { id -> if (db.expenses().find(id) != null) db.expenses().restore(id, now) }
            token.settledRows.forEach { AnswerWrites.revert(db, it) }
            AnswerWrites.reconcile(db, token.replacedLinks)
        }

        override suspend fun linksOf(entryId: String): List<EntryLink> = prefs.current().linksOf(entryId)

        override suspend fun unlink(entryId: String): AnswerUndo? {
            val token =
                eraseLock.mutex.withLock {
                    val links = prefs.current().linksOf(entryId).toSet()
                    if (links.isEmpty()) return@withLock null
                    val settled = db.withTransaction { AnswerWrites.settle(db, links, clock.millis()) }
                    val ids = links.flatMapTo(HashSet()) { listOf(it.first, it.second) }
                    prefs.edit { s -> s.copy(links = s.links - links, answered = s.answered - ids) }
                    AnswerUndo(replacedLinks = links, settledRows = settled.rows, settledInserted = settled.insertedDeleted)
                } ?: return null
            signal.changed()
            return token
        }

        override suspend fun undoUnlink(token: AnswerUndo) {
            eraseLock.mutex.withLock {
                val ids = token.replacedLinks.flatMapTo(HashSet()) { listOf(it.first, it.second) }
                // the entries were answered again meanwhile: the old pair is gone for good
                if (prefs.current().links.any { it.first in ids || it.second in ids }) return@withLock
                db.withTransaction { restoreSettled(token, clock.millis()) }
                prefs.edit { s -> s.copy(links = s.links + token.replacedLinks, answered = s.answered + ids) }
            }
            signal.changed()
        }

        private suspend fun live(id: String): ExpenseEntity? = db.expenses().find(id)?.takeIf { it.deletedAtMillis == null }

        /**
         * One Room transaction, then the remembered state, both while the erase lock is held (an "Erase all data" cannot interleave
         * and leave links of rows that no longer exist). Inserted rows have deterministic ids, so repeating an answer that was cut
         * short between the two writes is a no-op. Null = nothing to answer.
         */
        private suspend fun answer(
            alreadyDone: suspend (AnswerState) -> Boolean = { false },
            block: suspend (AnswerState) -> AnswerUndo?,
        ): AnswerUndo? {
            val undo =
                eraseLock.mutex.withLock {
                    // checked under the same lock as the write: a second tap (or caller) finds the first answer recorded
                    val state = prefs.current()
                    if (alreadyDone(state)) return@withLock null
                    val done = db.withTransaction { block(state) } ?: return@withLock null
                    val a = done.added
                    val atmBefore = prefs.current().atmChoice
                    prefs.edit { s ->
                        s.copy(
                            answered = s.answered - done.replacedLinks.flatMapTo(HashSet()) { listOf(it.first, it.second) } + a.answered,
                            links = s.links - done.replacedLinks + a.links,
                            ignoredCards = s.ignoredCards + a.ignoredCards,
                            aliases = s.aliases + a.aliases,
                            atmChoice = a.atmChoice ?: s.atmChoice,
                        )
                    }
                    if (a.atmChoice != null) done.copy(atmBefore = atmBefore) else done
                } ?: return null
            signal.changed()
            return undo
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

/** The links of an entry (live or deleted). The editor deletes, releases and restores pairs with them. */
interface PairLookup {
    suspend fun linksOf(entryId: String): List<EntryLink>

    /** "Unlink" on the Edit screen: both rows go back to their unlinked state; the token undoes it. Null = nothing linked. */
    suspend fun unlink(entryId: String): AnswerUndo? = null

    suspend fun undoUnlink(token: AnswerUndo) = Unit
}

object NoPairLookup : PairLookup {
    override suspend fun linksOf(entryId: String): List<EntryLink> = emptyList()
}

@Singleton
class AnswerPairLookup
    @Inject
    constructor(
        private val repository: AnswerRepository,
    ) : PairLookup {
        override suspend fun linksOf(entryId: String): List<EntryLink> = repository.linksOf(entryId)

        override suspend fun unlink(entryId: String): AnswerUndo? = repository.unlink(entryId)

        override suspend fun undoUnlink(token: AnswerUndo) = repository.undoUnlink(token)
    }
