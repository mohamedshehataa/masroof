package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.EntryLink
import eg.masroof.data.answers.LinkKind
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.MergeResult
import eg.masroof.data.edit.MergeUndo
import eg.masroof.data.edit.RestoreOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

private const val SEQUENCES = 150
private const val STEPS = 24
private const val TWO_HOURS_MIN = 120

/**
 * STORY-173: a seeded generated test over random sequences of add manual (untimed or timed), add capture (EGP or USD, EXPENSE or
 * INCOME), edit, delete, restore, Merge (also from a stale card), Undo, Keep both, a link added to an entry (also between a merge
 * and its Undo), a restore of a merged-away typed entry, a restart and the real Erase all data (the Room wipe). The expectations
 * come from a tiny model that merges ONLY on a tap and knows the pair rule from calendar days and minutes (never from the code
 * under test): after every step the month total must equal what the model counts, the income must too, and the set of cards must
 * equal the model's pairs. The base seed comes from `-Dduplicate.seed` (default 1; forwarded by build.gradle.kts); a failure
 * prints the seed and the steps so far.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LargeClass", "LongParameterList")
class DuplicateGeneratedTest {
    private class M(
        val id: String,
        val manual: Boolean,
        var amount: Long,
        val day: String,
        val time: String,
        val channel: String,
        val currency: String,
        val type: String,
        val timed: Boolean,
    ) {
        var live = true
        var deletedByOp = false
        var carriesManual = false
        var mergedAway = false

        val minute: Int
            get() {
                val (h, m) = time.split(":").map { it.toInt() }
                return (if (day == "2026-11-05") 0 else 1440) + h * 60 + m
            }
    }

    /** What the generator really did across all sequences: a generator that never merges proves nothing. */
    private val seen = mutableMapOf<String, Int>()

    private fun count(what: String) {
        seen[what] = (seen[what] ?: 0) + 1
    }

    private val amounts = longArrayOf(DUP_AMOUNT, 99_000L)
    private val days = listOf("2026-11-05", "2026-11-06")
    private val times = listOf("00:01", "01:30", "12:00", "14:05", "23:59")

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = Unit

    @Test
    fun `REQ-DEDUP-063 REQ-DEDUP-064 REQ-DEDUP-065 random sequences count every purchase once and never merge without a tap`() =
        runTest(timeout = 15.minutes) {
            val base = System.getProperty("duplicate.seed")?.toLongOrNull() ?: 1L
            for (n in 0 until SEQUENCES) {
                val seed = base * 100_000 + n
                val trace = mutableListOf<String>()
                try {
                    sequence(seed, trace)
                } catch (e: AssertionError) {
                    fail("seed $seed failed: ${e.message}\nsteps: ${trace.joinToString(" | ")}")
                }
            }
            val wanted =
                listOf(
                    "merged",
                    "outOfDate",
                    "undoOk",
                    "undoRefused",
                    "keepBoth",
                    "restart",
                    "wipe",
                    "delete",
                    "edit",
                    "timedMerged",
                    "usdSeen",
                    "incomeMerged",
                    "linkBetween",
                    "restoreMerged",
                )
            for (what in wanted) assertTrue("the generator never did '$what': $seen", (seen[what] ?: 0) > 0)
        }

    private suspend fun sequence(
        seed: Long,
        trace: MutableList<String>,
    ) {
        val rnd = Random(seed)
        val w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        try {
            var repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
            val model = mutableListOf<M>()
            val kept = mutableSetOf<String>()
            val linked = mutableSetOf<String>()
            val shown = mutableListOf<Pair<String, String>>() // cards the user once saw: tapping one later is a stale tap
            var token: MergeUndo? = null
            var tokenPair: Pair<M, M>? = null
            var tokenValid = false
            var next = 0
            var sources = 0

            fun close(
                m: M,
                c: M,
            ) = if (!m.timed) m.day == c.day else kotlin.math.abs(m.minute - c.minute) <= TWO_HOURS_MIN

            fun expectedKeys(): Set<String> =
                buildSet {
                    for (m in model.filter { it.live && it.manual && it.id !in linked }) {
                        for (c in model.filter { it.live && !it.manual && !it.carriesManual && it.id !in linked }) {
                            val same = m.amount == c.amount && m.currency == c.currency && m.type == c.type
                            if (same && close(m, c) && "${m.id}|${c.id}" !in kept) add("${m.id}|${c.id}")
                        }
                    }
                }

            suspend fun check(step: String) {
                trace += step
                val cards = repo.observeCards().first().map { it.key }
                val want = expectedKeys()
                if (cards.toSet() != want || cards.size != want.size) fail("cards $cards, model says $want")
                val (from, to) = CairoTime.monthRangeMillis(ANSWER_NOW)
                val total =
                    w.db
                        .expenses()
                        .observeTotal(from, to)
                        .first()
                val expected = model.filter { it.live && it.currency == "EGP" && it.type == "EXPENSE" }.sumOf { it.amount }
                if (total != expected) fail("total $total, model says $expected (a purchase counted twice or a merge nobody tapped)")
                val income =
                    w.db
                        .expenses()
                        .all()
                        .filter { it.deletedAtMillis == null && it.type == "INCOME" }
                        .sumOf { it.amountMinor }
                val wantIncome = model.filter { it.live && it.type == "INCOME" }.sumOf { it.amount }
                if (income != wantIncome) fail("income $income, model says $wantIncome")
                if (w.db
                        .sources()
                        .all()
                        .size != sources
                ) {
                    fail("an expense_source row was lost or made up")
                }
                for (c in model.filter { it.live && it.carriesManual }) {
                    val ch =
                        w.db
                            .sources()
                            .forExpense(c.id)
                            .map { it.channel }
                            .toSet()
                    if ("MANUAL" !in ch || c.channel !in ch) fail("survivor ${c.id} lost a source: $ch")
                }
            }

            suspend fun add(m: M) {
                model += m
                val e =
                    if (m.manual) {
                        manualEntry(m.id, m.amount, cairo(m.day, if (m.timed) m.time else "12:00"), timeKnown = m.timed, type = m.type)
                    } else {
                        captureEntry(m.id, m.amount, cairo(m.day, m.time), source = m.channel, type = m.type)
                    }
                w.db.expenses().insert(e.copy(currency = m.currency))
                w.db.sources().insert(if (m.manual) manualSource(m.id) else captureSource(m.id, m.channel))
                sources++
                if (m.currency != "EGP") count("usdSeen")
            }

            fun touchesToken(m: M) {
                if (tokenPair?.second === m || tokenPair?.first === m) tokenValid = false
            }

            repeat(STEPS) {
                val live = model.filter { it.live }
                when (rnd.nextInt(18)) {
                    0, 1 -> {
                        val timed = rnd.nextInt(3) == 0
                        val m =
                            M(
                                "m${next++}",
                                true,
                                amounts[rnd.nextInt(amounts.size)],
                                days.random(rnd),
                                if (timed) times.random(rnd) else "12:00",
                                "MANUAL",
                                listOf("EGP", "EGP", "USD").random(rnd),
                                listOf("EXPENSE", "EXPENSE", "INCOME").random(rnd),
                                timed,
                            )
                        add(m)
                        check("manual ${m.id} ${m.amount} ${m.day} ${m.time} timed=$timed ${m.currency} ${m.type}")
                    }
                    2, 3 -> {
                        val m =
                            M(
                                "c${next++}",
                                false,
                                amounts[rnd.nextInt(amounts.size)],
                                days.random(rnd),
                                times.random(rnd),
                                listOf("SMS", "PUSH").random(rnd),
                                listOf("EGP", "EGP", "USD").random(rnd),
                                listOf("EXPENSE", "EXPENSE", "INCOME").random(rnd),
                                true,
                            )
                        add(m)
                        check("capture ${m.id} ${m.amount} ${m.day} ${m.time} ${m.currency} ${m.type}")
                    }
                    4 ->
                        if (live.isNotEmpty()) {
                            val m = live.random(rnd)
                            val other = amounts.first { it != m.amount }
                            w.editor.update(m.id, ExpenseChanges(amountMinor = other))
                            m.amount = other
                            count("edit")
                            touchesToken(m)
                            check("edit ${m.id} -> $other")
                        }
                    5 ->
                        if (live.isNotEmpty()) {
                            val m = live.random(rnd)
                            w.editor.delete(m.id)
                            m.live = false
                            m.deletedByOp = true
                            count("delete")
                            touchesToken(m)
                            check("delete ${m.id}")
                        }
                    6 ->
                        model.filter { it.deletedByOp && !it.live }.randomOrNull(rnd)?.let { m ->
                            w.editor.restore(m.id)
                            m.live = true
                            m.deletedByOp = false
                            touchesToken(m)
                            check("restore ${m.id}")
                        }
                    7, 8, 9 -> {
                        val cards = repo.observeCards().first()
                        cards.forEach { shown += it.manual.id to it.capture.id }
                        // a fresh card, or one the user saw earlier that may be out of date now
                        val (mid, cid) =
                            if (shown.isNotEmpty() && rnd.nextInt(3) == 0) {
                                shown.random(rnd)
                            } else {
                                cards.randomOrNull(rnd)?.let { it.manual.id to it.capture.id } ?: return@repeat
                            }
                        val shouldMerge = "$mid|$cid" in expectedKeys()
                        val result = repo.merge(mid, cid)
                        if (shouldMerge != (result is MergeResult.Merged)) fail("merge $mid|$cid: model says $shouldMerge, got $result")
                        count(if (result is MergeResult.Merged) "merged" else "outOfDate")
                        if (result is MergeResult.Merged) {
                            val m = model.first { it.id == mid }
                            val c = model.first { it.id == cid }
                            m.live = false
                            m.mergedAway = true
                            c.carriesManual = true
                            token = result.undo
                            tokenPair = m to c
                            tokenValid = true
                            if (m.timed) count("timedMerged")
                            if (m.type == "INCOME") count("incomeMerged")
                        }
                        check("merge $mid|$cid ${result::class.simpleName}")
                    }
                    10, 11 -> {
                        val t = token
                        val pair = tokenPair
                        if (t != null && pair != null) {
                            val ok = repo.undo(t)
                            if (ok != tokenValid) fail("undo: model says $tokenValid, got $ok")
                            count(if (ok) "undoOk" else "undoRefused")
                            if (ok) {
                                pair.first.live = true
                                pair.first.mergedAway = false
                                pair.second.carriesManual = false
                            }
                            // one Undo per merge, valid or not
                            tokenValid = false
                            check("undo $ok")
                        }
                    }
                    12 -> {
                        val cards = repo.observeCards().first()
                        cards.randomOrNull(rnd)?.let {
                            repo.keepBoth(it.manual.id, it.capture.id)
                            kept += it.key
                            count("keepBoth")
                            check("keep both ${it.key}")
                        }
                    }
                    13 ->
                        model.filter { it.mergedAway && !it.live }.randomOrNull(rnd)?.let { m ->
                            // never comes back by itself: its sources live on the survivor, so it would count twice
                            if (w.editor.restore(m.id)) fail("a merged-away entry ${m.id} was restored")
                            if (w.editor.restoreOutcome(m.id) != RestoreOutcome.MergedAway) fail("no reason for ${m.id}")
                            count("restoreMerged")
                            check("restore merged-away ${m.id}")
                        }
                    14 -> {
                        val survivor = tokenPair?.second
                        val target = if (survivor != null && rnd.nextBoolean()) survivor else live.randomOrNull(rnd)
                        if (target != null) {
                            val link = EntryLink(LinkKind.TRANSFER, target.id, "ghost${next++}", nonce = "g$next")
                            w.prefs.edit { it.copy(links = it.links + link) }
                            linked += target.id
                            if (survivor === target) count("linkBetween")
                            touchesToken(target)
                            check("link ${target.id}")
                        }
                    }
                    15 -> {
                        repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
                        count("restart")
                        check("restart")
                    }
                    else ->
                        if (rnd.nextInt(4) == 0) {
                            // the real "Erase all data": the Room wipe and the answers store
                            RoomDataWiper(
                                w.db,
                                NoInsightsCache,
                                androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                                Dispatchers.Unconfined,
                                answerPrefs = w.prefs,
                            ).wipe()
                            model.clear()
                            kept.clear()
                            linked.clear()
                            shown.clear()
                            sources = 0
                            tokenValid = false
                            count("wipe")
                            check("wipe")
                        } else {
                            repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
                            count("restart")
                            check("restart")
                        }
                }
            }
        } finally {
            w.close()
        }
    }

    @Test
    fun `the generator's Cairo helper puts 00-01 Cairo on the evening before in UTC`() {
        assertEquals("2026-11-05T22:01:00Z", Instant.ofEpochMilli(cairo("2026-11-06", "00:01")).toString())
    }
}
