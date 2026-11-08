package eg.masroof.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.core.analytics.Projections
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.budgetName
import eg.masroof.ui.insights.CategoryDonut
import eg.masroof.ui.insights.DonutSlice
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle

private const val TILE_MIN_DP = 132
private const val DONUT_SLICES = 5
private const val BPS_FULL = 10_000f

/** The insight tiles grid (Say teardown item 15): two columns, each tile opens the screen it summarises. */
@Composable
internal fun InsightTiles(
    state: WalletUi,
    budget: BudgetRowUi?,
    actions: WalletActions,
) {
    val tiles: List<@Composable (Modifier) -> Unit> =
        listOf(
            { m -> TopSpendingTile(state, actions, m) },
            { m -> BillsDueTile(state, actions, m) },
            { m -> BudgetTile(budget, actions, m) },
            { m -> InOutTile(state, actions, m) },
            { m -> KeptTile(state, actions, m) },
            { m -> GoalTile(state, actions, m) },
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_tiles_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        tiles.chunked(2).forEach { pair ->
            // Equal height per row so a taller tile (larger text) does not leave its neighbour short.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun Tile(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier
            .heightIn(min = TILE_MIN_DP.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            content()
        }
    }
}

/** [part] of [whole] in basis points, never negative and never failing on huge numbers. */
private fun bps(
    part: Long,
    whole: Long,
): Int = runCatching { Projections.shareBps(part.coerceAtLeast(0L), whole) }.getOrDefault(0)

@Composable
private fun money(minor: Long): String {
    val s = LocalMoneyStyle.current
    return MoneyFormat.format(Money(minor), s.arabic, s.digits)
}

@Composable
private fun TopSpendingTile(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier,
) {
    val style = LocalMoneyStyle.current
    val figures = state.figures
    val top = figures?.categories.orEmpty().take(DONUT_SLICES)
    val title = stringResource(R.string.tile_top_spending)
    val summary =
        top.firstOrNull()?.let {
            val name = if (style.arabic) it.nameAr else it.nameEn
            "$name ${PercentFormat.format(it.shareBps, style.arabic, style.digits)}"
        }
    Tile(title, "$title. ${summary ?: stringResource(R.string.tile_top_spending_empty)}", actions.onOpenInsights, modifier) {
        if (top.isEmpty()) {
            Text(
                stringResource(R.string.tile_top_spending_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CategoryDonut(
                slices = top.map { DonutSlice(CategoryPalette.colorFor(it.categoryId), it.shareBps) },
                description = summary.orEmpty(),
                size = 88.dp,
            ) {}
            Text(
                listOfNotNull(top.first().categoryId?.let(state.categoryEmoji::get), summary).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BillsDueTile(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier,
) {
    val digits = LocalMoneyStyle.current.digits
    val title = stringResource(R.string.tile_bills_due)
    val due = state.billsDue
    val value =
        if (due.count == 0) {
            stringResource(R.string.tile_bills_none)
        } else {
            pluralStringResource(R.plurals.tile_bills_count, due.count, DateFormat.number(due.count, digits))
        }
    Tile(title, "$title. $value", actions.onOpenBills, modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        if (due.count > due.otherCurrency) {
            Text(money(due.totalMinor), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        }
        if (due.otherCurrency > 0) {
            Text(
                pluralStringResource(R.plurals.tile_bills_other, due.otherCurrency, DateFormat.number(due.otherCurrency, digits)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BudgetTile(
    budget: BudgetRowUi?,
    actions: WalletActions,
    modifier: Modifier,
) {
    val style = LocalMoneyStyle.current
    val title = stringResource(R.string.tile_budget)
    val percent = budget?.let { PercentFormat.format(it.progressBps, style.arabic, style.digits) }
    val name = budget?.let { budgetName(it) }
    Tile(title, "$title. ${name?.let { "$it $percent" } ?: stringResource(R.string.budgets_home_cta)}", actions.onOpenBudgets, modifier) {
        if (budget == null) {
            Text(
                stringResource(R.string.budgets_home_cta),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { (budget.progressBps.coerceAtLeast(0) / BPS_FULL).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text(percent.orEmpty(), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun InOutTile(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier,
) {
    val figures = state.figures
    val title = stringResource(R.string.tile_in_out)
    val income = figures?.incomeMinor ?: 0L
    val spent = figures?.spentMinor ?: 0L
    val widest = maxOf(income, spent, 1L)
    val inText = money(income)
    val outText = money(spent)
    Tile(
        title,
        "$title. ${stringResource(R.string.tile_in)} $inText, ${stringResource(R.string.tile_out)} $outText",
        actions.onOpenInsights,
        modifier,
    ) {
        FlowBar(stringResource(R.string.tile_in), inText, bps(income, widest) / BPS_FULL, MaterialTheme.colorScheme.primary)
        FlowBar(stringResource(R.string.tile_out), outText, bps(spent, widest) / BPS_FULL, MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun FlowBar(
    label: String,
    amount: String,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(amount, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun KeptTile(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier,
) {
    val style = LocalMoneyStyle.current
    val title = stringResource(R.string.tile_kept)
    val kept = state.figures?.keptBps
    val value = kept?.let { PercentFormat.format(it, style.arabic, style.digits) }
    Tile(title, "$title. ${value ?: stringResource(R.string.tile_kept_none)}", actions.onOpenInsights, modifier) {
        if (value == null) {
            Text(
                stringResource(R.string.tile_kept_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
            Text(
                stringResource(R.string.tile_kept_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoalTile(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier,
) {
    val title = stringResource(R.string.tile_goal)
    val goal = state.topGoal
    if (goal == null) {
        Tile(title, "$title. ${stringResource(R.string.tile_goal_cta)}", actions.onOpenGoals, modifier) {
            Text(
                stringResource(R.string.tile_goal_cta),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    val shownProgress = bps(goal.savedMinor, goal.goal.targetMinor) / BPS_FULL
    val label = listOfNotNull(goal.goal.emoji, goal.goal.name).joinToString(" ")
    val of = stringResource(R.string.goals_of, money(goal.savedMinor), money(goal.goal.targetMinor))
    Tile(title, "$title. $label, $of", actions.onOpenGoals, modifier) {
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { shownProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(of, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
    }
}
