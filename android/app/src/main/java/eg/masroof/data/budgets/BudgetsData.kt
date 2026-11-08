package eg.masroof.data.budgets

import eg.masroof.core.analytics.BudgetPeriodKind

/** What the user entered in the budget form. [categoryId] null is the whole household budget ("All spending"). */
data class BudgetDraft(
    val categoryId: String?,
    val amountMinor: Long,
    val rollover: Boolean,
    val period: BudgetPeriodKind = BudgetPeriodKind.MONTHLY,
)

/** Result of a create, update or delete. Only [Ok] changed anything. */
enum class MutationOutcome { Ok, Invalid, Duplicate, NotFound, Failed }

/** The largest budget a user may set, in minor units (same cap as a manual entry). */
const val MAX_BUDGET_MINOR = 1_000_000_000_000L
