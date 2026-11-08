package eg.masroof.data.bills

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.AppDatabase
import eg.masroof.work.DueReminder
import eg.masroof.work.ReminderSource
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Route a reminder opens for a subscription; MainActivity accepts exactly this shape. */
fun subscriptionRoute(id: String) = "subscriptions/$id"

/**
 * Active subscriptions and bills whose renewal is today or later and whose own reminder window ("N days before") includes today.
 * Income is never reminded. Notifications carry the name and amount only.
 */
@Singleton
class SubscriptionReminderSource
    @Inject
    constructor(
        private val db: AppDatabase,
    ) : ReminderSource {
        override suspend fun dueOn(today: LocalDate): List<DueReminder> =
            db
                .subscriptions()
                .all()
                .filter { it.active && it.kind != KIND_INCOME }
                .mapNotNull { row ->
                    val due = CairoTime.dateOf(row.nextRenewalAtMillis)
                    if (due.isBefore(today) || due.minusDays(row.reminderDaysBefore.toLong()).isAfter(today)) return@mapNotNull null
                    DueReminder(
                        key = "sub:${row.id}:$due",
                        label = row.name,
                        amount = Money(row.amountMinor, row.currency),
                        dueDate = due,
                        route = subscriptionRoute(row.id),
                    )
                }
    }
