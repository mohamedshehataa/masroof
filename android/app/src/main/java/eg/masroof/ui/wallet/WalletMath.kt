package eg.masroof.ui.wallet

import eg.masroof.core.Money
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsExtras
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.core.analytics.BusinessCalendar
import eg.masroof.core.analytics.CategoryLine
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.WalletRow
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.BalanceMath
import eg.masroof.data.wealth.KIND_CREDIT_CARD
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.saturatingAdd
import eg.masroof.ui.insights.TrendBar
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** What the Wallet hero shows for one period: the D2 numbers, the same ones Insights computes. */
data class WalletFigures(
    val period: AnalyticsPeriod,
    val spentMinor: Long,
    /** Spent today; null when [period] does not contain today. */
    val todayMinor: Long?,
    val incomeMinor: Long,
    /** Share of income kept, in basis points; null while there is no income. */
    val keptBps: Int?,
    val deltaBps: Int?,
    val pendingReview: Int,
    val categories: List<CategoryLine>,
    /** The last months ending with [period], oldest first, for the mini trend. */
    val trend: List<TrendBar> = emptyList(),
)

/** The Balance line: [minor] is the sum of counted accounts, null when none has a confirmed balance. */
data class WalletBalance(
    val minor: Long?,
    /** Active accounts in the base currency that have no confirmed balance yet (credit cards are not counted here). */
    val uncounted: Int,
    val accounts: Int,
)

/** Pure arithmetic behind Wallet (STORY-206); no clock, no database, so it is unit tested directly. */
object WalletMath {
    const val COMING_UP_DAYS = 7
    const val PERIOD_CHOICES = 6
    const val TREND_MONTHS = 6
    private const val BASE = "EGP"

    /** The household month that holds [today], then the months before it: what the period pill offers. */
    fun periodChoices(
        today: LocalDate,
        monthStartDay: Int,
    ): List<AnalyticsPeriod> {
        val first = AnalyticsPeriod.currentMonth(today, monthStartDay)
        val ym = YearMonth.parse(first.label)
        return (0 until PERIOD_CHOICES).map { AnalyticsPeriod.month(ym.minusMonths(it.toLong()), monthStartDay) }
    }

    /** The chosen period by its label (`YYYY-MM`), or the current one when none is chosen or the label is not valid. */
    fun resolve(
        label: String?,
        today: LocalDate,
        monthStartDay: Int,
    ): AnalyticsPeriod =
        label
            ?.let { runCatching { AnalyticsPeriod.parse(it, today, monthStartDay) }.getOrNull() }
            ?.takeIf { !it.week }
            ?: AnalyticsPeriod.currentMonth(today, monthStartDay)

    private fun trendPeriods(period: AnalyticsPeriod): List<AnalyticsPeriod> {
        val out = ArrayList<AnalyticsPeriod>()
        var p = period
        repeat(TREND_MONTHS) {
            out.add(0, p)
            p = p.previous()
        }
        return out
    }

    /** The first instant of the oldest trend month to the end of [period]: the one range the rows are read for. */
    fun range(period: AnalyticsPeriod): Pair<Long, Long> =
        trendPeriods(period).first().start().toEpochMilli() to period.endExclusive().toEpochMilli()

    /**
     * Spent, income and the rest for [period] from [rows] (already without "Don't count in totals" entries), through the
     * same calculator Insights uses. [accountId] null = every account; otherwise an entry belongs to the account the same way
     * the account's balance attributes it (its named account, or the one account that has its card's last four).
     */
    @Suppress("LongParameterList")
    fun figures(
        period: AnalyticsPeriod,
        today: LocalDate,
        rows: List<WalletRow>,
        accounts: List<AccountEntity>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
        accountId: String?,
    ): WalletFigures {
        val scoped =
            rows
                .filter { accountId == null || BalanceMath.accountOf(accounts, it.toFlow()) == accountId }
                .map { it.toAnalytics() }
        val summary = AnalyticsCalculator.compute(period, today, scoped, categories, includeCash)
        val inPeriod = !today.isBefore(period.from) && !today.isAfter(period.to)
        val points = AnalyticsExtras.trend(trendPeriods(period), scoped, categories, includeCash)
        return WalletFigures(
            period = period,
            spentMinor = summary.summary.total,
            todayMinor = if (inPeriod) spentOn(today, scoped, includeCash) else null,
            incomeMinor = summary.summary.income,
            keptBps = summary.summary.savingsRateBps,
            deltaBps = summary.summary.deltaBps,
            pendingReview = summary.summary.pendingReviewCount,
            categories = summary.categories,
            trend =
                points.map {
                    TrendBar(it.period.from, Money(it.total), it.period.label == period.label, Money(it.income), it.savingsRateBps)
                },
        )
    }

    /** [figures], or null when a stored amount is so large that a projection overflows: Wallet then shows its error state. */
    @Suppress("LongParameterList")
    fun figuresOrNull(
        period: AnalyticsPeriod,
        today: LocalDate,
        rows: List<WalletRow>,
        accounts: List<AccountEntity>,
        categories: List<AnalyticsCategory>,
        includeCash: Boolean,
        accountId: String?,
    ): WalletFigures? =
        try {
            figures(period, today, rows, accounts, categories, includeCash, accountId)
        } catch (_: ArithmeticException) {
            null
        }

    /** Net spending (purchases minus refunds, cash only when counted) on one Cairo day. */
    fun spentOn(
        day: LocalDate,
        rows: List<AnalyticsRow>,
        includeCash: Boolean,
    ): Long =
        with(AnalyticsCalculator) {
            rows
                .filter { BusinessCalendar.dateOf(Instant.ofEpochMilli(it.occurredAtMillis)) == day }
                .filter { it.status == "CONFIRMED" || it.status == "PENDING_REVIEW" }
                .filter { it.counts(includeCash) }
                .fold(0L) { acc, r -> saturatingAdd(acc, r.signed()) }
        }

    /**
     * Balance for all accounts (credit cards are what you owe, not what you have, so they are not added and not counted as
     * missing) or for the one chosen account (a card then shows what is owed as a negative number).
     */
    fun balance(
        views: List<AccountView>,
        accountId: String?,
    ): WalletBalance {
        val live = views.filter { !it.account.archived && it.account.currency == BASE }
        if (accountId != null) {
            val one = live.firstOrNull { it.account.id == accountId }
            val minor = one?.balanceMinor?.let { if (one.account.kind == KIND_CREDIT_CARD) -it else it }
            return WalletBalance(minor, if (minor == null) 1 else 0, 1)
        }
        val held = live.filter { it.account.kind != KIND_CREDIT_CARD }
        val counted = held.filter { it.balanceMinor != null }
        val sum = if (counted.isEmpty()) null else counted.fold(0L) { acc, v -> saturatingAdd(acc, v.balanceMinor ?: 0L) }
        return WalletBalance(sum, held.size - counted.size, held.size)
    }

    /** What the household owes: Wealth's own Debt figure (debts, cards, overdrawn accounts and installments left). */
    fun owed(worth: NetWorth): Long = worth.debtsMinor
}
