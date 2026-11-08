package eg.masroof.data.serverimport

import android.database.SQLException
import androidx.room.withTransaction
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.SettingsRepository
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.AnswerRepository
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.budgets.MAX_BUDGET_MINOR
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ME_MEMBER_ID
import eg.masroof.data.db.ME_MEMBER_NAME
import eg.masroof.data.db.MemberEntity
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.Currency
import javax.inject.Inject
import javax.inject.Singleton

enum class ImportStage { DOWNLOADING, SAVING }

/** [total] is known once the download finished (the server sends no count): "Imported [done] of [total]". */
data class ImportProgress(
    val stage: ImportStage,
    val done: Int,
    val total: Int?,
)

sealed interface ImportResult {
    /** The run finished and the server was forgotten. [skipped] rows could not be imported (invalid) and were not stored. */
    data class Completed(
        val imported: Int,
        val skipped: Int,
    ) : ImportResult

    /** The run stopped; credentials are kept so Import can be tapped again. */
    data class Failed(
        val kind: ImportFailureKind,
    ) : ImportResult
}

private const val CHUNK = 200
private const val MAX_PAGES = 5_000
private const val CURRENCY_LENGTH = 3
private const val MAX_THRESHOLD = 200
private val EXPENSE_TYPES = setOf("EXPENSE", "INCOME", "REFUND", "TRANSFER", "CASH_WITHDRAWAL")
private val STATUSES = setOf("CONFIRMED", "PENDING_REVIEW", "POSSIBLE_DUPLICATE", "IGNORED")
private const val MAX_MINOR = MAX_BUDGET_MINOR
private val MONTH_START_DAYS = 1..28

/** Rows the server marks ignored are not imported: the household chose to drop them there. */
private const val IGNORED = "IGNORED"

/**
 * REQ-OPS-175 / REQ-SEC-065 (ADR-0009 D6): the one-time import. It first downloads every page (nothing is written, so a drop, a 5xx
 * or a kill leaves the phone exactly as it was), then writes in transactions of [CHUNK] rows, and only after a run completed does it
 * call [ServerForgetter]. Idempotent: a row is matched by its server id, and a row changed on the phone since is never rewritten
 * (see [ImportWriter]). Raw values are never logged; this class has no logger.
 */
@Suppress("LongParameterList") // one collaborator per concern; all injected
@Singleton
class ServerImporter
    @Inject
    constructor(
        private val db: AppDatabase,
        private val source: ServerImportSource,
        private val session: SessionManager,
        private val settings: SettingsRepository,
        private val answers: AnswerRepository,
        private val prefs: AnswerPrefs,
        private val forgetter: ServerForgetter,
        private val eraseLock: EraseLock,
        private val signal: LocalDataSignal,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val writer = ImportWriter(db)

        /** Runs one import. [onProgress] is called from the importing coroutine. Cancelling it stops the run without forgetting. */
        suspend fun run(onProgress: (ImportProgress) -> Unit): ImportResult =
            try {
                withContext(io) { runChecked(onProgress) }
            } catch (e: ImportException) {
                ImportResult.Failed(e.kind)
            } catch (_: SQLException) {
                ImportResult.Failed(ImportFailureKind.STORAGE)
            }

        private suspend fun runChecked(onProgress: (ImportProgress) -> Unit): ImportResult {
            // no session: nothing to import with, and no request is made
            val me = session.memberId() ?: return ImportResult.Failed(ImportFailureKind.SESSION)
            val stamp = clock.millis()
            val lookups = Lookups(source.categories(), source.accounts(), source.members(), source.budgets(), source.settings())
            val download = downloadExpenses(onProgress)
            val ctx = writeLookups(lookups, me, stamp)
            applySettings(lookups.settings)
            val skippedBudgets = ctx.skippedBudgets
            val (imported, rejected) = writeExpenses(download.live, ctx.context, onProgress)
            // the run completed: only now is the server forgotten, and a cancel arriving here cannot half-do it
            withContext(NonCancellable) { forgetter.forget() }
            signal.changed()
            return ImportResult.Completed(imported, download.invalid + rejected + skippedBudgets)
        }

        private class Lookups(
            val categories: List<ServerCategory>,
            val accounts: List<ServerAccount>,
            val members: List<ServerMember>,
            val budgets: List<ServerBudget>,
            val settings: ServerSettings,
        )

        private class Downloaded(
            val live: List<ServerExpense>,
            val invalid: Int,
        )

        private suspend fun downloadExpenses(onProgress: (ImportProgress) -> Unit): Downloaded {
            val live = ArrayList<ServerExpense>()
            val seen = HashSet<String>()
            val cursors = HashSet<String>()
            var invalid = 0
            var cursor: String? = null
            var pages = 0
            do {
                currentCoroutineContext().ensureActive()
                if (++pages > MAX_PAGES) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
                val batch = source.expenses(cursor, IMPORT_PAGE_LIMIT)
                for (row in batch.rows) {
                    // soft-deleted and ignored rows are not imported; a row the server lists twice counts once
                    val e = (row as? ServerExpenseRow.Valid)?.expense
                    when {
                        e == null -> invalid++
                        e.deleted || e.status == IGNORED || !seen.add(e.id) -> Unit
                        valid(e) -> live += e
                        else -> invalid++
                    }
                }
                onProgress(ImportProgress(ImportStage.DOWNLOADING, live.size + invalid, null))
                cursor = batch.nextCursor
                // a cursor that comes back would page forever
                if (cursor != null && !cursors.add(cursor)) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            } while (cursor != null)
            return Downloaded(live, invalid)
        }

        /** The checks of REQ-OPS-175: amount, currency, enums. The category id is checked against the category list when writing. */
        private fun valid(e: ServerExpense): Boolean =
            e.amountMinor in 1..MAX_MINOR &&
                validCurrency(e.currency) &&
                e.type in EXPENSE_TYPES &&
                e.status in STATUSES &&
                e.id.isNotBlank()

        private fun validCurrency(code: String): Boolean =
            code.length == CURRENCY_LENGTH && code.all { it in 'A'..'Z' } && runCatching { Currency.getInstance(code) }.isSuccess

        private class Written(
            val context: ImportContext,
            val skippedBudgets: Int,
        )

        /** Categories, accounts, members and budgets: only ever added, so a name or a budget the user changed stays. */
        private suspend fun writeLookups(
            l: Lookups,
            signedInMemberId: String,
            stamp: Long,
        ): Written =
            eraseLock.mutex.withLock {
                db.withTransaction {
                    val lookups = db.lookups()
                    val defaults =
                        lookups
                            .allCategories()
                            .filter { it.id.startsWith(DEFAULT_PREFIX) }
                            .associateBy { it.nameEn.trim().lowercase() }
                    // a server category that is one of the phone's defaults (same English name) maps onto it; the rest keep their id
                    val categoryMap = l.categories.associate { it.id to (defaults[it.nameEn.trim().lowercase()]?.id ?: it.id) }
                    for (c in l.categories) {
                        if (categoryMap[c.id] != c.id) continue
                        lookups.insertCategoryIfAbsent(
                            CategoryEntity(
                                id = c.id,
                                nameEn = c.nameEn,
                                nameAr = c.nameAr,
                                sortOrder = c.sortOrder,
                                parentId = c.parentId?.let { categoryMap[it] },
                                color = c.color,
                                archived = c.archived,
                                system = false,
                            ),
                        )
                    }
                    for (a in l.accounts) {
                        lookups.insertAccountIfAbsent(AccountEntity(a.id, a.label, a.currency, a.kind, a.institution, a.last4))
                    }
                    val memberMap = HashMap<String, String>()
                    l.members.forEachIndexed { i, m ->
                        if (m.id == signedInMemberId) {
                            memberMap[m.id] = ME_MEMBER_ID
                            // the phone's own "Me" takes the signed-in member's name, unless the user already renamed it
                            if (m.displayName.isNotBlank()) lookups.renameMemberIf(ME_MEMBER_ID, ME_MEMBER_NAME, m.displayName)
                        } else {
                            memberMap[m.id] = m.id
                            lookups.insertMemberIfAbsent(MemberEntity(m.id, m.displayName, i + 1))
                        }
                    }
                    val skipped = writeBudgets(l.budgets, categoryMap, stamp)
                    Written(
                        ImportContext(
                            categories = categoryMap,
                            accountIds = l.accounts.mapTo(HashSet()) { it.id },
                            members = memberMap,
                            stampMillis = stamp,
                            nowMillis = stamp,
                            blocked = emptySet(),
                        ),
                        skipped,
                    )
                }
            }

        /**
         * One budget per server id, stored through the same table STORY-181 reads. A server duplicate (same category and period) is
         * stored as sent, so the Budgets screen shows its "Duplicate budget" row; a period or currency the phone cannot compute is
         * stored as sent too ("Unsupported budget"). Only a budget that breaks a limit is skipped and counted.
         */
        private suspend fun writeBudgets(
            budgets: List<ServerBudget>,
            categoryMap: Map<String, String>,
            stamp: Long,
        ): Int {
            var skipped = 0
            for (b in budgets) {
                val category = b.categoryId?.let { categoryMap[it] }
                val unknownCategory = b.categoryId != null && category == null
                if (b.amountMinor !in 1..MAX_BUDGET_MINOR || !validCurrency(b.currency) || unknownCategory) {
                    skipped++
                    continue
                }
                val thresholds =
                    b.alertThresholds
                        .filter { it in 1..MAX_THRESHOLD }
                        .distinct()
                        .sorted()
                        .joinToString(
                            ",",
                        ).ifEmpty { "50,80,100" }
                val existing = db.budgets().byId(b.id)
                val row =
                    BudgetEntity(
                        id = b.id,
                        categoryId = category,
                        period = b.period,
                        amountMinor = b.amountMinor,
                        currency = b.currency,
                        rollover = b.rollover,
                        alertThresholds = thresholds,
                        createdAtMillis = existing?.createdAtMillis ?: stamp,
                        updatedAtMillis = existing?.createdAtMillis ?: stamp,
                    )
                when {
                    existing == null -> db.budgets().insert(row)
                    // unchanged on the phone (an edit there sets updatedAt past createdAt): refreshed; otherwise left alone
                    existing.updatedAtMillis <= existing.createdAtMillis -> if (row != existing) db.budgets().update(row)
                }
            }
            return skipped
        }

        /** Month start day and the ATM rule go to the phone's settings, unless the user already chose on the phone. */
        private suspend fun applySettings(server: ServerSettings) {
            if (server.monthStartDay in MONTH_START_DAYS) {
                settings.update {
                    if (it.monthStartDay ==
                        eg.masroof.data.AppSettings.DEFAULT_MONTH_START_DAY
                    ) {
                        it.copy(monthStartDay = server.monthStartDay)
                    } else {
                        it
                    }
                }
            }
            if (answers.atmChoice.first() == null) {
                answers.setAtmChoice(if (server.countAtmWithdrawalsAsSpending) AtmChoice.COUNT else AtmChoice.TRACK)
            }
        }

        private suspend fun writeExpenses(
            rows: List<ServerExpense>,
            base: ImportContext,
            onProgress: (ImportProgress) -> Unit,
        ): Pair<Int, Int> {
            var imported = 0
            var rejected = 0
            var done = 0
            onProgress(ImportProgress(ImportStage.SAVING, 0, rows.size))
            for (chunk in rows.chunked(CHUNK)) {
                currentCoroutineContext().ensureActive()
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val ctx = base.withBlocked(prefs.current().linkedIds() + db.expenses().referencedIds())
                        for (e in chunk) {
                            // the category must be in the server's category list: an unknown id is an invalid row
                            if (e.categoryId != null && e.categoryId !in ctx.categories) {
                                rejected++
                                continue
                            }
                            if (writer.write(e, ctx) == WriteOutcome.CLASH) rejected++ else imported++
                        }
                    }
                }
                done += chunk.size
                onProgress(ImportProgress(ImportStage.SAVING, done, rows.size))
            }
            return imported to rejected
        }

        private fun ImportContext.withBlocked(ids: Set<String>) =
            ImportContext(categories, accountIds, members, stampMillis, nowMillis, ids)

        private companion object {
            const val DEFAULT_PREFIX = "cat-"
        }
    }
