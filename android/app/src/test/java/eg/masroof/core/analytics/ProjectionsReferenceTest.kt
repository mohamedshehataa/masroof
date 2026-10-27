package eg.masroof.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import kotlin.random.Random

/**
 * Seeded randomized comparison against an independent BigInteger reference (REQ-AN-141) and
 * overflow semantics (REQ-AN-142): wherever the reference needs an intermediate outside Long
 * (or a result outside Int), the port must throw ArithmeticException; otherwise it must be equal.
 */
class ProjectionsReferenceTest {
    private val cases = 1_500
    private val limit = 1_000_000_000_000_000L // 10^15

    /** BigInteger reference that records whether any intermediate would overflow Long/Int. */
    private class Ref {
        var overflow = false

        fun l(x: BigInteger): BigInteger {
            if (x.bitLength() > 63) overflow = true
            return x
        }

        fun i(x: BigInteger): BigInteger {
            if (x.bitLength() > 31) overflow = true
            return x
        }

        fun big(x: Long): BigInteger = BigInteger.valueOf(x)

        /** Half away from zero, formulated via remainder (differs from the port's formula). */
        fun div(
            n: BigInteger,
            d: BigInteger,
        ): BigInteger {
            l(n.abs() * BigInteger.TWO + d)
            l(d * BigInteger.TWO)
            val (q, r) = n.abs().divideAndRemainder(d)
            val up = if (r * BigInteger.TWO >= d) q + BigInteger.ONE else q
            return if (n.signum() < 0) up.negate() else up
        }
    }

    private fun value(rnd: Random): Long {
        var scale = 1L
        repeat(rnd.nextInt(0, 16)) { scale *= 10 }
        return rnd.nextLong(-scale, scale + 1).coerceIn(-limit, limit)
    }

    private fun check(
        ref: Ref,
        expected: BigInteger,
        actual: () -> Long,
    ) {
        if (ref.overflow) {
            assertThrows(ArithmeticException::class.java) { actual() }
        } else {
            assertEquals(expected.toLong(), actual())
        }
    }

    @Test
    fun `REQ-AN-141 divideRounded equals the BigInteger reference`() {
        val rnd = Random(20261027)
        repeat(cases) {
            val n = value(rnd)
            val d = Math.abs(value(rnd)) + 1
            val r = Ref()
            val e = r.div(r.big(n), r.big(d))
            check(r, e) { Projections.divideRounded(n, d) }
        }
    }

    @Test
    fun `REQ-AN-141 deltaBps equals the BigInteger reference`() {
        val rnd = Random(1)
        repeat(cases) {
            val cur = value(rnd)
            val prev = value(rnd)
            val got = runCatching { Projections.deltaBps(cur, prev) }
            if (prev <= 0) {
                assertEquals(null, got.getOrThrow())
            } else {
                val r = Ref()
                val e = r.i(r.div(r.l(r.big(cur) - r.big(prev)) * BigInteger.valueOf(10_000), r.big(prev)).also { r.l(it) })
                if (r.overflow) {
                    assertTrue(got.exceptionOrNull() is ArithmeticException)
                } else {
                    assertEquals(e.toInt(), got.getOrThrow())
                }
            }
        }
    }

    @Test
    fun `REQ-AN-141 shareBps equals the BigInteger reference`() {
        val rnd = Random(2)
        repeat(cases) {
            val part = value(rnd)
            val whole = value(rnd)
            val got = runCatching { Projections.shareBps(part, whole) }
            if (whole <= 0) {
                assertEquals(0, got.getOrThrow())
            } else {
                val r = Ref()
                val num = r.l(r.big(part) * BigInteger.valueOf(10_000))
                val e = r.i(r.div(num, r.big(whole)))
                if (r.overflow) assertTrue(got.exceptionOrNull() is ArithmeticException) else assertEquals(e.toInt(), got.getOrThrow())
            }
        }
    }

    @Test
    fun `REQ-AN-141 savingsRateBps equals the BigInteger reference`() {
        val rnd = Random(3)
        repeat(cases) {
            val income = value(rnd)
            val spent = value(rnd)
            val got = runCatching { Projections.savingsRateBps(income, spent) }
            if (income <= 0) {
                assertEquals(null, got.getOrThrow())
            } else {
                val r = Ref()
                val saved = r.l(r.big(income) - r.big(spent))
                val e = r.i(r.div(r.l(saved * BigInteger.valueOf(10_000)), r.big(income)))
                if (r.overflow) assertTrue(got.exceptionOrNull() is ArithmeticException) else assertEquals(e.toInt(), got.getOrThrow())
            }
        }
    }

    @Test
    fun `REQ-AN-141 dailyAverage equals the BigInteger reference`() {
        val rnd = Random(4)
        repeat(cases) {
            val total = value(rnd)
            val days = rnd.nextInt(-3, 40)
            if (days <= 0) {
                assertEquals(0L, Projections.dailyAverage(total, days))
            } else {
                val r = Ref()
                val e = r.div(r.big(total), r.big(days.toLong()))
                check(r, e) { Projections.dailyAverage(total, days) }
            }
        }
    }

    @Test
    fun `REQ-AN-141 linear equals the BigInteger reference`() {
        val rnd = Random(5)
        repeat(cases) {
            val total = value(rnd)
            val length = rnd.nextInt(1, 32)
            val elapsed = rnd.nextInt(-2, 34)
            when {
                elapsed <= 0 -> assertEquals(0L, Projections.linear(total, elapsed, length))
                elapsed >= length -> assertEquals(total, Projections.linear(total, elapsed, length))
                else -> {
                    val r = Ref()
                    val e = r.div(r.l(r.big(total) * r.big(length.toLong())), r.big(elapsed.toLong()))
                    check(r, e) { Projections.linear(total, elapsed, length) }
                }
            }
        }
    }

    @Test
    fun `REQ-AN-141 weekdayAware equals the BigInteger reference`() {
        val rnd = Random(6)
        repeat(cases) {
            val length = rnd.nextInt(1, 32)
            val elapsed = rnd.nextInt(0, 34)
            val start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(0, 700))
            val daily = LongArray(elapsed) { value(rnd) }
            val r = Ref()
            var total = BigInteger.ZERO
            for (x in daily) total = r.l(total + r.big(x))
            val expected: BigInteger =
                if (elapsed == 0) {
                    BigInteger.ZERO
                } else if (elapsed >= length) {
                    total
                } else {
                    val sum = Array(7) { BigInteger.ZERO }
                    val seen = IntArray(7)
                    for (i in 0 until elapsed) {
                        val k = start.plusDays(i.toLong()).dayOfWeek.ordinal
                        sum[k] = r.l(sum[k] + r.big(daily[i]))
                        seen[k]++
                    }
                    val remaining = IntArray(7)
                    for (i in elapsed until length) remaining[start.plusDays(i.toLong()).dayOfWeek.ordinal]++
                    var projected = total
                    for (k in 0 until 7) {
                        if (remaining[k] == 0) continue
                        val add =
                            if (seen[k] > 0) {
                                r.div(r.l(sum[k] * BigInteger.valueOf(remaining[k].toLong())), BigInteger.valueOf(seen[k].toLong()))
                            } else {
                                r.div(r.l(total * BigInteger.valueOf(remaining[k].toLong())), BigInteger.valueOf(elapsed.toLong()))
                            }
                        projected = r.l(projected + add)
                    }
                    projected
                }
            check(r, expected) { Projections.weekdayAware(start, daily, length) }
        }
    }

    @Test
    fun `REQ-AN-142 package sources have no android, room, Double or Float`() {
        val dir = File("src/main/java/eg/masroof/core/analytics")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("analytics sources not found at ${dir.absolutePath}", files.size >= 3)
        val banned = listOf(Regex("""\bandroid\."""), Regex("""androidx\.room"""), Regex("""\bDouble\b"""), Regex("""\bFloat\b"""))
        for (f in files) {
            // Strip comments so prose cannot trip the check.
            val code = f.readText().replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            for (b in banned) assertTrue("${f.name} matches ${b.pattern}", !b.containsMatchIn(code))
        }
    }
}
