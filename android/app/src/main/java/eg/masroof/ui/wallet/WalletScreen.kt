package eg.masroof.ui.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.data.wallet.ComingUpItem
import eg.masroof.data.wallet.ComingUpKind
import eg.masroof.ui.answers.AnswerCardsSection
import eg.masroof.ui.answers.AnswerCardsViewModel
import eg.masroof.ui.ask.AskIcon
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.HomeBudgetsCard
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.DeltaChip
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.components.StopLearningDialog
import eg.masroof.ui.insights.BarChart
import eg.masroof.ui.insights.BarItem
import eg.masroof.ui.insights.InsightsIcon
import eg.masroof.ui.insights.TrendBar
import eg.masroof.ui.receipts.rememberCameraLaunch
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.CapturedViewModel
import eg.masroof.ui.viewmodel.HomeBudgetsViewModel
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.viewmodel.ReceiptListViewModel

private const val TARGET_DP = 48

/** Everything Wallet can open or do. The NavHost fills it; each field has a harmless default so a preview or test can omit it. */
@Suppress("LongParameterList")
class WalletActions(
    val onOpenSettings: () -> Unit = {},
    val onOpenInsights: () -> Unit = {},
    val onOpenTransactions: () -> Unit = {},
    val onOpenExpense: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onSayIt: () -> Unit = {},
    val onReceipt: () -> Unit = {},
    /** Null hides the Ask action (no Ask screen to open). */
    val onAsk: (() -> Unit)? = null,
    val onOpenAccounts: () -> Unit = {},
    val onOpenWealth: () -> Unit = {},
    val onOpenBills: () -> Unit = {},
    val onOpenBudgets: () -> Unit = {},
    val onOpenGoals: () -> Unit = {},
    val onTurnOnCapture: () -> Unit = {},
    val onAddBill: () -> Unit = {},
    val onSelectPeriod: (String?) -> Unit = {},
    val onSelectAccount: (String?) -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun WalletRoute(
    actions: WalletActions,
    onOpenReceipts: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
    budgetsViewModel: HomeBudgetsViewModel = hiltViewModel(),
    captured: CapturedViewModel = hiltViewModel(),
    receipts: ReceiptListViewModel = hiltViewModel(),
    answerCards: AnswerCardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cards by answerCards.cards.collectAsStateWithLifecycle()
    val answerAccounts by answerCards.accounts.collectAsStateWithLifecycle()
    val answerUndo by answerCards.undo.collectAsStateWithLifecycle()
    val refundPick by answerCards.pick.collectAsStateWithLifecycle()
    val budgets by budgetsViewModel.state.collectAsStateWithLifecycle()
    val capturedIds by captured.byExpenseId.collectAsStateWithLifecycle()
    val stored by receipts.state.collectAsStateWithLifecycle()
    val camera = rememberCameraLaunch { file -> receipts.add(after = { file.delete() }) { file.inputStream() } }
    // A receipt stored from here is a new row: open the Receipts list (the same hand-off as "Receipt only" on Add).
    var seen by remember { mutableStateOf(-1) }
    LaunchedEffect(stored.receipts.size, stored.loaded) {
        if (stored.loaded) {
            if (seen >= 0 && stored.receipts.size > seen) onOpenReceipts()
            seen = stored.receipts.size
        }
    }
    WalletContent(
        state = state,
        budgets = budgets,
        actions =
            WalletActions(
                onOpenSettings = actions.onOpenSettings,
                onOpenInsights = actions.onOpenInsights,
                onOpenTransactions = actions.onOpenTransactions,
                onOpenExpense = actions.onOpenExpense,
                onAdd = actions.onAdd,
                onSayIt = actions.onSayIt,
                onReceipt = camera ?: onOpenReceipts,
                onAsk = actions.onAsk,
                onOpenAccounts = actions.onOpenAccounts,
                onOpenWealth = actions.onOpenWealth,
                onOpenBills = actions.onOpenBills,
                onOpenBudgets = actions.onOpenBudgets,
                onOpenGoals = actions.onOpenGoals,
                onTurnOnCapture = actions.onTurnOnCapture,
                onAddBill = actions.onAddBill,
                onSelectPeriod = viewModel::selectPeriod,
                onSelectAccount = viewModel::selectAccount,
                onRetry = viewModel::retry,
            ),
        capturedIds = capturedIds,
        onUndoCaptured = captured::undo,
        answers =
            if (cards.isEmpty() && answerUndo == null && refundPick == null) {
                null
            } else {
                { AnswerCardsSection(answerCards, cards, answerAccounts, hasUndo = answerUndo != null, pick = refundPick) }
            },
    )
    StopLearningDialog(captured)
}

@Composable
fun WalletContent(
    state: WalletUi,
    budgets: List<BudgetRowUi>,
    actions: WalletActions,
    modifier: Modifier = Modifier,
    capturedIds: Map<String, String> = emptyMap(),
    onUndoCaptured: (String) -> Unit = {},
    /** The "Needs an answer" row (STORY-208); null = nothing to ask, so no row and no gap. */
    answers: (@Composable () -> Unit)? = null,
) {
    // rememberLazyListState is saved: the tab keeps its scroll position when the user switches tabs and comes back.
    val listState = rememberLazyListState()
    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "bar") { WalletTopBar(actions) }
        if (!state.loaded) {
            item(key = "loading") { SkeletonList() }
            return@LazyColumn
        }
        // Local data can still fail to load (or a stored amount can be unusable): say so with Retry, and keep the rest.
        if (state.status != LoadStatus.READY) {
            item(key = "status") { StatusBanner(state.status, actions.onRetry) }
        }
        item(key = "hero") { HeroCard(state, actions) }
        item(key = "actions") { QuickActions(actions) }
        val pending = state.figures?.pendingReview ?: 0
        if (pending > 0) {
            item(key = "review") { ReviewBanner(pending, actions.onOpenInsights) }
        }
        if (answers != null) item(key = "answers") { answers() }
        if (budgets.isNotEmpty()) {
            item(key = "budgets") { HomeBudgetsCard(budgets, actions.onOpenBudgets) }
        }
        if (state.accounts.isNotEmpty()) {
            item(key = "accounts") { AccountsCarousel(state, actions) }
        }
        item(key = "coming") { ComingUpCard(state.comingUp, actions) }
        item(key = "recent-head") { RecentHeader(actions.onOpenTransactions) }
        if (state.recent.isEmpty()) {
            item(key = "recent-empty") {
                Text(
                    stringResource(R.string.empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.recent, key = { "recent-${it.id}" }) {
            ExpenseListItem(it, capturedLocalId = capturedIds[it.id], onUndoCaptured = onUndoCaptured, onOpen = actions.onOpenExpense)
        }
        if (state.firstRun) {
            item(key = "start") { GetStartedCards(actions) }
        } else {
            item(key = "tiles") { InsightTiles(state, budgets.firstOrNull(), actions) }
        }
    }
}

@Composable
private fun WalletTopBar(actions: WalletActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onOpenSettings, modifier = Modifier.size(TARGET_DP.dp)) {
            Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.wallet_profile_desc))
        }
        Text(
            stringResource(R.string.wallet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        IconButton(onClick = actions.onOpenInsights, modifier = Modifier.size(TARGET_DP.dp)) {
            Icon(InsightsIcon, contentDescription = stringResource(R.string.wallet_analytics))
        }
    }
}

@Composable
private fun HeroCard(
    state: WalletUi,
    actions: WalletActions,
) {
    val figures = state.figures
    val style = LocalMoneyStyle.current
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeriodPill(state, actions, Modifier.weight(1f, fill = false))
                AccountPicker(state, actions, Modifier.weight(1f, fill = false))
            }
            // Only the figures that failed are replaced by the error banner above; the pill, picker and balance stay.
            if (figures != null) SpentBlock(figures)
            BalanceBlock(state, actions)
        }
    }
}

@Composable
private fun SpentBlock(figures: WalletFigures) {
    val style = LocalMoneyStyle.current
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wallet_spent), style = MaterialTheme.typography.labelLarge, color = onContainer)
        AmountText(Money(figures.spentMinor), style = MaterialTheme.typography.displaySmall, color = onContainer)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            figures.todayMinor?.let {
                Text(
                    stringResource(R.string.wallet_today, MoneyFormat.format(Money(it), style.arabic, style.digits)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = onContainer,
                )
            }
            DeltaChip(figures.deltaBps)
            if (figures.deltaBps != null) {
                Text(stringResource(R.string.home_vs_last_month), style = MaterialTheme.typography.bodySmall, color = onContainer)
            }
        }
        MiniTrend(figures.trend)
    }
}

@Composable
private fun PeriodPill(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier = Modifier,
) {
    val period = state.figures?.period ?: state.period ?: return
    val style = LocalMoneyStyle.current
    var open by remember { mutableStateOf(false) }

    @Composable
    fun range(p: AnalyticsPeriod) =
        stringResource(
            R.string.wallet_period_range,
            DateFormat.shortDate(p.from, style.arabic, style.digits),
            DateFormat.shortDate(p.to, style.arabic, style.digits),
        )
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
            Text(range(period), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.choices.forEachIndexed { index, p ->
                DropdownMenuItem(
                    text = { Text(range(p)) },
                    onClick = {
                        open = false
                        actions.onSelectPeriod(if (index == 0) null else p.label)
                    },
                    modifier = Modifier.heightIn(min = TARGET_DP.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountPicker(
    state: WalletUi,
    actions: WalletActions,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = state.accounts.firstOrNull { it.account.id == state.selectedAccountId }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
            Text(
                chosen?.account?.label ?: stringResource(R.string.wallet_all_accounts),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wallet_all_accounts)) },
                onClick = {
                    open = false
                    actions.onSelectAccount(null)
                },
                modifier = Modifier.heightIn(min = TARGET_DP.dp),
            )
            state.accounts.filter { it.account.currency == "EGP" }.forEach { view ->
                DropdownMenuItem(
                    text = { Text(listOfNotNull(view.account.emoji, view.account.label).joinToString(" ")) },
                    onClick = {
                        open = false
                        actions.onSelectAccount(view.account.id)
                    },
                    modifier = Modifier.heightIn(min = TARGET_DP.dp),
                )
            }
        }
    }
}

@Composable
private fun BalanceBlock(
    state: WalletUi,
    actions: WalletActions,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val style = LocalMoneyStyle.current
    val balance = state.balance
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wallet_balance), style = MaterialTheme.typography.labelLarge, color = onContainer)
        if (balance.minor != null) {
            AmountText(Money(balance.minor), style = MaterialTheme.typography.titleLarge, color = onContainer)
        }
        if (balance.minor == null || balance.uncounted > 0) {
            val text =
                if (balance.minor == null) {
                    stringResource(R.string.wallet_set_balances)
                } else {
                    pluralStringResource(R.plurals.wallet_uncounted, balance.uncounted, DateFormat.number(balance.uncounted, style.digits))
                }
            TextButton(onClick = actions.onOpenAccounts, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                Text(text, color = onContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.owedMinor > 0) {
            Text(
                stringResource(R.string.wallet_owe, MoneyFormat.format(Money(state.owedMinor), style.arabic, style.digits)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = onContainer,
                modifier =
                    Modifier
                        .heightIn(
                            min = TARGET_DP.dp,
                        ).clickable(role = Role.Button, onClick = actions.onOpenWealth)
                        .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun QuickActions(actions: WalletActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("＋", stringResource(R.string.wallet_action_add), actions.onAdd, Modifier.weight(1f))
        QuickAction("🎙", stringResource(R.string.wallet_action_say), actions.onSayIt, Modifier.weight(1f))
        QuickAction("🧾", stringResource(R.string.wallet_action_receipt), actions.onReceipt, Modifier.weight(1f))
        actions.onAsk?.let {
            QuickAction(
                null,
                stringResource(R.string.wallet_action_ask),
                it,
                Modifier.weight(1f),
                AskIcon,
                stringResource(R.string.ask_open),
            )
        }
    }
}

@Composable
private fun QuickAction(
    glyph: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
) {
    Card(
        modifier
            .heightIn(min = TARGET_DP.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { description?.let { contentDescription = it } },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(glyph.orEmpty(), style = MaterialTheme.typography.titleLarge)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            .heightIn(min = TARGET_DP.dp)
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

@Composable
private fun AccountsCarousel(
    state: WalletUi,
    actions: WalletActions,
) {
    val style = LocalMoneyStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_accounts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.accounts, key = { it.account.id }) { view ->
                val balance = view.balanceMinor
                val name = listOfNotNull(view.account.emoji, view.account.label).joinToString(" ")
                val shown =
                    if (balance == null) {
                        stringResource(R.string.wallet_set_balance)
                    } else {
                        MoneyFormat.format(
                            Money(if (view.isCreditCard) -balance else balance, view.account.currency),
                            style.arabic,
                            style.digits,
                        )
                    }
                Card(
                    Modifier
                        .width(160.dp)
                        .heightIn(min = TARGET_DP.dp)
                        .clickable(role = Role.Button, onClick = actions.onOpenAccounts)
                        .semantics(mergeDescendants = true) { contentDescription = "$name, $shown" },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            shown,
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = if (balance == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComingUpCard(
    items: List<ComingUpItem>,
    actions: WalletActions,
) {
    val style = LocalMoneyStyle.current
    SectionCard(stringResource(R.string.wallet_coming_up)) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.wallet_coming_up_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.forEach { item ->
            val kind =
                when (item.kind) {
                    ComingUpKind.INSTALLMENT -> R.string.wallet_coming_installment
                    ComingUpKind.SUBSCRIPTION -> R.string.wallet_coming_subscription
                    ComingUpKind.PAYOUT -> R.string.wallet_coming_payout
                    ComingUpKind.PAYDAY -> R.string.wallet_coming_payday
                }
            Row(
                Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp).clickable(role = Role.Button, onClick = actions.onOpenBills),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${stringResource(kind)} · ${DateFormat.shortDate(item.date, style.arabic, style.digits)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(
                    item.money,
                    color = if (item.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecentHeader(onViewAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.wallet_recent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        TextButton(onClick = onViewAll, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(stringResource(R.string.wallet_view_all)) }
    }
}

@Composable
internal fun GetStartedCards(actions: WalletActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_start_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        StartCard(
            "📥",
            stringResource(R.string.wallet_start_capture),
            stringResource(R.string.wallet_start_capture_body),
            actions.onTurnOnCapture,
        )
        StartCard(
            "🏦",
            stringResource(R.string.wallet_set_balances),
            stringResource(R.string.wallet_start_balances_body),
            actions.onOpenAccounts,
        )
        StartCard("🗓", stringResource(R.string.wallet_start_bill), stringResource(R.string.wallet_start_bill_body), actions.onAddBill)
    }
}

@Composable
private fun StartCard(
    glyph: String,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = TARGET_DP.dp,
            ).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The last months of spending as small bars (from the same rows as the figures above). */
@Composable
private fun MiniTrend(trend: List<TrendBar>) {
    val style = LocalMoneyStyle.current
    if (trend.none { it.total.amountMinor > 0 }) return
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val description =
        stringResource(
            R.string.home_trend_desc,
            trend.joinToString(", ") {
                DateFormat.monthShort(it.start, style.arabic) + " " +
                    MoneyFormat.format(it.total, style.arabic, style.digits)
            },
        )
    BarChart(
        items = trend.map { BarItem(DateFormat.monthShort(it.start, style.arabic), it.total.amountMinor, it.selected) },
        description = description,
        height = 72.dp,
        showLabels = false,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(stringResource(R.string.home_trend_title), style = MaterialTheme.typography.labelSmall, color = onContainer)
}
