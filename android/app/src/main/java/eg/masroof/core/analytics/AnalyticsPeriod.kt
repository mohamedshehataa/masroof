package eg.masroof.core.analytics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * An analytics period in Cairo calendar days, both ends inclusive. A month period starts on the
 * household `month_start_day` of the calendar month it is named after (so `2026-10` with start day
 * 25 runs 25 Oct to 24 Nov); a week period is an ISO week, Monday to Sunday.
 * Port of the server's AnalyticsPeriod; invalid input throws IllegalArgumentException.
 */
data class AnalyticsPeriod(
    val label: String,
    val from: LocalDate,
    val to: LocalDate,
    val week: Boolean,
) {
    init {
        require(!to.isBefore(from)) { "period end before start" }
    }

    /** The period right before this one, of the same kind. */
    fun previous(): AnalyticsPeriod =
        if (week) {
            week(from.minusDays(7))
        } else {
            month(YearMonth.from(from).minusMonths(1), from.dayOfMonth)
        }

    /** Number of Cairo calendar days in the period. */
    fun lengthDays(): Int = Math.toIntExact(ChronoUnit.DAYS.between(from, to)) + 1

    /** First instant of the period (inclusive). */
    fun start(): Instant = BusinessCalendar.startOfDay(from)

    /** First instant after the period (exclusive), correct across DST changes. */
    fun endExclusive(): Instant = BusinessCalendar.endOfDayExclusive(to)

    /** Days of the period that have started as of [today], between 0 and the length. */
    fun elapsedDays(today: LocalDate): Int {
        if (today.isBefore(from)) return 0
        if (today.isAfter(to)) return lengthDays()
        return Math.toIntExact(ChronoUnit.DAYS.between(from, today)) + 1
    }

    /** Days after [today] until the period ends; 0 once it is over. */
    fun daysLeft(today: LocalDate): Int = lengthDays() - elapsedDays(today)

    companion object {
        private val SPEC = Regex("^(\\d{4})-(?:(\\d{2})|W(\\d{2}))$")

        /**
         * Resolves `current`, `previous`, `YYYY-MM` or `YYYY-Www`.
         *
         * @throws IllegalArgumentException when the spec is malformed or names a month/week that does not exist
         */
        fun parse(
            spec: String,
            today: LocalDate,
            monthStartDay: Int,
        ): AnalyticsPeriod {
            requireStartDay(monthStartDay)
            if (spec == "current") return currentMonth(today, monthStartDay)
            if (spec == "previous") return currentMonth(today, monthStartDay).previous()
            val m =
                requireNotNull(SPEC.matchEntire(spec)) {
                    "period must be current, previous, YYYY-MM or YYYY-Www"
                }
            val year = m.groupValues[1].toInt()
            val monthText = m.groups[2]?.value
            if (monthText != null) {
                val month = monthText.toInt()
                require(month in 1..12) { "month must be 01..12" }
                return month(YearMonth.of(year, month), monthStartDay)
            }
            val weekNo = m.groupValues[3].toInt()
            require(weekNo in 1..53) { "week must be 01..53" }
            val monday =
                LocalDate
                    .of(year, 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, weekNo.toLong())
                    .with(DayOfWeek.MONDAY)
            require(monday.get(IsoFields.WEEK_BASED_YEAR) == year) { "year has no such ISO week" }
            return week(monday)
        }

        /** The household month that contains [today]. */
        fun currentMonth(
            today: LocalDate,
            monthStartDay: Int,
        ): AnalyticsPeriod {
            requireStartDay(monthStartDay)
            val ym = YearMonth.from(today)
            return month(if (today.dayOfMonth >= monthStartDay) ym else ym.minusMonths(1), monthStartDay)
        }

        /** The household month named after [ym]. */
        fun month(
            ym: YearMonth,
            monthStartDay: Int,
        ): AnalyticsPeriod {
            requireStartDay(monthStartDay)
            val from = ym.atDay(monthStartDay)
            val to = ym.plusMonths(1).atDay(monthStartDay).minusDays(1)
            return AnalyticsPeriod(ym.toString(), from, to, false)
        }

        /** The ISO week that starts on [monday]. */
        fun week(monday: LocalDate): AnalyticsPeriod {
            // Locale.ROOT: Java's default-locale String.format would emit Arabic-Indic digits under ar.
            val label =
                String.format(
                    Locale.ROOT,
                    "%04d-W%02d",
                    monday.get(IsoFields.WEEK_BASED_YEAR),
                    monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                )
            return AnalyticsPeriod(label, monday, monday.plusDays(6), true)
        }

        private fun requireStartDay(day: Int) {
            require(day in 1..28) { "month start day must be 1..28" }
        }
    }
}
