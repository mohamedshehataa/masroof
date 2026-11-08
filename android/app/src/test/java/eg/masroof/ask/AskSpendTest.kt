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
                "how much did I spend in 1950",
                "spent 3 weeks ago",
                "groceries last 2 months",
                "spent from 20 to 5 october",
            )) {
                val answer = spend(q)
                assertTrue("$q -> $answer", answer is AskAnswer.PeriodUnreadable)
                assertTrue(answer.followUps.isNotEmpty())
            }
            val text = en().text(spend("how much did I spend in 1950"))
            assertTrue(text.headline.startsWith("I can’t read that period"))
            assertTrue(ar().text(spend("how much did I spend in 1950")).headline.startsWith("معرفتش أقرا الفترة دي"))
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

    private suspend fun seedMay() {
        seedCategories()
        put("may", 9_000, "2026-05-10T10:00:00Z", category = GROCERIES)
        put("oct", 1_000, "2026-10-10T10:00:00Z", category = GROCERIES)
        put("pharm", 3_000, "2026-10-11T10:00:00Z", merchant = "Mar Mina Pharmacy")
    }

    @Test
    fun `REQ-AN-160 may next to a subject or alone is the month, never silently this month`() =
        runTest {
            seedMay()
            for (q in listOf("groceries may", "may groceries", "how much did I spend on groceries in may")) {
                val a = spend(q) as AskAnswer.SpendTotal
                assertEquals("$q", 9_000L, a.totalMinor)
                assertEquals(LocalDate.of(2026, 5, 1), a.period.from)
            }
            assertEquals(9_000L, (spend("spending may") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(9_000L, (spend("how much in may") as AskAnswer.SpendTotal).totalMinor)
            val march = spend("march groceries") as AskAnswer.SpendTotal
            assertEquals(LocalDate.of(2026, 3, 1), march.period.from)
        }

    @Test
    fun `REQ-AN-165 may as a modal verb is not a month, a name part stays a merchant, and a doubtful month is not guessed`() =
        runTest {
            seedMay()
            val modal = spend("I may have spent too much on groceries") as AskAnswer.SpendTotal
            assertEquals(AskPeriod.THIS_MONTH, modal.period.period)
            assertEquals(1_000L, modal.totalMinor)
            val polite = spend("may I know how much I spent today") as AskAnswer.SpendTotal
            assertEquals(AskPeriod.TODAY, polite.period.period)
            val pharmacy = spend("spent at mar mina pharmacy") as AskAnswer.SpendTotal
            assertEquals(3_000L, pharmacy.totalMinor)
            assertEquals(AskPeriod.THIS_MONTH, pharmacy.period.period)
            assertTrue(spend("groceries may spending") is AskAnswer.PeriodUnreadable)
        }

    @Test
    fun `REQ-AN-160 a bare number is not a year unless it has a cue, and a named period wins over a number`() =
        runTest {
            seedMay()
            val future = spend("did I spend more than 2050 on groceries this month") as AskAnswer.SpendTotal
            assertEquals(AskPeriod.THIS_MONTH, future.period.period)
            assertEquals(1_000L, future.totalMinor)
            val amount = spend("spent 2000 on groceries") as AskAnswer.SpendTotal
            assertEquals(AskPeriod.THIS_MONTH, amount.period.period)
            assertTrue(spend("groceries in 2050") is AskAnswer.PeriodUnreadable)
            assertTrue(spend("groceries in 2027") is AskAnswer.PeriodUnreadable)
        }

    @Test
    fun `REQ-AN-160 colloquial short prepositions and words for whom do not break the subject`() =
        runTest {
            seedMay()
            val food = spend("صرفت كام ع الاكل الشهر ده") as AskAnswer.SpendTotal
            assertEquals("Groceries + Dining out", food.subject?.en)
            assertEquals(1_000L, food.totalMinor)
            val forMom = spend("how much did I spend on groceries for mom this month") as AskAnswer.SpendTotal
            assertEquals("Groceries", forMom.subject?.en)
            assertEquals(1_000L, forMom.totalMinor)
            assertEquals("mom", forMom.ignored)
            assertTrue(en().text(forMom).lines.contains("Answered for Groceries only. I didn’t use: mom"))
            assertEquals(
                listOf("groceries xyzzy"),
                listOf((spend("how much did I spend on groceries xyzzy mom") as? AskAnswer.UnknownSubject)?.text),
            )
        }

    @Test
    fun `REQ-AN-162 two subjects with vs are not merged into this month vs last month`() =
        runTest {
            seedMay()
            val answer = spend("groceries vs dining this month") as AskAnswer.OneAtATime
            assertEquals(2, answer.followUps.size)
            val tapped = ask().answer(answer.followUps.first { it.arg?.en == "Groceries" }.query) as AskAnswer.Compare
            assertEquals("Groceries", tapped.subject?.en)
            assertEquals(1_000L, tapped.currentMinor)
        }

    @Test
    fun `REQ-AN-160 merged labels name each category once`() =
        runTest {
            seedMay()
            val a = spend("how much did I spend on food and groceries this month") as AskAnswer.SpendTotal
            assertEquals("Groceries + Dining out", a.subject?.en)
            assertEquals("بقالة + مطاعم", a.subject?.ar)
        }

    @Test
    fun `REQ-AN-160 kids is a category, so groceries and kids sums both, and for-mom words are generic with an Arabic prefix`() =
        runTest {
            seedCategories()
            put("g", 5_000, "2026-10-10T10:00:00Z", category = GROCERIES)
            put("k", 7_000, "2026-10-11T10:00:00Z", category = "cat-kids")
            val both = spend("how much did I spend on groceries and kids this month") as AskAnswer.SpendTotal
            assertEquals(12_000L, both.totalMinor)
            assertEquals("Groceries + Kids", both.subject?.en)
            val mama = spend("how much did I spend on groceries لماما this month") as AskAnswer.SpendTotal
            assertEquals(5_000L, mama.totalMinor)
            assertEquals("لماما", mama.ignored)
        }

    @Test
    fun `REQ-AN-162 two years compare as years with the year in both labels`() =
        runTest {
            seedCategories()
            put("a", 5_000, "2025-03-10T10:00:00Z", category = GROCERIES)
            put("b", 2_000, "2024-03-10T10:00:00Z", category = GROCERIES)
            val answer = spend("groceries 2025 vs 2024") as AskAnswer.Compare
            assertEquals(5_000L, answer.currentMinor)
            assertEquals(2_000L, answer.previousMinor)
            assertEquals("Groceries: 50 EGP in 2025 vs 20 EGP in 2024", en().text(answer).headline)
            assertTrue(spend("groceries in 2027") is AskAnswer.PeriodUnreadable)
        }

    @Test
    fun `REQ-AN-162 both sides the same period say so instead of comparing a period with itself`() =
        runTest {
            seedCategories()
            put("a", 5_000, "2025-03-10T10:00:00Z", category = GROCERIES)
            val same = spend("groceries in 2025 vs last year") as AskAnswer.SamePeriod
            assertEquals("Those are the same period, so there is nothing to compare. Try two different periods.", en().text(same).headline)
            assertTrue(spend("groceries this month vs this month") is AskAnswer.SamePeriod)
        }

    @Test
    fun `REQ-AN-160 a single date with its year is one day, and two periods without compare word are not read`() =
        runTest {
            seedCategories()
            put("d", 4_000, "2026-05-31T10:00:00Z", category = GROCERIES)
            put("e", 9_000, "2026-05-30T10:00:00Z", category = GROCERIES)
            val day = spend("groceries may 31 2026") as AskAnswer.SpendTotal
            assertEquals(4_000L, day.totalMinor)
            assertEquals(LocalDate.of(2026, 5, 31), day.period.to)
            assertTrue(spend("april may june") is AskAnswer.PeriodUnreadable)
            assertTrue(spend("groceries october 2030") is AskAnswer.PeriodUnreadable)
        }
}
