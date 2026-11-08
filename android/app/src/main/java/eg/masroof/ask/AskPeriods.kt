package eg.masroof.ask

import eg.masroof.core.analytics.AnalyticsPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Turns an [AskPeriod] into Cairo calendar days (with the household month start day) and finds the period before it. */
internal object AskPeriods {
    fun resolve(
        p: AskPeriod,
        today: LocalDate,
        monthStartDay: Int,
    ): AnalyticsPeriod =
        when (p.kind) {
            AskPeriodKind.TODAY -> day(today)
            AskPeriodKind.YESTERDAY -> day(today.minusDays(1))
            AskPeriodKind.THIS_WEEK -> AnalyticsPeriod.week(today.with(DayOfWeek.MONDAY))
            AskPeriodKind.LAST_WEEK -> AnalyticsPeriod.week(today.with(DayOfWeek.MONDAY).minusDays(WEEK_DAYS))
            AskPeriodKind.THIS_MONTH -> AnalyticsPeriod.currentMonth(today, monthStartDay)
            AskPeriodKind.LAST_MONTH -> AnalyticsPeriod.currentMonth(today, monthStartDay).previous()
            AskPeriodKind.THIS_YEAR ->
                AnalyticsPeriod(
                    today.year.toString(),
                    LocalDate.of(today.year, 1, 1),
                    LocalDate.of(today.year, 12, 31),
                    false,
                )
            AskPeriodKind.LAST_YEAR -> yearPeriod(today.year - 1)
            AskPeriodKind.YEAR -> yearPeriod(requireNotNull(p.year))
            AskPeriodKind.MONTHS_AGO -> monthsAgo(p.days, today, monthStartDay)
            AskPeriodKind.DAY_RANGE -> dayRange(p, today)
            AskPeriodKind.SINCE_YEAR -> AnalyticsPeriod("since-${p.year}", LocalDate.of(requireNotNull(p.year), 1, 1), today, false)
            AskPeriodKind.LAST_DAYS -> AnalyticsPeriod("last-${p.days}", today.minusDays(p.days - 1L), today, false)
            AskPeriodKind.MONTH_OF_YEAR -> AnalyticsPeriod.month(monthOf(p, today, monthStartDay), monthStartDay)
        }

    private const val WEEK_DAYS = 7L

    private fun yearPeriod(y: Int) = AnalyticsPeriod(y.toString(), LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), false)

    private fun monthsAgo(
        n: Int,
        today: LocalDate,
        monthStartDay: Int,
    ): AnalyticsPeriod {
        var m = AnalyticsPeriod.currentMonth(today, monthStartDay)
        repeat(n) { m = m.previous() }
        return m
    }

    /** "1 to 15 October": calendar days of the most recent such calendar month that has started, or of the given year. */
    private fun dayRange(
        p: AskPeriod,
        today: LocalDate,
    ): AnalyticsPeriod {
        fun fromOf(ym: YearMonth) = ym.atDay(minOf(p.days, ym.lengthOfMonth()))
        val thisYear = YearMonth.of(today.year, p.month)
        val named =
            p.year?.let { YearMonth.of(it, p.month) }
                ?: thisYear.let { if (fromOf(it).isAfter(today)) it.minusYears(1) else it }
        val from = fromOf(named)
        return AnalyticsPeriod("range-$from", from, named.atDay(minOf(p.toDay, named.lengthOfMonth())), false)
    }

    private fun day(d: LocalDate) = AnalyticsPeriod(d.toString(), d, d, false)

    /** A month named without a year is the most recent one that is not after the current household month. */
    private fun monthOf(
        p: AskPeriod,
        today: LocalDate,
        monthStartDay: Int,
    ): YearMonth {
        p.year?.let { return YearMonth.of(it, p.month) }
        val current = YearMonth.from(AnalyticsPeriod.currentMonth(today, monthStartDay).from)
        val candidate = YearMonth.of(current.year, p.month)
        return if (candidate.isAfter(current)) candidate.minusYears(1) else candidate
    }

    /** The period right before [a], of the same kind. */
    fun previousOf(
        p: AskPeriod,
        a: AnalyticsPeriod,
    ): AnalyticsPeriod =
        when (p.kind) {
            AskPeriodKind.THIS_MONTH, AskPeriodKind.LAST_MONTH, AskPeriodKind.MONTH_OF_YEAR,
            AskPeriodKind.THIS_WEEK, AskPeriodKind.LAST_WEEK,
            -> a.previous()
            AskPeriodKind.MONTHS_AGO -> a.previous()
            AskPeriodKind.THIS_YEAR, AskPeriodKind.LAST_YEAR, AskPeriodKind.YEAR ->
                AnalyticsPeriod((a.from.year - 1).toString(), a.from.minusYears(1), a.to.minusYears(1), false)
            AskPeriodKind.TODAY, AskPeriodKind.YESTERDAY, AskPeriodKind.LAST_DAYS, AskPeriodKind.DAY_RANGE, AskPeriodKind.SINCE_YEAR -> {
                val len = a.lengthDays().toLong()
                AnalyticsPeriod("before-${a.from}", a.from.minusDays(len), a.from.minusDays(1), false)
            }
        }

    /** The name of the period before one that has a name, when there is one; otherwise the UI shows dates. */
    fun previousName(
        p: AskPeriod,
        a: AnalyticsPeriod,
    ): AskPeriod? =
        when (p.kind) {
            AskPeriodKind.THIS_MONTH -> AskPeriod.LAST_MONTH
            AskPeriodKind.THIS_WEEK -> AskPeriod.LAST_WEEK
            AskPeriodKind.THIS_YEAR -> AskPeriod.LAST_YEAR
            AskPeriodKind.YEAR, AskPeriodKind.LAST_YEAR -> AskPeriod.year(a.from.year - 1)
            AskPeriodKind.TODAY -> AskPeriod.YESTERDAY
            AskPeriodKind.LAST_MONTH, AskPeriodKind.MONTH_OF_YEAR -> {
                val ym = YearMonth.from(a.from).minusMonths(1)
                AskPeriod.monthOfYear(ym.monthValue, ym.year)
            }
            else -> null
        }
}
