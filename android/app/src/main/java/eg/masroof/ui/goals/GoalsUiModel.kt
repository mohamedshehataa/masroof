package eg.masroof.ui.goals

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.analytics.Projections
import eg.masroof.data.db.GoalMovementEntity
import eg.masroof.data.goals.GoalDetail
import eg.masroof.data.goals.GoalMath
import eg.masroof.data.goals.GoalSummary
import java.time.LocalDate

/** One goal as the screens show it. */
data class GoalCardUi(
    val id: String,
    val name: String,
    val emoji: String?,
    val currency: String,
    val completed: Boolean,
    val progress: GoalMath.Progress,
)

/** Saved of target across the active goals in the household currency (EGP). */
data class GoalsTotals(
    val savedMinor: Long,
    val targetMinor: Long,
    val percentBp: Int,
    val otherCurrencyCount: Int,
)

data class GoalsListState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val cards: List<GoalCardUi> = emptyList(),
    val totals: GoalsTotals? = null,
)

data class GoalDetailState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val card: GoalCardUi? = null,
    val targetAtMillis: Long? = null,
    /** Newest first. */
    val history: List<GoalMovementEntity> = emptyList(),
    /** Running total after each movement, oldest first, for the step chart. */
    val steps: List<Long> = emptyList(),
)

/** What the detail screen asks of its host. */
class GoalDetailActions(
    val onAdd: () -> Unit,
    val onTakeOut: () -> Unit,
    val onEdit: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
)

class GoalFormActions(
    val onName: (String) -> Unit,
    val onEmoji: (String) -> Unit,
    val onTarget: (String) -> Unit,
    val onDate: (String) -> Unit,
    val onSave: () -> Unit,
)

internal fun card(
    summary: GoalSummary,
    today: LocalDate,
    monthStartDay: Int,
    extraSavedMinor: Long = 0L,
): GoalCardUi = cardOf(summary.goal, summary.savedMinor, today, monthStartDay, extraSavedMinor)

private fun cardOf(
    goal: eg.masroof.data.db.SavingsGoalEntity,
    savedMinor: Long,
    today: LocalDate,
    monthStartDay: Int,
    extraSavedMinor: Long,
): GoalCardUi =
    GoalCardUi(
        id = goal.id,
        name = goal.name,
        emoji = goal.emoji,
        currency = goal.currency,
        completed = savedMinor + extraSavedMinor >= goal.targetMinor,
        progress =
            GoalMath.progress(
                GoalMath.Input(
                    targetMinor = goal.targetMinor,
                    savedMinor = savedMinor,
                    createdOn = CairoTime.dateOf(goal.createdAtMillis),
                    dueOn = goal.targetAtMillis?.let(CairoTime::dateOf),
                    today = today,
                    monthStartDay = monthStartDay,
                    extraSavedMinor = extraSavedMinor,
                ),
            ),
    )

/** Open goals first, then reached ones, each group in the user's order. */
internal fun cards(
    summaries: List<GoalSummary>,
    today: LocalDate,
    monthStartDay: Int,
): List<GoalCardUi> = summaries.map { card(it, today, monthStartDay) }.sortedBy { it.completed }

/** Null when there is no EGP goal; goals in other currencies are only counted, never added in. */
internal fun totals(cards: List<GoalCardUi>): GoalsTotals? {
    val egp = cards.filter { it.currency == Money.EGP }
    if (egp.isEmpty()) return null
    val saved = egp.sumOf { it.progress.savedMinor }
    val target = egp.sumOf { it.progress.targetMinor }
    return GoalsTotals(saved, target, Projections.shareBps(saved, target).coerceIn(0, MAX_BP), cards.size - egp.size)
}

private const val MAX_BP = 10_000

internal fun detailState(
    detail: GoalDetail?,
    today: LocalDate,
    monthStartDay: Int,
): GoalDetailState =
    if (detail == null) {
        GoalDetailState(loading = false, missing = true)
    } else {
        GoalDetailState(
            loading = false,
            card = cardOf(detail.goal, detail.savedMinor, today, monthStartDay, 0L),
            targetAtMillis = detail.goal.targetAtMillis,
            history = detail.movements.sortedWith(compareByDescending<GoalMovementEntity> { it.atMillis }.thenBy { it.id }),
            steps = GoalMath.runningTotals(detail.movements.map { it.amountMinor }),
        )
    }
