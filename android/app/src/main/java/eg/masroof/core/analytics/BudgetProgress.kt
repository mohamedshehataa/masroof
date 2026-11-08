package eg.masroof.core.analytics

import java.math.BigInteger

/**
 * Progress of spending against a budget limit, integer arithmetic only. Port of the server's `BudgetProgress`
 * (REQ-AN-153): [progressBps] is spent / limit in basis points, half away from zero, capped at [MAX_BPS];
 * [alertsFired] are the configured thresholds (percent) already reached, ascending.
 */
data class BudgetProgress(
    val spent: Long,
    val limit: Long,
    val progressBps: Int,
    val alertsFired: List<Int>,
) {
    /** The highest threshold reached, or 0. */
    fun thresholdReached(): Int = alertsFired.lastOrNull() ?: 0

    companion object {
        /** Upper cap, 100x the limit, so absurd overspend cannot overflow. */
        const val MAX_BPS = 1_000_000
        private const val BPS_PER_PERCENT = 100L
        private const val CAP_FACTOR = 100L
        private const val FULL_BPS = 10_000

        /**
         * A non-positive limit (rollover ate the whole budget) counts as fully used: 100% with nothing spent and the cap
         * once anything is, so every threshold up to 200% can fire (REQ-AN-154).
         */
        fun of(
            spent: Long,
            limit: Long,
            thresholds: List<Int>,
        ): BudgetProgress {
            val bps =
                when {
                    limit <= 0 -> if (spent > 0) MAX_BPS else FULL_BPS
                    spent <= 0 -> 0
                    limit <= Long.MAX_VALUE / CAP_FACTOR && spent > limit * CAP_FACTOR -> MAX_BPS
                    else -> minOf(MAX_BPS, shareBps(spent, limit))
                }
            val fired = thresholds.filter { bps >= it * BPS_PER_PERCENT }.sorted()
            return BudgetProgress(spent, limit, bps, fired)
        }

        /** [Projections.shareBps] while it fits in a Long, the same rounding in BigInteger beyond (no overflow, ever). */
        private fun shareBps(
            spent: Long,
            limit: Long,
        ): Int =
            try {
                Projections.shareBps(spent, limit)
            } catch (_: ArithmeticException) {
                val num = BigInteger.valueOf(spent).multiply(BigInteger.valueOf(20_000L))
                val den = BigInteger.valueOf(limit).shiftLeft(1)
                num
                    .add(BigInteger.valueOf(limit))
                    .divide(den)
                    .min(BigInteger.valueOf(MAX_BPS.toLong()))
                    .toInt()
            }
    }
}
