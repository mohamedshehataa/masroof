package eg.masroof.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.DeltaChip
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.RetryButton
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.InsightsState
import eg.masroof.ui.viewmodel.InsightsViewModel
import eg.masroof.ui.viewmodel.LoadStatus
import java.time.DayOfWeek

private const val MAX_CATEGORY_ROWS = 8
private const val MAX_MERCHANT_ROWS = 10
private const val TREND_SHORT = 6
private const val TREND_LONG = 12
private const val DONUT_SLICES = 8

@Composable
fun InsightsRoute(
    onOpenBudgets: () -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Runs each time the destination enters composition (tab revisited), not on recomposition.
    LaunchedEffect(viewModel) { viewModel.onShown() }
    InsightsContent(state, onPeriod = viewModel::selectPeriod, onRetry = viewModel::refresh, onOpenBudgets = onOpenBudgets)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsContent(
    state: InsightsState,
    onPeriod: (InsightsPeriod) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBudgets: () -> Unit = {},
) {
    val data = state.data
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.insights_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            PeriodToggle(state.period, onPeriod)
            TextButton(onClick = onOpenBudgets, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.budgets_title))
            }
        }
        when {
            data == null -> NoDataState(state.status, onRetry)
            !data.hasData ->
                Column(Modifier.fillMaxSize()) {
                    StatusBanner(state.status, onRetry, Modifier.padding(horizontal = 16.dp))
                    MessageState(
                        stringResource(R.string.insights_empty_title),
                        stringResource(R.string.insights_empty_body),
                        icon = Icons.Filled.Warning,
                    )
                }
            else ->
                PullToRefreshBox(
                    isRefreshing = state.status == LoadStatus.LOADING,
                    onRefresh = onRetry,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { StatusBanner(state.status, onRetry) }
                        item { OverviewCard(data, state.period) }
                        if (data.otherCurrencyCount > 0) item { OtherCurrencyNote(data.otherCurrencyCount) }
                        if (data.categories.isNotEmpty()) item { CategoriesCard(data) }
                        if (data.trend.isNotEmpty()) item { TrendCard(data) }
                        item { MerchantsCard(data) }
                        if (data.calendar.isNotEmpty()) item { CalendarCard(data) }
                        if (data.weekday.isNotEmpty()) item { WeekdayCard(data) }
                    }
                }
        }
    }
}

@Composable
private fun NoDataState(
    status: LoadStatus,
    onRetry: () -> Unit,
) {
    when (status) {
        LoadStatus.LOADING, LoadStatus.READY ->
            if (status == LoadStatus.LOADING) {
                SkeletonList(Modifier.padding(16.dp), rows = 4)
            } else {
                MessageState(
                    stringResource(R.string.insights_empty_title),
                    stringResource(R.string.insights_empty_body),
                    icon = Icons.Filled.Warning,
                )
            }
        LoadStatus.OFFLINE ->
            MessageState(
                stringResource(R.string.insights_offline_title),
                stringResource(R.string.insights_offline_body),
                icon = Icons.Filled.Warning,
            ) { RetryButton(onRetry) }
        LoadStatus.SIGN_IN ->
            MessageState(
                stringResource(R.string.sign_in_title),
                stringResource(R.string.sign_in_body),
                icon = Icons.Filled.Warning,
            ) { RetryButton(onRetry) }
        LoadStatus.ERROR ->
            MessageState(
                stringResource(R.string.insights_error_title),
                stringResource(R.string.insights_error_body),
                icon = Icons.Filled.Warning,
            ) { RetryButton(onRetry) }
    }
}

@Composable
private fun PeriodToggle(
    selected: InsightsPeriod,
    onPeriod: (InsightsPeriod) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InsightsPeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onPeriod(period) },
                label = {
                    Text(
                        stringResource(
                            if (period == InsightsPeriod.CURRENT) R.string.insights_period_current else R.string.insights_period_previous,
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun OverviewCard(
    data: InsightsUi,
    period: InsightsPeriod,
) {
    val style = LocalMoneyStyle.current
    val current = period == InsightsPeriod.CURRENT
    val range =
        stringResource(
            R.string.insights_range,
            DateFormat.shortDate(data.from, style.arabic, style.digits),
            DateFormat.shortDate(data.to, style.arabic, style.digits),
        )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (current) R.string.insights_total_current else R.string.insights_total_previous),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            AmountText(data.total, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeltaChip(data.deltaBps)
                Text(
                    stringResource(
                        R.string.insights_vs_previous,
                        MoneyFormat.format(data.previousTotal, style.arabic, style.digits),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.insights_daily_average), data.dailyAverage, Modifier.weight(1f))
                if (data.daysLeft > 0) {
                    Stat(stringResource(R.string.insights_projected), data.projected, Modifier.weight(1f))
                    Column(Modifier.weight(0.6f)) {
                        Text(
                            stringResource(R.string.insights_days_left),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            DateFormat.number(data.daysLeft, style.digits),
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (data.pendingReview > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.insights_pending_review,
                        data.pendingReview,
                        DateFormat.number(data.pendingReview, style.digits),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    money: Money,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        AmountText(money, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun CategoriesCard(data: InsightsUi) {
    val style = LocalMoneyStyle.current
    val rows = data.categories.take(MAX_CATEGORY_ROWS)
    val slices = data.categories.take(DONUT_SLICES).map { DonutSlice(CategoryPalette.colorFor(it.id), it.shareBps) }

    fun name(c: CategoryShare) = if (style.arabic) c.nameAr else c.nameEn

    fun percent(bps: Int) = PercentFormat.format(bps, style.arabic, style.digits)
    val donutDescription =
        stringResource(
            R.string.insights_donut_desc,
            rows.joinToString(", ") { "${name(it)} ${percent(it.shareBps)}" },
        )
    SectionCard(stringResource(R.string.insights_categories_title)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CategoryDonut(slices, donutDescription) {
                val top = data.categories.first()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        percent(top.shareBps),
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        name(top),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        rows.forEachIndexed { index, c ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CategoryRow(c, name(c))
        }
    }
}

@Composable
private fun CategoryRow(
    c: CategoryShare,
    name: String,
) {
    val style = LocalMoneyStyle.current
    val amount = MoneyFormat.format(c.total, style.arabic, style.digits)
    val share = PercentFormat.format(c.shareBps, style.arabic, style.digits)
    val description = stringResource(R.string.insights_category_row_desc, name, amount, share)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(CategoryPalette.colorFor(c.id)))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "$share · " + pluralStringResource(R.plurals.insights_expense_count, c.count, DateFormat.number(c.count, style.digits)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AmountText(c.total, style = MaterialTheme.typography.bodyLarge)
            DeltaChip(c.deltaBps)
        }
    }
}

@Composable
private fun TrendCard(data: InsightsUi) {
    val style = LocalMoneyStyle.current
    var months by rememberSaveable { mutableIntStateOf(TREND_SHORT) }
    val shown = data.trend.takeLast(months)
    val items =
        shown.map {
            BarItem(DateFormat.monthShort(it.start, style.arabic), it.total.amountMinor, it.selected)
        }
    val description =
        stringResource(
            R.string.insights_trend_desc,
            shown.joinToString(", ") {
                DateFormat.monthShort(it.start, style.arabic) + " " + MoneyFormat.format(it.total, style.arabic, style.digits)
            },
        )
    val active = shown.filter { it.total.amountMinor > 0 }
    SectionCard(stringResource(R.string.insights_trend_title, DateFormat.number(shown.size, style.digits))) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(TREND_SHORT, TREND_LONG)) {
                FilterChip(
                    selected = months == n,
                    onClick = { months = n },
                    label = { Text(stringResource(R.string.insights_trend_range, DateFormat.number(n, style.digits))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        BarChart(items, description)
        val last = shown.lastOrNull()
        if (last != null) {
            val rate = last.savingsRateBps?.let { PercentFormat.format(it, style.arabic, style.digits) } ?: "-"
            Text(
                stringResource(R.string.insights_trend_savings, rate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active.isNotEmpty()) {
            val average = Money(active.sumOf { it.total.amountMinor } / active.size, data.total.currency)
            Text(
                stringResource(R.string.insights_trend_average, MoneyFormat.format(average, style.arabic, style.digits)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MerchantsCard(data: InsightsUi) {
    val style = LocalMoneyStyle.current
    SectionCard(stringResource(R.string.insights_merchants_title)) {
        if (data.merchants.isEmpty()) {
            Text(
                stringResource(R.string.insights_merchants_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        data.merchants.take(MAX_MERCHANT_ROWS).forEachIndexed { index, m ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val amount = MoneyFormat.format(m.total, style.arabic, style.digits)
            val visits = pluralStringResource(R.plurals.insights_merchant_visits, m.visits, DateFormat.number(m.visits, style.digits))
            val average = MoneyFormat.format(m.averageTicket, style.arabic, style.digits)
            val description = stringResource(R.string.insights_merchant_row_desc, m.name, amount, visits, average)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        stringResource(R.string.insights_merchant_detail, visits, average),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(m.total, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CalendarCard(data: InsightsUi) {
    val style = LocalMoneyStyle.current
    val busiest = data.calendar.maxByOrNull { it.total.amountMinor }?.takeIf { it.total.amountMinor > 0 }
    val description =
        if (busiest == null) {
            stringResource(R.string.insights_calendar_desc_none)
        } else {
            stringResource(
                R.string.insights_calendar_desc,
                DateFormat.shortDate(busiest.date, style.arabic, style.digits),
                MoneyFormat.format(busiest.total, style.arabic, style.digits),
            )
        }
    SectionCard(stringResource(R.string.insights_calendar_title)) {
        CalendarHeatmap(data.calendar, style.arabic, style.digits, description)
        if (busiest != null) {
            Text(
                stringResource(
                    R.string.insights_calendar_busiest,
                    DateFormat.shortDate(busiest.date, style.arabic, style.digits),
                    MoneyFormat.format(busiest.total, style.arabic, style.digits),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekdayCard(data: InsightsUi) {
    val style = LocalMoneyStyle.current
    val order = List(WEEK_DAYS) { DayOfWeek.SATURDAY.plus(it.toLong()) }
    val bars =
        order.mapIndexed { i, day ->
            val total = data.weekday.getOrNull(i) ?: Money.zero(data.total.currency)
            WeekdayBar(day, total.amountMinor, MoneyFormat.format(total, style.arabic, style.digits))
        }
    val description =
        stringResource(
            R.string.insights_weekday_desc,
            bars.joinToString(", ") { DateFormat.weekday(it.day, style.arabic, narrow = false) + " " + it.amount },
        )
    SectionCard(stringResource(R.string.insights_weekday_title)) {
        WeekdayBars(bars, style.arabic, description)
    }
}

@Composable
private fun OtherCurrencyNote(count: Int) {
    val digits = LocalMoneyStyle.current.digits
    Text(
        pluralStringResource(R.plurals.insights_other_currency, count, DateFormat.number(count, digits)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
