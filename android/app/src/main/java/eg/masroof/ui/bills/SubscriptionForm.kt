package eg.masroof.ui.bills

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.DEFAULT_SUBSCRIPTION_REMINDER_DAYS
import eg.masroof.data.bills.KIND_BILL
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.NewSubscription
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.bills.SubscriptionService
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.data.db.seededCategoryId
import eg.masroof.ui.viewmodel.plainAmount
import java.time.LocalDate

enum class SubField { NAME, AMOUNT, DATE }

val SUBSCRIPTION_CURRENCIES = listOf(Money.EGP, "USD", "EUR", "SAR")

/** What the Add / Edit subscription form holds: the text as typed, parsed only on Save. */
data class SubscriptionForm(
    val kind: String = KIND_SUBSCRIPTION,
    /** [SubscriptionService.key] or an income preset key; null for a typed name. */
    val serviceKey: String? = null,
    val name: String = "",
    val amount: String = "",
    val currency: String = Money.EGP,
    val cadence: Cadence = Cadence.MONTHLY,
    val date: String = "",
    val accountId: String? = null,
    val reminderDays: Int = DEFAULT_SUBSCRIPTION_REMINDER_DAYS,
    /** The name payments are matched by; blank = the service's or the name itself. */
    val merchant: String = "",
    val categoryId: String? = null,
) {
    val isIncome: Boolean get() = kind == KIND_INCOME

    companion object {
        fun of(row: SubscriptionEntity): SubscriptionForm =
            SubscriptionForm(
                kind = row.kind,
                name = row.name,
                amount = plainAmount(row.amountMinor),
                currency = row.currency,
                cadence = Cadence.of(row.cadence),
                date = CairoTime.dateOf(row.nextRenewalAtMillis).toString(),
                accountId = row.accountId,
                reminderDays = row.reminderDaysBefore,
                merchant = row.merchantKey.orEmpty(),
                categoryId = row.categoryId,
            )
    }
}

sealed interface SubFormResult {
    data class Ok(
        val value: NewSubscription,
    ) : SubFormResult

    data class Errors(
        val fields: Set<SubField>,
    ) : SubFormResult
}

/** Validates the whole form: either the row to store or the fields to flag (nothing is stored on errors). */
fun SubscriptionForm.parse(): SubFormResult {
    val bad = mutableSetOf<SubField>()
    if (name.isBlank()) bad += SubField.NAME
    val minor = (MoneyFormat.parseEntry(amount) as? MoneyFormat.EntryAmount.Valid)?.minor
    if (minor == null) bad += SubField.AMOUNT
    val first = parseDate(date)
    if (first == null) bad += SubField.DATE
    if (bad.isNotEmpty()) return SubFormResult.Errors(bad)
    val service = SubscriptionService.of(serviceKey)
    return SubFormResult.Ok(
        NewSubscription(
            name = name.trim(),
            kind = kind,
            amountMinor = minor ?: 0L,
            currency = currency,
            cadence = cadence,
            nextRenewal = first ?: LocalDate.ofEpochDay(0),
            categoryId = resolvedCategory(service),
            accountId = accountId,
            merchantKey = SubscriptionMatcher.keyOf(merchant) ?: service?.merchant,
            reminderDaysBefore = reminderDays,
        ),
    )
}

private fun SubscriptionForm.resolvedCategory(service: SubscriptionService?): String? =
    when {
        isIncome -> null
        categoryId != null -> categoryId
        service != null -> service.categoryId
        kind == KIND_BILL -> seededCategoryId("bills")
        else -> seededCategoryId("subscriptions")
    }
