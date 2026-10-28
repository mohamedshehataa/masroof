package eg.masroof.core.analytics

import java.time.Instant
import java.time.LocalDate

/** One merchant of the period. [name] is the display spelling; [categoryId] the most frequent stored category. */
data class MerchantLine(
    val name: String,
    val total: Long,
    val visits: Int,
    val averageTicket: Long,
    val categoryId: String?,
)

/** Spend of one Cairo day; [count] is the number of entries (refunds included). */
data class DayCell(
    val date: LocalDate,
    val total: Long,
    val count: Int,
)

/** [days] has every day of the period; [weekdayHour] is 7 rows (index 0 = Saturday) of 24 Cairo hours. */
data class HeatmapResult(
    val days: List<DayCell>,
    val weekdayHour: List<List<Long>>,
)

/** One household month of the trend; [savingsRateBps] is null without income. [byCategory] is by top-level category. */
data class TrendPoint(
    val period: AnalyticsPeriod,
    val total: Long,
    val income: Long,
    val savingsRateBps: Int?,
    val byCategory: List<CategoryLine>,
)

/** Merchants, heatmap and trend of ADR-0009 D2, ports of the server's AnalyticsService (the parity file is the referee). */
object AnalyticsExtras {
    const val TREND_PERIODS = 12
    private const val MAX_MERCHANTS = 50
    private const val WEEK = 7
    private const val HOURS = 24

    /** Grouped by `lower(trim(name))`, positive net only, never with cash; rows without a merchant are not listed. */
    fun merchants(rows: List<AnalyticsRow>): List<MerchantLine> {
        val groups = HashMap<String, MutableList<AnalyticsRow>>()
        for (r in rows) {
            val name = r.merchant?.trim(' ')
            if (name.isNullOrEmpty() || !r.isSpend(false)) continue
            groups.getOrPut(name.lowercase()) { ArrayList() }.add(r)
        }
        return groups.values
            .mapNotNull { g ->
                val total = g.sumOf { it.net() }
                if (total <= 0) return@mapNotNull null
                val category =
                    g
                        .mapNotNull { it.categoryId }
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .firstOrNull()
                        ?.key
                MerchantLine(
                    name = g.minOf { it.merchant!!.trim(' ') },
                    total = total,
                    visits = g.size,
                    averageTicket = Projections.divideRounded(total, g.size.toLong()),
                    categoryId = category,
                )
            }.sortedWith(compareByDescending<MerchantLine> { it.total }.thenBy { it.name })
            .take(MAX_MERCHANTS)
    }

    /** Per Cairo day totals and the Saturday-first weekday x hour matrix, hours in Africa/Cairo. */
    fun heatmap(
        period: AnalyticsPeriod,
        rows: List<AnalyticsRow>,
        includeCash: Boolean,
    ): HeatmapResult {
        val perDay = HashMap<LocalDate, LongArray>()
        val matrix = Array(WEEK) { LongArray(HOURS) }
        for (r in rows) {
            if (!r.isSpend(includeCash)) continue
            val at = Instant.ofEpochMilli(r.occurredAtMillis).atZone(BusinessCalendar.CAIRO)
            val cell = perDay.getOrPut(at.toLocalDate()) { LongArray(2) }
            cell[0] += r.net()
            cell[1]++
            matrix[(at.dayOfWeek.value + 1) % WEEK][at.hour] += r.net()
        }
        val days = ArrayList<DayCell>()
        var d = period.from
        while (!d.isAfter(period.to)) {
            val cell = perDay[d]
            days.add(DayCell(d, cell?.get(0) ?: 0L, cell?.get(1)?.toInt() ?: 0))
            d = d.plusDays(1)
        }
        return HeatmapResult(days, matrix.map { it.toList() })
    }

    /** The [count] household months ending with the one that holds [today], oldest first. */
    fun trendPeriods(
        today: LocalDate,
        monthStartDay: Int,
        count: Int = TREND_PERIODS,
    ): List<AnalyticsPeriod> {
        val out = ArrayList<AnalyticsPeriod>()
        var p = AnalyticsPeriod.currentMonth(today, monthStartDay)
        repeat(count) {
            out.add(0, p)
            p = p.previous()
        }
        return out
    }

    /** Empty periods are present with zeros. [rows] must span every period (one range query). */
    fun trend(
        periods: List<AnalyticsPeriod>,
        rows: List<AnalyticsRow>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
    ): List<TrendPoint> {
        val byId = categories.associateBy { it.id }
        return periods.map { p ->
            val inP = AnalyticsCalculator.inRange(rows, p)
            val income =
                inP
                    .filter {
                        it.currency == AnalyticsCalculator.BASE_CURRENCY && it.type == AnalyticsCalculator.INCOME
                    }.sumOf { it.amountMinor }
            val total = inP.filter { it.isSpend(includeCash) }.sumOf { it.net() }
            TrendPoint(
                p,
                total,
                income,
                Projections.savingsRateBps(income, total),
                AnalyticsCalculator.categoryLines(inP, emptyList(), byId, includeCash),
            )
        }
    }

    private fun AnalyticsRow.isSpend(cash: Boolean) = with(AnalyticsCalculator) { this@isSpend.counts(cash) }

    private fun AnalyticsRow.net() = with(AnalyticsCalculator) { this@net.signed() }
}
