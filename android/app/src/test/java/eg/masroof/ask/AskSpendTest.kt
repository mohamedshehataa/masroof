package eg.masroof.ask

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AskSpendTest : AskEngineFixture() {
    // region REQ-AN-160

    @Test
    fun `REQ-AN-160 groceries this month equals insights category total`() =
        runTest {
            seedOctober()
            val answer = spend("how much did I spend on groceries this month") as AskAnswer.SpendTotal
            val insightsLine = current().categories.first { it.categoryId == GROCERIES }
            assertEquals(402_000L, answer.totalMinor)
            assertEquals(insightsLine.total, answer.totalMinor)
            assertEquals(3, answer.count)
            assertEquals(1, answer.otherCurrencyCount)
            assertEquals("4,020 EGP on Groceries this month", en().text(answer).headline)
        }

    @Test
    fun `REQ-AN-160 the same question in Arabic answers in Arabic with Arabic-Indic digits`() =
        runTest {
            seedOctober()
            val answer = spend("صرفت كام على البقالة الشهر ده")
            val text = ar().text(answer)
            assertEquals("٤٬٠٢٠ ج.م على بقالة الشهر ده", text.headline)
            assertTrue(text.lines.contains("عملية واحدة بعملة تانية مش محسوبة"))
        }

    @Test
    fun `REQ-AN-160 a month name asks about that month and groceries october works without a spending word`() =
        runTest {
            seedOctober()
            val a = spend("groceries october") as AskAnswer.SpendTotal
            assertEquals(402_000L, a.totalMinor)
            assertEquals(LocalDate.of(2026, 10, 1), a.period.from)
            assertEquals(LocalDate.of(2026, 10, 31), a.period.to)
            assertEquals("4,020 EGP on Groceries in October 2026", en().text(a).headline)
        }

    @Test
    fun `REQ-AN-160 refunds are netted, transfers and soft-deleted entries never count, and cash follows the setting`() =
        runTest {
            seedOctober()
            val all = spend("total this month") as AskAnswer.SpendTotal
            assertEquals(current().summary.total, all.totalMinor)
            settings.update { it.copy(includeCashWithdrawals = true) }
            val withCash = spend("total this month") as AskAnswer.SpendTotal
            assertEquals(all.totalMinor + 100_000, withCash.totalMinor)
            assertEquals(current().summary.total, withCash.totalMinor)
        }

    @Test
    fun `REQ-AN-160 a sub-category question counts only it and the parent question rolls the sub-category up`() =
        runTest {
            seedOctober()
            val fuel = spend("how much did I spend on fuel this month") as AskAnswer.SpendTotal
            assertEquals(30_000L, fuel.totalMinor)
            val car = spend("how much did I spend on car this month") as AskAnswer.SpendTotal
            assertEquals(110_000L, car.totalMinor)
            assertEquals(current().categories.first { it.categoryId == CAR }.total, car.totalMinor)
        }

    @Test
    fun `REQ-AN-160 the food synonym means groceries plus dining out`() =
        runTest {
            seedOctober()
            val food = spend("how much did I spend on food this month") as AskAnswer.SpendTotal
            val lines = current().categories
            assertEquals(lines.first { it.categoryId == GROCERIES }.total + lines.first { it.categoryId == DINING }.total, food.totalMinor)
            assertEquals("Groceries + Dining out", food.subject?.en)
            val arabic = spend("صرفت كام على الاكل الشهر ده") as AskAnswer.SpendTotal
            assertEquals(food.totalMinor, arabic.totalMinor)
        }

    @Test
    fun `REQ-AN-160 a merchant name filters by the merchant, in Arabic or English`() =
        runTest {
            seedOctober()
            val a = spend("how much did I spend at talabat this month") as AskAnswer.SpendTotal
            assertEquals(321_000L, a.totalMinor)
            assertEquals(14, a.count)
            val b = spend("صرفت كام في طلبات الشهر ده") as AskAnswer.SpendTotal
            assertEquals(321_000L, b.totalMinor)
        }

    @Test
    fun `REQ-AN-160 an unknown category gives no number and the 3 closest category chips`() =
        runTest {
            seedOctober()
            val unknown = spend("how much did I spend on dragons this month") as AskAnswer.UnknownSubject
            assertEquals("dragons", unknown.text)
            assertEquals(3, unknown.followUps.size)
            assertTrue(unknown.followUps.all { it.kind == AskChipKind.SUGGESTED_CATEGORY })
            assertTrue(en().text(unknown).headline.contains("dragons"))
        }

    @Test
    fun `REQ-AN-160 a near-miss spelling offers the matching category first`() =
        runTest {
            seedOctober()
            val unknown = spend("how much did I spend on grocereis this month") as AskAnswer.UnknownSubject
            assertEquals(
                "Groceries",
                unknown.followUps
                    .first()
                    .arg
                    ?.en,
            )
            val tap = ask().answer(unknown.followUps.first().query) as AskAnswer.SpendTotal
            assertEquals(402_000L, tap.totalMinor)
        }

    @Test
    fun `REQ-AN-160 the entries behind a total are listed newest first and open by id`() =
        runTest {
            seedOctober()
            val a = spend("how much did I spend on groceries this month") as AskAnswer.SpendTotal
            assertEquals(listOf("g4", "g2", "g1"), a.entries.map { it.id })
            assertEquals(-10_000L, a.entries.first().amountMinor)
            val list = ask().answer(AskQuery.Entries(AskPeriod.THIS_MONTH, AskSubject.Text("groceries"))) as AskAnswer.EntryList
            assertEquals(3, list.count)
            val tal = spend("talabat this month") as AskAnswer.SpendTotal
            assertTrue(AskChipKind.SHOW_ENTRIES in tal.followUps.map { it.kind })
            val all = ask().answer(tal.followUps.first { it.kind == AskChipKind.SHOW_ENTRIES }.query) as AskAnswer.EntryList
            assertEquals(14, all.count)
            assertEquals(14, all.entries.size)
        }

    @Test
    fun `REQ-AN-160 a month start day of 25 moves this month and a named month resolves to the household month`() =
        runTest {
            seedCategories()
            settings.update { it.copy(monthStartDay = 25) }
            now = Instant.parse("2026-10-20T09:00:00Z")
            put("a", 100_000, "2026-09-26T10:00:00Z", category = GROCERIES)
            put("b", 50_000, "2026-10-24T10:00:00Z", category = GROCERIES)
            put("c", 70_000, "2026-10-25T10:00:00Z", category = GROCERIES)
            val thisMonth = spend("groceries this month") as AskAnswer.SpendTotal
            assertEquals(150_000L, thisMonth.totalMinor)
            assertEquals(LocalDate.of(2026, 9, 25), thisMonth.period.from)
            assertEquals(LocalDate.of(2026, 10, 24), thisMonth.period.to)
            assertEquals(current().categories.first { it.categoryId == GROCERIES }.total, thisMonth.totalMinor)
            val september = spend("groceries september") as AskAnswer.SpendTotal
            assertEquals(150_000L, september.totalMinor)
            // "october" is the household month that starts on 25 October 2026, which has not started: last year's one
            val october = spend("groceries october") as AskAnswer.SpendTotal
            assertEquals(LocalDate.of(2025, 10, 25), october.period.from)
            assertEquals(0L, october.totalMinor)
        }

    @Test
    fun `REQ-AN-160 dates are Cairo days an entry just after Cairo midnight belongs to the new month`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-11-02T09:00:00Z")
            // 22:30 UTC on 31 October is 00:30 on 1 November in Cairo (UTC+2 after the DST change)
            put("late", 10_000, "2026-10-31T22:30:00Z", category = GROCERIES)
            put("early", 20_000, "2026-10-31T20:30:00Z", category = GROCERIES)
            val nov = spend("groceries this month") as AskAnswer.SpendTotal
            assertEquals(10_000L, nov.totalMinor)
            assertEquals(LocalDate.of(2026, 11, 1), nov.entries.single().date)
            val oct = spend("groceries last month") as AskAnswer.SpendTotal
            assertEquals(20_000L, oct.totalMinor)
        }

    @Test
    fun `REQ-AN-160 today, yesterday, last week and last N days resolve to Cairo calendar ranges`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-10-20T09:00:00Z") // Tuesday
            put("today", 1_000, "2026-10-20T07:00:00Z", category = GROCERIES)
            put("yesterday", 2_000, "2026-10-19T07:00:00Z", category = GROCERIES)
            put("lastweek", 4_000, "2026-10-14T07:00:00Z", category = GROCERIES)
            put("old", 8_000, "2026-10-01T07:00:00Z", category = GROCERIES)
            assertEquals(1_000L, (spend("groceries today") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(2_000L, (spend("groceries yesterday") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(3_000L, (spend("groceries this week") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(4_000L, (spend("groceries last week") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(7_000L, (spend("groceries last 7 days") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(15_000L, (spend("groceries this year") as AskAnswer.SpendTotal).totalMinor)
        }

    // endregion

    @Test
    fun `REQ-AN-160 last year, a bare year, months ago, a day range and weeks all resolve to the right Cairo days`() =
        runTest {
            seedCategories()
            now = Instant.parse("2026-10-20T09:00:00Z")
            put("y25", 5_000, "2025-06-10T10:00:00Z", category = GROCERIES)
            put("aug", 7_000, "2026-08-10T10:00:00Z", category = GROCERIES)
            put("oct3", 11_000, "2026-10-03T10:00:00Z", category = GROCERIES)
            put("oct8", 17_000, "2026-10-08T10:00:00Z", category = GROCERIES)
            put("oct16", 13_000, "2026-10-16T10:00:00Z", category = GROCERIES)
            assertEquals(5_000L, (spend("groceries last year") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(5_000L, (spend("groceries in 2025") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(48_000L, (spend("groceries in 2026") as AskAnswer.SpendTotal).totalMinor)
            val ago = spend("groceries 2 months ago") as AskAnswer.SpendTotal
            assertEquals(7_000L, ago.totalMinor)
            assertEquals(LocalDate.of(2026, 8, 1), ago.period.from)
            val range = spend("groceries from 1 to 15 october") as AskAnswer.SpendTotal
            assertEquals(28_000L, range.totalMinor)
            assertEquals(LocalDate.of(2026, 10, 15), range.period.to)
            assertEquals("280 EGP on Groceries from 1 Oct to 15 Oct", en().text(range).headline)
            assertEquals(30_000L, (spend("groceries past 2 weeks") as AskAnswer.SpendTotal).totalMinor)
            assertEquals("50 EGP on Groceries in 2025", en().text(spend("groceries in 2025")).headline)
        }

    @Test
    fun `REQ-AN-160 a period that cannot be read is never answered for this month`() =
        runTest {
            seedOctober()
            for (q in listOf(
                "how much did I spend in 1999",
                "spent 3 weeks ago",
                "groceries last 2 months",
                "spent from 20 to 5 october",
            )) {
                val answer = spend(q)
                assertTrue("$q -> $answer", answer is AskAnswer.PeriodUnreadable)
                assertTrue(answer.followUps.isNotEmpty())
            }
            val text = en().text(spend("how much did I spend in 1999"))
            assertTrue(text.headline.startsWith("I can’t read that period"))
            assertTrue(ar().text(spend("how much did I spend in 1999")).headline.startsWith("معرفتش أقرا الفترة دي"))
        }

    @Test
    fun `REQ-AN-160 the word may is a month only with a cue`() =
        runTest {
            seedCategories()
            put("may", 9_000, "2026-05-10T10:00:00Z", category = GROCERIES)
            put("oct", 1_000, "2026-10-10T10:00:00Z", category = GROCERIES)
            val modal = spend("I may have spent too much on groceries") as AskAnswer.SpendTotal
            assertEquals(1_000L, modal.totalMinor)
            assertEquals(AskPeriod.THIS_MONTH, modal.period.period)
            assertEquals(9_000L, (spend("groceries in may") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(9_000L, (spend("groceries may 2026") as AskAnswer.SpendTotal).totalMinor)
        }

    @Test
    fun `REQ-AN-160 groceries and fuel is one question over both, and a mix of kinds asks for one at a time`() =
        runTest {
            seedOctober()
            val both = spend("how much did I spend on groceries and fuel this month") as AskAnswer.SpendTotal
            assertEquals(432_000L, both.totalMinor)
            assertEquals(4, both.count)
            assertEquals("Groceries + Fuel", both.subject?.en)
            assertEquals("4,320 EGP on Groceries + Fuel this month", en().text(both).headline)
            val mixed = spend("how much did I spend on groceries and talabat this month") as AskAnswer.OneAtATime
            assertEquals(2, mixed.followUps.size)
            assertEquals("I can only answer about one thing at a time. Pick one:", en().text(mixed).headline)
            val tapped = ask().answer(mixed.followUps.first { it.arg?.en == "Groceries" }.query) as AskAnswer.SpendTotal
            assertEquals(402_000L, tapped.totalMinor)
            val partly = spend("how much did I spend on groceries and xyzzy this month") as AskAnswer.UnknownSubject
            assertEquals("groceries xyzzy", partly.text)
        }
}
