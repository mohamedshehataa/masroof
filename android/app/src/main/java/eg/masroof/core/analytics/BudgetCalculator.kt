package eg.masroof.core.analytics

import eg.masroof.core.analytics.AnalyticsCalculator.counts
import eg.masroof.core.analytics.AnalyticsCalculator.saturatingAdd
import eg.masroof.core.analytics.AnalyticsCalculator.signed
import java.time.DayOfWeek
import java.time.LocalDate

enum class BudgetPeriodKind { MONTHLY, WEEKLY }

/**
 * A budget as the calculator sees it (STORY-181 stores it). [categoryId] null is the household total budget.
 * [amountMinor] is EGP minor units; [createdAtMillis] decides whether a previous period had the budget (rollover).
 */
data class BudgetSpec(
    val id: String,
    val categoryId: String?,
    val kind: BudgetPeriodKind,
    val amountMinor: Long,
    val rollover: Boolean,
    val alertThresholds: List<Int> = DEFAULT_THRESHOLDS,
    val createdAtMillis: Long,
) {
    companion object {
        val DEFAULT_THRESHOLDS = listOf(50, 80, 100)
    }
}

/**
 * Progress of one budget in its period. [rolledOver] is the carried leftover (negative for overspend), [duplicate] marks a
 * budget whose scope (category + period kind) already has an older one, which a restored backup can hold.
 */
data class BudgetStatus(
    val budget: BudgetSpec,
    val period: AnalyticsPeriod,
    val spent: Long,
    val remaining: Long,
    val rolledOver: Long,
    val progress: BudgetProgress,
    val onTrack: Boolean,
    val daysLeft: Int,
    val duplicate: Boolean = false,
)

/**
 * Port of the status part of the server's `BudgetService` (REQ-AN-153..155). The spend is the analytics rule (ADR-0009 D2,
 * the same [AnalyticsCalculator] filter): EGP, CONFIRMED or PENDING_REVIEW, refunds netted, transfers never, cash
 * withdrawals only with `includeCash`. Rows must already exclude soft-deleted and excluded-from-totals entries (the
 * analytics query does) and must cover the previous period too. Pure: no clock, no database.
 */
object BudgetCalculator {
    private val OLDEST_FIRST = compareBy<BudgetSpec>({ it.createdAtMillis }, { it.id })

    /** Status of every budget for [requested]; the older of two budgets in one scope is kept, the other is marked duplicate. */
    fun statuses(
        budgets: List<BudgetSpec>,
        requested: AnalyticsPeriod,
        monthStartDay: Int,
        today: LocalDate,
        rows: List<AnalyticsRow>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
    ): List<BudgetStatus> {
        val oldest = budgets.groupBy { it.categoryId to it.kind }.mapValues { (_, v) -> v.minWith(OLDEST_FIRST).id }
        return budgets.map { b ->
            statusOf(b, requested, monthStartDay, today, rows, categories, includeCash)
                .copy(duplicate = oldest[b.categoryId to b.kind] != b.id)
        }
    }

    fun statusOf(
        budget: BudgetSpec,
        requested: AnalyticsPeriod,
        monthStartDay: Int,
        today: LocalDate,
        rows: List<AnalyticsRow>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
    ): BudgetStatus {
        val byId = categories.associateBy { it.id }
        val period = periodFor(budget.kind, requested, monthStartDay, today)
        val spent = spent(rows, byId, budget.categoryId, period, includeCash)
        var rolled = 0L
        if (budget.rollover) {
            val prev = period.previous()
            if (budget.createdAtMillis < prev.endExclusive().toEpochMilli()) {
                rolled = saturatingSub(budget.amountMinor, spent(rows, byId, budget.categoryId, prev, includeCash))
            }
        }
        val limit = saturatingAdd(budget.amountMinor, rolled)
        val elapsed = period.elapsedDays(today)
        return BudgetStatus(
            budget = budget,
            period = period,
            spent = spent,
            remaining = saturatingSub(limit, spent),
            rolledOver = rolled,
            progress = BudgetProgress.of(spent, limit, budget.alertThresholds),
            onTrack = limit > 0 && spent <= limit && projectedWithin(spent, elapsed, period.lengthDays(), limit),
            daysLeft = period.daysLeft(today),
        )
    }

    /**
     * Remaining amount of the monthly household-total budget for a month [period] (the summary's `remainingBudget`); null for a
     * week or without a total budget. With two, the oldest wins.
     */
    fun totalRemaining(
        budgets: List<BudgetSpec>,
        period: AnalyticsPeriod,
        monthStartDay: Int,
        today: LocalDate,
        rows: List<AnalyticsRow>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
    ): Long? {
        if (period.week) return null
        val total =
            budgets
                .filter { it.categoryId == null && it.kind == BudgetPeriodKind.MONTHLY }
                .minWithOrNull(OLDEST_FIRST) ?: return null
        return statusOf(total, period, monthStartDay, today, rows, categories, includeCash).remaining
    }

    /** The period a budget of [kind] reports for the [requested] one (the server's `periodFor`). */
    fun periodFor(
        kind: BudgetPeriodKind,
        requested: AnalyticsPeriod,
        monthStartDay: Int,
        today: LocalDate,
    ): AnalyticsPeriod {
        if (kind == BudgetPeriodKind.MONTHLY) {
            return if (requested.week) AnalyticsPeriod.currentMonth(requested.to, monthStartDay) else requested
        }
        if (requested.week) return requested
        var anchor = if (today.isAfter(requested.to)) requested.to else today
        if (anchor.isBefore(requested.from)) anchor = requested.from
        return AnalyticsPeriod.week(anchor.with(DayOfWeek.MONDAY))
    }

    /** Eligible spend in [period]; a category budget also counts the rows of its subcategories. */
    private fun spent(
        rows: List<AnalyticsRow>,
        byId: Map<String, AnalyticsCategory>,
        categoryId: String?,
        period: AnalyticsPeriod,
        includeCash: Boolean,
    ): Long =
        AnalyticsCalculator
            .inRange(rows, period)
            .filter { r ->
                r.counts(includeCash) &&
                    (categoryId == null || r.categoryId == categoryId || r.categoryId?.let { byId[it]?.parentId } == categoryId)
            }.fold(0L) { acc, r -> saturatingAdd(acc, r.signed()) }

    private fun projectedWithin(
        spent: Long,
        elapsed: Int,
        length: Int,
        limit: Long,
    ): Boolean =
        try {
            Projections.linear(spent, elapsed, length) <= limit
        } catch (_: ArithmeticException) {
            false
        }

    private fun saturatingSub(
        a: Long,
        b: Long,
    ): Long =
        try {
            Math.subtractExact(a, b)
        } catch (_: ArithmeticException) {
            if (b < 0) Long.MAX_VALUE else Long.MIN_VALUE
        }
}
