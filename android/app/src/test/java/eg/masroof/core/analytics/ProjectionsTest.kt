package eg.masroof.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/** Port of the server's ProjectionsTest (REQ-AN-141, REQ-AN-142). */
class ProjectionsTest {
    @Test
    fun `REQ-AN-141 delta in basis points rounds half away from zero`() {
        assertEquals(1250, Projections.deltaBps(112_500, 100_000))
        assertEquals(-5000, Projections.deltaBps(50_000, 100_000))
        assertEquals(0, Projections.deltaBps(100_000, 100_000))
        assertEquals(3333, Projections.deltaBps(4, 3))
        assertEquals(6667, Projections.deltaBps(5, 3))
        assertEquals(-6667, Projections.deltaBps(1, 3))
    }

    @Test
    fun `REQ-AN-141 delta is null when the previous period is zero`() {
        assertNull(Projections.deltaBps(500, 0))
        assertNull(Projections.deltaBps(0, 0))
    }

    @Test
    fun `REQ-AN-141 share and savings rate`() {
        assertEquals(2500, Projections.shareBps(25, 100))
        assertEquals(0, Projections.shareBps(1, 0))
        assertEquals(2000, Projections.savingsRateBps(100_000, 80_000))
        assertEquals(-2000, Projections.savingsRateBps(100_000, 120_000))
        assertNull(Projections.savingsRateBps(0, 5))
    }

    @Test
    fun `REQ-AN-141 daily average and linear projection`() {
        assertEquals(2500L, Projections.dailyAverage(10_000, 4))
        assertEquals(0L, Projections.dailyAverage(10_000, 0))
        assertEquals(31_000L, Projections.linear(25_000, 25, 31))
        assertEquals(0L, Projections.linear(0, 0, 31))
        assertEquals(25_000L, Projections.linear(25_000, 31, 31))
        assertEquals(25_000L, Projections.linear(25_000, 40, 31))
    }

    @Test
    fun `REQ-AN-141 rounding helper rejects a non-positive divisor`() {
        assertThrows(IllegalArgumentException::class.java) { Projections.divideRounded(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Projections.divideRounded(1, -3) }
    }

    @Test
    fun `REQ-AN-141 weekday-aware heavy Fridays push the projection above linear`() {
        val start = LocalDate.of(2026, 10, 1)
        val daily = LongArray(14) { i -> if (start.plusDays(i.toLong()).dayOfWeek == DayOfWeek.FRIDAY) 10_000L else 0L }
        val projected = Projections.weekdayAware(start, daily, 31)
        assertEquals(20_000L + 3 * 10_000L, projected)
        assertTrue(projected != Projections.linear(20_000, 14, 31))
    }

    @Test
    fun `REQ-AN-141 weekday-aware unseen weekday uses the overall mean`() {
        val start = LocalDate.of(2026, 10, 1)
        val projected = Projections.weekdayAware(start, longArrayOf(1_000, 3_000), 9)
        assertEquals(4_000L + 1_000 + 3_000 + 5 * 2_000, projected)
    }

    @Test
    fun `REQ-AN-141 weekday-aware empty and finished periods`() {
        val start = LocalDate.of(2026, 10, 1)
        assertEquals(0L, Projections.weekdayAware(start, LongArray(0), 31))
        assertEquals(18L, Projections.weekdayAware(start, longArrayOf(5, 6, 7), 3))
    }

    @Test
    fun `REQ-AN-141 projections never drop below spend and equal spend at the end`() {
        val rnd = Random(42)
        repeat(500) {
            val length = 28 + rnd.nextInt(4)
            val elapsed = rnd.nextInt(length + 1)
            val daily = LongArray(elapsed) { if (rnd.nextInt(5) == 0) 0L else rnd.nextInt(2_000_000).toLong() }
            val total = daily.sum()
            val start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(700).toLong())
            val lin = Projections.linear(total, elapsed, length)
            val wk = Projections.weekdayAware(start, daily, length)
            if (elapsed == 0) {
                assertEquals(0L, lin)
                assertEquals(0L, wk)
            } else {
                assertTrue(lin >= total)
                assertTrue(wk >= total)
            }
            if (elapsed == length) {
                assertEquals(total, lin)
                assertEquals(total, wk)
            }
        }
    }

    @Test
    fun `REQ-AN-141 constant daily spend projects exactly both ways`() {
        val rnd = Random(7)
        repeat(200) {
            val length = 28 + rnd.nextInt(4)
            val elapsed = 7 + rnd.nextInt(length - 7)
            val perDay = 1L + rnd.nextInt(100_000)
            val daily = LongArray(elapsed) { perDay }
            val start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(700).toLong())
            assertEquals(perDay * length, Projections.linear(perDay * elapsed, elapsed, length))
            assertEquals(perDay * length, Projections.weekdayAware(start, daily, length))
        }
    }

    @Test
    fun `REQ-AN-141 delta and share stay consistent with exact fractions`() {
        val rnd = Random(99)
        repeat(500) {
            val prev = 1L + rnd.nextInt(10_000_000)
            val cur = rnd.nextInt(20_000_000).toLong()
            val delta = Projections.deltaBps(cur, prev)!!.toLong()
            val exactTimes2 = 2 * (cur - prev) * 10_000 / prev
            assertTrue(abs(2 * delta - exactTimes2) <= 2)
            val whole = prev + cur
            val sum = Projections.shareBps(cur, whole) + Projections.shareBps(prev, whole)
            assertTrue(sum in 9_999..10_001)
        }
    }

    @Test
    fun `REQ-AN-142 overflow throws ArithmeticException never a wrapped value`() {
        assertThrows(ArithmeticException::class.java) { Projections.linear(Long.MAX_VALUE, 1, 31) }
        assertThrows(ArithmeticException::class.java) { Projections.shareBps(Long.MAX_VALUE, 10) }
        assertThrows(ArithmeticException::class.java) { Projections.deltaBps(Long.MAX_VALUE, 1) }
        assertThrows(ArithmeticException::class.java) { Projections.deltaBps(Long.MIN_VALUE, 1) }
        assertThrows(ArithmeticException::class.java) { Projections.savingsRateBps(1, Long.MIN_VALUE) }
        assertThrows(ArithmeticException::class.java) { Projections.divideRounded(Long.MAX_VALUE, 1) }
        assertThrows(ArithmeticException::class.java) {
            Projections.weekdayAware(LocalDate.of(2026, 10, 1), longArrayOf(Long.MAX_VALUE, 1), 31)
        }
        // result does not fit Int: 10^12 / 1 * 10^4 bps
        assertThrows(ArithmeticException::class.java) { Projections.deltaBps(1_000_000_000_000L, 1) }
    }
}
