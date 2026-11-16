package eg.masroof.ui.wealth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.PercentFormat
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.data.wealth.AssetBucket
import eg.masroof.data.wealth.AssetValuation
import eg.masroof.data.wealth.AssetView
import eg.masroof.data.wealth.ChartRange
import eg.masroof.data.wealth.DebtLine
import eg.masroof.data.wealth.DebtSource
import eg.masroof.data.wealth.MetalMath
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.NetWorthHistory
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.Panel
import eg.masroof.ui.components.SectionLabel
import eg.masroof.ui.theme.AppIcons
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.extra

@Composable
fun WealthRoute(
    onOpenAccounts: () -> Unit,
    viewModel: WealthViewModel = hiltViewModel(),
    metalViewModel: MetalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A gold or silver holding opens in place (no new route); system Back returns to the list.
    var openMetal by rememberSaveable { mutableStateOf<String?>(null) }
    val metalId = openMetal
    if (metalId != null) {
        androidx.activity.compose.BackHandler { openMetal = null }
        MetalDetailRoute(metalId, onBack = { openMetal = null }, viewModel = metalViewModel)
        return
    }
    WealthContent(
        state,
        WealthActions(
            onAddMetal = { holding, lot, done -> metalViewModel.create(holding, lot, done) },
            onOpenMetal = { openMetal = it },
            onOpenAccounts = onOpenAccounts,
            onAddAsset = { viewModel.addAsset(it) },
            onAddDebt = { viewModel.addDebt(it) },
            onSettleDebt = { viewModel.settle(it) },
            onDeleteDebt = { viewModel.deleteDebt(it) },
            onHideAsset = { asset, hidden -> viewModel.hide(asset, hidden) },
            onDeleteAsset = { viewModel.deleteAsset(it) },
        ),
    )
}

@Composable
fun WealthContent(
    state: WealthUiState,
    actions: WealthActions,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) {
        AddWealthDialog(
            onDismiss = { adding = false },
            onAsset = {
                actions.onAddAsset(it)
                adding = false
            },
            today = state.today,
            onMetal = { holding, lot ->
                actions.onAddMetal(holding, lot) { out ->
                    if (out is MetalOutcome.Saved) {
                        adding = false
                        actions.onOpenMetal(out.id)
                    }
                }
            },
            onDebt = {
                actions.onAddDebt(it)
                adding = false
            },
        )
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.wealth_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onOpenAccounts, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.wealth_open_accounts))
            }
            Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wealth_add)) }
        }
        if (state.loaded && !state.netWorth.hasData) {
            MessageState(
                stringResource(R.string.wealth_empty_title),
                stringResource(R.string.wealth_empty_body),
                Modifier.height(280.dp),
                AppIcons.Info,
            )
            return@Column
        }
        val titles = listOf(R.string.wealth_tab_net, R.string.wealth_tab_assets, R.string.wealth_tab_debt)
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(12.dp))
                }
            }
        }
        when (tab) {
            0 -> NetWorthTab(state)
            1 -> AssetsTab(state, actions)
            else -> DebtTab(state, actions)
        }
        Hints(state.netWorth, state.unmatchedEntries)
    }
}

@Composable
private fun Hints(
    net: NetWorth,
    unmatched: Int,
) {
    if (unmatched > 0) {
        val n = DateFormat.number(unmatched, LocalMoneyStyle.current.digits)
        Text(
            pluralStringResource(R.plurals.wealth_unmatched, unmatched, n),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (net.uncountedAccounts > 0) {
        val n = DateFormat.number(net.uncountedAccounts, LocalMoneyStyle.current.digits)
        Text(
            pluralStringResource(R.plurals.wealth_uncounted, net.uncountedAccounts, n),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (net.otherCurrencyItems > 0) {
        val n = DateFormat.number(net.otherCurrencyItems, LocalMoneyStyle.current.digits)
        Text(
            pluralStringResource(R.plurals.wealth_other_currency, net.otherCurrencyItems, n),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A section heading on the page, above rows that sit flat on it. */
@Composable
private fun PageHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun RowRule() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NetWorthTab(state: WealthUiState) {
    val net = state.netWorth
    var range by rememberSaveable { mutableStateOf(ChartRange.MONTH_3) }
    NetWorthHero(state)
    PageHeading(stringResource(R.string.wealth_chart_desc))
    val labels =
        listOf(
            ChartRange.MONTH_1 to R.string.wealth_range_1m,
            ChartRange.MONTH_3 to R.string.wealth_range_3m,
            ChartRange.YEAR_1 to R.string.wealth_range_1y,
            ChartRange.ALL to R.string.wealth_range_all,
        )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (r, label) ->
            FilterChip(
                selected = range == r,
                onClick = { range = r },
                label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    val history = NetWorthHistory.points(state.snapshots, range, state.today)
    val current =
        NetWorthSnapshotEntity("current", state.today.toString(), net.assetsMinor, net.debtsMinor, net.netMinor, Money.EGP, "", 0)
    val rangeLabel = stringResource(labels.first { it.first == range }.second)
    val shown =
        when {
            history.isEmpty() -> listOf(current)
            history.size == 1 && history[0].cairoDate != current.cairoDate -> history + current
            else -> history
        }
    NetWorthChart(shown, rangeLabel)
}

private const val HERO_RULE_ALPHA = 0.14f

/** The dark panel of this tab: net worth as the one big figure, what makes it up, and the change this year. */
@Composable
private fun NetWorthHero(state: WealthUiState) {
    val net = state.netWorth
    val extra = MaterialTheme.extra
    Panel(Modifier.fillMaxWidth(), color = extra.hero) {
        CompositionLocalProvider(LocalContentColor provides extra.onHero) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.wealth_net_worth),
                        style = MaterialTheme.typography.labelLarge,
                        color = extra.onHeroMuted,
                        modifier = Modifier.semantics { heading() },
                    )
                    AmountText(Money(net.netMinor), style = MaterialTheme.typography.displaySmall, color = extra.onHero, smallUnit = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HeroStat(R.string.wealth_assets_total, net.assetsMinor, Modifier.weight(1f))
                    HeroStat(R.string.wealth_debt_total, net.debtsMinor, Modifier.weight(1f))
                }
                YearChange(state)
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: Int,
    minor: Long,
    modifier: Modifier = Modifier,
) {
    val extra = MaterialTheme.extra
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
        AmountText(Money(minor), style = MaterialTheme.typography.titleMedium, color = extra.onHero)
    }
}

/** The change since the start of the year, under a hairline; nothing when there is no earlier snapshot to compare with. */
@Composable
private fun YearChange(state: WealthUiState) {
    val net = state.netWorth
    val style = LocalMoneyStyle.current
    val extra = MaterialTheme.extra
    val change = NetWorthHistory.yearChange(state.snapshots, net.netMinor, state.today) ?: return
    val abs = moneyText(change.absoluteMinor).let { if (change.absoluteMinor > 0) "+$it" else it }
    val text =
        change.percentBps?.let {
            val plain = PercentFormat.format(kotlin.math.abs(it), style.arabic, style.digits)
            val pct = if (change.percentCapped) stringResource(R.string.wealth_change_capped, plain) else plain
            stringResource(
                R.string.wealth_change_year_percent,
                abs,
                if (it > 0) {
                    "+$pct"
                } else if (it < 0) {
                    "-$pct"
                } else {
                    pct
                },
            )
        } ?: abs
    HorizontalDivider(color = extra.onHero.copy(alpha = HERO_RULE_ALPHA))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.wealth_change_year), style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = extra.onHero)
    }
}

private const val RAMP_STRONG = 0.72f
private const val RAMP_MID = 0.5f
private const val RAMP_SOFT = 0.32f

/**
 * One tone per [AssetBucket], by ordinal: a green ramp for money and paper, quiet tones for things owned and amber for
 * gold and silver. All from the theme, so the bar follows light and dark.
 */
@Composable
private fun bucketColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primary,
        scheme.primary.copy(alpha = RAMP_STRONG),
        scheme.primary.copy(alpha = RAMP_MID),
        scheme.primary.copy(alpha = RAMP_SOFT),
        scheme.secondary,
        scheme.outline,
        scheme.tertiary,
    )
}

private fun bucketLabel(bucket: AssetBucket): Int =
    when (bucket) {
        AssetBucket.SPENDABLE -> R.string.wealth_bucket_spendable
        AssetBucket.CERTIFICATE -> R.string.wealth_bucket_certificate
        AssetBucket.FUND -> R.string.wealth_bucket_fund
        AssetBucket.PROPERTY -> R.string.wealth_bucket_property
        AssetBucket.VEHICLE -> R.string.wealth_bucket_vehicle
        AssetBucket.OTHER -> R.string.wealth_bucket_other
        AssetBucket.METAL -> R.string.gold_bucket
    }

private fun kindLabel(kind: String): Int =
    when (kind) {
        "PROPERTY" -> R.string.wealth_kind_property
        "VEHICLE" -> R.string.wealth_kind_vehicle
        "BUSINESS" -> R.string.wealth_kind_business
        "CERTIFICATE" -> R.string.wealth_kind_certificate
        "FUND" -> R.string.wealth_kind_fund
        "GOLD" -> R.string.wealth_kind_gold
        "SILVER" -> R.string.wealth_kind_silver
        "INVESTMENT" -> R.string.wealth_kind_investment
        else -> R.string.wealth_kind_other
    }

/** The summary panel of the Assets and Debt tabs: a quiet label, the total, then anything that explains it. */
@Composable
private fun TotalPanel(
    label: Int,
    minor: Long,
    content: @Composable () -> Unit = {},
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(stringResource(label))
                AmountText(Money(minor), style = MaterialTheme.typography.headlineMedium, smallUnit = true)
            }
            content()
        }
    }
}

@Composable
private fun AssetsTab(
    state: WealthUiState,
    actions: WealthActions,
) {
    val style = LocalMoneyStyle.current
    val shown = state.netWorth.buckets.filter { it.amountMinor > 0 }
    val colors = bucketColors()
    TotalPanel(R.string.wealth_assets_total, state.netWorth.assetsMinor) {
        if (shown.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { b ->
                    Box(
                        Modifier
                            .weight(b.shareBps.coerceAtLeast(1).toFloat())
                            .height(10.dp)
                            .background(colors[b.bucket.ordinal % colors.size]),
                    )
                }
            }
            Column {
                shown.forEach { b ->
                    val label = stringResource(bucketLabel(b.bucket))
                    val amount = moneyText(b.amountMinor)
                    val pct = PercentFormat.format(b.shareBps, style.arabic, style.digits)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors[b.bucket.ordinal % colors.size]))
                        Text(
                            stringResource(R.string.wealth_bucket_row, label, "$amount ($pct)"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    PageHeading(stringResource(R.string.wealth_assets_list))
    if (state.assets.isEmpty()) {
        Text(
            stringResource(R.string.wealth_assets_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    state.assets.forEachIndexed { index, asset ->
        if (index > 0) RowRule()
        AssetRow(asset, actions, style.arabic, style.digits, state.today)
    }
}

@Composable
private fun AssetRow(
    view: AssetView,
    actions: WealthActions,
    arabic: Boolean,
    digits: eg.masroof.core.DigitStyle,
    today: java.time.LocalDate,
) {
    val asset = view.asset
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${asset.name} - ${stringResource(kindLabel(asset.kind))}", style = MaterialTheme.typography.titleMedium)
                val updated = DateFormat.shortDate(CairoTime.dateOf(asset.updatedAtMillis), arabic, digits)
                Text(stringResource(R.string.wealth_asset_updated, updated), style = MaterialTheme.typography.bodySmall, color = quiet)
                if (asset.kind == "CERTIFICATE") {
                    val matured = AssetValuation.isMatured(asset, today, CairoTime::dateOf)
                    Text(
                        stringResource(if (matured) R.string.wealth_cert_matured else R.string.wealth_cert_monthly),
                        style = MaterialTheme.typography.bodySmall,
                        color = quiet,
                    )
                    if (matured) {
                        Text(stringResource(R.string.wealth_cert_matured_hint), style = MaterialTheme.typography.bodySmall, color = quiet)
                    }
                }
            }
            view.valueMinor?.let { AmountText(Money(it, asset.currency)) }
        }
        if (MetalMath.isMetal(asset.kind)) {
            OutlinedButton(onClick = { actions.onOpenMetal(asset.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.gold_open))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (asset.hiddenFromTotals) R.string.wealth_asset_hidden else R.string.wealth_asset_hide),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = quiet,
            )
            Switch(checked = asset.hiddenFromTotals, onCheckedChange = { actions.onHideAsset(asset, it) })
            TextButton(onClick = { actions.onDeleteAsset(asset) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.wealth_asset_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DebtTab(
    state: WealthUiState,
    actions: WealthActions,
) {
    val style = LocalMoneyStyle.current
    val byId = state.debts.associateBy { it.id }
    TotalPanel(R.string.wealth_debt_total, state.netWorth.debtsMinor)
    if (state.netWorth.debtLines.isEmpty()) {
        Text(
            stringResource(R.string.wealth_debt_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    state.netWorth.debtLines.forEachIndexed { index, line ->
        if (index > 0) RowRule()
        DebtLineRow(line, line.id?.let { byId[it] }, actions, style.arabic, style.digits)
    }
    val owed = state.debts.filter { it.direction == "OWED_TO_ME" && it.settledAtMillis == null }
    if (owed.isNotEmpty()) {
        PageHeading(stringResource(R.string.wealth_form_owed_to_me))
        owed.forEachIndexed { index, d ->
            if (index > 0) RowRule()
            DebtLineRow(DebtLine(d.id, DebtSource.DEBT, d.name, d.amountMinor), d, actions, style.arabic, style.digits)
        }
    }
}

@Composable
private fun DebtLineRow(
    line: DebtLine,
    debt: DebtEntity?,
    actions: WealthActions,
    arabic: Boolean,
    digits: eg.masroof.core.DigitStyle,
) {
    val name =
        when (line.source) {
            DebtSource.INSTALLMENTS -> stringResource(R.string.wealth_debt_installments)
            DebtSource.CARD -> stringResource(R.string.wealth_debt_card, line.name.orEmpty())
            DebtSource.OVERDRAWN -> stringResource(R.string.wealth_debt_overdrawn, line.name.orEmpty())
            DebtSource.DEBT -> line.name.orEmpty()
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                debt?.dueAtMillis?.let {
                    Text(
                        stringResource(R.string.wealth_debt_due, DateFormat.shortDate(CairoTime.dateOf(it), arabic, digits)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AmountText(Money(line.amountMinor))
        }
        if (debt != null) {
            Row {
                TextButton(onClick = { actions.onSettleDebt(debt) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.wealth_debt_settle))
                }
                TextButton(onClick = { actions.onDeleteDebt(debt) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.wealth_debt_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
