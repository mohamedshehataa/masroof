package eg.masroof.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/**
 * Port of the server's BudgetProgressTest and the status cases of BudgetServiceTest (REQ-AN-153..155), plus the story's own
 * examples and unhappy paths. Amounts are EGP minor units.
 */
class BudgetCalculatorTest {
    private val default = listOf(50, 80, 100)
    private val food = "food"
    private val today = LocalDate.of(2026, 10, 25)
    private val created = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    private fun row(
        date: LocalDate,
        amount: Long,
        category: String? = null,
        type: String = "EXPENSE",
        currency: String = "EGP",
        status: String = "CONFIRMED",
    ) = AnalyticsRow(type, status, currency, amount, BusinessCalendar.startOfDay(date).plusSeconds(12 * 3600).toEpochMilli(), category)

    private fun budget(
        category: String? = null,
        kind: BudgetPeriodKind = BudgetPeriodKind.MONTHLY,
        amount: Long,
        rollover: Boolean = false,
        createdAt: Long = created,
        id: String = "b-${category ?: "total"}-$kind",
    ) = BudgetSpec(id, category, kind, amount, rollover, BudgetSpec.DEFAULT_THRESHOLDS, createdAt)

    private fun status(
        b: BudgetSpec,
        rows: List<AnalyticsRow>,
        spec: String = "current",
        now: LocalDate = today,
        startDay: Int = 1,
        cats: List<AnalyticsCategory> = emptyList(),
        cash: Boolean = false,
    ) = BudgetCalculator.statusOf(b, AnalyticsPeriod.parse(spec, now, startDay), startDay, now, rows, cats, cash)

    private fun cat(
        id: String,
        parent: String? = null,
    ) = AnalyticsCategory(id, parent, id, id, null, false)

    // ---- BudgetProgressTest ----

    @Test
    fun `REQ-AN-153 progress in basis points rounds half away from zero`() {
        assertEquals(3333, BudgetProgress.of(1, 3, default).progressBps)
        assertEquals(6667, BudgetProgress.of(2, 3, default).progressBps)
        assertEquals(1250, BudgetProgress.of(1, 8, default).progressBps)
        assertEquals(625, BudgetProgress.of(1, 16, default).progressBps)
        assertEquals(1, BudgetProgress.of(5, 80_000, default).progressBps)
    }

    @Test
    fun `REQ-AN-153 thresholds 50 80 100 fire exactly at the boundary`() {
        assertEquals(emptyList<Int>(), BudgetProgress.of(4_999, 10_000, default).alertsFired)
        assertEquals(listOf(50), BudgetProgress.of(5_000, 10_000, default).alertsFired)
        assertEquals(50, BudgetProgress.of(7_999, 10_000, default).thresholdReached())
        assertEquals(listOf(50, 80), BudgetProgress.of(8_000, 10_000, default).alertsFired)
        assertEquals(100, BudgetProgress.of(10_000, 10_000, default).thresholdReached())
        assertEquals(12_000, BudgetProgress.of(12_000, 10_000, default).progressBps)
    }

    @Test
    fun `REQ-AN-153 custom thresholds above 100 percent`() {
        assertEquals(listOf(100, 150), BudgetProgress.of(15_000, 10_000, listOf(100, 150)).alertsFired)
        assertEquals(listOf(50, 80, 100), BudgetProgress.of(10_000, 10_000, listOf(100, 50, 80)).alertsFired)
    }

    @Test
    fun `REQ-AN-153 no spend, refunds beyond spend, huge overspend`() {
        assertEquals(0, BudgetProgress.of(0, 10_000, default).progressBps)
        assertEquals(0, BudgetProgress.of(-500, 10_000, default).progressBps)
        assertEquals(emptyList<Int>(), BudgetProgress.of(-500, 10_000, default).alertsFired)
        assertEquals(BudgetProgress.MAX_BPS, BudgetProgress.of(1_000_000_000_000L, 1, default).progressBps)
        assertEquals(1_000_000, BudgetProgress.of(100_000_000L, 1, default).progressBps)
        assertEquals(BudgetProgress.MAX_BPS, BudgetProgress.of(Long.MAX_VALUE, 1, default).progressBps)
        assertEquals(BudgetProgress.MAX_BPS, BudgetProgress.of(Long.MAX_VALUE, Long.MAX_VALUE / 200, default).progressBps)
    }

    @Test
    fun `REQ-AN-154 a limit eaten by rollover is over budget never 0 percent`() {
        val none = BudgetProgress.of(0, 0, default)
        assertEquals(10_000, none.progressBps)
        assertEquals(listOf(50, 80, 100), none.alertsFired)
        val negative = BudgetProgress.of(0, -5_000, listOf(100, 150))
        assertEquals(10_000, negative.progressBps)
        assertEquals(listOf(100), negative.alertsFired)
        val spent = BudgetProgress.of(100, -50, listOf(100, 150, 200))
        assertEquals(BudgetProgress.MAX_BPS, spent.progressBps)
        assertEquals(listOf(100, 150, 200), spent.alertsFired)
    }

    @Test
    fun `REQ-AN-153 progress matches BigInteger arithmetic for 1000 seeded cases`() {
        val rnd = Random(180)
        repeat(1_000) { i ->
            val spent = if (i % 5 == 0) rnd.nextLong(1, Long.MAX_VALUE) else rnd.nextLong(1, 1_000_000_000_000L)
            val limit = if (i % 7 == 0) rnd.nextLong(1, Long.MAX_VALUE) else rnd.nextLong(1, 1_000_000_000L)
            val exact =
                BigInteger
                    .valueOf(spent)
                    .multiply(BigInteger.valueOf(20_000))
                    .add(BigInteger.valueOf(limit))
                    .divide(BigInteger.valueOf(limit).multiply(BigInteger.TWO))
                    .min(BigInteger.valueOf(BudgetProgress.MAX_BPS.toLong()))
            assertEquals("spent=$spent limit=$limit", exact.toInt(), BudgetProgress.of(spent, limit, default).progressBps)
        }
    }

    // ---- BudgetServiceTest status cases ----

    @Test
    fun `REQ-AN-153 month start day 25 period runs 25 Oct to 24 Nov with DST correct bounds`() {
        val rows =
            listOf(
                row(LocalDate.of(2026, 10, 25), 10_000),
                row(LocalDate.of(2026, 11, 24), 20_000),
                row(LocalDate.of(2026, 10, 24), 99_999),
                row(LocalDate.of(2026, 11, 25), 88_888),
            )
        val s = status(budget(amount = 100_000), rows, startDay = 25)
        assertEquals("2026-10", s.period.label)
        assertEquals(LocalDate.of(2026, 10, 25), s.period.from)
        assertEquals(LocalDate.of(2026, 11, 24), s.period.to)
        assertEquals(30_000, s.spent)
        assertEquals(70_000, s.remaining)
        assertEquals(3_000, s.progress.progressBps)
        assertEquals(30, s.daysLeft)
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), s.period.start())
        assertEquals(Instant.parse("2026-11-24T22:00:00Z"), s.period.endExclusive())
    }

    @Test
    fun `REQ-AN-153 category budget sums the category rows thresholds and on track`() {
        val rows = listOf(row(LocalDate.of(2026, 10, 3), 4_000, food), row(LocalDate.of(2026, 10, 10), 4_500, food))
        val s = status(budget(food, amount = 10_000), rows)
        assertEquals(8_500, s.spent)
        assertEquals(8_500, s.progress.progressBps)
        assertEquals(listOf(50, 80), s.progress.alertsFired)
        assertEquals(1_500, s.remaining)
        assertFalse(s.onTrack)
        assertEquals(6, s.daysLeft)
    }

    @Test
    fun `REQ-AN-153 overspent budget reports negative remaining and 100 percent`() {
        val s = status(budget(amount = 10_000), listOf(row(LocalDate.of(2026, 10, 5), 12_345)), spec = "2026-10")
        assertEquals(-2_345, s.remaining)
        assertEquals(12_345, s.progress.progressBps)
        assertEquals(100, s.progress.thresholdReached())
    }

    @Test
    fun `REQ-AN-154 rollover carries last period leftover and overspend forward`() {
        val rows = mutableListOf(row(LocalDate.of(2026, 9, 10), 3_000), row(LocalDate.of(2026, 10, 10), 5_000))
        val b = budget(amount = 10_000, rollover = true)
        val s = status(b, rows)
        assertEquals(7_000, s.rolledOver)
        assertEquals(12_000, s.remaining)
        assertEquals(2_941, s.progress.progressBps)
        rows += row(LocalDate.of(2026, 9, 11), 12_000)
        val over = status(b, rows)
        assertEquals(-5_000, over.rolledOver)
        assertEquals(0, over.remaining)
        assertEquals(10_000, over.progress.progressBps)
    }

    @Test
    fun `REQ-AN-154 rollover that uses up the limit reads as over and off track`() {
        val s = status(budget(amount = 10_000, rollover = true), listOf(row(LocalDate.of(2026, 9, 10), 20_000)))
        assertEquals(-10_000, s.rolledOver)
        assertEquals(10_000, s.progress.progressBps)
        assertEquals(100, s.progress.thresholdReached())
        assertFalse(s.onTrack)
        val spent = status(budget(amount = 10_000, rollover = true), listOf(row(LocalDate.of(2026, 9, 10), 20_000), row(today, 1)))
        assertEquals(BudgetProgress.MAX_BPS, spent.progress.progressBps)
        assertEquals(listOf(50, 80, 100), spent.progress.alertsFired)
    }

    @Test
    fun `REQ-AN-153 already over the limit is never on track`() {
        assertFalse(status(budget(amount = 10_000), listOf(row(LocalDate.of(2026, 10, 25), 10_001))).onTrack)
    }

    @Test
    fun `REQ-AN-155 total remaining for the month summary none for weeks or without a total`() {
        val oct = AnalyticsPeriod.parse("2026-10", today, 1)
        val rows = listOf(row(LocalDate.of(2026, 10, 5), 4_000))

        fun remaining(
            budgets: List<BudgetSpec>,
            p: AnalyticsPeriod = oct,
        ) = BudgetCalculator.totalRemaining(budgets, p, 1, today, rows, emptyList(), false)
        assertNull(remaining(emptyList()))
        assertNull(remaining(listOf(budget(food, amount = 5_000))))
        assertEquals(6_000L, remaining(listOf(budget(food, amount = 5_000), budget(amount = 10_000))))
        assertNull(remaining(listOf(budget(amount = 10_000)), AnalyticsPeriod.week(LocalDate.of(2026, 10, 19))))
        assertNull(remaining(listOf(budget(amount = 10_000, kind = BudgetPeriodKind.WEEKLY))))
    }

    @Test
    fun `REQ-AN-154 a budget created this month does not roll over`() {
        val b = budget(amount = 10_000, rollover = true, createdAt = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli())
        assertEquals(0, status(b, listOf(row(LocalDate.of(2026, 9, 10), 3_000))).rolledOver)
    }

    @Test
    fun `REQ-AN-154 only one period back counts and a budget from the previous period rolls over`() {
        val rows = listOf(row(LocalDate.of(2026, 8, 10), 9_000), row(LocalDate.of(2026, 9, 10), 3_000))
        val fromSept = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()
        val s = status(budget(amount = 10_000, rollover = true, createdAt = fromSept), rows)
        assertEquals(7_000, s.rolledOver)
    }

    @Test
    fun `REQ-AN-153 weekly budget uses the ISO week of today`() {
        val rows = listOf(row(LocalDate.of(2026, 10, 19), 1_000), row(LocalDate.of(2026, 10, 18), 7_000))
        val s = status(budget(kind = BudgetPeriodKind.WEEKLY, amount = 5_000), rows)
        assertEquals("2026-W43", s.period.label)
        assertEquals(1_000, s.spent)
        assertEquals(0, s.daysLeft)
    }

    @Test
    fun `REQ-AN-153 monthly budget asked for a week resolves to the containing month`() {
        val week = AnalyticsPeriod.week(LocalDate.of(2026, 10, 19))
        assertEquals("2026-10", BudgetCalculator.periodFor(BudgetPeriodKind.MONTHLY, week, 1, today).label)
    }

    // ---- story examples and unhappy paths ----

    @Test
    fun `REQ-AN-153 groceries budget counts subcategories and only eligible spend`() {
        val cats = listOf(cat("groc"), cat("veg", "groc"), cat("fun"))
        val d = LocalDate.of(2026, 10, 10)
        val rows =
            listOf(
                row(d, 250_000, "groc"),
                row(d, 100_000, "veg"),
                row(d, 20_000, "veg", type = "REFUND"),
                row(d, 70_000, "groc", status = "PENDING_REVIEW"),
                row(d, 900_000, "fun"),
                row(d, 50_000, "groc", type = "TRANSFER"),
                row(d, 50_000, "groc", type = "TRANSFER_IN"),
                row(d, 50_000, "groc", type = "INCOME"),
                row(d, 50_000, "groc", type = "CASH_WITHDRAWAL"),
                row(d, 50_000, "groc", currency = "USD"),
                row(d, 50_000, "groc", status = "REJECTED"),
            )
        val s = status(budget("groc", amount = 400_000), rows, cats = cats)
        assertEquals(400_000, s.spent)
    }

    @Test
    fun `REQ-AN-153 spec example groceries 3000 of 4000 is 75 percent with alert 50`() {
        val cats = listOf(cat("groc"), cat("veg", "groc"))
        val d = LocalDate.of(2026, 10, 10)
        val rows = listOf(row(d, 250_000, "groc"), row(d, 100_000, "veg"), row(d, 50_000, "veg", type = "REFUND"))
        val s = status(budget("groc", amount = 400_000), rows, cats = cats)
        assertEquals(300_000, s.spent)
        assertEquals(100_000, s.remaining)
        assertEquals(7_500, s.progress.progressBps)
        assertEquals(listOf(50), s.progress.alertsFired)
    }

    @Test
    fun `REQ-AN-153 cash withdrawals count only with the setting and transfers never`() {
        val d = LocalDate.of(2026, 10, 10)
        val rows =
            listOf(
                row(d, 1_000),
                row(d, 500, type = "CASH_WITHDRAWAL"),
                row(d, 700, type = "TRANSFER"),
                row(d, 900, type = "TRANSFER_IN"),
            )
        assertEquals(1_000, status(budget(amount = 10_000), rows).spent)
        assertEquals(1_500, status(budget(amount = 10_000), rows, cash = true).spent)
    }

    @Test
    fun `REQ-AN-153 a category budget is not fed by a sibling or a missing category`() {
        val cats = listOf(cat("groc"), cat("fun"))
        val d = LocalDate.of(2026, 10, 10)
        val rows = listOf(row(d, 100, "fun"), row(d, 100, null), row(d, 100, "gone"))
        assertEquals(0, status(budget("groc", amount = 1_000), rows, cats = cats).spent)
        assertEquals(300, status(budget(amount = 1_000), rows, cats = cats).spent)
    }

    @Test
    fun `REQ-AN-153 spend far above 100 times the limit is capped without overflow`() {
        val s = status(budget(amount = 1), listOf(row(LocalDate.of(2026, 10, 10), 100_000_000L)))
        assertEquals(1_000_000, s.progress.progressBps)
        val two = listOf(row(LocalDate.of(2026, 10, 10), Long.MAX_VALUE), row(LocalDate.of(2026, 10, 11), Long.MAX_VALUE))
        val huge = status(budget(amount = 1), two)
        assertEquals(Long.MAX_VALUE, huge.spent)
        assertEquals(1 - Long.MAX_VALUE, huge.remaining)
        assertEquals(1_000_000, huge.progress.progressBps)
    }

    @Test
    fun `REQ-AN-153 weekly period is the Cairo ISO week at the Sunday Monday boundary`() {
        // Cairo is UTC+3 until 30 Oct 2026: Sunday 23:30 Cairo = 20:30Z, Monday 00:10 Cairo = 21:10Z.
        fun at(iso: String) = AnalyticsRow("EXPENSE", "CONFIRMED", "EGP", 100, Instant.parse(iso).toEpochMilli(), null)
        val rows = listOf(at("2026-10-25T20:30:00Z"), at("2026-10-25T21:10:00Z"), at("2026-10-18T21:00:00Z"))
        val b = budget(kind = BudgetPeriodKind.WEEKLY, amount = 1_000)
        val w43 = status(b, rows, spec = "2026-W43")
        assertEquals(200, w43.spent)
        val w44 = status(b, rows, spec = "2026-W44", now = LocalDate.of(2026, 10, 28))
        assertEquals("2026-W44", w44.period.label)
        assertEquals(100, w44.spent)
    }

    @Test
    fun `REQ-AN-153 weekly boundary after the clocks go back uses UTC plus 2`() {
        // Monday 2 Nov 2026 00:10 Cairo = Sunday 1 Nov 22:10Z (DST ended 30 Oct); Sunday 1 Nov 23:30 Cairo = 21:30Z.
        fun at(iso: String) = AnalyticsRow("EXPENSE", "CONFIRMED", "EGP", 100, Instant.parse(iso).toEpochMilli(), null)
        val rows = listOf(at("2026-11-01T21:30:00Z"), at("2026-11-01T22:10:00Z"))
        val b = budget(kind = BudgetPeriodKind.WEEKLY, amount = 1_000)
        assertEquals(100, status(b, rows, spec = "2026-W44", now = LocalDate.of(2026, 11, 1)).spent)
        assertEquals(100, status(b, rows, spec = "2026-W45", now = LocalDate.of(2026, 11, 2)).spent)
    }

    @Test
    fun `REQ-AN-154 rollover works for weekly budgets one week back`() {
        val rows = listOf(row(LocalDate.of(2026, 10, 14), 300), row(LocalDate.of(2026, 10, 20), 200))
        val s = status(budget(kind = BudgetPeriodKind.WEEKLY, amount = 1_000, rollover = true), rows)
        assertEquals(700, s.rolledOver)
        assertEquals(1_500, s.remaining)
    }

    @Test
    fun `REQ-AN-154 rollover does not overflow at the extremes`() {
        val refunds =
            listOf(
                row(LocalDate.of(2026, 9, 10), Long.MAX_VALUE, type = "REFUND"),
                row(LocalDate.of(2026, 9, 11), Long.MAX_VALUE, type = "REFUND"),
            )
        val up = status(budget(amount = Long.MAX_VALUE, rollover = true), refunds)
        assertEquals(Long.MAX_VALUE, up.rolledOver)
        assertEquals(Long.MAX_VALUE, up.remaining)
        val spends = listOf(row(LocalDate.of(2026, 9, 10), Long.MAX_VALUE), row(LocalDate.of(2026, 9, 11), Long.MAX_VALUE))
        val down = status(budget(amount = 1, rollover = true), spends)
        assertEquals(1 - Long.MAX_VALUE, down.rolledOver)
        assertEquals(10_000, down.progress.progressBps)
        assertFalse(down.onTrack)
    }

    @Test
    fun `REQ-AN-155 total budget 20000 with start day 25 and 9000 spent`() {
        val now = LocalDate.of(2026, 11, 5)
        val rows = listOf(row(LocalDate.of(2026, 10, 30), 900_000))
        val b = budget(amount = 2_000_000)
        val s = status(b, rows, now = now, startDay = 25)
        assertEquals("2026-10", s.period.label)
        assertEquals(19, s.daysLeft)
        assertEquals(1_100_000, s.remaining)
        // 900000 over 12 of 31 days projects to 2,325,000, past the limit.
        assertFalse(s.onTrack)
        assertEquals(1_100_000L, BudgetCalculator.totalRemaining(listOf(b), s.period, 25, now, rows, emptyList(), false))
        val calm = status(b, listOf(row(LocalDate.of(2026, 10, 30), 300_000)), now = now, startDay = 25)
        assertTrue(calm.onTrack)
    }

    @Test
    fun `REQ-AN-155 the analytics summary carries the total budget remaining`() {
        val period = AnalyticsPeriod.parse("current", today, 1)
        val rows = listOf(row(LocalDate.of(2026, 10, 5), 4_000))
        val with = AnalyticsCalculator.compute(period, today, rows, emptyList(), false, listOf(budget(amount = 10_000)))
        assertEquals(6_000L, with.summary.remainingBudget)
        assertNull(AnalyticsCalculator.compute(period, today, rows, emptyList(), false).summary.remainingBudget)
        val categoryOnly = AnalyticsCalculator.compute(period, today, rows, emptyList(), false, listOf(budget(food, amount = 1)))
        assertNull(categoryOnly.summary.remainingBudget)
    }

    @Test
    fun `REQ-AN-155 two total budgets use the oldest and mark the other as duplicate`() {
        val older = budget(amount = 10_000, createdAt = 1_000, id = "old")
        val newer = budget(amount = 99_999, createdAt = 2_000, id = "new")
        val rows = listOf(row(LocalDate.of(2026, 10, 5), 4_000))
        val period = AnalyticsPeriod.parse("current", today, 1)
        assertEquals(6_000L, BudgetCalculator.totalRemaining(listOf(newer, older), period, 1, today, rows, emptyList(), false))
        val all = BudgetCalculator.statuses(listOf(newer, older, budget(food, amount = 5)), period, 1, today, rows, emptyList(), false)
        assertEquals(listOf(true, false, false), all.map { it.duplicate })
    }
}
