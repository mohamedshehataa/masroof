package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerUndo
import eg.masroof.data.answers.EntryLink
import eg.masroof.data.answers.ExpenseTypes
import eg.masroof.data.answers.LinkKind
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.ingest.CapturedActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

private const val SEQUENCES = 2500
private const val STEPS = 12

/**
 * STORY-208 fix round 3: a seeded generated test over one small world (CIB, NBE, Cash; captured transfers, incomes, refunds,
 * purchases and an ATM withdrawal). Random answers, answer Undo, deletes, restores in any order, edits, capture Undo and erase;
 * after EVERY step the invariants are checked against a plain reference. The seed and the steps so far are in the failure message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerGeneratedTest {
    private lateinit var w: AnswerWorld
    private val trace = mutableListOf<String>()
    private val tokens = mutableListOf<AnswerUndo>()

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    private val ids = listOf("out", "in", "t2", "i2", "r", "p1", "p2", "w1", "e1")

    private suspend fun seed() {
        w.db.clearAllTables()
        w.prefs.clear()
        w.settings.update { it.copy(includeCashWithdrawals = false) }
        tokens.clear()
        w.db.lookups().upsertAccounts(
            listOf(
                answerAccount("cib", "CIB", last4 = "1111"),
                answerAccount("nbe", "NBE", last4 = "2222"),
                answerAccount("cash", "Cash", kind = "CASH"),
            ),
        )
        listOf("cib" to 2_000_000L, "nbe" to 1_000_000L, "cash" to 0L).forEach { (id, b) ->
            w.db.accountBalances().insert(AccountBalanceEntity("chk-$id", id, b, "EGP", ago(20), "MANUAL", ago(20)))
        }
        val rows =
            listOf(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)),
                answerEntry("in", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", at = ago(0, 23)),
                answerEntry("t2", type = "TRANSFER", amount = 70_000, last4 = "2222", method = "BANK_TRANSFER", at = ago(2)),
                answerEntry("i2", type = "INCOME", amount = 30_000, last4 = "1111", method = "INSTAPAY", merchant = "Mona", at = ago(3)),
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000, last4 = "1111", at = ago(1)),
                answerEntry("p1", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop", last4 = "1111"),
                answerEntry("p2", merchant = "Noon", amount = 80_000, at = ago(4), category = "cat-shop", last4 = "2222"),
                answerEntry("w1", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111", at = ago(2)),
                answerEntry("e1", merchant = "Shop", amount = 25_000, at = ago(6), category = "cat-elec", last4 = "2222"),
            )
        rows.forEach {
            w.db.expenses().insert(it)
            w.db.captured().insert(CapturedTxEntity("c-${it.id}", it.id, it.amountMinor, "EGP", null, "SMS", null, it.occurredAtMillis))
        }
    }

    @Test
    fun `REQ-EXP-065 random answers, deletes, restores, edits and Undos keep every pair and refund consistent`() =
        runBlocking {
            for (seed in 1..SEQUENCES) {
                trace.clear()
                seed()
                val random = Random(seed)
                try {
                    repeat(STEPS) {
                        step(random)
                        check()
                    }
                } catch (e: Throwable) {
                    throw AssertionError("seed=$seed after ${trace.size} steps: ${trace.joinToString(" | ")}: ${e.message}", e)
                }
            }
        }

    // ---- the operations

    private suspend fun step(r: Random) {
        val id = ids.random(r)
        when (r.nextInt(OPS)) {
            0, 1, 2 -> answer(r, id)
            3 -> undoAnswer(r)
            4, 5 -> {
                trace += "delete $id"
                w.editor.delete(id)
            }
            6 -> {
                trace += "deleteBoth $id"
                w.editor.deleteWithPartner(id)
            }
            7, 8 -> {
                trace += "restore $id"
                w.editor.restore(id)
            }
            9, 10 -> edit(r, id)
            11 -> {
                trace += "captureUndo $id"
                CapturedActions(w.db, w.clock, answers = w.prefs).undo("c-$id")
            }
            else -> {
                trace += "erase"
                seed()
                assertTrue(
                    w.prefs.flow.value.links
                        .isEmpty() &&
                        w.prefs.flow.value.answered
                            .isEmpty(),
                )
            }
        }
    }

    private suspend fun answer(
        r: Random,
        id: String,
    ) {
        val target = listOf("cib", "nbe", "cash").random(r)
        val purchase = listOf("p1", "p2").random(r)
        val pick = r.nextInt(ANSWERS)
        trace += "answer#$pick $id->$target/$purchase"
        val token =
            when (pick) {
                0, 1, 2 -> w.repo.transferToOwn(id, target)
                3 -> w.repo.transferToOther(id)
                4, 5 -> w.repo.linkRefund(id, purchase)
                6 -> w.repo.notRefund(id)
                7 -> w.repo.confirmRefund(id)
                8 -> w.repo.trackCash(id)
                else -> w.repo.countCash(id)
            }
        if (token != null) tokens += token
    }

    private suspend fun undoAnswer(r: Random) {
        if (tokens.isEmpty()) return
        val token = tokens.removeAt(r.nextInt(tokens.size))
        trace += "undoAnswer"
        w.repo.undo(token)
    }

    private suspend fun edit(
        r: Random,
        id: String,
    ) {
        val amounts = listOf(10_000L, 60_000L, 130_000L, 500_000L)
        val changes =
            when (r.nextInt(4)) {
                0 -> ExpenseChanges(amountMinor = amounts.random(r))
                1 -> ExpenseChanges(categoryId = listOf("cat-shop", "cat-elec").random(r))
                2 -> ExpenseChanges(notes = "n${r.nextInt(9)}")
                else -> ExpenseChanges(currency = listOf("USD", "EGP").random(r))
            }
        trace += "edit $id ${changes.amountMinor ?: ""}${changes.categoryId ?: ""}${changes.currency ?: ""}${changes.notes ?: ""}"
        val row = w.db.expenses().find(id)
        val expectRefusal = row != null && row.deletedAtMillis == null && refusedByLink(row, changes)
        val outcome = w.editor.update(id, changes)
        if (expectRefusal) assertEquals("an edit that breaks a refund link is refused", EditOutcome.LinkedRefund, outcome)
        if (!expectRefusal && row != null && row.deletedAtMillis == null) assertTrue("edit saved: $outcome", outcome is EditOutcome.Saved)
    }

    /** The reference rule: a live linked refund and purchase keep the same currency and refund <= purchase. */
    private suspend fun refusedByLink(
        row: ExpenseEntity,
        c: ExpenseChanges,
    ): Boolean {
        val amount = c.amountMinor ?: row.amountMinor
        val currency = c.currency ?: row.currency
        return w.prefs.flow.value.links.filter { it.kind == LinkKind.REFUND }.any { link ->
            val refund =
                w.db
                    .expenses()
                    .find(link.first)
                    ?.takeIf { it.deletedAtMillis == null }
            val purchase =
                w.db
                    .expenses()
                    .find(link.second)
                    ?.takeIf { it.deletedAtMillis == null }
            when {
                refund == null || purchase == null -> false
                row.id == purchase.id -> amount < refund.amountMinor || currency != refund.currency
                row.id == refund.id -> amount > purchase.amountMinor || currency != purchase.currency
                else -> false
            }
        }
    }

    // ---- the invariants

    private suspend fun check() {
        val all = w.db.expenses().all()
        val live = all.filter { it.deletedAtMillis == null }
        val byId = all.associateBy { it.id }
        w.prefs.flow.value.links
            .forEach { checkLink(it, byId) }
        // an app-inserted row lives only while its source entry is live
        all.filter { it.id.endsWith(":pair") && it.deletedAtMillis == null }.forEach {
            val source = byId[it.id.removeSuffix(":pair")]
            assertTrue("inserted ${it.id} lives without its source", source != null && source.deletedAtMillis == null)
        }
        checkTotals(live)
        checkWorth(live)
        checkCards(live)
    }

    private fun checkLink(
        link: EntryLink,
        byId: Map<String, ExpenseEntity>,
    ) {
        val a = byId[link.first] ?: return
        val b = byId[link.second] ?: return
        if (link.kind == LinkKind.TRANSFER) {
            if (link.inserted.isNotEmpty()) {
                val (ins, src) = if (link.inserted == a.id) a to b else b to a
                assertTrue("inserted ${ins.id} outlives ${src.id}", src.deletedAtMillis == null || ins.deletedAtMillis != null)
            }
            val c = byId[link.converted]
            if (link.converted.isNotEmpty() && c != null && c.deletedAtMillis == null && link.pairType.isNotEmpty()) {
                val other = if (link.converted == a.id) b else a
                val whole = other.deletedAtMillis == null
                val type = if (whole) link.pairType else link.origType
                val account = (if (whole) link.pairAccountId else link.origAccountId).ifEmpty { null }
                assertEquals("converted ${c.id} type (pair whole=$whole)", type, c.type)
                assertEquals("converted ${c.id} account (pair whole=$whole)", account, c.accountId)
            }
        } else {
            val refund = a.takeIf { it.deletedAtMillis == null } ?: return
            if (b.deletedAtMillis == null) {
                assertEquals("refund ${refund.id} type while its purchase lives", ExpenseTypes.REFUND, refund.type)
                if (b.categoryId != null) assertEquals("refund category follows the purchase", b.categoryId, refund.categoryId)
            } else {
                assertEquals("refund ${refund.id} type while its purchase is deleted", ExpenseTypes.INCOME, refund.type)
            }
        }
    }

    private fun checkTotals(live: List<ExpenseEntity>) {
        val rows =
            live.map {
                AnalyticsRow(
                    it.type,
                    it.status,
                    it.currency,
                    it.amountMinor,
                    it.occurredAtMillis,
                    it.categoryId,
                    it.merchant,
                )
            }
        val categories = listOf("cat-shop", "cat-elec").map { AnalyticsCategory(it, null, it, it, null, false) }
        val period = AnalyticsPeriod.month(YearMonth.of(2026, 11), 1)
        for (cash in listOf(false, true)) {
            val result = AnalyticsCalculator.compute(period, LocalDate.of(2026, 11, 8), rows, categories, cash)
            val egp = live.filter { it.currency == "EGP" }
            val spending =
                egp.sumOf {
                    when (it.type) {
                        "EXPENSE" -> it.amountMinor
                        "REFUND" -> -it.amountMinor
                        "CASH_WITHDRAWAL" -> if (cash) it.amountMinor else 0L
                        else -> 0L
                    }
                }
            val income = egp.filter { it.type == "INCOME" }.sumOf { it.amountMinor }
            assertEquals("spending (cash=$cash)", spending, result.summary.total)
            assertEquals("income", income, result.summary.income)
            assertEquals("the category list matches the total", result.summary.total, result.categoryTotal)
        }
    }

    private suspend fun checkWorth(live: List<ExpenseEntity>) {
        val accounts = w.db.accountBalances().accounts()
        var expected = 0L
        for (account in accounts) {
            var balance = mapOf("cib" to 2_000_000L, "nbe" to 1_000_000L, "cash" to 0L).getValue(account.id)
            live.filter { it.currency == "EGP" && owner(accounts, it) == account.id }.forEach {
                val sign = if (it.type in setOf("INCOME", "REFUND", "TRANSFER_IN")) 1 else -1
                balance += sign * it.amountMinor
            }
            expected += balance
        }
        assertEquals("net worth", expected, w.worth())
    }

    private fun owner(
        accounts: List<eg.masroof.data.db.AccountEntity>,
        e: ExpenseEntity,
    ): String? =
        e.accountId ?: e.instrumentLast4?.let { l4 -> accounts.filter { it.last4 == l4 }.singleOrNull()?.id }

    private suspend fun checkCards(live: List<ExpenseEntity>) {
        val cards = w.repo.observeCards().first()
        val state = w.prefs.flow.value
        val settled = state.settledIds(live.mapTo(HashSet()) { it.id })
        for (e in live.filter { it.source == "SMS" && it.currency == "EGP" && it.id !in settled }) {
            if (e.type == "REFUND") {
                assertTrue(
                    "a captured REFUND ${e.id} that is not answered must have its card",
                    cards.any {
                        it is AnswerCard.Refund &&
                            it.entryId == e.id
                    },
                )
            }
            if (e.type == "TRANSFER" && e.paymentMethod?.startsWith("GAM3EYA") != true && cards.size < 10) {
                assertTrue(
                    "a captured TRANSFER ${e.id} that is not answered must have its card",
                    cards.any {
                        it is AnswerCard.Transfer &&
                            it.entryId == e.id
                    },
                )
            }
        }
    }

    private companion object {
        const val OPS = 13
        const val ANSWERS = 10
    }
}
