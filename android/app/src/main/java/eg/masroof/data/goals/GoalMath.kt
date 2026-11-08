package eg.masroof.data.goals

import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.Projections
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Savings goal maths (REQ-AN-167), pure and in Cairo calendar days. Money is Long minor units; nothing here knows about Room.
 * [Input.extraSavedMinor] is the hook for gold-backed goals (STORY-209): the value of an asset linked to the goal counts as saved.
 */
object GoalMath {
    private const val BPS = 10_000L
    private const val MAX_PERIODS = 1_200
    private const val MINOR_PER_MAJOR = 100L
    private const val HALF_PERCENT_BP = 50
    private const val BP_PER_PERCENT = 100
    private const val FULL_PERCENT = 100

    data class Input(
        val targetMinor: Long,
        val savedMinor: Long,
        val createdOn: LocalDate,
        val dueOn: LocalDate?,
        val today: LocalDate,
        val monthStartDay: Int,
        val extraSavedMinor: Long = 0L,
    )

    sealed interface Pace {
        /** No target date, so nothing to compare with. */
        data object None : Pace

        data object Reached : Pace

        data object OnTrack : Pace

        data class Behind(
            val byMinor: Long,
        ) : Pace

        data class PastDate(
            val leftMinor: Long,
        ) : Pace
    }

    data class Progress(
        val savedMinor: Long,
        val targetMinor: Long,
        val remainingMinor: Long,
        /** Saved as basis points of the target, half away from zero, between 0 and 10,000. */
        val percentBp: Int,
        val daysToGo: Int?,
        val remainingPeriods: Int?,
        val monthlyNeedMinor: Long?,
        val pace: Pace,
    ) {
        val reached: Boolean get() = pace == Pace.Reached
    }

    fun progress(input: Input): Progress {
        val saved = input.savedMinor + input.extraSavedMinor
        val target = input.targetMinor
        val remaining = (target - saved).coerceAtLeast(0L)
        val percent = Projections.shareBps(saved.coerceAtLeast(0L), target).coerceIn(0, BPS.toInt())
        val due = input.dueOn
        val dueInFuture = due != null && !due.isBefore(input.today)
        val periods = if (due != null && dueInFuture) remainingPeriods(input.today, due, input.monthStartDay) else null
        val reached = saved >= target
        val pace =
            when {
                reached -> Pace.Reached
                due == null -> Pace.None
                !dueInFuture -> Pace.PastDate(remaining)
                else -> paceFor(input, saved, due)
            }
        return Progress(
            savedMinor = saved,
            targetMinor = target,
            remainingMinor = remaining,
            percentBp = percent,
            daysToGo = if (due != null && dueInFuture) ChronoUnit.DAYS.between(input.today, due).toInt() else null,
            remainingPeriods = periods,
            // ceil(remaining / periods) in whole pounds, as the story's worked example shows (42,058.8 -> 42,059).
            monthlyNeedMinor = if (periods != null && !reached) ceilDiv(remaining, periods * MINOR_PER_MAJOR) * MINOR_PER_MAJOR else null,
            pace = pace,
        )
    }

    /** Whole percent for display: rounds half up, but never shows 100 until the target is reached (9,950 bp reads 99). */
    fun displayPercent(bp: Int): Int {
        val rounded = (bp + HALF_PERCENT_BP) / BP_PER_PERCENT
        return if (bp < BPS.toInt() && rounded >= FULL_PERCENT) FULL_PERCENT - 1 else rounded
    }

    /** The current household period plus every later one that starts before [due]; at least 1. */
    fun remainingPeriods(
        today: LocalDate,
        due: LocalDate,
        monthStartDay: Int,
    ): Int {
        val first = AnalyticsPeriod.currentMonth(today, monthStartDay)
        var ym = YearMonth.from(first.from)
        var count = 1
        while (count < MAX_PERIODS) {
            ym = ym.plusMonths(1)
            if (!AnalyticsPeriod.month(ym, monthStartDay).from.isBefore(due)) break
            count++
        }
        return count
    }

    // On track when saved >= target x elapsed / total duration since creation; "behind by" is the ceiling of the gap.
    private fun paceFor(
        input: Input,
        saved: Long,
        due: LocalDate,
    ): Pace {
        val total = ChronoUnit.DAYS.between(input.createdOn, due).coerceAtLeast(0L)
        val elapsed = ChronoUnit.DAYS.between(input.createdOn, input.today).coerceIn(0L, total)
        val expected = if (total == 0L) input.targetMinor else ceilDiv(Math.multiplyExact(input.targetMinor, elapsed), total)
        return if (saved >= expected) Pace.OnTrack else Pace.Behind(expected - saved)
    }

    private fun ceilDiv(
        numerator: Long,
        divisor: Long,
    ): Long = if (numerator <= 0L) 0L else (numerator + divisor - 1) / divisor

    /** Running total after each movement, oldest first, for the "Money you've added" step chart. */
    fun runningTotals(amountsOldestFirst: List<Long>): List<Long> {
        var sum = 0L
        return amountsOldestFirst.map {
            sum += it
            sum
        }
    }
}
