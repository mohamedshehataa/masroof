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
class AskMerchantPaydayTest : AskEngineFixture() {
    // region REQ-AN-163

    @Test
    fun `REQ-AN-163 how often do I order talabat gives times, total and average, equal to the Insights merchant line`() =
        runTest {
            seedOctober()
            val answer = spend("how often do I order talabat") as AskAnswer.MerchantVisits
            val line = current().merchants.first { it.name.equals("talabat", ignoreCase = true) }
            assertEquals(14, answer.visits)
            assertEquals(line.visits, answer.visits)
            assertEquals(321_000L, answer.totalMinor)
            assertEquals(line.total, answer.totalMinor)
            assertEquals(line.averageTicket, answer.averageMinor)
            assertEquals(22_929L, answer.averageMinor)
            assertEquals("14 times this month, 3,210 EGP, average 229.29 EGP", en().text(answer).headline)
            val arabic = ar().text(spend("طلبت من طلبات كام مرة"))
            assertEquals("١٤ مرة الشهر ده، ٣٬٢١٠ ج.م، المتوسط ٢٢٩٫٢٩ ج.م", arabic.headline)
        }

    @Test
    fun `REQ-AN-163 top places are the top 5 merchants with the same numbers as the Insights merchants card`() =
        runTest {
            seedOctober()
            val answer = spend("top places") as AskAnswer.TopMerchants
            val insights = current().merchants.take(5)
            assertEquals(insights.map { it.name }, answer.merchants.map { it.name })
            assertEquals(insights.map { it.total }, answer.merchants.map { it.totalMinor })
            assertEquals(insights.map { it.visits }, answer.merchants.map { it.visits })
            assertTrue(answer.merchants.size <= 5)
        }

    @Test
    fun `REQ-AN-163 a merchant with no entries in the period says so and never divides by zero`() =
        runTest {
            seedOctober()
            val answer = spend("how often do I go to uber") as AskAnswer.MerchantVisits
            assertEquals(0, answer.visits)
            assertEquals(0L, answer.averageMinor)
            assertEquals("No entries for uber this month", en().text(answer).headline)
        }

    // endregion

    // region REQ-AN-164

    @Test
    fun `REQ-AN-164 whats left until payday with month start day 25 uses income, spending and the existing projection`() =
        runTest {
            seedCategories()
            settings.update { it.copy(monthStartDay = 25) }
            now = Instant.parse("2026-10-20T09:00:00Z")
            put("pay", 2_000_000, "2026-09-25T08:00:00Z", type = "INCOME")
            put("s1", 400_000, "2026-09-28T10:00:00Z", category = GROCERIES)
            put("s2", 500_000, "2026-10-10T10:00:00Z", category = RENT)
            val answer = spend("what's left until payday") as AskAnswer.Payday
            val summary = current().summary
            assertEquals(5, answer.daysToPayday)
            assertEquals(LocalDate.of(2026, 10, 25), answer.payday)
            assertEquals(2_000_000L, answer.incomeMinor)
            assertEquals(900_000L, answer.spentMinor)
            assertEquals(1_100_000L, answer.leftMinor)
            assertEquals(summary.projectedTotal, answer.projectedMinor)
            val text = en().text(answer)
            assertEquals("5 days to payday", text.headline)
            assertEquals("Income 20,000 EGP, spent 9,000 EGP, 11,000 EGP left", text.lines.first())
            assertTrue(text.lines.last().startsWith("At your pace you will spend about "))
            assertEquals("فاضل ٥ يوم على القبض", ar().text(answer).headline)
        }

    @Test
    fun `REQ-AN-164 without income it shows days left, spent so far and the daily average`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-10-20T09:00:00Z")
            put("s1", 400_000, "2026-10-05T10:00:00Z", category = GROCERIES)
            val answer = spend("فاضل كام لحد القبض") as AskAnswer.Payday
            assertNull(answer.leftMinor)
            assertEquals(12, answer.daysToPayday)
            assertEquals(current().summary.dailyAverage, answer.dailyAverageMinor)
            assertEquals("Spent so far 4,000 EGP, daily average 200 EGP", en().text(answer).lines.first())
        }

    @Test
    fun `REQ-AN-164 overspending is shown as over by and payday on the last day is one day away`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-10-31T09:00:00Z")
            put("pay", 100_000, "2026-10-01T08:00:00Z", type = "INCOME")
            put("s1", 150_000, "2026-10-05T10:00:00Z", category = GROCERIES)
            val answer = spend("payday") as AskAnswer.Payday
            assertEquals(1, answer.daysToPayday)
            assertEquals(-50_000L, answer.leftMinor)
            val text = en().text(answer)
            assertEquals("1 day to payday", text.headline)
            assertEquals("Income 1,000 EGP, spent 1,500 EGP, over by 500 EGP", text.lines.first())
        }

    @Test
    fun `REQ-AN-164 biggest expense is the single largest spending entry with its date and merchant`() =
        runTest {
            seedOctober()
            val answer = spend("biggest expense this month") as AskAnswer.Biggest
            assertEquals("g2", answer.entry?.id)
            assertEquals(252_000L, answer.entry?.amountMinor)
            assertEquals("Spinneys", answer.entry?.merchant)
            assertEquals(LocalDate.of(2026, 10, 10), answer.entry?.date)
            val text = en().text(answer)
            assertEquals("Biggest expense this month: 2,520 EGP", text.headline)
            assertEquals("10 Oct - Spinneys", text.lines.single())
        }

    @Test
    fun `REQ-AN-164 an empty database gives friendly answers and never a zero division or a crash`() =
        runTest {
            val queries =
                listOf(
                    AskQuery.Payday,
                    AskQuery.Roast,
                    AskQuery.NotUnderstood,
                    AskQuery.Biggest(AskPeriod.THIS_MONTH),
                    AskQuery.Breakdown(AskPeriod.THIS_MONTH),
                    AskQuery.TopMerchants(AskPeriod.THIS_MONTH),
                    AskQuery.Compare(AskPeriod.THIS_MONTH, AskPeriod.LAST_MONTH),
                    AskQuery.Compare(AskPeriod.THIS_WEEK),
                    AskQuery.Compare(AskPeriod.THIS_YEAR),
                    AskQuery.Compare(AskPeriod.lastDays(3)),
                    AskQuery.MerchantVisits(AskPeriod.THIS_MONTH, "talabat"),
                    AskQuery.Spend(AskPeriod.THIS_MONTH),
                    AskQuery.Spend(AskPeriod.THIS_MONTH, AskSubject.Text("groceries")),
                    AskQuery.Why(AskPeriod.THIS_MONTH, AskSubject.Text("groceries")),
                    AskQuery.Entries(AskPeriod.TODAY, null),
                )
            queries.forEach { q ->
                val answer = ask().answer(q)
                en().text(answer)
                ar().text(answer)
            }
            val payday = ask().answer(AskQuery.Payday) as AskAnswer.Payday
            assertTrue(!payday.hasData)
            assertEquals("Nothing recorded yet this month.", en().text(payday).lines.single())
            val intro = ask().intro()
            assertNull(intro.topMerchant)
            assertTrue(intro.chips.isNotEmpty())
        }

    // endregion
}
