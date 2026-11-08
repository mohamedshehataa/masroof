package eg.masroof

import eg.masroof.core.Money
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.levelOf

/** Budget rows for the screen tests: the UI model only, no database. */
object BudgetsFixtures {
    const val GROCERIES = "cat-groceries"
    const val CAR = "cat-car"
    const val ID_GROCERIES = "b-groceries"
    const val ID_TOTAL = "b-total"
    const val ID_CAR = "b-car"

    val categories =
        listOf(
            CategoryEntity(GROCERIES, "Groceries", "بقالة", 1),
            CategoryEntity(CAR, "Car", "سيارة", 2),
        )

    @Suppress("LongParameterList")
    fun row(
        id: String,
        categoryId: String?,
        nameEn: String?,
        nameAr: String?,
        limitMinor: Long,
        spentMinor: Long,
        bps: Int,
        weekly: Boolean = false,
        deletedCategory: Boolean = false,
        hasProgress: Boolean = true,
    ) = BudgetRowUi(
        id = id,
        categoryId = categoryId,
        nameEn = nameEn,
        nameAr = nameAr,
        limit = Money(limitMinor),
        effectiveLimit = Money(limitMinor),
        spent = Money(spentMinor),
        remaining = Money(limitMinor - spentMinor),
        progressBps = bps,
        level = levelOf(bps),
        daysLeft = if (hasProgress) 6 else null,
        rollover = false,
        weekly = weekly,
        period = if (weekly) BudgetPeriodKind.WEEKLY else BudgetPeriodKind.MONTHLY,
        deletedCategory = deletedCategory,
        hasProgress = hasProgress,
    )

    /** Car 120% of 500 EGP (over), All spending 82.5% of 10,000 EGP, Groceries 75% of 4,000 EGP (1,000 left), closest first. */
    fun rows() =
        listOf(
            row(ID_CAR, CAR, "Car", "سيارة", 50_000, 60_000, 12_000),
            row(ID_TOTAL, null, null, null, 1_000_000, 825_000, 8_250),
            row(ID_GROCERIES, GROCERIES, "Groceries", "بقالة", 400_000, 300_000, 7_500),
        )

    /** One budget for every scope of [categories]: the total and each category, monthly and weekly. */
    fun everythingTaken(): List<BudgetRowUi> =
        listOf(false, true).flatMap { weekly ->
            listOf(
                row("t-$weekly", null, null, null, 100_000, 0, 0, weekly),
                row("g-$weekly", GROCERIES, "Groceries", "بقالة", 100_000, 0, 0, weekly),
                row("c-$weekly", CAR, "Car", "سيارة", 100_000, 0, 0, weekly),
            )
        }
}
