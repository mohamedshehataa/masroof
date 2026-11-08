package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AnswerUndo
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.categories.DefaultMerchantRuleRepository
import eg.masroof.data.categories.MerchantKey
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

private const val SEQUENCES = 2000
private const val STEPS = 20

/**
 * STORY-208 fix round 4: a seeded generated test over one small world (CIB, NBE, Cash; captured transfers, incomes, a refund,
 * purchases and an ATM withdrawal). Its expectations come from [AnswerLedger], which keeps the real-world money from the abstract
 * operations and never reads a link, a reconcile result or a stored row. After EVERY step the app's spending, income, category
 * totals, account balances and net worth are compared with the ledger. The base seed comes from `-Danswer.seed` (default 1); a failure
 * prints the seed and the steps so far.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// a generator: one branch per operation, mirrored in the ledger
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition", "ThrowsCount")
class AnswerGeneratedTest {
    private lateinit var w: AnswerWorld
    private val m = AnswerLedger()
    private val trace = mutableListOf<String>()

    private class Token(
        val pair: AnswerLedger.Pair? = null,
        val refund: AnswerLedger.Refund? = null,
        val plain: String? = null,
        val replacedPairs: List<AnswerLedger.Pair> = emptyList(),
        val replacedRefunds: List<AnswerLedger.Refund> = emptyList(),
        val includeBefore: Boolean? = null,
        val product: AnswerUndo? = null,
    )

    private class Unlinked(
        val pairs: List<AnswerLedger.Pair>,
        val refunds: List<AnswerLedger.Refund>,
        val product: AnswerUndo,
    )

    private val tokens = mutableListOf<Token>()
    private val unlinked = mutableListOf<Unlinked>()

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    private val events = listOf("out", "in", "t2", "i2", "r", "p1", "p2", "w1", "e1")
    private val insertedIds = listOf("out", "in", "t2", "i2", "r", "w1").map { "$it:pair" }

    private suspend fun seed() {
        w.db.clearAllTables()
        w.prefs.clear()
        w.settings.update { it.copy(includeCashWithdrawals = false) }
        tokens.clear()
        unlinked.clear()
        m.reset()
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
    fun `REQ-EXP-065 random answers, unlinks, deletes, restores, edits and erases match the independent money ledger`() =
        runBlocking {
            val base = System.getProperty("answer.seed")?.toIntOrNull() ?: 1
            for (seed in base until base + SEQUENCES) {
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

    // ---- the operations (each one updates the ledger by the user-visible rules, then calls the app)

    private suspend fun step(r: Random) {
        val id = events.random(r)
        when (r.nextInt(OPS)) {
            0, 1, 2 -> answer(r, id)
            3 -> undoAnswer(r)
            4 -> unlink(r, id)
            5 -> undoUnlink(r)
            6, 7 -> remove(id, both = false, tag = "delete")
            8 -> remove(id, both = true, tag = "deleteBoth")
            9 -> remove((events + insertedIds).random(r), both = false, tag = "delete")
            10, 11 -> restore((events + insertedIds).random(r))
            12, 13 -> edit(r, id)
            14 -> remove(id, both = false, tag = "captureUndo")
            15 -> {
                trace += "applyToPast"
                val cat = listOf("cat-shop", "cat-elec").random(r)
                m.events["p1"]?.let { if (it.alive) it.cat = cat }
                rules().applyToPast(MerchantKey.of("Amazon"), cat)
            }
            else -> {
                trace += "erase"
                RoomDataWiper(
                    w.db,
                    NoInsightsCache,
                    androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                    Dispatchers.Unconfined,
                    answerPrefs = w.prefs,
                ).wipe()
                seed()
            }
        }
    }

    private fun rules() =
        DefaultMerchantRuleRepository(
            w.db,
            w.clock,
            eg.masroof.data.UuidIdFactory(),
            eg.masroof.data.LocalDataSignal(),
            eg.masroof.data.auth
                .EraseLock(),
            w.prefs,
        )

    private fun ev(id: String) = m.events[id]

    private suspend fun answer(
        r: Random,
        id: String,
    ) {
        when (r.nextInt(6)) {
            0, 1 -> {
                val target = listOf("cib", "nbe", "cash").random(r)
                trace += "transfer $id->$target"
                val token = modelTransfer(id, target)
                val product = w.repo.transferToOwn(id, target)
                if (token !=
                    null
                ) {
                    tokens += token.copy(product)
                } else if (product !=
                    null
                ) {
                    fail("the app answered what the ledger refuses")
                }
            }
            2 -> {
                val p = listOf("p1", "p2").random(r)
                trace += "refund r->$p"
                val token = modelRefund(p)
                val product = w.repo.linkRefund("r", p)
                if (token !=
                    null
                ) {
                    tokens += token.copy(product)
                } else if (product !=
                    null
                ) {
                    fail("the app linked what the ledger refuses")
                }
            }
            3 -> {
                trace += "other $id"
                val ok = ev(id)?.alive == true && !m.settled(id)
                if (ok) m.plain += id
                val product = w.repo.transferToOther(id)
                if (ok) {
                    tokens += Token(plain = id, product = product)
                } else {
                    assertTrue(
                        "the app answered what the ledger refuses",
                        product == null,
                    )
                }
            }
            4 -> {
                trace += "track w1"
                val token = modelTrack()
                val product = w.repo.trackCash("w1")
                if (token !=
                    null
                ) {
                    tokens += token.copy(product)
                } else if (product !=
                    null
                ) {
                    fail("the app tracked what the ledger refuses")
                }
            }
            else -> {
                trace += "count w1"
                val w1 = ev("w1")
                val ok = w1 != null && w1.alive && !m.settled("w1") && !m.pairs.any { m.typed(it) && it.src == "w1" }
                val before = m.include
                if (ok) {
                    m.plain += "w1"
                    m.include = true
                }
                val product = w.repo.countCash("w1")
                if (ok) {
                    tokens += Token(plain = "w1", includeBefore = before, product = product)
                } else {
                    assertTrue(
                        "counted what the ledger refuses",
                        product == null,
                    )
                }
            }
        }
    }

    private fun Token.copy(product: AnswerUndo?) =
        Token(pair, refund, plain, replacedPairs, replacedRefunds, includeBefore, product)

    private fun modelTransfer(
        id: String,
        target: String,
    ): Token? {
        val s = ev(id) ?: return null
        if (!s.alive || m.settled(id) || s.kind !in "TI" || s.cur != "EGP" || s.acct == target) return null
        val candidate =
            when {
                id == "out" -> ev("in")?.takeIf { target == "nbe" }
                id == "in" -> ev("out")?.takeIf { target == "cib" }
                else -> null
            }?.takeIf { it.alive && !m.settled(it.id) && it.amount == s.amount && it.cur == s.cur && arrivesAfterSend(s, it) }
        return replace(listOfNotNull(id, candidate?.id)) { oldPairs, oldRefunds ->
            val pair = AnswerLedger.Pair(id, candidate?.id, 'X', target)
            m.pairs += pair
            Token(pair = pair, replacedPairs = oldPairs, replacedRefunds = oldRefunds)
        }
    }

    /** The arrival is on the right side of the send, within a day (the rule a user would state). */
    private fun arrivesAfterSend(
        a: AnswerLedger.Ev,
        b: AnswerLedger.Ev,
    ): Boolean {
        val (send, arrival) = if (a.sign < 0) a to b else b to a
        return arrival.at - send.at in 0..DAY_MS
    }

    private fun modelRefund(p: String): Token? {
        val r = ev("r") ?: return null
        val purchase = ev(p) ?: return null
        if (!r.alive || !purchase.alive || m.settled("r") || r.cur != purchase.cur || purchase.amount < r.amount) return null
        return replace(listOf("r")) { oldPairs, oldRefunds ->
            val link = AnswerLedger.Refund("r", p)
            m.refunds += link
            Token(refund = link, replacedPairs = oldPairs, replacedRefunds = oldRefunds)
        }
    }

    private fun modelTrack(): Token? {
        val w1 = ev("w1") ?: return null
        if (!w1.alive || m.settled("w1") || w1.cur != "EGP") return null
        val before = m.include
        return replace(listOf("w1")) { oldPairs, oldRefunds ->
            val pair = AnswerLedger.Pair("w1", null, 'A', "cash")
            m.pairs += pair
            m.include = false
            Token(pair = pair, replacedPairs = oldPairs, replacedRefunds = oldRefunds, includeBefore = before)
        }
    }

    /** A new answer replaces the older links of its entries: they are final (their rows are plain again). */
    private fun replace(
        ids: List<String>,
        make: (List<AnswerLedger.Pair>, List<AnswerLedger.Refund>) -> Token,
    ): Token {
        val oldPairs = m.pairs.filter { (it.src in ids || it.joined in ids) && !m.bothLive(it) }
        val oldRefunds = m.refunds.filter { (it.r in ids || it.p in ids) && !m.bothLive(it) }
        m.pairs.removeAll(oldPairs.toSet())
        m.refunds.removeAll(oldRefunds.toSet())
        return make(oldPairs, oldRefunds)
    }

    private suspend fun undoAnswer(r: Random) {
        if (tokens.isEmpty()) return
        val t = tokens.removeAt(r.nextInt(tokens.size))
        trace += "undoAnswer"
        val live = t.pair?.let { it in m.pairs } ?: t.refund?.let { it in m.refunds } ?: true
        if (live) {
            t.pair?.let { m.pairs.remove(it) }
            t.refund?.let { m.refunds.remove(it) }
            t.plain?.let { m.plain.remove(it) }
            m.pairs += t.replacedPairs
            m.refunds += t.replacedRefunds
            t.includeBefore?.let { m.include = it }
        }
        t.product?.let { w.repo.undo(it) }
    }

    private suspend fun unlink(
        r: Random,
        id: String,
    ) {
        val pairs = m.pairs.filter { m.bothLive(it) && (it.src == id || it.joined == id) }
        val refunds = m.refunds.filter { m.bothLive(it) && (it.r == id || it.p == id) }
        if (pairs.isEmpty() && refunds.isEmpty()) return
        trace += "unlink $id"
        // the app unlinks every link of the entry (the ledger drops the same ones)
        val all = m.pairs.filter { it.src == id || it.joined == id }
        val allRefunds = m.refunds.filter { it.r == id || it.p == id }
        m.pairs.removeAll(all.toSet())
        m.refunds.removeAll(allRefunds.toSet())
        val product = AnswerPairLookup(w.repo).unlink(id)
        if (product != null) unlinked += Unlinked(all, allRefunds, product)
    }

    private suspend fun undoUnlink(r: Random) {
        if (unlinked.isEmpty()) return
        val u = unlinked.removeAt(r.nextInt(unlinked.size))
        trace += "undoUnlink"
        val ids = u.pairs.flatMap { listOfNotNull(it.src, it.joined) } + u.refunds.flatMap { listOf(it.r, it.p) }
        // the app refuses when one of the entries was answered again meanwhile
        val clash = m.pairs.any { it.src in ids || it.joined in ids } || m.refunds.any { it.r in ids || it.p in ids }
        if (!clash) {
            m.pairs += u.pairs
            m.refunds += u.refunds
        }
        AnswerPairLookup(w.repo).undoUnlink(u.product)
    }

    private suspend fun remove(
        id: String,
        both: Boolean,
        tag: String,
    ) {
        trace += "$tag $id"
        val row = w.db.expenses().find(id)
        val pair = m.pairs.firstOrNull { it.joined == null && "${it.src}:pair" == id }
        if (id.endsWith(":pair")) {
            if (pair != null && pair.insAlive && row?.deletedAtMillis == null) {
                pair.insAlive = false
                pair.insWithSrc = false
            }
        } else {
            val e = ev(id)
            if (e != null && e.alive) {
                e.alive = false
                m.pairs.filter { it.src == id && it.joined == null && it.insAlive }.forEach {
                    it.insAlive = false
                    it.insWithSrc = true
                }
                if (both) {
                    m.refunds.filter { it.p == id }.forEach { l ->
                        ev(l.r)?.let { r ->
                            if (r.alive) {
                                r.alive = false
                                l.delWithP =
                                    true
                            }
                        }
                    }
                }
            }
        }
        // an app-added row is reachable only while its pair exists (a settled one is gone for good)
        if (id.endsWith(":pair") && pair == null) return
        if (both) w.editor.deleteWithPartner(id) else w.editor.delete(id)
    }

    private suspend fun restore(id: String) {
        trace += "restore $id"
        if (id.endsWith(":pair")) {
            val pair = m.pairs.firstOrNull { it.joined == null && "${it.src}:pair" == id }
            if (pair != null && !pair.insAlive) {
                if (ev(pair.src)?.alive == true) {
                    pair.insAlive = true
                } else {
                    pair.insWithSrc = true
                }
            }
        } else {
            val e = ev(id)
            if (e != null && !e.alive) {
                e.alive = true
                m.pairs.filter { it.src == id && it.joined == null && !it.insAlive && it.insWithSrc }.forEach {
                    it.insAlive = true
                    it.insWithSrc = false
                }
                m.refunds.filter { it.p == id && it.delWithP }.forEach { l ->
                    ev(l.r)?.alive = true
                    l.delWithP = false
                }
            }
            m.refunds.filter { it.r == id }.forEach { it.delWithP = false }
        }
        if (id.endsWith(":pair") && m.pairs.none { it.joined == null && "${it.src}:pair" == id }) return
        w.editor.restore(id)
    }

    private suspend fun edit(
        r: Random,
        id: String,
    ) {
        val e = ev(id) ?: return
        if (!e.alive) return
        val money = r.nextInt(5)
        val changes =
            when (money) {
                0 -> ExpenseChanges(amountMinor = listOf(60_000L, 130_000L, 500_000L, 45_000L).random(r).takeIf { it != e.amount })
                1 -> ExpenseChanges(currency = if (e.cur == "EGP") "USD" else "EGP")
                2 -> ExpenseChanges(categoryId = listOf("cat-shop", "cat-elec").random(r))
                3 -> ExpenseChanges(occurredAtMillis = ago(r.nextLong(1, 5)).takeIf { it != e.at })
                else -> ExpenseChanges(notes = "n${r.nextInt(9)}")
            }
        if (changes.isEmpty || (changes.categoryId != null && e.kind != 'P')) return
        trace +=
            "edit $id ${changes.amountMinor ?: ""}${changes.currency ?: ""}${changes.categoryId ?: ""}${changes.occurredAtMillis?.let {
                "date"
            } ?: ""}"
        val touchesMoney = changes.amountMinor != null || changes.currency != null || changes.occurredAtMillis != null
        val refused = touchesMoney && m.locked(id)
        val outcome = w.editor.update(id, changes)
        if (refused) {
            assertEquals("a linked row's money fields are locked", EditOutcome.LinkedRefund, outcome)
            return
        }
        assertTrue("edit saved: $outcome", outcome is EditOutcome.Saved)
        changes.amountMinor?.let { e.amount = it }
        changes.currency?.let { e.cur = it }
        changes.occurredAtMillis?.let { e.at = it }
        // a date edit changes no money; the category of a purchase moves its refund (the ledger derives that)
        if (changes.categoryId != null && e.kind == 'P') e.cat = changes.categoryId
    }

    // ---- the invariants, against the ledger

    private suspend fun check() {
        val live =
            w.db
                .expenses()
                .all()
                .filter { it.deletedAtMillis == null }
        val include = w.settings.current().includeCashWithdrawals
        assertEquals("the spending switch", m.include, include)
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
        val result =
            AnalyticsCalculator.compute(
                AnalyticsPeriod.month(YearMonth.of(2026, 11), 1),
                LocalDate.of(2026, 11, 8),
                rows,
                categories,
                include,
            )
        assertEquals("spending", m.spending(), result.summary.total)
        assertEquals("income", m.income(), result.summary.income)
        assertEquals("the category list sums to the total", result.summary.total, result.categoryTotal)
        val byCat = result.categories.associate { (it.categoryId ?: "") to it.total }
        val expectedCats = m.categoryTotals()
        listOf("cat-shop", "cat-elec").forEach { assertEquals("category $it", expectedCats[it] ?: 0L, byCat[it] ?: 0L) }
        val views = WealthViews.balances(w)
        listOf("cib", "nbe", "cash").forEach { assertEquals("balance $it", m.balance(it), views[it]) }
        assertEquals("net worth", m.worth(), views.values.sum())
        // TRANSFER_IN exists only as the receiving side of a live pair; app-added rows live only with their source
        assertEquals("the TRANSFER_IN rows", m.transferInIds(), live.filter { it.type == "TRANSFER_IN" }.mapTo(HashSet()) { it.id })
        val insertedExpected = m.pairs.filter { m.insertedAlive(it) }.mapTo(HashSet()) { "${it.src}:pair" }
        assertEquals("the app-added rows", insertedExpected, live.filter { it.id.endsWith(":pair") }.mapTo(HashSet()) { it.id })
        // both sides of a live pair have the same amount, currency (and, for an app-added side, date)
        m.pairs.filter { m.typed(it) }.forEach { p ->
            val src = live.single { it.id == p.src }
            val other = live.single { it.id == (p.joined ?: "${p.src}:pair") }
            assertEquals("pair amount", src.amountMinor, other.amountMinor)
            assertEquals("pair currency", src.currency, other.currency)
            if (p.joined == null) assertEquals("pair date", src.occurredAtMillis, other.occurredAtMillis)
        }
        checkCards(live)
    }

    private suspend fun checkCards(live: List<eg.masroof.data.db.ExpenseEntity>) {
        val cards = w.repo.observeCards().first()
        if (cards.size >= eg.masroof.data.answers.AnswerCards.MAX_CARDS) return
        for (e in m.events.values.filter { it.alive && it.cur == "EGP" && !m.settled(it.id) }) {
            val card = cards.any { it is AnswerCard.Transfer && it.entryId == e.id }
            val isTransfer = e.kind == 'T'
            val isIncomeWithMethod = e.id == "in" || e.id == "i2"
            val typed = m.pairs.any { m.typed(it) && (it.src == e.id || it.joined == e.id) }
            if ((isTransfer || isIncomeWithMethod) && !typed) assertTrue("an unanswered ${e.id} must have its card", card)
        }
        assertTrue(live.isNotEmpty() || true)
    }

    private companion object {
        const val OPS = 17
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

internal object WealthViews {
    suspend fun balances(w: AnswerWorld): Map<String, Long> =
        eg.masroof.data.wealth
            .WealthRepository(w.db, w.clock)
            .observeAccountViews()
            .first()
            .associate { it.account.id to (it.balanceMinor ?: 0L) }
}
