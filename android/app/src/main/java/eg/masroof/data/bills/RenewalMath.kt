package eg.masroof.data.bills

import eg.masroof.core.Money
import eg.masroof.core.analytics.Projections
import eg.masroof.data.db.SubscriptionEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

const val KIND_SUBSCRIPTION = "SUBSCRIPTION"
const val KIND_BILL = "BILL"
const val KIND_INCOME = "INCOME"

/** How often a subscription renews; [key] is what `subscription.cadence` stores. */
enum class Cadence(
    val key: String,
    /** Renewals in a year, for the per-year total (a week is 52 of them). */
    val perYear: Int,
    /** Calendar months between two renewals; 0 for a weekly cadence (7 days). */
    val months: Int,
) {
    WEEKLY("WEEKLY", 52, 0),
    MONTHLY("MONTHLY", 12, 1),
    QUARTERLY("QUARTERLY", 4, 3),
    YEARLY("YEARLY", 1, 12),
    ;

    companion object {
        fun of(key: String): Cadence = entries.firstOrNull { it.key == key } ?: MONTHLY
    }
}

/** Per-year and per-month cost of the active EGP subscriptions and bills; income is never part of it. */
data class SubscriptionTotals(
    val perYearMinor: Long,
    val perMonthMinor: Long,
    val activeCount: Int,
    val hasOtherCurrency: Boolean,
)

/** Pure date and total maths for subscriptions (all dates are Cairo calendar days). */
object RenewalMath {
    /** Days after a renewal during which a late payment still counts for it, and the renewal is not rolled past. */
    const val GRACE_DAYS = 3L
    private const val MONTH_END_FROM = 28
    private const val MONTH_END_ANCHOR = 31
    private const val MONTHS_PER_YEAR = 12L
    private const val MAX_ROLL = 5_000

    /**
     * The day of the month a renewal keeps. The schema stores one date only, so a date that is the last day of its month
     * (28 and up) counts as "month end": 31 Jan -> 28 Feb -> 31 Mar, 30 Nov -> 31 Dec.
     */
    fun anchorOf(date: LocalDate): Int {
        val monthEnd = date.dayOfMonth >= MONTH_END_FROM && date.dayOfMonth == date.lengthOfMonth()
        return if (monthEnd) MONTH_END_ANCHOR else date.dayOfMonth
    }

    /** The renewal after [date]; the day of month is [anchor] clamped to the length of the target month. */
    fun nextAfter(
        date: LocalDate,
        cadence: Cadence,
        anchor: Int = anchorOf(date),
    ): LocalDate {
        if (cadence.months == 0) return date.plusWeeks(1)
        val target = YearMonth.from(date).plusMonths(cadence.months.toLong())
        return target.atDay(minOf(anchor, target.lengthOfMonth()))
    }

    /** The first renewal on or after [today], stepping from [date] with one fixed anchor. */
    fun rollTo(
        date: LocalDate,
        cadence: Cadence,
        today: LocalDate,
    ): LocalDate = roll(date, cadence) { it.isBefore(today) }

    /** Like [rollTo] but a renewal stays until its grace days have passed (a late payment can still match it). */
    fun rollPastGrace(
        date: LocalDate,
        cadence: Cadence,
        today: LocalDate,
    ): LocalDate = roll(date, cadence) { it.plusDays(GRACE_DAYS).isBefore(today) }

    private fun roll(
        date: LocalDate,
        cadence: Cadence,
        stale: (LocalDate) -> Boolean,
    ): LocalDate {
        val anchor = anchorOf(date)
        var d = date
        var guard = 0
        while (stale(d) && guard++ < MAX_ROLL) d = nextAfter(d, cadence, anchor)
        return d
    }

    /** Every renewal that falls in [month], stepping forward from [date] (renewals before [date] are not known). */
    fun occurrencesIn(
        date: LocalDate,
        cadence: Cadence,
        month: YearMonth,
    ): List<LocalDate> {
        val anchor = anchorOf(date)
        val out = mutableListOf<LocalDate>()
        var d = date
        var guard = 0
        while (!YearMonth.from(d).isAfter(month) && guard++ < MAX_ROLL) {
            if (YearMonth.from(d) == month) out += d
            d = nextAfter(d, cadence, anchor)
        }
        return out
    }

    fun perYearMinor(
        amountMinor: Long,
        cadence: Cadence,
    ): Long = Math.multiplyExact(amountMinor, cadence.perYear.toLong())

    /** Per-year divided by 12, half away from zero. */
    fun perMonthMinor(perYearMinor: Long): Long = Projections.divideRounded(perYearMinor, MONTHS_PER_YEAR)

    fun totals(rows: List<SubscriptionEntity>): SubscriptionTotals {
        val costs = rows.filter { it.active && it.kind != KIND_INCOME }
        val egp = costs.filter { it.currency == Money.EGP }
        val year = egp.sumOf { perYearMinor(it.amountMinor, Cadence.of(it.cadence)) }
        return SubscriptionTotals(
            perYearMinor = year,
            perMonthMinor = perMonthMinor(year),
            activeCount = costs.size,
            hasOtherCurrency = costs.any { it.currency != Money.EGP },
        )
    }

    fun daysUntil(
        today: LocalDate,
        date: LocalDate,
    ): Int = ChronoUnit.DAYS.between(today, date).toInt()
}

enum class PaydaySource { INCOME, MONTH_START }

/** "17 days to payday": [days] from today to [date]; [name] is the income row when [source] is INCOME. */
data class Payday(
    val days: Int,
    val date: LocalDate,
    val source: PaydaySource,
    val name: String?,
)

/** Hook for Home and Ask (later stories): the days left to the next payday, from recurring income else the month start day. */
interface PaydayProvider {
    suspend fun daysToPayday(today: LocalDate): Payday
}

object PaydayMath {
    fun next(
        today: LocalDate,
        rows: List<SubscriptionEntity>,
        monthStartDay: Int,
        dateOf: (Long) -> LocalDate,
    ): Payday {
        val best =
            rows
                .filter { it.active && it.kind == KIND_INCOME }
                .map { it to RenewalMath.rollTo(dateOf(it.nextRenewalAtMillis), Cadence.of(it.cadence), today) }
                .minWithOrNull(compareBy({ it.second }, { it.first.id }))
        if (best != null) {
            return Payday(RenewalMath.daysUntil(today, best.second), best.second, PaydaySource.INCOME, best.first.name)
        }
        val thisMonth = YearMonth.from(today)
        val inMonth = thisMonth.atDay(minOf(monthStartDay, thisMonth.lengthOfMonth()))
        val date =
            if (inMonth.isBefore(today)) {
                val following = thisMonth.plusMonths(1)
                following.atDay(minOf(monthStartDay, following.lengthOfMonth()))
            } else {
                inMonth
            }
        return Payday(RenewalMath.daysUntil(today, date), date, PaydaySource.MONTH_START, null)
    }
}
