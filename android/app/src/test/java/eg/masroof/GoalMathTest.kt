package eg.masroof

import eg.masroof.data.goals.GoalEmoji
import eg.masroof.data.goals.GoalInput
import eg.masroof.data.goals.GoalMath
import eg.masroof.data.goals.GoalMath.Pace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalMathTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun input(
        target: Long = 85_000_000L,
        saved: Long = 13_500_000L,
        created: String = "2026-01-01",
        due: String? = "2028-04-01",
        today: String = "2026-11-08",
        startDay: Int = 1,
        extra: Long = 0L,
    ) = GoalMath.Input(target, saved, d(created), due?.let(::d), d(today), startDay, extra)

    @Test
    fun `REQ-AN-167 worked example 135000 of 850000 needs 42059 a month and 510 days to go`() {
        val p = GoalMath.progress(input())
        assertEquals(13_500_000L, p.savedMinor)
        assertEquals(1588, p.percentBp) // 15.88% -> shown as 16%
        assertEquals(510, p.daysToGo)
        assertEquals(17, p.remainingPeriods)
        assertEquals(4_205_900L, p.monthlyNeedMinor) // ceil(715,000 / 17) = 42,059 EGP
        assertEquals(71_500_000L, p.remainingMinor)
    }

    @Test
    fun `REQ-AN-167 remaining periods for month start days 1 15 and 28`() {
        // start day 1: the current period plus every later one that starts before the date
        assertEquals(1, GoalMath.remainingPeriods(d("2026-11-08"), d("2026-11-20"), 1))
        assertEquals(1, GoalMath.remainingPeriods(d("2026-11-08"), d("2026-11-08"), 1))
        assertEquals(2, GoalMath.remainingPeriods(d("2026-11-08"), d("2026-12-02"), 1))
        assertEquals(1, GoalMath.remainingPeriods(d("2026-11-08"), d("2026-12-01"), 1)) // Dec 1 does not start before Dec 1
        // start day 15: Nov 8 is still in the Oct 15 period
        assertEquals(3, GoalMath.remainingPeriods(d("2026-11-08"), d("2027-01-10"), 15))
        assertEquals(2, GoalMath.remainingPeriods(d("2026-11-15"), d("2027-01-15"), 15))
        // start day 28 across a leap February
        assertEquals(1, GoalMath.remainingPeriods(d("2028-02-29"), d("2028-03-28"), 28))
        assertEquals(2, GoalMath.remainingPeriods(d("2028-02-29"), d("2028-03-29"), 28))
        assertEquals(1, GoalMath.remainingPeriods(d("2028-02-10"), d("2028-02-28"), 28))
        assertEquals(3, GoalMath.remainingPeriods(d("2028-01-10"), d("2028-02-29"), 28)) // Dec 28, Jan 28, Feb 28
    }

    @Test
    fun `REQ-AN-167 days to go across a leap day`() {
        assertEquals(2, GoalMath.progress(input(due = "2028-03-01", today = "2028-02-28")).daysToGo)
        assertEquals(1, GoalMath.progress(input(due = "2027-03-01", today = "2027-02-28")).daysToGo)
        assertEquals(0, GoalMath.progress(input(due = "2026-11-08")).daysToGo)
    }

    @Test
    fun `REQ-AN-167 the monthly need is the next whole pound and at least one period is used`() {
        val p = GoalMath.progress(input(target = 100_001L, saved = 0L, due = "2026-11-30"))
        assertEquals(1, p.remainingPeriods)
        assertEquals(100_100L, p.monthlyNeedMinor) // 1,000.01 -> 1,001 EGP
    }

    @Test
    fun `REQ-AN-167 on track at or above target times elapsed over duration and behind by the gap`() {
        val base = input(target = 1_000_000L, created = "2026-01-01", due = "2026-12-31", today = "2026-07-01")
        // 181 of 364 days gone: 1,000,000 x 181 / 364 = 497,252.75, rounded up to 497,253
        assertEquals(Pace.OnTrack, GoalMath.progress(base.copy(savedMinor = 497_253L)).pace)
        assertEquals(Pace.Behind(253L), GoalMath.progress(base.copy(savedMinor = 497_000L)).pace)
        assertEquals(Pace.OnTrack, GoalMath.progress(base.copy(savedMinor = 900_000L)).pace)
    }

    @Test
    fun `REQ-AN-167 no target date gives no monthly need and no pace`() {
        val p = GoalMath.progress(input(due = null))
        assertNull(p.monthlyNeedMinor)
        assertNull(p.daysToGo)
        assertEquals(Pace.None, p.pace)
    }

    @Test
    fun `REQ-AN-167 a date in the past that is not reached shows what is left and no monthly need`() {
        val p = GoalMath.progress(input(due = "2026-11-07"))
        assertEquals(Pace.PastDate(71_500_000L), p.pace)
        assertNull(p.monthlyNeedMinor)
        assertNull(p.daysToGo)
    }

    @Test
    fun `REQ-AN-167 saved at or over the target is reached with no monthly need`() {
        val exact = GoalMath.progress(input(saved = 85_000_000L))
        assertEquals(Pace.Reached, exact.pace)
        assertTrue(exact.reached)
        assertNull(exact.monthlyNeedMinor)
        assertEquals(10_000, exact.percentBp)
        val over = GoalMath.progress(input(saved = 90_000_000L, due = "2020-01-01"))
        assertEquals(Pace.Reached, over.pace)
        assertEquals(10_000, over.percentBp)
        assertEquals(0L, over.remainingMinor)
    }

    @Test
    fun `REQ-AN-167 an asset value linked to the goal counts as saved`() {
        val p = GoalMath.progress(input(saved = 13_500_000L, extra = 1_500_000L))
        assertEquals(15_000_000L, p.savedMinor)
        assertEquals(70_000_000L, p.remainingMinor)
    }

    @Test
    fun `REQ-AN-167 percent is half away from zero in basis points`() {
        assertEquals(1, GoalMath.progress(input(target = 20_000L, saved = 1L)).percentBp) // 0.5 bp rounds up
        assertEquals(0, GoalMath.progress(input(target = 40_000L, saved = 1L)).percentBp)
    }

    @Test
    fun `REQ-EXP-056 the step chart runs on the running total`() {
        assertEquals(listOf(500_000L, 300_000L, 800_000L), GoalMath.runningTotals(listOf(500_000L, -200_000L, 500_000L)))
        assertEquals(emptyList<Long>(), GoalMath.runningTotals(emptyList()))
    }

    @Test
    fun `REQ-EXP-056 the emoji grid has at least 40 distinct emoji`() {
        assertTrue(GoalEmoji.ALL.size >= 40)
        assertEquals(GoalEmoji.ALL.size, GoalEmoji.ALL.toSet().size)
    }

    @Test
    fun `REQ-EXP-056 target and movement dates are typed as yyyy-mm-dd in either digit set`() {
        assertEquals(d("2028-04-01"), GoalInput.parseDate("2028-04-01"))
        assertEquals(d("2028-04-01"), GoalInput.parseDate("٢٠٢٨-٠٤-٠١"))
        assertNull(GoalInput.parseDate("2027-02-29"))
        assertNull(GoalInput.parseDate("1/4/2028"))
        assertNull(GoalInput.parseDate(""))
    }
}
