package eg.masroof.data.budgets

import eg.masroof.api.models.BudgetPeriod
import eg.masroof.api.models.BudgetStatusList
import kotlinx.serialization.Serializable

/** Budget progress for the current period exactly as the server computed it; cached for offline display. */
@Serializable
data class BudgetsSnapshot(
    val status: BudgetStatusList,
    val fetchedAtMillis: Long,
)

/** What the user entered in the budget form. [categoryId] null is the whole household budget. */
data class BudgetDraft(
    val categoryId: String?,
    val amountMinor: Long,
    val currency: String,
    val rollover: Boolean,
    /** Sent as-is: editing a weekly budget must not turn it monthly. */
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
)

/** Result of a create, update or delete. Only [Ok] changed anything. */
enum class MutationOutcome { Ok, Invalid, Duplicate, NotFound, Offline, SignIn, Failed }
