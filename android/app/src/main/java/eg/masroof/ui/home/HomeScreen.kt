package eg.masroof.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.HomeBudgetsCard
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.DeltaChip
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.components.ListStates
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.components.StopLearningDialog
import eg.masroof.ui.insights.BarChart
import eg.masroof.ui.insights.BarItem
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.CapturedViewModel
import eg.masroof.ui.viewmodel.HomeBudgetsViewModel
import eg.masroof.ui.viewmodel.HomeInsight
import eg.masroof.ui.viewmodel.HomeInsightsViewModel
import eg.masroof.ui.viewmodel.HomeUiState
import eg.masroof.ui.viewmodel.HomeViewModel
import eg.masroof.ui.viewmodel.LoadStatus

@Composable
fun HomeRoute(
    onSeeAll: () -> Unit,
    onOpenInsights: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    insightsViewModel: HomeInsightsViewModel = hiltViewModel(),
    onOpenBudgets: () -> Unit = {},
    budgetsViewModel: HomeBudgetsViewModel = hiltViewModel(),
    captured: CapturedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val insight by insightsViewModel.state.collectAsStateWithLifecycle()
    val budgets by budgetsViewModel.state.collectAsStateWithLifecycle()
    val capturedIds by captured.byExpenseId.collectAsStateWithLifecycle()
    HomeContent(
        state,
        onRetry = {
            viewModel.refresh()
            insightsViewModel.refresh()
            budgetsViewModel.refresh()
        },
        onSeeAll = onSeeAll,
        onRetryEntry = viewModel::retryEntry,
        onDeleteEntry = viewModel::deleteEntry,
        insight = insight,
        onOpenInsights = onOpenInsights,
        budgets = budgets,
        onOpenBudgets = onOpenBudgets,
        capturedIds = capturedIds,
        onUndoCaptured = captured::undo,
    )
    StopLearningDialog(captured)
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
    insight: HomeInsight? = null,
    onOpenInsights: () -> Unit = {},
    budgets: List<BudgetRowUi> = emptyList(),
    onOpenBudgets: () -> Unit = {},
    capturedIds: Map<String, String> = emptyMap(),
    onUndoCaptured: (String) -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SpentCard(state, insight)
        if (state.recent.isNotEmpty()) StatusBanner(state.status, onRetry)
        if (insight != null && insight.pendingReview > 0) ReviewBanner(insight.pendingReview, onOpenInsights)
        HomeBudgetsCard(budgets, onOpenBudgets)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.home_see_all)) }
        }
        ListStates(state.status, isEmpty = state.recent.isEmpty(), onRetry = onRetry) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recent, key = { it.id }) {
                    ExpenseListItem(
                        it,
                        onRetryEntry = onRetryEntry,
                        onDeleteEntry = onDeleteEntry,
                        capturedLocalId = capturedIds[it.id],
                        onUndoCaptured = onUndoCaptured,
                    )
                }
            }
        }
    }
}

/** "Spent this month" with the change vs last month and a 6-month mini trend (from the cached analytics). */
@Composable
private fun SpentCard(
    state: HomeUiState,
    insight: HomeInsight?,
) {
    val style = LocalMoneyStyle.current
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_this_month), style = MaterialTheme.typography.labelLarge, color = onContainer)
            AmountText(state.total, style = MaterialTheme.typography.displaySmall, color = onContainer)
            if (insight != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeltaChip(insight.deltaBps)
                    if (insight.deltaBps != null) {
                        Text(stringResource(R.string.home_vs_last_month), style = MaterialTheme.typography.bodySmall, color = onContainer)
                    }
                }
                if (insight.trend.any { it.total.amountMinor > 0 }) {
                    val description =
                        stringResource(
                            R.string.home_trend_desc,
                            insight.trend.joinToString(", ") {
                                DateFormat.monthShort(it.start, style.arabic) + " " +
                                    MoneyFormat.format(it.total, style.arabic, style.digits)
                            },
                        )
                    BarChart(
                        items =
                            insight.trend.map {
                                BarItem(
                                    DateFormat.monthShort(it.start, style.arabic),
                                    it.total.amountMinor,
                                    it.selected,
                                )
                            },
                        description = description,
                        height = 72.dp,
                        showLabels = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.home_trend_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainer,
                    )
                }
            }
            if (state.pendingCount > 0) {
                Text(
                    stringResource(R.string.pending_count, state.pendingCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun ReviewBanner(
    count: Int,
    onOpen: () -> Unit,
) {
    val digits = LocalMoneyStyle.current.digits
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            pluralStringResource(R.plurals.insights_pending_review, count, DateFormat.number(count, digits)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Used by previews/tests. */
val HomeLoading = HomeUiState(status = LoadStatus.LOADING)
