package eg.masroof.ui.insights

import eg.masroof.core.Money
import eg.masroof.data.insights.InsightsSnapshot
import kotlinx.datetime.toJavaLocalDate
import java.time.LocalDate
import eg.masroof.api.models.Money as ApiMoney

data class CategoryShare(
    val id: String?,
    val nameEn: String,
    val nameAr: String,
    val total: Money,
    val shareBps: Int,
    val deltaBps: Int?,
    val count: Int,
)

/** One month of the trend chart; [start] is the first day of that household month. */
data class TrendBar(
    val start: LocalDate,
    val total: Money,
    val selected: Boolean,
    val income: Money = Money.zero(total.currency),
    /** `(income - spent) / income` in basis points; null without income, shown as "-". */
    val savingsRateBps: Int? = null,
)

/** A merchant of the period; [averageTicket] is per visit. */
data class MerchantRow(
    val name: String,
    val total: Money,
    val visits: Int,
    val averageTicket: Money,
)

data class CalendarDay(
    val date: LocalDate,
    val total: Money,
    val count: Int,
)

/** Server-computed analytics for one period, shaped for the screen. All amounts are minor units + currency. */
data class InsightsUi(
    val periodLabel: String,
    val from: LocalDate,
    val to: LocalDate,
    val total: Money,
    val previousTotal: Money,
    val deltaBps: Int?,
    val dailyAverage: Money,
    val projected: Money,
    val daysLeft: Int,
    val pendingReview: Int,
    val categories: List<CategoryShare>,
    val trend: List<TrendBar>,
    val merchants: List<MerchantRow>,
    val calendar: List<CalendarDay>,
    /** Spend per weekday, Saturday first (the Egyptian week), summed over all hours. */
    val weekday: List<Money>,
    val fetchedAtMillis: Long,
    val otherCurrencyCount: Int = 0,
) {
    /** False when there is nothing worth charting yet ("not enough data"). */
    val hasData: Boolean get() = total.amountMinor > 0 || trend.any { it.total.amountMinor > 0 } || otherCurrencyCount > 0
}

private fun ApiMoney.toMoney() = Money(amountMinor, currency)

fun InsightsSnapshot.toUi(): InsightsUi {
    val s = summary
    return InsightsUi(
        periodLabel = s.period.label,
        from = s.period.from.toJavaLocalDate(),
        to = s.period.to.toJavaLocalDate(),
        total = s.total.toMoney(),
        previousTotal = s.previousTotal.toMoney(),
        deltaBps = s.deltaPercentBps,
        dailyAverage = s.dailyAverage.toMoney(),
        projected = s.projectedTotal.toMoney(),
        daysLeft = s.daysLeft,
        pendingReview = s.pendingReviewCount,
        categories =
            categories.items.map {
                CategoryShare(
                    id = it.categoryId?.toString(),
                    nameEn = it.nameEn,
                    nameAr = it.nameAr,
                    total = it.total.toMoney(),
                    shareBps = it.shareBps,
                    deltaBps = it.deltaPercentBps,
                    count = it.count,
                )
            },
        trend =
            trend.points.map { p ->
                TrendBar(
                    start = p.period.from.toJavaLocalDate(),
                    total = p.total.toMoney(),
                    selected = p.period.label == s.period.label,
                    income = p.income?.toMoney() ?: Money.zero(p.total.currency),
                    savingsRateBps = p.savingsRateBps,
                )
            },
        merchants = merchants.map { MerchantRow(it.name, it.total.toMoney(), it.visits, it.averageTicket.toMoney()) },
        calendar =
            heatmap.days.map {
                CalendarDay(it.date.toJavaLocalDate(), it.total.toMoney(), it.count)
            },
        weekday =
            heatmap.weekdayHour.map { hours ->
                hours.fold(Money.zero(s.total.currency)) { acc, m -> acc + m.toMoney() }
            },
        fetchedAtMillis = fetchedAtMillis,
        otherCurrencyCount = otherCurrencyCount,
    )
}
