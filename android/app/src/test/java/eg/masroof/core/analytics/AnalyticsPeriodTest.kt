package eg.masroof.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Port of the server's AnalyticsPeriodTest and BusinessCalendarTest (REQ-AN-140). */
class AnalyticsPeriodTest {
    private fun d(
        y: Int,
        m: Int,
        day: Int,
    ) = LocalDate.of(y, m, day)

    @Test
    fun `REQ-AN-140 current month with start day 1 is the calendar month`() {
        val p = AnalyticsPeriod.parse("current", d(2026, 10, 25), 1)
        assertEquals("2026-10", p.label)
        assertEquals(d(2026, 10, 1), p.from)
        assertEquals(d(2026, 10, 31), p.to)
        assertEquals(31, p.lengthDays())
    }

    @Test
    fun `REQ-AN-140 start day 25 puts 24 Oct in the month that began 25 Sep`() {
        val before = AnalyticsPeriod.parse("current", d(2026, 10, 24), 25)
        assertEquals("2026-09", before.label)
        assertEquals(d(2026, 9, 25), before.from)
        assertEquals(d(2026, 10, 24), before.to)
        val on = AnalyticsPeriod.parse("current", d(2026, 10, 25), 25)
        assertEquals("2026-10", on.label)
        assertEquals(d(2026, 11, 24), on.to)
        assertEquals("2026-09", AnalyticsPeriod.parse("previous", d(2026, 10, 25), 25).label)
    }

    @Test
    fun `REQ-AN-140 start day 25 on 2026-10-27 starts at Cairo midnight 25 Oct`() {
        val p = AnalyticsPeriod.parse("current", d(2026, 10, 27), 25)
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), p.start())
        // 25 Nov 00:00 Cairo (UTC+2 after the 29 Oct DST end)
        assertEquals(Instant.parse("2026-11-24T22:00:00Z"), p.endExclusive())
    }

    @Test
    fun `REQ-AN-140 january start day 25 crosses the year boundary`() {
        val p = AnalyticsPeriod.parse("current", d(2026, 1, 10), 25)
        assertEquals("2025-12", p.label)
        assertEquals(d(2026, 1, 24), p.to)
        assertEquals("2025-11", p.previous().label)
    }

    @Test
    fun `REQ-AN-140 explicit YYYY-MM and ISO week specs`() {
        assertEquals(28, AnalyticsPeriod.parse("2026-02", d(2026, 10, 1), 1).lengthDays())
        val w = AnalyticsPeriod.parse("2026-W44", d(2026, 10, 1), 1)
        assertTrue(w.week)
        assertEquals(d(2026, 10, 26), w.from)
        assertEquals(d(2026, 11, 1), w.to)
        assertEquals("2026-W44", w.label)
        assertEquals("2026-W43", w.previous().label)
        assertEquals(d(2020, 12, 28), AnalyticsPeriod.parse("2020-W53", d(2026, 10, 1), 1).from)
    }

    @Test
    fun `REQ-AN-140 malformed or non-existent periods are rejected`() {
        for (spec in listOf("", "now", "2026-13", "2026-00", "2026-W00", "2026-W54", "2025-W53", "26-10", "abc")) {
            assertThrows(spec, IllegalArgumentException::class.java) {
                AnalyticsPeriod.parse(spec, d(2026, 10, 1), 1)
            }
        }
    }

    @Test
    fun `REQ-AN-140 month start day outside 1 to 28 is rejected not defaulted`() {
        for (day in listOf(-1, 0, 29, 31)) {
            assertThrows(IllegalArgumentException::class.java) { AnalyticsPeriod.parse("current", d(2026, 10, 1), day) }
            assertThrows(IllegalArgumentException::class.java) { AnalyticsPeriod.currentMonth(d(2026, 10, 1), day) }
            assertThrows(IllegalArgumentException::class.java) { AnalyticsPeriod.month(YearMonth.of(2026, 10), day) }
        }
    }

    @Test
    fun `REQ-AN-140 consecutive months tile the calendar for every start day`() {
        for (startDay in 1..28) {
            var p = AnalyticsPeriod.month(YearMonth.of(2023, 1), startDay)
            repeat(48) {
                val next = AnalyticsPeriod.month(YearMonth.from(p.from).plusMonths(1), startDay)
                assertEquals(p.to.plusDays(1), next.from)
                assertEquals(p, next.previous())
                assertTrue(p.lengthDays() in 28..31)
                p = next
            }
        }
    }

    @Test
    fun `REQ-AN-140 every day belongs to exactly the current month containing it`() {
        for (startDay in intArrayOf(1, 5, 25, 28)) {
            var day = d(2025, 1, 1)
            while (day.isBefore(d(2027, 1, 1))) {
                val p = AnalyticsPeriod.currentMonth(day, startDay)
                assertFalse(day.isBefore(p.from))
                assertFalse(day.isAfter(p.to))
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun `REQ-AN-140 elapsed and remaining days`() {
        val p = AnalyticsPeriod.parse("2026-10", d(2026, 10, 25), 1)
        assertEquals(0, p.elapsedDays(d(2026, 9, 30)))
        assertEquals(1, p.elapsedDays(d(2026, 10, 1)))
        assertEquals(25, p.elapsedDays(d(2026, 10, 25)))
        assertEquals(6, p.daysLeft(d(2026, 10, 25)))
        assertEquals(31, p.elapsedDays(d(2026, 11, 20)))
        assertEquals(0, p.daysLeft(d(2026, 11, 20)))
    }

    @Test
    fun `REQ-AN-140 instants are Cairo midnights across the October DST change`() {
        val p = AnalyticsPeriod.parse("2026-10", d(2026, 10, 1), 1)
        assertEquals(Instant.parse("2026-09-30T21:00:00Z"), p.start())
        assertEquals(Instant.parse("2026-10-31T22:00:00Z"), p.endExclusive())
        assertEquals(Duration.ofDays(31).plusHours(1), Duration.between(p.start(), p.endExclusive()))
    }

    @Test
    fun `REQ-AN-140 an inverted period is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsPeriod("x", d(2026, 1, 2), d(2026, 1, 1), false)
        }
    }

    @Test
    fun `REQ-AN-140 DST 2026 spring forward on Friday 24 April makes that day 23 hours`() {
        val before = AnalyticsPeriod("d", d(2026, 4, 23), d(2026, 4, 23), false)
        val day = AnalyticsPeriod("d", d(2026, 4, 24), d(2026, 4, 24), false)
        assertEquals(Instant.parse("2026-04-22T22:00:00Z"), before.start())
        assertEquals(Duration.ofHours(24), Duration.between(before.start(), before.endExclusive()))
        assertEquals(Instant.parse("2026-04-23T22:00:00Z"), day.start())
        assertEquals(Instant.parse("2026-04-24T21:00:00Z"), day.endExclusive())
        assertEquals(Duration.ofHours(23), Duration.between(day.start(), day.endExclusive()))
        val april = AnalyticsPeriod.parse("2026-04", d(2026, 4, 1), 1)
        assertEquals(Duration.ofDays(30).minusHours(1), Duration.between(april.start(), april.endExclusive()))
        assertEquals(25, april.elapsedDays(d(2026, 4, 25)))
    }

    @Test
    fun `REQ-AN-140 DST 2026 fall back on Thursday 29 October makes that day 25 hours`() {
        val day = AnalyticsPeriod("d", d(2026, 10, 29), d(2026, 10, 29), false)
        assertEquals(Instant.parse("2026-10-28T21:00:00Z"), day.start())
        assertEquals(Instant.parse("2026-10-29T22:00:00Z"), day.endExclusive())
        assertEquals(Duration.ofHours(25), Duration.between(day.start(), day.endExclusive()))
        val w = AnalyticsPeriod.parse("2026-W44", d(2026, 10, 1), 1)
        assertEquals(7, w.lengthDays())
        assertEquals(4, w.elapsedDays(d(2026, 10, 29)))
    }

    @Test
    fun `REQ-AN-140 business calendar late evening UTC is the next Cairo day`() {
        assertEquals(d(2027, 1, 1), BusinessCalendar.dateOf(Instant.parse("2026-12-31T22:30:00Z")))
        assertEquals(d(2026, 12, 31), BusinessCalendar.dateOf(Instant.parse("2026-12-31T21:59:59Z")))
    }

    @Test
    fun `REQ-AN-140 business calendar day bounds use Cairo midnight`() {
        val day = d(2026, 1, 15)
        assertEquals(Instant.parse("2026-01-14T22:00:00Z"), BusinessCalendar.startOfDay(day))
        assertEquals(Instant.parse("2026-01-15T22:00:00Z"), BusinessCalendar.endOfDayExclusive(day))
    }

    @Test
    fun `REQ-AN-140 business calendar summer days use the DST offset`() {
        assertEquals(Instant.parse("2026-06-30T21:00:00Z"), BusinessCalendar.startOfDay(d(2026, 7, 1)))
    }
}
