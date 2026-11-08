package eg.masroof.ui.bills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.PaydaySource
import eg.masroof.data.bills.PriceNotice
import eg.masroof.data.bills.SubscriptionService
import eg.masroof.data.bills.Suggestion
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.theme.LocalMoneyStyle

/** Which part of the money-life screen shows: installments are the existing Bills content. */
enum class BillsTab { INSTALLMENTS, SUBSCRIPTIONS, COMING_IN }

class SubscriptionsActions(
    val onAdd: () -> Unit,
    val onOpen: (String) -> Unit,
    val onShiftMonth: (Int) -> Unit,
    val onAddSuggestion: (Suggestion) -> Unit,
    val onDismissSuggestion: (String) -> Unit,
    val onDismissNotice: (String) -> Unit,
)

@Composable
fun SubscriptionsRoute(
    section: BillsTab,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onAddSuggestion: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SubscriptionsContent(
        state,
        section,
        SubscriptionsActions(onAdd, onOpen, viewModel::shiftMonth, onAddSuggestion, viewModel::dismissSuggestion, viewModel::dismissNotice),
        modifier,
    )
}

/** The localized display name of a built-in service. */
@Composable
fun serviceName(service: SubscriptionService): String =
    stringResource(
        when (service) {
            SubscriptionService.NETFLIX -> R.string.subs_service_netflix
            SubscriptionService.SHAHID -> R.string.subs_service_shahid
            SubscriptionService.WATCH_IT -> R.string.subs_service_watch_it
            SubscriptionService.OSN -> R.string.subs_service_osn
            SubscriptionService.SPOTIFY -> R.string.subs_service_spotify
            SubscriptionService.ANGHAMI -> R.string.subs_service_anghami
            SubscriptionService.YOUTUBE -> R.string.subs_service_youtube
            SubscriptionService.ICLOUD -> R.string.subs_service_icloud
            SubscriptionService.GOOGLE_ONE -> R.string.subs_service_google_one
            SubscriptionService.CHATGPT -> R.string.subs_service_chatgpt
            SubscriptionService.WE -> R.string.subs_service_we
            SubscriptionService.VODAFONE -> R.string.subs_service_vodafone
            SubscriptionService.ORANGE -> R.string.subs_service_orange
            SubscriptionService.ETISALAT -> R.string.subs_service_etisalat
            SubscriptionService.GYM -> R.string.subs_service_gym
            SubscriptionService.OTHER -> R.string.subs_service_other
        },
    )

@Composable
fun cadenceLabel(cadence: Cadence): String =
    stringResource(
        when (cadence) {
            Cadence.WEEKLY -> R.string.subs_cadence_weekly
            Cadence.MONTHLY -> R.string.subs_cadence_monthly
            Cadence.QUARTERLY -> R.string.subs_cadence_quarterly
            Cadence.YEARLY -> R.string.subs_cadence_yearly
        },
    )

/** "in 3 days", "tomorrow", "today" or "2 days late". */
@Composable
fun renewalIn(days: Int): String =
    when {
        days < 0 -> stringResource(R.string.subs_days_late, billNumber(-days))
        days == 0 -> stringResource(R.string.subs_today)
        days == 1 -> stringResource(R.string.subs_tomorrow)
        else -> stringResource(R.string.subs_in_days, billNumber(days))
    }

@Composable
fun SubscriptionsContent(
    state: SubscriptionsUiState,
    section: BillsTab,
    actions: SubscriptionsActions,
    modifier: Modifier = Modifier,
) {
    val income = section == BillsTab.COMING_IN
    var calendar by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (income) R.string.subs_income_title else R.string.subs_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Button(onClick = actions.onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (income) R.string.subs_add_income else R.string.subs_add))
                }
            }
        }
        if (!state.loaded) {
            item { SkeletonList(rows = 3) }
        } else if (income) {
            incomeItems(state, actions)
        } else {
            subscriptionItems(state, actions, calendar) { calendar = it }
        }
    }
}

private fun LazyListScope.incomeItems(
    state: SubscriptionsUiState,
    actions: SubscriptionsActions,
) {
    state.payday?.let { payday ->
        item {
            SectionCard(stringResource(R.string.subs_payday_title)) {
                Text(
                    when (payday.days) {
                        0 -> stringResource(R.string.subs_payday_today)
                        1 -> stringResource(R.string.subs_payday_tomorrow)
                        else -> stringResource(R.string.subs_payday_days, billNumber(payday.days))
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    if (payday.source == PaydaySource.INCOME) {
                        stringResource(R.string.subs_payday_from, payday.name.orEmpty(), billDate(payday.date))
                    } else {
                        stringResource(R.string.subs_payday_month_start, billDate(payday.date))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.income.isEmpty()) {
        item { EmptyState(R.string.subs_income_empty_title, R.string.subs_income_empty_body, actions.onAdd, R.string.subs_add_income) }
    } else {
        items(state.income, key = { it.row.id }) { SubscriptionRow(it, onClick = { actions.onOpen(it.row.id) }) }
    }
}

private fun LazyListScope.subscriptionItems(
    state: SubscriptionsUiState,
    actions: SubscriptionsActions,
    calendar: Boolean,
    setCalendar: (Boolean) -> Unit,
) {
    items(state.notices, key = { "notice-${it.subscriptionId}" }) { PriceNoticeCard(it, actions.onDismissNotice) }
    items(state.suggestions, key = { "suggest-${it.key}" }) { SuggestionCard(it, actions) }
    if (state.subscriptions.isEmpty()) {
        item { EmptyState(R.string.subs_empty_title, R.string.subs_empty_body, actions.onAdd, R.string.subs_add) }
        return
    }
    item { TotalsCard(state) }
    if (state.totals.hasOtherCurrency) {
        item {
            Text(
                stringResource(R.string.bills_other_currency_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !calendar,
                onClick = { setCalendar(false) },
                label = { Text(stringResource(R.string.bills_view_list)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = calendar,
                onClick = { setCalendar(true) },
                label = { Text(stringResource(R.string.bills_view_calendar)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    if (calendar) {
        calendarSection(state, actions)
    } else {
        items(state.subscriptions, key = { it.row.id }) { SubscriptionRow(it, onClick = { actions.onOpen(it.row.id) }) }
    }
}

@Composable
private fun TotalsCard(state: SubscriptionsUiState) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    SectionCard(stringResource(R.string.subs_totals_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !yearly,
                onClick = { yearly = false },
                label = { Text(stringResource(R.string.subs_per_month)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = yearly,
                onClick = { yearly = true },
                label = { Text(stringResource(R.string.subs_per_year)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        Text(
            billMoney(if (yearly) state.totals.perYearMinor else state.totals.perMonthMinor),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(stringResource(R.string.subs_active_count, billNumber(state.totals.activeCount)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubscriptionRow(
    item: SubItem,
    onClick: () -> Unit,
) {
    val row = item.row
    val service = SubscriptionService.entries.firstOrNull { it.merchant != null && it.merchant == row.merchantKey }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (service?.emoji?.plus(" ") ?: "") + row.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val renews =
                if (row.active) {
                    stringResource(R.string.subs_renews, billDate(item.date), renewalIn(item.daysUntil))
                } else {
                    stringResource(R.string.subs_paused)
                }
            Text(renews, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.lastPaid?.let {
                Text(
                    stringResource(R.string.subs_paid_on, billDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(billMoney(row.amountMinor, row.currency), style = MaterialTheme.typography.bodyLarge)
            Text(cadenceLabel(Cadence.of(row.cadence)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PriceNoticeCard(
    notice: PriceNotice,
    onDismiss: (String) -> Unit,
) {
    val up = notice.toMinor > notice.fromMinor
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (up) R.string.subs_price_up else R.string.subs_price_down,
                    notice.name,
                    billMoney(notice.fromMinor, notice.currency),
                    billMoney(notice.toMinor, notice.currency),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onDismiss(notice.subscriptionId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.subs_got_it))
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    actions: SubscriptionsActions,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.subs_suggestion, suggestion.name, billMoney(suggestion.amountMinor, suggestion.currency)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onAddSuggestion(suggestion) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.subs_suggestion_add))
                }
                OutlinedButton(onClick = { actions.onDismissSuggestion(suggestion.key) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.subs_suggestion_no))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: Int,
    body: Int,
    onAdd: () -> Unit,
    addLabel: Int,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(addLabel)) }
    }
}

private fun LazyListScope.calendarSection(
    state: SubscriptionsUiState,
    actions: SubscriptionsActions,
) {
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { actions.onShiftMonth(-1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_prev_month))
            }
            val arabic = LocalMoneyStyle.current.arabic
            Text(
                DateFormat.monthShort(state.calendarMonth.atDay(1), arabic) + " " +
                    DateFormat.digitsAs(state.calendarMonth.year.toString(), LocalMoneyStyle.current.digits),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { actions.onShiftMonth(1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_next_month))
            }
        }
    }
    item { CalendarGrid(state.calendarMonth, state.calendar) }
    if (state.calendar.isEmpty()) {
        item { Text(stringResource(R.string.subs_calendar_empty), style = MaterialTheme.typography.bodyMedium) }
    }
    items(state.calendar, key = { "${it.planId}-${it.date}" }) { item ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.onOpen(item.planId) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(billDate(item.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val amount = billMoney(item.amountMinor, item.currency)
            Text(if (item.incoming) stringResource(R.string.bills_incoming, amount) else amount, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
