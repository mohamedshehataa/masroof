package eg.masroof

import eg.masroof.core.CairoTime
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.EntryFact
import eg.masroof.data.bills.KIND_BILL
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.PaydayMath
import eg.masroof.data.bills.PaydaySource
import eg.masroof.data.bills.RenewalMath
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.db.SubscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

private fun d(iso: String): LocalDate = LocalDate.parse(iso)

internal fun subRow(
    id: String,
    name: String,
    amountMinor: Long,
    cadence: Cadence = Cadence.MONTHLY,
    next: String = "2026-11-20",
    kind: String = KIND_SUBSCRIPTION,
    merchantKey: String? = null,
) = SubscriptionEntity(
    id = id,
    kind = kind,
    name = name,
    amountMinor = amountMinor,
    currency = "EGP",
    cadence = cadence.key,
    nextRenewalAtMillis = CairoTime.noonOf(d(next)).toEpochMilli(),
    categoryId = null,
    accountId = null,
    merchantKey = merchantKey,
    reminderDaysBefore = 1,
    active = true,
    notes = null,
    createdAtMillis = 0L,
)

class RenewalMathTest {
    @Test
    fun `REQ-AN-170 the worked example gives 12,876 a year and 1,073 a month`() {
        val rows =
            listOf(
                subRow("a", "Netflix", 19_900),
                subRow("b", "iCloud", 4_900),
                subRow("c", "Gym", 600_000, Cadence.YEARLY),
                subRow("d", "Water bill", 7_500, Cadence.WEEKLY, kind = KIND_BILL),
            )
        val totals = RenewalMath.totals(rows)
        assertEquals(1_287_600L, totals.perYearMinor)
        assertEquals(107_300L, totals.perMonthMinor)
        assertEquals(4, totals.activeCount)
        assertFalse(totals.hasOtherCurrency)
    }

    @Test
    fun `REQ-AN-170 paused rows, income and other currencies stay out of the EGP totals`() {
        val rows =
            listOf(
                subRow("a", "Netflix", 19_900),
                subRow("b", "Paused", 50_000).copy(active = false),
                subRow("c", "Salary", 2_000_000, kind = KIND_INCOME),
                subRow("d", "ChatGPT", 2_000).copy(currency = "USD"),
            )
        val totals = RenewalMath.totals(rows)
        assertEquals(238_800L, totals.perYearMinor)
        assertEquals(2, totals.activeCount)
        assertTrue(totals.hasOtherCurrency)
    }

    @Test
    fun `REQ-AN-170 the monthly figure rounds half away from zero`() {
        // 0.50 EGP a year / 12 = 4.17 minor -> 4; 7 piasters a year /12 -> 1 (0.58); 6 -> 1 (0.5 up)
        assertEquals(1L, RenewalMath.perMonthMinor(6L))
        assertEquals(0L, RenewalMath.perMonthMinor(5L))
        assertEquals(-1L, RenewalMath.perMonthMinor(-6L))
        assertEquals(1_073L, RenewalMath.perMonthMinor(12_876L))
    }

    @Test
    fun `REQ-AN-171 the anchor day is kept and short months clamp`() {
        // 31 Oct monthly: 30 Nov, then 31 Dec, 31 Jan, 28 Feb, 31 Mar
        val chain = generateSequence(d("2026-10-31")) { RenewalMath.nextAfter(it, Cadence.MONTHLY) }.take(6).toList()
        assertEquals(
            listOf("2026-10-31", "2026-11-30", "2026-12-31", "2027-01-31", "2027-02-28", "2027-03-31"),
            chain.map { it.toString() },
        )
    }

    @Test
    fun `REQ-AN-171 a fixed anchor survives a clamped month`() {
        assertEquals(d("2027-02-28"), RenewalMath.nextAfter(d("2027-01-31"), Cadence.MONTHLY, 31))
        assertEquals(d("2028-02-29"), RenewalMath.nextAfter(d("2028-01-31"), Cadence.MONTHLY, 31))
        assertEquals(d("2027-03-30"), RenewalMath.nextAfter(d("2027-02-28"), Cadence.MONTHLY, 30))
    }

    @Test
    fun `REQ-AN-171 weekly quarterly and yearly cadences`() {
        assertEquals(d("2026-11-15"), RenewalMath.nextAfter(d("2026-11-08"), Cadence.WEEKLY))
        assertEquals(d("2027-02-10"), RenewalMath.nextAfter(d("2026-11-10"), Cadence.QUARTERLY))
        assertEquals(d("2027-02-28"), RenewalMath.nextAfter(d("2026-11-30"), Cadence.QUARTERLY))
        assertEquals(d("2027-03-15"), RenewalMath.nextAfter(d("2026-03-15"), Cadence.YEARLY))
        assertEquals(d("2025-02-28"), RenewalMath.nextAfter(d("2024-02-29"), Cadence.YEARLY))
    }

    @Test
    fun `REQ-AN-171 a passed renewal rolls to the next one on or after today`() {
        assertEquals(d("2026-11-30"), RenewalMath.rollTo(d("2026-10-31"), Cadence.MONTHLY, d("2026-11-08")))
        assertEquals(d("2026-12-31"), RenewalMath.rollTo(d("2026-10-31"), Cadence.MONTHLY, d("2026-12-01")))
        assertEquals(d("2026-11-08"), RenewalMath.rollTo(d("2026-11-08"), Cadence.MONTHLY, d("2026-11-08")))
        assertEquals(d("2026-11-09"), RenewalMath.rollTo(d("2026-10-19"), Cadence.WEEKLY, d("2026-11-08")))
    }

    @Test
    fun `REQ-AN-171 a renewal is kept for three days so a late payment can still match`() {
        assertEquals(d("2026-11-05"), RenewalMath.rollPastGrace(d("2026-11-05"), Cadence.MONTHLY, d("2026-11-08")))
        assertEquals(d("2026-12-05"), RenewalMath.rollPastGrace(d("2026-11-05"), Cadence.MONTHLY, d("2026-11-09")))
    }

    @Test
    fun `REQ-AN-170 the calendar lists every renewal of the chosen month`() {
        val weekly = RenewalMath.occurrencesIn(d("2026-11-02"), Cadence.WEEKLY, YearMonth.of(2026, 11))
        assertEquals(listOf("2026-11-02", "2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30"), weekly.map { it.toString() })
        assertEquals(listOf(d("2026-12-31")), RenewalMath.occurrencesIn(d("2026-10-31"), Cadence.MONTHLY, YearMonth.of(2026, 12)))
        assertTrue(RenewalMath.occurrencesIn(d("2026-12-31"), Cadence.MONTHLY, YearMonth.of(2026, 11)).isEmpty())
    }

    @Test
    fun `REQ-AN-172 days to payday come from the next active income row`() {
        val rows = listOf(subRow("s", "Salary", 2_000_000, next = "2026-11-25", kind = KIND_INCOME))
        val p = PaydayMath.next(d("2026-11-08"), rows, 1, CairoTime::dateOf, emptyMap())
        assertEquals(17, p.days)
        assertEquals(PaydaySource.INCOME, p.source)
        assertEquals("Salary", p.name)
    }

    @Test
    fun `REQ-AN-172 the earliest income wins, a paused one is ignored and a passed date rolls forward`() {
        val rows =
            listOf(
                subRow("s", "Salary", 2_000_000, next = "2026-10-25", kind = KIND_INCOME),
                subRow("c", "Certificate", 500_000, next = "2026-11-12", kind = KIND_INCOME),
                subRow("x", "Old", 1, next = "2026-11-09", kind = KIND_INCOME).copy(active = false),
            )
        val p = PaydayMath.next(d("2026-11-08"), rows, 1, CairoTime::dateOf, emptyMap())
        assertEquals(4, p.days)
        assertEquals("Certificate", p.name)
    }

    @Test
    fun `REQ-AN-172 without income the month start day is used`() {
        val p = PaydayMath.next(d("2026-11-08"), emptyList(), 1, CairoTime::dateOf, emptyMap())
        assertEquals(PaydaySource.MONTH_START, p.source)
        assertEquals(d("2026-12-01"), p.date)
        assertEquals(23, p.days)
        assertEquals(0, PaydayMath.next(d("2026-11-25"), emptyList(), 25, CairoTime::dateOf, emptyMap()).days)
        assertEquals(d("2027-02-28"), PaydayMath.next(d("2027-02-10"), emptyList(), 28, CairoTime::dateOf, emptyMap()).date)
    }
}

class SubscriptionMatcherTest {
    private fun fact(
        id: String,
        key: String,
        amount: Long,
        date: String,
        currency: String = "EGP",
        income: Boolean = false,
    ) = EntryFact(id, key, key.replaceFirstChar { it.uppercase() }, amount, currency, d(date), income)

    @Test
    fun `REQ-AN-171 the merchant key is trimmed and lower-cased like the Insights merchants`() {
        assertEquals("netflix", SubscriptionMatcher.keyOf("  Netflix "))
        assertNull(SubscriptionMatcher.keyOf("   "))
        assertNull(SubscriptionMatcher.keyOf(null))
    }

    @Test
    fun `REQ-AN-171 the amount must be within 10 percent, the boundary included`() {
        assertTrue(SubscriptionMatcher.withinTolerance(21_890, 19_900))
        assertFalse(SubscriptionMatcher.withinTolerance(21_891, 19_900))
        assertTrue(SubscriptionMatcher.withinTolerance(17_910, 19_900))
        assertFalse(SubscriptionMatcher.withinTolerance(17_909, 19_900))
        assertFalse(SubscriptionMatcher.withinTolerance(100, 0))
    }

    @Test
    fun `REQ-AN-171 the date must be within 3 days, key and currency must be equal`() {
        val renewal = d("2026-11-10")
        val entries =
            listOf(
                fact("early", "netflix", 19_900, "2026-11-07"),
                fact("late", "netflix", 19_900, "2026-11-14"),
                fact("other", "spotify", 19_900, "2026-11-10"),
                fact("usd", "netflix", 19_900, "2026-11-10", currency = "USD"),
            )
        assertEquals("early", SubscriptionMatcher.match("netflix", 19_900, "EGP", renewal, entries, emptySet())?.id)
        assertNull(SubscriptionMatcher.match("netflix", 19_900, "EGP", renewal, listOf(entries[1]), emptySet()))
    }

    @Test
    fun `REQ-AN-171 with two candidates the closer date wins and an used entry is skipped`() {
        val renewal = d("2026-11-10")
        val entries = listOf(fact("a", "netflix", 19_900, "2026-11-12"), fact("b", "netflix", 19_900, "2026-11-11"))
        assertEquals("b", SubscriptionMatcher.match("netflix", 19_900, "EGP", renewal, entries, emptySet())?.id)
        assertEquals("a", SubscriptionMatcher.match("netflix", 19_900, "EGP", renewal, entries, setOf("b"))?.id)
        assertNull(SubscriptionMatcher.match("netflix", 19_900, "EGP", renewal, entries, setOf("a", "b")))
    }

    @Test
    fun `REQ-AN-172 three monthly payments of about the same amount make a suggestion`() {
        val entries =
            listOf(
                fact("1", "netflix", 19_900, "2026-09-05"),
                fact("2", "netflix", 19_900, "2026-10-05"),
                fact("3", "netflix", 20_900, "2026-11-04"),
            )
        val s = SubscriptionMatcher.suggest(entries, emptySet(), emptySet(), d("2026-11-08"))
        assertEquals(1, s.size)
        assertEquals("netflix", s[0].key)
        assertEquals("Netflix", s[0].name)
        assertEquals(20_900L, s[0].amountMinor)
        assertEquals(d("2026-12-04"), s[0].nextRenewal)
    }

    @Test
    fun `REQ-AN-172 fewer than three, irregular spacing or different amounts give no suggestion`() {
        val two = listOf(fact("1", "netflix", 19_900, "2026-10-05"), fact("2", "netflix", 19_900, "2026-11-04"))
        assertTrue(SubscriptionMatcher.suggest(two, emptySet(), emptySet(), d("2026-11-08")).isEmpty())
        val irregular =
            listOf(
                fact("1", "netflix", 19_900, "2026-09-05"),
                fact("2", "netflix", 19_900, "2026-09-20"),
                fact("3", "netflix", 19_900, "2026-11-04"),
            )
        assertTrue(SubscriptionMatcher.suggest(irregular, emptySet(), emptySet(), d("2026-11-08")).isEmpty())
        val amounts =
            listOf(
                fact("1", "netflix", 19_900, "2026-09-05"),
                fact("2", "netflix", 30_000, "2026-10-05"),
                fact("3", "netflix", 19_900, "2026-11-04"),
            )
        assertTrue(SubscriptionMatcher.suggest(amounts, emptySet(), emptySet(), d("2026-11-08")).isEmpty())
    }

    @Test
    fun `REQ-AN-172 spacing of 25 and 35 days counts, 24 and 36 do not`() {
        fun series(
            gap1: Long,
            gap2: Long,
        ): List<EntryFact> {
            val first = d("2026-08-20")
            val second = first.plusDays(gap1)
            return listOf(
                fact("1", "gym", 50_000, first.toString()),
                fact("2", "gym", 50_000, second.toString()),
                fact("3", "gym", 50_000, second.plusDays(gap2).toString()),
            )
        }
        assertEquals(1, SubscriptionMatcher.suggest(series(25, 35), emptySet(), emptySet(), d("2026-11-08")).size)
        assertTrue(SubscriptionMatcher.suggest(series(24, 30), emptySet(), emptySet(), d("2026-11-08")).isEmpty())
        assertTrue(SubscriptionMatcher.suggest(series(30, 36), emptySet(), emptySet(), d("2026-11-08")).isEmpty())
    }

    @Test
    fun `REQ-AN-172 entries older than 120 days, existing subscriptions, dismissed keys and income do not suggest`() {
        val old =
            listOf(
                fact("1", "netflix", 19_900, "2026-05-01"),
                fact("2", "netflix", 19_900, "2026-05-31"),
                fact("3", "netflix", 19_900, "2026-06-30"),
            )
        assertTrue(SubscriptionMatcher.suggest(old, emptySet(), emptySet(), d("2026-11-08")).isEmpty())
        val recent =
            listOf(
                fact("1", "netflix", 19_900, "2026-09-05"),
                fact("2", "netflix", 19_900, "2026-10-05"),
                fact("3", "netflix", 19_900, "2026-11-04"),
            )
        assertTrue(SubscriptionMatcher.suggest(recent, setOf("netflix"), emptySet(), d("2026-11-08")).isEmpty())
        assertTrue(SubscriptionMatcher.suggest(recent, emptySet(), setOf("netflix"), d("2026-11-08")).isEmpty())
        assertTrue(SubscriptionMatcher.suggest(recent.map { it.copy(income = true) }, emptySet(), emptySet(), d("2026-11-08")).isEmpty())
    }
}
