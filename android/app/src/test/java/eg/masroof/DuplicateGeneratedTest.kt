package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.MergeResult
import eg.masroof.data.edit.MergeUndo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

private const val SEQUENCES = 120
private const val STEPS = 20

/**
 * STORY-173: a seeded generated test over random sequences of add manual, add capture, edit, delete, restore, Merge (also from a
 * stale card), Undo, Keep both, restart and Erase. The expectations come from a tiny model that merges ONLY on a tap and knows
 * the pair rule from calendar days (never from instants): after every step the month total must equal what the model counts (each
 * real purchase once, and no merge that nobody tapped), and the set of cards must equal the model's pairs. The base seed comes from
 * `-Dduplicate.seed` (default 1); a failure prints the seed and the steps so far.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LargeClass")
class DuplicateGeneratedTest {
    private class M(
        val id: String,
        val manual: Boolean,
        var amount: Long,
        val day: String,
        val time: String,
        val channel: String,
    ) {
        var live = true
        var deletedByOp = false
        var carriesManual = false
    }

    /** What the generator really did across all sequences: a generator that never merges proves nothing. */
    private val seen = mutableMapOf<String, Int>()

    private fun count(what: String) {
        seen[what] = (seen[what] ?: 0) + 1
    }

    private val amounts = longArrayOf(DUP_AMOUNT, 99_000L)
    private val days = listOf("2026-11-05", "2026-11-06")
    private val times = listOf("00:01", "01:30", "12:00", "14:05", "23:59")

    @Test
    fun `REQ-DEDUP-063 REQ-DEDUP-064 REQ-DEDUP-065 random sequences count every purchase once and never merge without a tap`() =
        runTest(timeout = 10.minutes) {
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
            for (what in listOf("merged", "outOfDate", "undoOk", "undoRefused", "keepBoth", "restart", "erase", "delete", "edit")) {
                assertTrue("the generator never did '$what': $seen", (seen[what] ?: 0) > 0)
            }
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
            val shown = mutableListOf<Pair<String, String>>() // cards the user once saw: tapping one later is a stale tap
            var token: MergeUndo? = null
            var tokenPair: Pair<M, M>? = null
            var tokenValid = false
            var next = 0
            var sources = 0

            fun expectedKeys(): Set<String> =
                buildSet {
                    for (m in model.filter { it.live && it.manual }) {
                        for (c in model.filter { it.live && !it.manual && !it.carriesManual }) {
                            if (m.amount == c.amount && m.day == c.day && "${m.id}|${c.id}" !in kept) add("${m.id}|${c.id}")
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
                val expected = model.filter { it.live }.sumOf { it.amount }
                if (total != expected) fail("total $total, model says $expected (a purchase counted twice or a merge nobody tapped)")
                if (w.db
                        .sources()
                        .all()
                        .size != sources
                ) {
                    fail("an expense_source row was lost or made up")
                }
                // every live capture that carries a typed source also holds both sources
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
                        manualEntry(m.id, m.amount, cairo(m.day, "12:00"))
                    } else {
                        captureEntry(m.id, m.amount, cairo(m.day, m.time), source = m.channel)
                    }
                w.db.expenses().insert(e)
                w.db.sources().insert(if (m.manual) manualSource(m.id) else captureSource(m.id, m.channel))
                sources++
            }

            repeat(STEPS) {
                val live = model.filter { it.live }
                when (rnd.nextInt(14)) {
                    0, 1 -> {
                        val m = M("m${next++}", true, amounts[rnd.nextInt(amounts.size)], days.random(rnd), "", "MANUAL")
                        add(m)
                        check("manual ${m.id} ${m.amount} ${m.day}")
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
                            )
                        add(m)
                        check("capture ${m.id} ${m.amount} ${m.day} ${m.time}")
                    }
                    4 ->
                        if (live.isNotEmpty()) {
                            val m = live.random(rnd)
                            val other = amounts.first { it != m.amount }
                            w.editor.update(m.id, ExpenseChanges(amountMinor = other))
                            m.amount = other
                            count("edit")
                            if (tokenPair?.second === m) tokenValid = false
                            check("edit ${m.id} -> $other")
                        }
                    5 ->
                        if (live.isNotEmpty()) {
                            val m = live.random(rnd)
                            w.editor.delete(m.id)
                            m.live = false
                            m.deletedByOp = true
                            count("delete")
                            if (tokenPair?.second === m) tokenValid = false
                            check("delete ${m.id}")
                        }
                    6 ->
                        model.filter { it.deletedByOp && !it.live }.randomOrNull(rnd)?.let { m ->
                            w.editor.restore(m.id)
                            m.live = true
                            m.deletedByOp = false
                            if (tokenPair?.second === m) tokenValid = false
                            check("restore ${m.id}")
                        }
                    7, 8, 9 -> {
                        val cards = repo.observeCards().first()
                        cards.forEach { shown += it.manual.id to it.capture.id }
                        // a fresh card, or one the user saw earlier that may be out of date now
                        val (mid, cid) =
                            if (shown.isNotEmpty() &&
                                rnd.nextInt(3) == 0
                            ) {
                                shown.random(rnd)
                            } else {
                                cards.randomOrNull(rnd)?.let { it.manual.id to it.capture.id }
                                    ?: return@repeat
                            }
                        val shouldMerge = "$mid|$cid" in expectedKeys()
                        val result = repo.merge(mid, cid)
                        if (shouldMerge != (result is MergeResult.Merged)) fail("merge $mid|$cid: model says $shouldMerge, got $result")
                        count(if (result is MergeResult.Merged) "merged" else "outOfDate")
                        if (result is MergeResult.Merged) {
                            val m = model.first { it.id == mid }
                            val c = model.first { it.id == cid }
                            m.live = false
                            c.carriesManual = true
                            token = result.undo
                            tokenPair = m to c
                            tokenValid = true
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
                    else ->
                        if (rnd.nextInt(5) == 0) {
                            w.prefs.clear()
                            kept.clear()
                            count("erase")
                            tokenValid = false
                            check("erase")
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
