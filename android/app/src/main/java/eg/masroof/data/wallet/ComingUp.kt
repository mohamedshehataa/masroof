package eg.masroof.data.wallet

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.bills.BillMath
import eg.masroof.data.bills.InstallmentRepository
import eg.masroof.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

/** What an item that is coming up is, so the row can pick its words and the screen can open the right place. */
enum class ComingUpKind { INSTALLMENT, SUBSCRIPTION, PAYOUT, PAYDAY }

/** One thing falling due (or arriving) in the next few days. Money is minor units + currency. */
data class ComingUpItem(
    val id: String,
    val kind: ComingUpKind,
    val title: String,
    val date: LocalDate,
    val money: Money,
    /** True for money arriving (a payout, a payday); false for money leaving. */
    val incoming: Boolean,
)

/**
 * A feature that has things falling due. Register one with a Hilt multibinding (`@Binds @IntoSet`) and Wallet's "Coming up"
 * lists it; Wallet is never edited. [observe] returns the items dated up to [today] + [days] (Cairo dates), overdue ones included.
 */
interface ComingUpSource {
    fun observe(
        today: LocalDate,
        days: Int,
    ): Flow<List<ComingUpItem>>
}

/** Installment payments due and gam3eya payouts arriving (STORY-202). */
class InstallmentsComingUpSource
    @Inject
    constructor(
        private val repository: InstallmentRepository,
    ) : ComingUpSource {
        override fun observe(
            today: LocalDate,
            days: Int,
        ): Flow<List<ComingUpItem>> =
            combine(repository.observePlans(), repository.observePayments()) { plans, payments ->
                val end = today.plusDays(days.toLong())
                val overview = BillMath.overview(plans, payments, today)
                (overview.overdue + overview.comingUp)
                    .filter { !it.date.isAfter(end) }
                    .map {
                        ComingUpItem(
                            id = "${it.planId}-${it.date}",
                            kind = if (it.incoming) ComingUpKind.PAYOUT else ComingUpKind.INSTALLMENT,
                            title = it.title,
                            date = it.date,
                            money = Money(it.amountMinor, it.currency),
                            incoming = it.incoming,
                        )
                    }
            }
    }

/** Recurring items from the `subscription` table: subscriptions and bills fall due, a recurring INCOME is a payday. */
class SubscriptionsComingUpSource
    @Inject
    constructor(
        private val db: AppDatabase,
    ) : ComingUpSource {
        override fun observe(
            today: LocalDate,
            days: Int,
        ): Flow<List<ComingUpItem>> =
            db.subscriptions().observeAll().map { rows ->
                val end = today.plusDays(days.toLong())
                rows
                    .filter { it.active }
                    .map { it to CairoTime.dateOf(it.nextRenewalAtMillis) }
                    .filter { (_, date) -> !date.isBefore(today) && !date.isAfter(end) }
                    .map { (row, date) ->
                        val income = row.kind == "INCOME"
                        ComingUpItem(
                            id = row.id,
                            kind = if (income) ComingUpKind.PAYDAY else ComingUpKind.SUBSCRIPTION,
                            title = row.name,
                            date = date,
                            money = Money(row.amountMinor, row.currency),
                            incoming = income,
                        )
                    }
            }
    }
