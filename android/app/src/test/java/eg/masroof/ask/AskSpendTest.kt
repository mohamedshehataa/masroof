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
}
