package eg.masroof.data.bills

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.InstallmentPaymentEntity
import eg.masroof.data.db.InstallmentPlanEntity
import java.time.LocalDate
import java.time.YearMonth

/** Plan statuses stored in `installment_plan.status`. */
const val PLAN_ACTIVE = "ACTIVE"
const val PLAN_COMPLETED = "COMPLETED"

/** The provider key of a gam3eya (rotating savings circle). */
const val PROVIDER_GAM3EYA = "GAM3EYA"

/** Pure schedule maths for installment plans: integer minor units and Cairo calendar dates only. */
object BillMath {
    const val MAX_PAYMENTS = 120
    const val DEFAULT_REMINDER_DAYS = 1
    const val CANDIDATE_WINDOW_DAYS = 5L
    const val COMING_UP_DAYS = 14L

    /**
     * The due dates of [count] monthly payments from [first]. Every date is computed from [first], never from the previous
     * one, so a due day of 31 gives 31 Jan, 28 Feb, 31 Mar (no drift to 28 afterwards).
     */
    fun dueDates(
        first: LocalDate,
        count: Int,
    ): List<LocalDate> = (0 until count).map { first.plusMonths(it.toLong()) }

    /** The date of the last of [count] payments. */
    fun endDate(
        first: LocalDate,
        count: Int,
    ): LocalDate = first.plusMonths((count - 1).toLong())

    /** How many monthly payments fall on or before [end]; null when [end] is before [first] or more than [MAX_PAYMENTS] fit. */
    fun countUntil(
        first: LocalDate,
        end: LocalDate,
    ): Int? {
        if (end.isBefore(first)) return null
        var n = 0
        while (n <= MAX_PAYMENTS && !first.plusMonths(n.toLong()).isAfter(end)) n++
        return n.takeIf { it <= MAX_PAYMENTS }
    }

    /** How many of [dates] are before [today]: the default answer to "How many are already paid?". */
    fun dueBefore(
        dates: List<LocalDate>,
        today: LocalDate,
    ): Int = dates.count { it.isBefore(today) }

    /** The collection date of a gam3eya: the member with turn [turn] collects in the [turn]-th month. */
    fun collectionDate(
        first: LocalDate,
        turn: Int,
    ): LocalDate = first.plusMonths((turn - 1).toLong())

    /** Share x members, or null on overflow. */
    fun defaultPayout(
        shareMinor: Long,
        members: Int,
    ): Long? =
        try {
            Math.multiplyExact(shareMinor, members.toLong())
        } catch (_: ArithmeticException) {
            null
        }

    /** The overview of every plan, as of [today] (a Cairo date). Only active EGP plans count in the totals. */
    fun overview(
        plans: List<InstallmentPlanEntity>,
        payments: List<InstallmentPaymentEntity>,
        today: LocalDate,
    ): BillsOverview {
        val byPlan = payments.groupBy { it.planId }
        val progress = plans.map { progressOf(it, byPlan[it.id].orEmpty()) }
        val counted = progress.filter { it.plan.status == PLAN_ACTIVE && it.plan.currency == Money.EGP }
        val countedPayments = counted.flatMap { it.payments }
        val perMonth = countedPayments.groupBy { YearMonth.from(dateOf(it)) }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val thisMonth = YearMonth.from(today)
        val monthly = perMonth[thisMonth] ?: 0L
        return BillsOverview(
            monthlyTotalMinor = monthly,
            activeCount = progress.count { it.plan.status == PLAN_ACTIVE },
            remainingMinor = countedPayments.filter { it.paidAtMillis == null }.sumOf { it.amountMinor },
            dropsTo = dropsTo(perMonth, thisMonth, monthly),
            comingUp = comingUp(progress.filter { it.plan.status == PLAN_ACTIVE }, today),
            overdue = overdue(progress.filter { it.plan.status == PLAN_ACTIVE }, today),
            plans = progress.sortedWith(compareBy({ it.plan.status != PLAN_ACTIVE }, { it.nextDue?.dueAtMillis ?: Long.MAX_VALUE })),
            hasOtherCurrency = plans.any { it.status == PLAN_ACTIVE && it.currency != Money.EGP },
        )
    }

    /** The first month after [thisMonth] whose total is lower than [current], with how many months away it is. */
    fun dropsTo(
        perMonth: Map<YearMonth, Long>,
        thisMonth: YearMonth,
        current: Long,
    ): DropsTo? {
        if (current <= 0L) return null
        val last = perMonth.keys.maxOrNull() ?: return null
        var month = thisMonth.plusMonths(1)
        var n = 1
        while (!month.isAfter(last.plusMonths(1))) {
            val total = perMonth[month] ?: 0L
            if (total < current) return DropsTo(total, n)
            month = month.plusMonths(1)
            n++
        }
        return null
    }

    /** Unpaid payments of active plans whose day has passed, oldest first. They stay in "remaining". */
    private fun overdue(
        active: List<PlanProgress>,
        today: LocalDate,
    ): List<ComingUp> =
        active
            .flatMap { p ->
                p.payments
                    .filter { it.paidAtMillis == null && dateOf(it).isBefore(today) }
                    .map {
                        ComingUp(
                            p.plan.id,
                            p.plan.title,
                            p.plan.provider,
                            dateOf(it),
                            it.amountMinor,
                            p.plan.currency,
                            incoming = false,
                        )
                    }
            }.sortedWith(compareBy({ it.date }, { it.planId }))

    private fun comingUp(
        active: List<PlanProgress>,
        today: LocalDate,
    ): List<ComingUp> {
        val end = today.plusDays(COMING_UP_DAYS)
        val items = mutableListOf<ComingUp>()
        for (p in active) {
            p.payments.filter { it.paidAtMillis == null }.forEach {
                val date = dateOf(it)
                if (!date.isBefore(today) && !date.isAfter(end)) {
                    items += ComingUp(p.plan.id, p.plan.title, p.plan.provider, date, it.amountMinor, p.plan.currency, incoming = false)
                }
            }
            val payout = p.plan.payoutAtMillis
            val amount = p.plan.payoutAmountMinor
            if (payout != null && amount != null && p.plan.payoutReceivedAtMillis == null) {
                val date = CairoTime.dateOf(payout)
                if (!date.isBefore(today) && !date.isAfter(end)) {
                    items += ComingUp(p.plan.id, p.plan.title, p.plan.provider, date, amount, p.plan.currency, incoming = true)
                }
            }
        }
        return items.sortedWith(compareBy({ it.date }, { it.planId }))
    }

    fun progressOf(
        plan: InstallmentPlanEntity,
        payments: List<InstallmentPaymentEntity>,
    ): PlanProgress {
        val sorted = payments.sortedBy { it.seq }
        val paid = sorted.count { it.paidAtMillis != null }
        return PlanProgress(
            plan = plan,
            payments = sorted,
            paid = paid,
            total = sorted.size,
            remainingMinor = sorted.filter { it.paidAtMillis == null }.sumOf { it.amountMinor },
            nextDue = sorted.firstOrNull { it.paidAtMillis == null },
            completionMonth = sorted.lastOrNull()?.let { YearMonth.from(dateOf(it)) },
        )
    }

    /** Calendar entries of [month]: one per payment due then, and a gam3eya collection on its day. */
    fun calendar(
        plans: List<InstallmentPlanEntity>,
        payments: List<InstallmentPaymentEntity>,
        month: YearMonth,
    ): List<CalendarItem> {
        val byId = plans.associateBy { it.id }
        val due =
            payments.mapNotNull { pay ->
                val plan = byId[pay.planId]
                val date = dateOf(pay)
                if (plan == null || YearMonth.from(date) != month) {
                    null
                } else {
                    CalendarItem(date, plan.id, plan.title, plan.provider, pay.amountMinor, plan.currency, pay.paidAtMillis != null, false)
                }
            }
        val collections =
            plans.mapNotNull { plan ->
                val payout = plan.payoutAtMillis?.let { CairoTime.dateOf(it) }
                val amount = plan.payoutAmountMinor
                if (payout == null || amount == null || YearMonth.from(payout) != month) {
                    null
                } else {
                    CalendarItem(
                        payout,
                        plan.id,
                        plan.title,
                        plan.provider,
                        amount,
                        plan.currency,
                        plan.payoutReceivedAtMillis != null,
                        true,
                    )
                }
            }
        return (due + collections).sortedWith(compareBy({ it.date }, { it.planId }))
    }

    private fun dateOf(payment: InstallmentPaymentEntity): LocalDate = CairoTime.dateOf(payment.dueAtMillis)
}

data class DropsTo(
    val amountMinor: Long,
    val inMonths: Int,
)

data class PlanProgress(
    val plan: InstallmentPlanEntity,
    val payments: List<InstallmentPaymentEntity>,
    val paid: Int,
    val total: Int,
    val remainingMinor: Long,
    val nextDue: InstallmentPaymentEntity?,
    val completionMonth: YearMonth?,
) {
    /** 0..10000 */
    val progressBps: Int get() = if (total == 0) 0 else (paid * FULL_BPS) / total

    private companion object {
        const val FULL_BPS = 10_000
    }
}

data class ComingUp(
    val planId: String,
    val title: String,
    val provider: String,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val incoming: Boolean,
)

data class CalendarItem(
    val date: LocalDate,
    val planId: String,
    val title: String,
    val provider: String,
    val amountMinor: Long,
    val currency: String,
    val done: Boolean,
    val incoming: Boolean,
)

data class BillsOverview(
    val monthlyTotalMinor: Long,
    val activeCount: Int,
    val remainingMinor: Long,
    val dropsTo: DropsTo?,
    val comingUp: List<ComingUp>,
    val overdue: List<ComingUp>,
    val plans: List<PlanProgress>,
    val hasOtherCurrency: Boolean,
)
