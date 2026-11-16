package eg.masroof.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.HomeBudgetsCard
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.DeltaChip
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.components.IconTile
import eg.masroof.ui.components.Panel
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SectionLabel
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.components.StopLearningDialog
import eg.masroof.ui.insights.BarChart
import eg.masroof.ui.insights.BarItem
import eg.masroof.ui.insights.TrendBar
import eg.masroof.ui.receipts.rememberCameraLaunch
import eg.masroof.ui.theme.AppIcons
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.extra
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
    val totalBudget by budgetsViewModel.total.collectAsStateWithLifecycle()
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
        totalBudget = totalBudget,
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
    totalBudget: BudgetRowUi? = null,
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
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
            item(key = "tiles") { InsightTiles(state, totalBudget ?: budgets.firstOrNull(), actions) }
        }
    }
}

@Composable
private fun WalletTopBar(actions: WalletActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onOpenSettings, modifier = Modifier.size(TARGET_DP.dp)) {
            Icon(AppIcons.Person, contentDescription = stringResource(R.string.wallet_profile_desc))
        }
        Text(
            stringResource(R.string.wallet_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        IconButton(onClick = actions.onOpenInsights, modifier = Modifier.size(TARGET_DP.dp)) {
            Icon(AppIcons.Bars, contentDescription = stringResource(R.string.wallet_analytics))
        }
    }
}

/** The one loud surface of the app: a dark green panel with the period's spending, its trend and the balance. */
@Composable
private fun HeroCard(
    state: WalletUi,
    actions: WalletActions,
) {
    val figures = state.figures
    val extra = MaterialTheme.extra
    Panel(Modifier.fillMaxWidth(), color = extra.hero) {
        CompositionLocalProvider(LocalContentColor provides extra.onHero) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PeriodPill(state, actions, Modifier.weight(1f, fill = false))
                    AccountPicker(state, actions, Modifier.weight(1f, fill = false))
                }
                // Only the figures that failed are replaced by the error banner above; the pill, picker and balance stay.
                if (figures != null) {
                    SpentBlock(figures, Modifier.padding(top = 4.dp))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = extra.onHero.copy(alpha = HERO_RULE_ALPHA))
                }
                BalanceBlock(state, actions)
            }
        }
    }
}

private const val HERO_RULE_ALPHA = 0.14f
private const val HERO_CHIP_ALPHA = 0.1f

@Composable
private fun SpentBlock(
    figures: WalletFigures,
    modifier: Modifier = Modifier,
) {
    val style = LocalMoneyStyle.current
    val extra = MaterialTheme.extra
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The figure and its six-month trend share a line; on a narrow screen or at a large font the trend wraps under it.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(stringResource(R.string.wallet_spent), style = MaterialTheme.typography.labelLarge, color = extra.onHeroMuted)
                AmountText(
                    Money(figures.spentMinor),
                    style = MaterialTheme.typography.displaySmall,
                    color = extra.onHero,
                    smallUnit = true,
                )
            }
            MiniTrend(figures.trend)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            figures.todayMinor?.let {
                Text(
                    stringResource(R.string.wallet_today, MoneyFormat.format(Money(it), style.arabic, style.digits)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = extra.onHero,
                )
            }
            DeltaChip(figures.deltaBps)
            if (figures.deltaBps != null) {
                Text(stringResource(R.string.home_vs_last_month), style = MaterialTheme.typography.bodySmall, color = extra.onHeroMuted)
            }
        }
    }
}

/** A quiet chip on the dark panel that opens a menu: the text, then a small chevron. */
@Composable
private fun HeroChip(
    text: String,
    onClick: () -> Unit,
) {
    val extra = MaterialTheme.extra
    Box(Modifier.heightIn(min = TARGET_DP.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), Alignment.Center) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(extra.onHero.copy(alpha = HERO_CHIP_ALPHA))
                .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = extra.onHero,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(AppIcons.ChevronDown, contentDescription = null, tint = extra.onHeroMuted, modifier = Modifier.size(16.dp))
        }
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
        HeroChip(range(period)) { open = true }
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
        HeroChip(chosen?.account?.label ?: stringResource(R.string.wallet_all_accounts)) { open = true }
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
    val extra = MaterialTheme.extra
    val style = LocalMoneyStyle.current
    val balance = state.balance
    Column {
        // Label at the start, figure at the end: one line, like a total at the foot of a statement.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.wallet_balance),
                style = MaterialTheme.typography.labelLarge,
                color = extra.onHeroMuted,
                modifier = Modifier.weight(1f),
            )
            if (balance.minor != null) {
                AmountText(Money(balance.minor), style = MaterialTheme.typography.titleLarge, color = extra.onHero)
            }
        }
        if (balance.minor == null || balance.uncounted > 0) {
            val text =
                if (balance.minor == null) {
                    stringResource(R.string.wallet_set_balances)
                } else {
                    pluralStringResource(R.plurals.wallet_uncounted, balance.uncounted, DateFormat.number(balance.uncounted, style.digits))
                }
            HeroLink(text, actions.onOpenAccounts)
        }
        if (state.owedMinor > 0) {
            HeroLink(
                stringResource(R.string.wallet_owe, MoneyFormat.format(Money(state.owedMinor), style.arabic, style.digits)),
                actions.onOpenWealth,
            )
        }
    }
}

/** A line of the dark panel that opens a screen: full-height touch target, a chevron at the end. */
@Composable
private fun HeroLink(
    text: String,
    onClick: () -> Unit,
) {
    val extra = MaterialTheme.extra
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TARGET_DP.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = extra.onHero,
            modifier = Modifier.weight(1f),
        )
        Icon(AppIcons.ChevronEnd, contentDescription = null, tint = extra.onHeroMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun QuickActions(actions: WalletActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(AppIcons.Plus, stringResource(R.string.wallet_action_add), actions.onAdd, Modifier.weight(1f), primary = true)
        QuickAction(AppIcons.Mic, stringResource(R.string.wallet_action_say), actions.onSayIt, Modifier.weight(1f))
        QuickAction(AppIcons.Receipt, stringResource(R.string.wallet_action_receipt), actions.onReceipt, Modifier.weight(1f))
        actions.onAsk?.let {
            QuickAction(
                AppIcons.Chat,
                stringResource(R.string.wallet_action_ask),
                it,
                Modifier.weight(1f),
                description = stringResource(R.string.ask_open),
            )
        }
    }
}

/** One home action: a round icon button with its name under it. Add is the filled one; the rest are quiet. */
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    description: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { description?.let { contentDescription = it } }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(ACTION_DP.dp).clip(CircleShape).background(if (primary) scheme.primary else scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (primary) scheme.onPrimary else scheme.onSurface,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val ACTION_DP = 56

@Composable
private fun ReviewBanner(
    count: Int,
    onOpen: () -> Unit,
) {
    val digits = LocalMoneyStyle.current.digits
    val scheme = MaterialTheme.colorScheme
    Panel(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = TARGET_DP.dp)
            .semantics(mergeDescendants = true) {},
        color = scheme.tertiaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.insights_pending_review, count, DateFormat.number(count, digits)),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.ChevronEnd, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AccountsCarousel(
    state: WalletUi,
    actions: WalletActions,
) {
    val style = LocalMoneyStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.wallet_accounts), Modifier.padding(horizontal = 4.dp))
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
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.wallet_recent),
            style = MaterialTheme.typography.titleLarge,
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
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
        StartCard(
            AppIcons.Inbox,
            stringResource(R.string.wallet_start_capture),
            stringResource(R.string.wallet_start_capture_body),
            actions.onTurnOnCapture,
        )
        StartCard(
            AppIcons.Bank,
            stringResource(R.string.wallet_set_balances),
            stringResource(R.string.wallet_start_balances_body),
            actions.onOpenAccounts,
        )
        StartCard(
            AppIcons.Calendar,
            stringResource(R.string.wallet_start_bill),
            stringResource(R.string.wallet_start_bill_body),
            actions.onAddBill,
        )
    }
}

@Composable
private fun StartCard(
    icon: ImageVector,
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
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                AppIcons.ChevronEnd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The last months of spending as small bars (from the same rows as the figures above). */
@Composable
private fun MiniTrend(trend: List<TrendBar>) {
    val style = LocalMoneyStyle.current
    if (trend.none { it.total.amountMinor > 0 }) return
    val extra = MaterialTheme.extra
    val description =
        stringResource(
            R.string.home_trend_desc,
            trend.joinToString(", ") {
                DateFormat.monthShort(it.start, style.arabic) + " " +
                    MoneyFormat.format(it.total, style.arabic, style.digits)
            },
        )
    Column(Modifier.width(TREND_WIDTH_DP.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BarChart(
            items = trend.map { BarItem(DateFormat.monthShort(it.start, style.arabic), it.total.amountMinor, it.selected) },
            description = description,
            height = 40.dp,
            showLabels = false,
            barColor = extra.onHero,
            restColor = extra.onHero.copy(alpha = TREND_REST_ALPHA),
            gap = 5.dp,
        )
        Text(
            stringResource(R.string.home_trend_title),
            style = MaterialTheme.typography.labelSmall,
            color = extra.onHeroMuted,
            maxLines = 1,
        )
    }
}

private const val TREND_REST_ALPHA = 0.28f
private const val TREND_WIDTH_DP = 88
