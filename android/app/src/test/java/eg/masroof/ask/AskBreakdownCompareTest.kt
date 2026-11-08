package eg.masroof.ask

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AskBreakdownCompareTest : AskEngineFixture() {
    // region REQ-AN-161

    @Test
    fun `REQ-AN-161 where did my money go shows top 5 plus Other with shares that sum to 10000 bps and 100 percent`() =
        runTest {
            seedOctober()
            put("h", 40_000, "2026-10-14T10:00:00Z", category = "cat-health")
            put("k", 30_000, "2026-10-14T10:00:00Z", category = "cat-kids")
            put("cl", 20_000, "2026-10-14T10:00:00Z", category = "cat-clothing")
            put("tr", 10_000, "2026-10-14T10:00:00Z", category = "cat-travel")
            val answer = spend("where did my money go this month") as AskAnswer.Breakdown
            assertEquals(current().summary.total, answer.totalMinor)
            assertEquals(6, answer.shares.size)
            assertTrue(answer.shares.last().isOther)
            assertNull(answer.shares.last().query)
            assertEquals(10_000, answer.shares.sumOf { it.bps })
            assertEquals(100, answer.shares.sumOf { it.percent })
            val insightsTop = current().categories.take(5)
            assertEquals(insightsTop.map { it.total }, answer.shares.take(5).map { it.amountMinor })
            assertEquals(current().categories.drop(5).sumOf { it.total }, answer.shares.last().amountMinor)
        }

    @Test
    fun `REQ-AN-161 tapping a category asks the REQ-AN-160 question for it`() =
        runTest {
            seedOctober()
            val answer = spend("where did my money go this month") as AskAnswer.Breakdown
            val groceries = answer.shares.first { it.categoryId == GROCERIES }
            val tapped = ask().answer(requireNotNull(groceries.query)) as AskAnswer.SpendTotal
            assertEquals(groceries.amountMinor, tapped.totalMinor)
            assertTrue(answer.shares.filter { it.isOther }.all { it.query == null })
        }

    @Test
    fun `REQ-AN-161 no spending in the period says so and shows no chart`() =
        runTest {
            seedOctober()
            val answer = spend("where did my money go last month") as AskAnswer.Breakdown
            assertTrue(answer.shares.isEmpty())
            assertEquals("No spending recorded last month", en().text(answer).headline)
        }

    @Test
    fun `REQ-AN-161 largest remainder always adds up to the whole`() {
        val random = kotlin.random.Random(7)
        repeat(300) {
            val amounts = List(random.nextInt(1, 8)) { random.nextLong(1, 5_000_000) }
            assertEquals(10_000, AskCalculator.largestRemainder(amounts, 10_000).sum())
            assertEquals(100, AskCalculator.largestRemainder(amounts, 100).sum())
        }
        assertEquals(listOf(3334, 3333, 3333), AskCalculator.largestRemainder(listOf(1L, 1L, 1L), 10_000))
        assertEquals(listOf(0, 0), AskCalculator.largestRemainder(listOf(0L, 0L), 100))
        assertEquals(emptyList<Int>(), AskCalculator.largestRemainder(emptyList(), 100))
    }

    // endregion

    // region REQ-AN-162

    @Test
    fun `REQ-AN-162 this month vs last month gives both totals, the change, the 3 biggest movers and two bars`() =
        runTest {
            seedOctober()
            put("s1", 300_000, "2026-09-05T10:00:00Z", category = GROCERIES)
            put("s2", 10_000, "2026-09-06T10:00:00Z", category = DINING)
            put("s3", 90_000, "2026-09-07T10:00:00Z", category = RENT)
            val answer = spend("this month vs last month") as AskAnswer.Compare
            val insights = current().summary
            assertEquals(insights.total, answer.currentMinor)
            assertEquals(insights.previousTotal, answer.previousMinor)
            assertEquals(insights.deltaBps, answer.deltaBps)
            assertEquals(400_000L, answer.previousMinor)
            assertEquals(3, answer.moves.size)
            val diffs = answer.moves.map { Math.abs(it.currentMinor - it.previousMinor) }
            assertEquals(diffs.sortedDescending(), diffs)
            assertEquals(LocalDate.of(2026, 9, 1), answer.previous.from)
            assertEquals("Spent 8,330 EGP this month vs 4,000 EGP last month", en().text(answer).headline)
        }

    @Test
    fun `REQ-AN-162 percent change rounds half away from zero in basis points`() =
        runTest {
            seedCategories()
            put("a", 20_000, "2026-10-05T10:00:00Z", category = GROCERIES)
            put("b", 300, "2026-09-05T10:00:00Z", category = GROCERIES)
            // (20,000 - 300) * 10,000 / 300 = 656,666.67 -> 656,667
            val up = spend("this month vs last month") as AskAnswer.Compare
            assertEquals(656_667, up.deltaBps)
            // asked the other way round, the later period is still the current one
            val swapped = ask().answer(AskQuery.Compare(AskPeriod.LAST_MONTH, AskPeriod.THIS_MONTH)) as AskAnswer.Compare
            assertEquals(LocalDate.of(2026, 10, 1), swapped.current.from)
            assertEquals(656_667, swapped.deltaBps)
        }

    @Test
    fun `REQ-AN-162 a drop rounds half away from zero too`() =
        runTest {
            seedCategories()
            put("a", 100, "2026-10-05T10:00:00Z", category = GROCERIES)
            put("b", 300, "2026-09-05T10:00:00Z", category = GROCERIES)
            // (100 - 300) * 10,000 / 300 = -6,666.67 -> -6,667
            val down = spend("this month vs last month") as AskAnswer.Compare
            assertEquals(-6_667, down.deltaBps)
        }

    @Test
    fun `REQ-AN-162 an empty previous period shows no percent`() =
        runTest {
            seedOctober()
            val answer = spend("this month vs last month") as AskAnswer.Compare
            assertEquals(0L, answer.previousMinor)
            assertNull(answer.deltaBps)
            val text = en().text(answer)
            assertTrue(text.lines.any { it.contains("No spending last month to compare with") })
            assertTrue(text.lines.none { it.contains("%") })
        }

    @Test
    fun `REQ-AN-162 this week vs last week and a filtered comparison work the same way`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-10-20T09:00:00Z")
            put("a", 5_000, "2026-10-19T10:00:00Z", category = GROCERIES)
            put("b", 4_000, "2026-10-13T10:00:00Z", category = GROCERIES)
            put("c", 9_000, "2026-10-13T10:00:00Z", category = DINING)
            val week = spend("this week vs last week") as AskAnswer.Compare
            assertEquals(5_000L, week.currentMinor)
            assertEquals(13_000L, week.previousMinor)
            val filtered = spend("groceries this week vs last week") as AskAnswer.Compare
            assertEquals(4_000L, filtered.previousMinor)
            assertTrue(filtered.moves.isEmpty())
            assertEquals("Groceries", filtered.subject?.en)
        }

    // endregion
}
