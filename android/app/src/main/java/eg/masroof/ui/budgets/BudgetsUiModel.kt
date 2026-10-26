package eg.masroof.ui.budgets

import eg.masroof.core.Money
import eg.masroof.data.budgets.BudgetsSnapshot
import eg.masroof.data.db.CategoryEntity

/** How close a budget is to its limit. Colors come from this, never from the raw percentage alone. */
enum class BudgetLevel { OK, HALF, WARNING, OVER }

private const val HALF_BPS = 5_000
private const val WARNING_BPS = 8_000
private const val FULL_BPS = 10_000

fun levelOf(progressBps: Int): BudgetLevel =
    when {
        progressBps >= FULL_BPS -> BudgetLevel.OVER
        progressBps >= WARNING_BPS -> BudgetLevel.WARNING
        progressBps >= HALF_BPS -> BudgetLevel.HALF
        else -> BudgetLevel.OK
    }

/** One budget as the screens show it. A null category is the whole-household budget. */
data class BudgetRowUi(
    val id: String,
    val categoryId: String?,
    val nameEn: String?,
    val nameAr: String?,
    val limit: Money,
    val spent: Money,
    val remaining: Money,
    val progressBps: Int,
    val level: BudgetLevel,
    val daysLeft: Int?,
    val rollover: Boolean,
    val weekly: Boolean,
) {
    /** Bar fill in basis points, clamped to a full bar. */
    val barBps: Int get() = progressBps.coerceIn(0, FULL_BPS)
}

/** Maps the cached server answer to rows, closest to (or furthest over) the limit first. */
fun BudgetsSnapshot.toRows(categories: List<CategoryEntity>): List<BudgetRowUi> {
    val byId = categories.associateBy { it.id }
    return status.items
        .map { s ->
            val b = s.budget
            val cat = b.categoryId?.toString()
            BudgetRowUi(
                id = b.id.toString(),
                categoryId = cat,
                nameEn = cat?.let { byId[it]?.nameEn },
                nameAr = cat?.let { byId[it]?.nameAr },
                limit = Money(b.amount.amountMinor, b.amount.currency),
                spent = Money(s.spent.amountMinor, s.spent.currency),
                remaining = Money(s.remaining.amountMinor, s.remaining.currency),
                progressBps = s.progressBps,
                level = levelOf(s.progressBps),
                daysLeft = s.daysLeft,
                rollover = b.rollover,
                weekly = b.period == eg.masroof.api.models.BudgetPeriod.WEEKLY,
            )
        }.sortedWith(compareByDescending<BudgetRowUi> { it.progressBps }.thenBy { it.id })
}
