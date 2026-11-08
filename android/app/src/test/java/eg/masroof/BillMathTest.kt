package eg.masroof

import eg.masroof.core.CairoTime
import eg.masroof.data.bills.BillMath
import eg.masroof.data.bills.PLAN_ACTIVE
import eg.masroof.data.bills.PLAN_COMPLETED
import eg.masroof.data.db.InstallmentPaymentEntity
import eg.masroof.data.db.InstallmentPlanEntity
import eg.masroof.work.BillsReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Shared builders for the Bills tests. */
internal object BillsFixtures {
    fun noon(date: String): Long = CairoTime.noonOf(LocalDate.parse(date)).toEpochMilli()

    @Suppress("LongParameterList")
    fun plan(
        id: String,
        monthly: Long,
        count: Int,
        first: String,
        provider: String = "VALU",
        title: String = "Plan $id",
        currency: String = "EGP",
        status: String = PLAN_ACTIVE,
        reminderDays: Int = 1,
        payoutAt: String? = null,
        payoutMinor: Long? = null,
    ) = InstallmentPlanEntity(
        id = id,
        title = title,
        provider = provider,
        categoryId = null,
        accountId = null,
        currency = currency,
        purchasePriceMinor = null,
        downPaymentMinor = 0,
        monthlyAmountMinor = monthly,
        paymentCount = count,
        firstDueAtMillis = noon(first),
        reminderDaysBefore = reminderDays,
        status = status,
        notes = null,
        createdAtMillis = 1,
        completedAtMillis = null,
        payoutAtMillis = payoutAt?.let { noon(it) },
        payoutAmountMinor = payoutMinor,
        payoutReceivedAtMillis = null,
    )

    /** [paid] first payments are marked paid. */
    fun payments(
        plan: InstallmentPlanEntity,
        paid: Int = 0,
    ): List<InstallmentPaymentEntity> {
        val first = CairoTime.dateOf(plan.firstDueAtMillis)
        return BillMath.dueDates(first, plan.paymentCount).mapIndexed { i, d ->
            val due = CairoTime.noonOf(d).toEpochMilli()
            InstallmentPaymentEntity("${plan.id}-${i + 1}", plan.id, i + 1, due, plan.monthlyAmountMinor, if (i < paid) due else null, null)
        }
    }
}

class BillMathTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `REQ-EXP-053 18 monthly payments fall on the 5th of each month with seq 1 to 18`() {
        val dates = BillMath.dueDates(d("2026-03-05"), 18)
        assertEquals(18, dates.size)
        assertTrue(dates.all { it.dayOfMonth == 5 })
        assertEquals(d("2026-03-05"), dates.first())
        assertEquals(d("2027-08-05"), dates.last())
        assertEquals(d("2027-08-05"), BillMath.endDate(d("2026-03-05"), 18))
    }

    @Test
    fun `REQ-EXP-053 a due day of 31 clamps to the month end without drifting`() {
        val dates = BillMath.dueDates(d("2026-01-31"), 5)
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31").map(::d),
            dates,
        )
    }

    @Test
    fun `REQ-EXP-053 leap years clamp to 29 February`() {
        assertEquals(d("2028-02-29"), BillMath.dueDates(d("2028-01-31"), 2)[1])
        assertEquals(d("2027-02-28"), BillMath.dueDates(d("2027-01-30"), 2)[1])
        assertEquals(d("2028-02-29"), BillMath.dueDates(d("2028-01-29"), 2)[1])
    }

    @Test
    fun `REQ-EXP-053 due times are Cairo noon, in winter and in summer time`() {
        val winter = CairoTime.noonOf(d("2026-11-09")).atZone(CairoTime.zone)
        val summer = CairoTime.noonOf(d("2026-07-09")).atZone(CairoTime.zone)
        assertEquals(12, winter.hour)
        assertEquals(12, summer.hour)
        assertEquals(10, CairoTime.noonOf(d("2026-11-09")).atZone(java.time.ZoneOffset.UTC).hour)
        assertEquals(9, CairoTime.noonOf(d("2026-07-09")).atZone(java.time.ZoneOffset.UTC).hour)
    }

    @Test
    fun `REQ-EXP-053 an end date gives the number of payments`() {
        assertEquals(18, BillMath.countUntil(d("2026-03-05"), d("2027-08-05")))
        assertEquals(17, BillMath.countUntil(d("2026-03-05"), d("2027-08-04")))
        assertEquals(1, BillMath.countUntil(d("2026-03-05"), d("2026-03-05")))
        assertEquals(2, BillMath.countUntil(d("2026-01-31"), d("2026-02-28")))
    }

    @Test
    fun `REQ-EXP-053 an end date before the first payment or beyond 120 payments is not a count`() {
        assertNull(BillMath.countUntil(d("2026-03-05"), d("2026-03-04")))
        assertEquals(120, BillMath.countUntil(d("2026-03-05"), d("2036-02-05")))
        assertNull(BillMath.countUntil(d("2026-03-05"), d("2036-03-05")))
    }

    @Test
    fun `REQ-EXP-053 how many are already paid defaults to those due before today`() {
        val dates = BillMath.dueDates(d("2026-03-05"), 18)
        assertEquals(9, BillMath.dueBefore(dates, d("2026-11-08")))
        assertEquals(7, BillMath.dueBefore(dates, d("2026-10-05")))
        assertEquals(0, BillMath.dueBefore(dates, d("2026-03-05")))
    }

    @Test
    fun `REQ-EXP-055 the collection falls in the month of the chosen turn and defaults to share times members`() {
        assertEquals(d("2027-02-01"), BillMath.collectionDate(d("2026-11-01"), 4))
        assertEquals(d("2026-11-01"), BillMath.collectionDate(d("2026-11-01"), 1))
        assertEquals(2_000_000L, BillMath.defaultPayout(200_000L, 10))
        assertNull(BillMath.defaultPayout(Long.MAX_VALUE, 10))
    }

    @Test
    fun `REQ-AN-166 overview totals for 3250 plus 1450 plus 2100 with the monthly drop`() {
        val a = BillsFixtures.plan("a", 325_000, 18, "2026-03-05")
        val b = BillsFixtures.plan("b", 145_000, 12, "2026-06-10")
        val c = BillsFixtures.plan("c", 210_000, 6, "2026-09-20")
        val payments = BillsFixtures.payments(a, paid = 8) + BillsFixtures.payments(b, paid = 5) + BillsFixtures.payments(c, paid = 2)
        val o = BillMath.overview(listOf(a, b, c), payments, d("2026-11-08"))
        // November: a (5th), b (10th) and c (20th) all fall due this month
        assertEquals(325_000L + 145_000L + 210_000L, o.monthlyTotalMinor)
        assertEquals(3, o.activeCount)
        // a: payments 9..18 = 10, b: 6..12 = 7, c: 3..6 = 4
        assertEquals(10 * 325_000L + 7 * 145_000L + 4 * 210_000L, o.remainingMinor)
        // c ends with its 6th payment on 2027-02-20, so March 2027 is when it is first lower: 3250 + 1450 is still the total.
        // b runs to 2027-05-10, a to 2027-08-05: the first lower month is March 2027 (c gone), 4 months from November
        val drop = o.dropsTo
        assertNotNull(drop)
        assertEquals(325_000L + 145_000L, drop!!.amountMinor)
        assertEquals(4, drop.inMonths)
        assertEquals(YearMonth.of(2027, 8), o.plans.first { it.plan.id == "a" }.completionMonth)
        assertEquals(8, o.plans.first { it.plan.id == "a" }.paid)
        assertEquals(18, o.plans.first { it.plan.id == "a" }.total)
    }

    @Test
    fun `REQ-AN-166 the monthly total counts payments already paid this month`() {
        val a = BillsFixtures.plan("a", 325_000, 18, "2026-03-05")
        val o = BillMath.overview(listOf(a), BillsFixtures.payments(a, paid = 9), d("2026-11-20"))
        assertEquals(325_000L, o.monthlyTotalMinor)
    }

    @Test
    fun `REQ-AN-166 coming up lists payments due in the next 14 days and gam3eya collections as incoming`() {
        val a = BillsFixtures.plan("a", 325_000, 18, "2026-03-05")
        val g = BillsFixtures.plan("g", 200_000, 10, "2026-07-15", provider = "GAM3EYA", payoutAt = "2026-11-12", payoutMinor = 2_000_000)
        val far = BillsFixtures.plan("far", 100_000, 3, "2026-12-30")
        val payments = BillsFixtures.payments(a, paid = 8) + BillsFixtures.payments(g, paid = 4) + BillsFixtures.payments(far)
        val o = BillMath.overview(listOf(a, g, far), payments, d("2026-11-08"))
        // a's 9th payment (2026-11-05) is overdue, not coming up; g's 5th is on 2026-11-15 and its collection on 2026-11-12
        assertEquals(listOf("g", "g"), o.comingUp.map { it.planId })
        assertEquals(listOf(false, true), o.comingUp.map { it.incoming }.sorted())
        assertEquals(2_000_000L, o.comingUp.first { it.incoming }.amountMinor)
    }

    @Test
    fun `REQ-AN-166 non-EGP plans are listed but excluded from the EGP totals`() {
        val a = BillsFixtures.plan("a", 325_000, 3, "2026-11-05")
        val usd = BillsFixtures.plan("usd", 50_000, 3, "2026-11-05", currency = "USD")
        val o = BillMath.overview(listOf(a, usd), BillsFixtures.payments(a) + BillsFixtures.payments(usd), d("2026-11-08"))
        assertEquals(325_000L, o.monthlyTotalMinor)
        assertEquals(3 * 325_000L, o.remainingMinor)
        assertEquals(2, o.plans.size)
        assertTrue(o.hasOtherCurrency)
    }

    @Test
    fun `REQ-AN-166 completed plans leave the totals and the active count`() {
        val a = BillsFixtures.plan("a", 325_000, 3, "2026-11-05", status = PLAN_COMPLETED)
        val o = BillMath.overview(listOf(a), BillsFixtures.payments(a), d("2026-11-08"))
        assertEquals(0L, o.monthlyTotalMinor)
        assertEquals(0, o.activeCount)
        assertEquals(0L, o.remainingMinor)
        assertNull(o.dropsTo)
    }

    @Test
    fun `REQ-AN-166 the calendar marks the due days of the month`() {
        val a = BillsFixtures.plan("a", 325_000, 18, "2026-03-05")
        val g = BillsFixtures.plan("g", 200_000, 10, "2026-07-01", provider = "GAM3EYA", payoutAt = "2026-11-12", payoutMinor = 2_000_000)
        val items = BillMath.calendar(listOf(a, g), BillsFixtures.payments(a) + BillsFixtures.payments(g), YearMonth.of(2026, 11))
        assertEquals(listOf(1, 5, 12), items.map { it.date.dayOfMonth })
        assertEquals(listOf(false, false, true), items.map { it.incoming })
    }

    @Test
    fun `REQ-AND-148 the daily job is first delayed to the next 09 00 in Cairo`() {
        // 2026-11-08 07:00Z is 09:00 Cairo: not after now, so tomorrow's 09:00
        assertEquals(24 * 3_600_000L, BillsReminderScheduler.delayUntilNextNine(Instant.parse("2026-11-08T07:00:00Z")))
        assertEquals(3_600_000L, BillsReminderScheduler.delayUntilNextNine(Instant.parse("2026-11-08T06:00:00Z")))
        // summer time: 09:00 Cairo is 06:00Z
        assertEquals(3_600_000L, BillsReminderScheduler.delayUntilNextNine(Instant.parse("2026-07-08T05:00:00Z")))
    }
}
