package eg.masroof.ui.bills

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.bills.BillsOverview
import eg.masroof.data.bills.CalendarItem
import eg.masroof.data.bills.ComingUp
import eg.masroof.data.bills.PLAN_COMPLETED
import eg.masroof.data.bills.PlanProgress
import eg.masroof.data.bills.providerRes
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun billMoney(
    minor: Long,
    currency: String = Money.EGP,
): String {
    val s = LocalMoneyStyle.current
    return MoneyFormat.format(Money(minor, currency), s.arabic, s.digits)
}

@Composable
fun billDate(date: LocalDate): String {
    val s = LocalMoneyStyle.current
    return DateFormat.shortDate(date, s.arabic, s.digits)
}

@Composable
fun billNumber(value: Int): String = DateFormat.number(value, LocalMoneyStyle.current.digits)

@Composable
fun providerLabel(key: String): String? = providerRes(key)?.let { stringResource(it) }

/** "valU - iPhone 17 Pro", or the title alone for "No provider". */
@Composable
fun planLabel(
    provider: String,
    title: String,
): String = providerLabel(provider)?.let { "$it - $title" } ?: title

class BillsActions(
    val onAdd: () -> Unit,
    val onOpenPlan: (String) -> Unit,
    val onShiftMonth: (Int) -> Unit,
    val onAllowNotifications: () -> Unit,
)

@Composable
fun BillsRoute(
    onAdd: () -> Unit,
    onOpenPlan: (String) -> Unit,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermission() }
    BillsContent(
        state,
        BillsActions(
            onAdd = onAdd,
            onOpenPlan = onOpenPlan,
            onShiftMonth = viewModel::shiftMonth,
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
        ),
    )
}

@Composable
fun BillsContent(
    state: BillsUiState,
    actions: BillsActions,
    modifier: Modifier = Modifier,
) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val overview = state.overview
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bills_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Button(onClick = actions.onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bills_add)) }
            }
        }
        when {
            !state.loaded || overview == null -> item { SkeletonList(rows = 3) }
            overview.plans.isEmpty() -> item { EmptyBills(actions.onAdd) }
            else -> {
                if (!state.notificationsAllowed) item { NotificationsHint(actions.onAllowNotifications) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !calendar,
                            onClick = { calendar = false },
                            label = { Text(stringResource(R.string.bills_view_list)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = calendar,
                            onClick = { calendar = true },
                            label = { Text(stringResource(R.string.bills_view_calendar)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (calendar) calendarItems(state, actions) else listItems(overview, actions)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.listItems(
    overview: BillsOverview,
    actions: BillsActions,
) {
    item { SummaryCard(overview) }
    if (overview.hasOtherCurrency) {
        item {
            Text(
                stringResource(R.string.bills_other_currency_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (overview.comingUp.isNotEmpty()) {
        item {
            SectionCard(stringResource(R.string.bills_coming_up)) {
                overview.comingUp.forEach { ComingUpRow(it, onClick = { actions.onOpenPlan(it.planId) }) }
            }
        }
    }
    items(overview.plans, key = { it.plan.id }) { PlanCard(it, onClick = { actions.onOpenPlan(it.plan.id) }) }
}

@Composable
private fun SummaryCard(overview: BillsOverview) {
    SectionCard(stringResource(R.string.bills_this_month)) {
        Text(
            billMoney(overview.monthlyTotalMinor),
            style = MaterialTheme.typography.headlineMedium,
        )
        overview.dropsTo?.let {
            Text(
                stringResource(R.string.bills_drops_to, billMoney(it.amountMinor), billNumber(it.inMonths)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.bills_active), style = MaterialTheme.typography.labelMedium)
                Text(billNumber(overview.activeCount), style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.bills_remaining), style = MaterialTheme.typography.labelMedium)
                Text(billMoney(overview.remainingMinor), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ComingUpRow(
    item: ComingUp,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(planLabel(item.provider, item.title), style = MaterialTheme.typography.bodyLarge)
            Text(billDate(item.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val amount = billMoney(item.amountMinor, item.currency)
        Text(
            if (item.incoming) stringResource(R.string.bills_incoming, amount) else amount,
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PlanCard(
    p: PlanProgress,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(planLabel(p.plan.provider, p.plan.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(billMoney(p.plan.monthlyAmountMinor, p.plan.currency), style = MaterialTheme.typography.titleMedium)
            }
            PlanProgressLine(p)
        }
    }
}

/** "7 of 18 paid", the bar and the estimated completion month (or "Completed"). */
@Composable
fun PlanProgressLine(p: PlanProgress) {
    val paidText = stringResource(R.string.bills_paid_of, billNumber(p.paid), billNumber(p.total))
    LinearProgressIndicator(
        progress = { p.progressBps / FULL_BPS_F },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = paidText },
    )
    val arabic = LocalMoneyStyle.current.arabic
    val end = p.completionMonth?.atDay(1)?.let { DateFormat.monthShort(it, arabic) + " " + billNumberText(it.year) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(paidText, style = MaterialTheme.typography.bodySmall)
        if (p.plan.status == PLAN_COMPLETED) {
            Text(
                stringResource(R.string.bills_completed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (end != null) {
            Text(stringResource(R.string.bills_ends, end), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val FULL_BPS_F = 10_000f

@Composable
private fun billNumberText(year: Int): String = DateFormat.digitsAs(year.toString(), LocalMoneyStyle.current.digits)

@Composable
private fun EmptyBills(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.bills_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.bills_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bills_example_label), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.bills_example_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.bills_example_line), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { EXAMPLE_PROGRESS }, modifier = Modifier.fillMaxWidth())
            }
        }
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bills_add)) }
    }
}

private const val EXAMPLE_PROGRESS = 0.4f

@Composable
private fun NotificationsHint(onAllow: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bills_notifications_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onAllow,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.bills_notifications_allow)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.calendarItems(
    state: BillsUiState,
    actions: BillsActions,
) {
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { actions.onShiftMonth(-1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_prev_month))
            }
            val arabic = LocalMoneyStyle.current.arabic
            Text(
                DateFormat.monthShort(state.calendarMonth.atDay(1), arabic) + " " + billNumberText(state.calendarMonth.year),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { actions.onShiftMonth(1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_next_month))
            }
        }
    }
    item { CalendarGrid(state.calendarMonth, state.calendar) }
    if (state.calendar.isEmpty()) {
        item { Text(stringResource(R.string.bills_calendar_empty), style = MaterialTheme.typography.bodyMedium) }
    }
    items(state.calendar, key = { "${it.planId}-${it.date}-${it.incoming}" }) { item ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.onOpenPlan(item.planId) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(planLabel(item.provider, item.title), style = MaterialTheme.typography.bodyLarge)
                Text(billDate(item.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val amount = billMoney(item.amountMinor, item.currency)
            Text(if (item.incoming) stringResource(R.string.bills_incoming, amount) else amount, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A month grid (weeks start on Saturday) with a dot on every day something falls due. */
@Composable
fun CalendarGrid(
    month: YearMonth,
    items: List<CalendarItem>,
) {
    val byDay = items.groupBy { it.date.dayOfMonth }
    val first = month.atDay(1)
    val offset = (first.dayOfWeek.value - DayOfWeek.SATURDAY.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { it }
    val digits = LocalMoneyStyle.current.digits
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until DAYS_IN_WEEK) {
                    val day = week.getOrNull(i)
                    val count = day?.let { byDay[it]?.size } ?: 0
                    val description =
                        day?.let {
                            stringResource(
                                R.string.bills_calendar_day,
                                DateFormat.number(it, digits),
                                billNumber(count),
                            )
                        }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { if (description != null) contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(DateFormat.number(day, digits), style = MaterialTheme.typography.bodyMedium)
                                if (count > 0) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DAYS_IN_WEEK = 7
