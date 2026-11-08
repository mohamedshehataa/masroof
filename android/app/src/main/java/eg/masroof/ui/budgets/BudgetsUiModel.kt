package eg.masroof.ui.budgets

import eg.masroof.core.Money
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetStatus
import eg.masroof.data.db.BudgetEntity
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

/** One budget as the screens show it. A null category is the whole-household budget unless [deletedCategory] is set. */
data class BudgetRowUi(
    val id: String,
    val categoryId: String?,
    val nameEn: String?,
    val nameAr: String?,
    /** The amount the user set (what the edit form shows). */
    val limit: Money,
    /** Base amount plus rollover: what progress, remaining and "spent of" are measured against. */
    val effectiveLimit: Money,
    val spent: Money,
    val remaining: Money,
    val progressBps: Int,
    val level: BudgetLevel,
    val daysLeft: Int?,
    val rollover: Boolean,
    val weekly: Boolean,
    val period: BudgetPeriodKind,
    /** The category was archived or no longer exists: shown as "Deleted category", still editable and deletable. */
    val deletedCategory: Boolean = false,
    /** False while the engine has not answered or failed: only the name and the limit are shown then. */
    val hasProgress: Boolean = true,
    /** A second budget for the same category and period (a restored backup can hold one): shown, marked, deletable. */
    val duplicate: Boolean = false,
    /** A stored period or currency this app cannot compute (only a restore could bring one): "Unsupported budget", deletable. */
    val unsupported: Boolean = false,
) {
    /** Bar fill in basis points, clamped to a full bar. */
    val barBps: Int get() = progressBps.coerceIn(0, FULL_BPS)
}

/**
 * The stored budgets as rows, closest to (or furthest over) the limit first. [statuses] is keyed by budget id; a budget
 * without one is listed without progress. A category that is archived or missing makes the row a "Deleted category".
 */
fun buildBudgetRows(
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    statuses: Map<String, BudgetStatus>,
): List<BudgetRowUi> {
    val byId = categories.associateBy { it.id }
    return budgets
        .map { b ->
            val knownKind = BudgetPeriodKind.entries.firstOrNull { it.name == b.period }
            val kind = knownKind ?: BudgetPeriodKind.MONTHLY
            // a period the app never writes, or a currency other than EGP, has no progress: listed so it can be deleted
            val unsupported = knownKind == null || b.currency != Money.EGP
            val status = statuses[b.id]
            val cat = b.categoryId?.let { byId[it] }
            val deleted = b.categoryId != null && (cat == null || cat.archived)
            val limit = Money(b.amountMinor, b.currency)
            BudgetRowUi(
                id = b.id,
                categoryId = b.categoryId,
                nameEn = if (deleted) null else cat?.nameEn,
                nameAr = if (deleted) null else cat?.nameAr,
                limit = limit,
                effectiveLimit = status?.let { Money(it.progress.limit, b.currency) } ?: limit,
                spent = Money(status?.spent ?: 0L, b.currency),
                remaining = status?.let { Money(it.remaining, b.currency) } ?: limit,
                progressBps = status?.progress?.progressBps ?: 0,
                level = levelOf(status?.progress?.progressBps ?: 0),
                daysLeft = status?.daysLeft,
                rollover = b.rollover,
                weekly = kind == BudgetPeriodKind.WEEKLY,
                period = kind,
                deletedCategory = deleted,
                hasProgress = status != null && !unsupported,
                unsupported = unsupported,
                duplicate = status?.duplicate == true && !unsupported,
            )
        }.sortedWith(compareByDescending<BudgetRowUi> { it.progressBps }.thenBy { it.id })
}

/** A category (null = "All spending") and a period: a budget exists at most once for each. */
data class BudgetScope(
    val categoryId: String?,
    val period: BudgetPeriodKind,
)

/** The scopes of [period] that have no budget yet: "All spending" first when free, then the categories in the given order. */
fun freeScopes(
    rows: List<BudgetRowUi>,
    categories: List<CategoryEntity>,
    period: BudgetPeriodKind,
): List<BudgetScope> {
    val taken = rows.filter { it.period == period && !it.unsupported }.mapTo(HashSet()) { it.categoryId }
    return (listOf<String?>(null) + categories.map { it.id }).filter { it !in taken }.map { BudgetScope(it, period) }
}

/** Where the Add dialog starts: the first free scope, monthly before weekly; null when everything is taken (Add is disabled). */
fun firstFreeScope(
    rows: List<BudgetRowUi>,
    categories: List<CategoryEntity>,
): BudgetScope? = BudgetPeriodKind.entries.firstNotNullOfOrNull { freeScopes(rows, categories, it).firstOrNull() }
