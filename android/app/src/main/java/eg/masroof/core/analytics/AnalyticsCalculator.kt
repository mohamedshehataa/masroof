package eg.masroof.core.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The few columns of one stored entry the analytics need. [type] and [status] are the stored strings. */
data class AnalyticsRow(
    val type: String,
    val status: String,
    val currency: String,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val categoryId: String?,
)

/** A category as the engine sees it. [parentId] is null for a top-level category. */
data class AnalyticsCategory(
    val id: String,
    val parentId: String?,
    val nameEn: String,
    val nameAr: String,
    val color: String?,
    val archived: Boolean,
)

/** One line of the by-category list; [categoryId] null is the "Uncategorised / غير مصنف" bucket. Amounts are EGP minor units. */
data class CategoryLine(
    val categoryId: String?,
    val nameEn: String,
    val nameAr: String,
    val color: String?,
    val total: Long,
    val previousTotal: Long,
    val shareBps: Int,
    val count: Int,
    val deltaBps: Int?,
)

data class SummaryResult(
    val period: AnalyticsPeriod,
    val total: Long,
    val previousTotal: Long,
    val deltaBps: Int?,
    val dailyAverage: Long,
    val projectedTotal: Long,
    val projectedWeekdayAware: Long,
    val income: Long,
    val savingsRateBps: Int?,
    /** Null while no total budget exists (budgets come with STORY-180). */
    val remainingBudget: Long?,
    val topCategories: List<CategoryLine>,
    val daysLeft: Int,
    val pendingReviewCount: Int,
    /** Entries of the period in a currency other than EGP: shown as "N entries in another currency are not included". */
    val otherCurrencyCount: Int,
)

data class ForecastResult(
    val period: AnalyticsPeriod,
    val spentSoFar: Long,
    val linear: Long,
    val weekdayAware: Long,
)

data class PeriodAnalytics(
    val summary: SummaryResult,
    val forecast: ForecastResult,
    val categories: List<CategoryLine>,
    /** Sum of every category total (also the ones that net to zero or less), equal to the summary total. */
    val categoryTotal: Long,
)

/**
 * The filter and the arithmetic of ADR-0009 D2, a port of the server's AnalyticsService: EGP only, CONFIRMED or
 * PENDING_REVIEW, EXPENSE and REFUND netted, CASH_WITHDRAWAL only with [compute]'s `includeCash`, TRANSFER never,
 * income separate. Rows must already exclude soft-deleted entries. Pure: no clock, no database.
 */
object AnalyticsCalculator {
    const val BASE_CURRENCY = "EGP"
    const val UNCATEGORISED_EN = "Uncategorised"
    const val UNCATEGORISED_AR = "غير مصنف"
    private const val TOP_CATEGORIES = 5
    private const val EXPENSE = "EXPENSE"
    private const val REFUND = "REFUND"
    private const val CASH = "CASH_WITHDRAWAL"
    private const val INCOME = "INCOME"
    private const val CONFIRMED = "CONFIRMED"
    private const val PENDING = "PENDING_REVIEW"

    /**
     * @param rows entries from the start of the previous period to the end of [period]; each is assigned to its period here
     */
    fun compute(
        period: AnalyticsPeriod,
        today: LocalDate,
        rows: List<AnalyticsRow>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
    ): PeriodAnalytics {
        val prev = period.previous()
        val cur = inRange(rows, period)
        val before = inRange(rows, prev)
        val byId = categories.associateBy { it.id }
        val elapsed = period.elapsedDays(today)
        val total = spend(cur, includeCash)
        val previousTotal = spend(before, includeCash)
        val lines = categoryLines(cur, before, byId, includeCash)
        val income = cur.filter { it.currency == BASE_CURRENCY && it.type == INCOME }.sumOf { it.amountMinor }
        val summary =
            SummaryResult(
                period = period,
                total = total,
                previousTotal = previousTotal,
                deltaBps = Projections.deltaBps(total, previousTotal),
                dailyAverage = Projections.dailyAverage(total, elapsed),
                projectedTotal = Projections.linear(total, elapsed, period.lengthDays()),
                projectedWeekdayAware =
                    Projections.weekdayAware(period.from, daily(cur, period, elapsed, includeCash), period.lengthDays()),
                income = income,
                savingsRateBps = Projections.savingsRateBps(income, total),
                remainingBudget = null,
                topCategories = lines.take(TOP_CATEGORIES),
                daysLeft = period.daysLeft(today),
                pendingReviewCount = cur.count { it.status == PENDING },
                otherCurrencyCount = cur.count { it.currency != BASE_CURRENCY && it.countsAsSpend(includeCash) },
            )
        // The forecast never counts cash withdrawals, whatever the switch says (the server's rule, pinned by the parity file).
        val noCashTotal = spend(cur, false)
        val forecast =
            ForecastResult(
                period,
                noCashTotal,
                Projections.linear(noCashTotal, elapsed, period.lengthDays()),
                Projections.weekdayAware(period.from, daily(cur, period, elapsed, false), period.lengthDays()),
            )
        return PeriodAnalytics(summary, forecast, lines, total)
    }

    private fun inRange(
        rows: List<AnalyticsRow>,
        period: AnalyticsPeriod,
    ): List<AnalyticsRow> {
        val from = period.start().toEpochMilli()
        val to = period.endExclusive().toEpochMilli()
        return rows.filter { it.occurredAtMillis in from until to && (it.status == CONFIRMED || it.status == PENDING) }
    }

    private fun AnalyticsRow.countsAsSpend(includeCash: Boolean) = type == EXPENSE || type == REFUND || (includeCash && type == CASH)

    private fun AnalyticsRow.counts(includeCash: Boolean) = currency == BASE_CURRENCY && countsAsSpend(includeCash)

    private fun AnalyticsRow.signed(): Long = if (type == REFUND) -amountMinor else amountMinor

    private fun spend(
        rows: List<AnalyticsRow>,
        includeCash: Boolean,
    ): Long = rows.filter { it.counts(includeCash) }.sumOf { it.signed() }

    private fun daily(
        rows: List<AnalyticsRow>,
        period: AnalyticsPeriod,
        elapsed: Int,
        includeCash: Boolean,
    ): LongArray {
        val daily = LongArray(elapsed)
        for (r in rows) {
            if (!r.counts(includeCash)) continue
            val date = BusinessCalendar.dateOf(Instant.ofEpochMilli(r.occurredAtMillis))
            val idx = ChronoUnit.DAYS.between(period.from, date).toInt()
            if (idx in 0 until elapsed) daily[idx] += r.signed()
        }
        return daily
    }

    /**
     * The category a row is reported under: its parent when it has one that exists. An archived category without a
     * parent, a missing category and a row without one are "Uncategorised". Nothing is dropped, so totals never shrink.
     */
    private fun reportedUnder(
        categoryId: String?,
        byId: Map<String, AnalyticsCategory>,
    ): AnalyticsCategory? {
        val c = categoryId?.let(byId::get) ?: return null
        val parent = c.parentId?.let(byId::get)
        return when {
            parent != null -> parent
            c.archived -> null
            else -> c
        }
    }

    private fun categoryLines(
        cur: List<AnalyticsRow>,
        before: List<AnalyticsRow>,
        byId: Map<String, AnalyticsCategory>,
        includeCash: Boolean,
    ): List<CategoryLine> {
        val totals = LinkedHashMap<String?, LongArray>()
        val refs = HashMap<String?, AnalyticsCategory?>()
        for (r in cur) {
            if (!r.counts(includeCash)) continue
            val ref = reportedUnder(r.categoryId, byId)
            val acc = totals.getOrPut(ref?.id) { LongArray(2) }
            acc[0] += r.signed()
            acc[1]++
            refs[ref?.id] = ref
        }
        val prev = HashMap<String?, Long>()
        for (r in before) {
            if (r.counts(includeCash)) prev.merge(reportedUnder(r.categoryId, byId)?.id, r.signed(), Long::plus)
        }
        val positive = totals.values.filter { it[0] > 0 }.sumOf { it[0] }
        val lines =
            totals.entries
                .filter { it.value[0] > 0 }
                .map { (id, acc) ->
                    val ref = refs[id]
                    val earlier = prev[id] ?: 0L
                    CategoryLine(
                        categoryId = id,
                        nameEn = ref?.nameEn ?: UNCATEGORISED_EN,
                        nameAr = ref?.nameAr ?: UNCATEGORISED_AR,
                        color = ref?.color,
                        total = acc[0],
                        previousTotal = earlier,
                        shareBps = Projections.shareBps(acc[0], positive),
                        count = acc[1].toInt(),
                        deltaBps = Projections.deltaBps(acc[0], earlier),
                    )
                }
        return lines.sortedWith(compareByDescending<CategoryLine> { it.total }.thenBy { it.nameEn })
    }
}
