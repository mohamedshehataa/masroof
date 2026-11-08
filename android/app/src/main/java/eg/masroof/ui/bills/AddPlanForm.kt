package eg.masroof.ui.bills

import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.bills.BillMath
import eg.masroof.data.bills.BillProvider
import eg.masroof.data.bills.BillPurpose
import eg.masroof.data.bills.DEFAULT_CATEGORY_KEY
import eg.masroof.data.bills.NewPlan
import eg.masroof.data.bills.PROVIDER_GAM3EYA
import eg.masroof.data.bills.PlanField
import eg.masroof.data.bills.PlanValidator
import eg.masroof.data.db.seededCategoryId
import java.time.LocalDate

/** What the Add installment form holds: the text as typed, parsed only on Save. */
data class AddPlanForm(
    val provider: BillProvider = BillProvider.VALU,
    val purpose: BillPurpose? = null,
    val title: String = "",
    /** The monthly amount, or a gam3eya's monthly share. */
    val monthly: String = "",
    val count: String = "",
    val byEndDate: Boolean = false,
    val endDate: String = "",
    val firstDate: String = "",
    val purchasePrice: String = "",
    val downPayment: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val reminderDays: Int = BillMath.DEFAULT_REMINDER_DAYS,
    val members: String = "",
    val turn: String = "",
    val payout: String = "",
    /** Null = not typed: the default (those due before today) is used. */
    val alreadyPaid: String? = null,
) {
    val isGam3eya: Boolean get() = provider.key == PROVIDER_GAM3EYA
}

sealed interface FormResult {
    data class Ok(
        val plan: NewPlan,
    ) : FormResult

    data class Errors(
        val fields: Set<PlanField>,
    ) : FormResult
}

/** Numbers the form shows while it is being filled: the other duration value and the "already paid" default. */
data class FormDerived(
    val firstDue: LocalDate?,
    val count: Int?,
    val endDate: LocalDate?,
)

private fun western(text: String): String = text.trim().map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")

fun parseDate(text: String): LocalDate? =
    eg.masroof.core.UserDate
        .parse(text)

private fun parseInt(text: String): Int? = western(text).toIntOrNull()

/** An optional amount: blank is fine (null); anything else must be a valid amount or [field] is flagged in [bad]. */
private fun optionalMinor(
    text: String,
    field: PlanField,
    bad: MutableSet<PlanField>,
): Long? {
    if (text.isBlank()) return null
    val valid = MoneyFormat.parseEntry(text) as? MoneyFormat.EntryAmount.Valid
    if (valid == null) bad += field
    return valid?.minor
}

fun AddPlanForm.derived(): FormDerived {
    val first = parseDate(firstDate)
    val valid = durationCount(first)?.takeIf { it in 1..BillMath.MAX_PAYMENTS }
    return FormDerived(
        firstDue = first,
        count = valid,
        endDate = if (first != null && valid != null) BillMath.endDate(first, valid) else null,
    )
}

/** The number of payments the duration fields say (members for a gam3eya, months, or from the end date); null when unreadable. */
private fun AddPlanForm.durationCount(first: LocalDate?): Int? =
    when {
        isGam3eya -> parseInt(members)
        byEndDate -> first?.let { f -> parseDate(endDate)?.let { BillMath.countUntil(f, it) } }
        else -> parseInt(count)
    }

fun AddPlanForm.dueBefore(today: LocalDate): Int {
    val d = derived()
    val first = d.firstDue
    val count = d.count
    return if (first == null || count == null) 0 else BillMath.dueBefore(BillMath.dueDates(first, count), today)
}

/** Parses and validates the whole form; either the plan to store or the fields to flag (nothing is stored on errors). */
fun AddPlanForm.parse(today: LocalDate): FormResult {
    val bad = mutableSetOf<PlanField>()
    val first = parseDate(firstDate)
    if (first == null) bad += PlanField.FIRST_DATE
    val monthlyMinor = (MoneyFormat.parseEntry(monthly) as? MoneyFormat.EntryAmount.Valid)?.minor
    if (monthlyMinor == null) bad += PlanField.AMOUNT
    val count = durationCount(first)
    if (count == null || count !in 1..BillMath.MAX_PAYMENTS) bad += countField()
    val price = optionalMinor(purchasePrice, PlanField.PURCHASE_PRICE, bad)
    val down = optionalMinor(downPayment, PlanField.DOWN_PAYMENT, bad)
    val gam = if (isGam3eya) gam3eyaTerms(count ?: 0, monthlyMinor ?: 0L, bad) else null
    val typedPaid = alreadyPaid?.let { parseInt(it) }
    if (alreadyPaid != null && typedPaid == null) bad += PlanField.ALREADY_PAID
    val plan =
        NewPlan(
            title = title,
            provider = provider.key,
            monthlyMinor = monthlyMinor ?: 0L,
            paymentCount = count ?: 0,
            firstDue = first ?: today,
            categoryId = resolvedCategory(),
            accountId = accountId,
            purchasePriceMinor = price,
            downPaymentMinor = down ?: 0L,
            reminderDaysBefore = reminderDays,
            alreadyPaid = typedPaid ?: dueBefore(today),
            collectionTurn = gam?.turn,
            payoutMinor = gam?.payout,
            currency = Money.EGP,
        )
    bad += PlanValidator.validate(plan)
    if (countField() == PlanField.END_DATE && PlanField.COUNT in bad) {
        bad -= PlanField.COUNT
        bad += PlanField.END_DATE
    }
    return if (bad.isEmpty()) FormResult.Ok(plan) else FormResult.Errors(bad)
}

private data class Gam3eyaTerms(
    val turn: Int?,
    val payout: Long?,
)

/** The gam3eya fields: the collection turn (1..members) and the amount collected (default share x members). */
private fun AddPlanForm.gam3eyaTerms(
    members: Int,
    share: Long,
    bad: MutableSet<PlanField>,
): Gam3eyaTerms {
    val turnValue = parseInt(turn)?.takeIf { it in 1..members.coerceAtLeast(1) }
    if (turnValue == null) bad += PlanField.TURN
    val typed = optionalMinor(payout, PlanField.PAYOUT, bad)
    val computable = share > 0 && members > 0
    val payoutValue = typed ?: if (computable) BillMath.defaultPayout(share, members) else null
    if (computable && payoutValue == null) bad += PlanField.PAYOUT
    return Gam3eyaTerms(turnValue, payoutValue)
}

/** The category the plan gets: the one picked, else the chip's default, else Household items; none for a gam3eya. */
private fun AddPlanForm.resolvedCategory(): String? =
    if (isGam3eya) null else categoryId ?: purpose?.categoryId ?: seededCategoryId(DEFAULT_CATEGORY_KEY)

/** Which field shows a duration error: the end date when that is how the duration is given, else the count. */
private fun AddPlanForm.countField(): PlanField = if (byEndDate && !isGam3eya) PlanField.END_DATE else PlanField.COUNT
