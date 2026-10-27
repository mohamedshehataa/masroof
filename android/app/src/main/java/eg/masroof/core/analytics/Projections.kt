package eg.masroof.core.analytics

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure integer arithmetic for analytics: basis-point changes and period-end projections.
 * Line-for-line port of the server's Projections; "no value" is null (Java OptionalInt.empty()),
 * overflow throws ArithmeticException.
 */
object Projections {
    private const val BPS = 10_000L

    /** Round-half-away-from-zero division of [numerator] by a positive [divisor]. */
    internal fun divideRounded(
        numerator: Long,
        divisor: Long,
    ): Long {
        require(divisor > 0) { "divisor must be positive" }
        val abs = Math.addExact(Math.multiplyExact(Math.abs(numerator), 2L), divisor)
        val q = abs / Math.multiplyExact(divisor, 2L)
        return if (numerator < 0) -q else q
    }

    /** Change from [previous] to [current] in basis points; null when previous is not positive. */
    fun deltaBps(
        current: Long,
        previous: Long,
    ): Int? {
        if (previous <= 0) return null
        val diff = Math.subtractExact(current, previous)
        return Math.toIntExact(divideRounded(Math.multiplyExact(diff, BPS), previous))
    }

    /** [part] as basis points of [whole]; 0 when whole is not positive. */
    fun shareBps(
        part: Long,
        whole: Long,
    ): Int {
        if (whole <= 0) return 0
        return Math.toIntExact(divideRounded(Math.multiplyExact(part, BPS), whole))
    }

    /** `(income - spent) / income` in basis points; null without income. */
    fun savingsRateBps(
        income: Long,
        spent: Long,
    ): Int? {
        if (income <= 0) return null
        val saved = Math.subtractExact(income, spent)
        return Math.toIntExact(divideRounded(Math.multiplyExact(saved, BPS), income))
    }

    /** Mean per elapsed day, rounded; 0 before the first day ends. */
    fun dailyAverage(
        total: Long,
        elapsedDays: Int,
    ): Long = if (elapsedDays <= 0) 0 else divideRounded(total, elapsedDays.toLong())

    /** Linear projection of [total] after [elapsedDays] to [lengthDays]. */
    fun linear(
        total: Long,
        elapsedDays: Int,
        lengthDays: Int,
    ): Long {
        if (elapsedDays <= 0) return 0
        if (elapsedDays >= lengthDays) return total
        return divideRounded(Math.multiplyExact(total, lengthDays.toLong()), elapsedDays.toLong())
    }

    /**
     * Weekday-aware projection: each remaining day is expected to cost the mean of the elapsed days
     * with the same weekday; a weekday not seen yet falls back to the overall daily mean.
     *
     * @param periodStart first day of the period
     * @param elapsedDaily spend per elapsed day, index 0 being the period start
     * @param lengthDays days in the whole period
     */
    fun weekdayAware(
        periodStart: LocalDate,
        elapsedDaily: LongArray,
        lengthDays: Int,
    ): Long {
        val elapsed = elapsedDaily.size
        var total = 0L
        for (d in elapsedDaily) total = Math.addExact(total, d)
        if (elapsed == 0) return 0
        if (elapsed >= lengthDays) return total
        val sum = LongArray(7)
        val seen = IntArray(7)
        for (i in 0 until elapsed) {
            val dow = periodStart.plusDays(i.toLong()).dayOfWeek.value - 1
            sum[dow] = Math.addExact(sum[dow], elapsedDaily[i])
            seen[dow]++
        }
        val remaining = IntArray(7)
        for (i in elapsed until lengthDays) {
            remaining[periodStart.plusDays(i.toLong()).dayOfWeek.value - 1]++
        }
        var projected = total
        for (day in DayOfWeek.entries) {
            val k = day.value - 1
            if (remaining[k] == 0) continue
            val add =
                if (seen[k] > 0) {
                    divideRounded(Math.multiplyExact(sum[k], remaining[k].toLong()), seen[k].toLong())
                } else {
                    divideRounded(Math.multiplyExact(total, remaining[k].toLong()), elapsed.toLong())
                }
            projected = Math.addExact(projected, add)
        }
        return projected
    }
}
