package eg.masroof.data.bills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.AppDatabase
import eg.masroof.ui.AppLanguage
import eg.masroof.work.DueReminder
import eg.masroof.work.ReminderSource
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Route a reminder opens for a plan; the only shape MainActivity accepts from outside for bills. */
fun billRoute(planId: String) = "bills/$planId"

/**
 * Unpaid payments of active plans whose reminder window includes today: due from today, no further away than the plan's
 * "days before". Overdue payments are not reminded again (the plan shows them as due).
 */
@Singleton
class BillsReminderSource
    @Inject
    constructor(
        private val db: AppDatabase,
        @ApplicationContext private val context: Context,
    ) : ReminderSource {
        override suspend fun dueOn(today: LocalDate): List<DueReminder> {
            val start = today.atStartOfDay(CairoTime.zone).toInstant().toEpochMilli()
            // The longest reminder window is a month: look that far ahead and keep what each plan's own window includes.
            val end =
                today
                    .plusDays(MAX_LOOKAHEAD_DAYS)
                    .atStartOfDay(CairoTime.zone)
                    .toInstant()
                    .toEpochMilli()
            val localized = AppLanguage.wrap(context)
            return db
                .installmentPayments()
                .unpaidDueBetween(start, end)
                .mapNotNull { payment ->
                    val plan = db.installmentPlans().find(payment.planId)?.takeIf { it.status == PLAN_ACTIVE } ?: return@mapNotNull null
                    val due = CairoTime.dateOf(payment.dueAtMillis)
                    if (due.minusDays(plan.reminderDaysBefore.toLong()).isAfter(today)) return@mapNotNull null
                    DueReminder(
                        key = "bill:${payment.id}",
                        label = label(localized, plan.provider, plan.title),
                        amount = Money(payment.amountMinor, plan.currency),
                        dueDate = due,
                        route = billRoute(plan.id),
                    )
                }
        }

        private companion object {
            const val MAX_LOOKAHEAD_DAYS = 31L
        }
    }

/** "valU - iPhone 17 Pro", or just the title when the plan has no named provider. */
fun label(
    context: Context,
    providerKey: String,
    title: String,
): String {
    val name = providerName(context, providerKey)
    return if (name == null) title else "$name - $title"
}

/** The provider's display name; null for "No provider". */
fun providerName(
    context: Context,
    providerKey: String,
): String? = providerRes(providerKey)?.let { context.getString(it) }

/** The string resource naming a provider; null for "No provider". */
fun providerRes(providerKey: String): Int? =
    when (BillProvider.of(providerKey)) {
        BillProvider.VALU -> R.string.bills_provider_valu
        BillProvider.HALAN -> R.string.bills_provider_halan
        BillProvider.SOUHOOLA -> R.string.bills_provider_souhoola
        BillProvider.GAM3EYA -> R.string.bills_provider_gam3eya
        BillProvider.MONEY_FELLOWS -> R.string.bills_provider_money_fellows
        BillProvider.CONTACT -> R.string.bills_provider_contact
        BillProvider.AMAN -> R.string.bills_provider_aman
        BillProvider.FORSA -> R.string.bills_provider_forsa
        BillProvider.SYMPL -> R.string.bills_provider_sympl
        BillProvider.BANK -> R.string.bills_provider_bank
        BillProvider.OTHER_STORE -> R.string.bills_provider_other_store
        BillProvider.NONE -> null
    }
