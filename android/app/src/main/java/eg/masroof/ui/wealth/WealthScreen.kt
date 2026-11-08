package eg.masroof.ui.wealth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.NetWorthHistory
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle

@Composable
fun WealthRoute(
    onOpenAccounts: () -> Unit,
    viewModel: WealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WealthContent(
        state,
        WealthActions(
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
            onDebt = {
                actions.onAddDebt(it)
                adding = false
            },
        )
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.wealth_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Icons.Filled.Info,
            )
            return@Column
        }
        val titles = listOf(R.string.wealth_tab_net, R.string.wealth_tab_assets, R.string.wealth_tab_debt)
        PrimaryTabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(title), modifier = Modifier.padding(12.dp))
                }
            }
        }
        when (tab) {
            0 -> NetWorthTab(state)
            1 -> AssetsTab(state, actions)
            else -> DebtTab(state, actions)
        }
        Hints(state.netWorth)
    }
}

@Composable
private fun Hints(net: NetWorth) {
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

@Composable
private fun NetWorthTab(state: WealthUiState) {
    val net = state.netWorth
    val style = LocalMoneyStyle.current
    var range by rememberSaveable { mutableStateOf(ChartRange.MONTH_3) }
    SectionCard(stringResource(R.string.wealth_net_worth)) {
        AmountText(Money(net.netMinor), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(stringResource(R.string.wealth_assets_total), style = MaterialTheme.typography.labelMedium)
                AmountText(Money(net.assetsMinor))
            }
            Column {
                Text(stringResource(R.string.wealth_debt_total), style = MaterialTheme.typography.labelMedium)
                AmountText(Money(net.debtsMinor))
            }
        }
        val change = NetWorthHistory.yearChange(state.snapshots, net.netMinor, state.today)
        if (change != null) {
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
            Text(stringResource(R.string.wealth_change_year), style = MaterialTheme.typography.labelMedium)
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
    SectionCard(stringResource(R.string.wealth_chart_desc)) {
        val labels =
            listOf(
                ChartRange.MONTH_1 to R.string.wealth_range_1m,
                ChartRange.MONTH_3 to R.string.wealth_range_3m,
                ChartRange.YEAR_1 to R.string.wealth_range_1y,
                ChartRange.ALL to R.string.wealth_range_all,
            )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        NetWorthChart(if (history.size <= 1) listOf(current) else history, rangeLabel)
    }
}

private val BUCKET_COLORS =
    listOf(Color(0xFF0E7C66), Color(0xFF6366F1), Color(0xFF0EA5E9), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF64748B))

private fun bucketLabel(bucket: AssetBucket): Int =
    when (bucket) {
        AssetBucket.SPENDABLE -> R.string.wealth_bucket_spendable
        AssetBucket.CERTIFICATE -> R.string.wealth_bucket_certificate
        AssetBucket.FUND -> R.string.wealth_bucket_fund
        AssetBucket.PROPERTY -> R.string.wealth_bucket_property
        AssetBucket.VEHICLE -> R.string.wealth_bucket_vehicle
        AssetBucket.OTHER -> R.string.wealth_bucket_other
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

@Composable
private fun AssetsTab(
    state: WealthUiState,
    actions: WealthActions,
) {
    val style = LocalMoneyStyle.current
    val shown = state.netWorth.buckets.filter { it.amountMinor > 0 }
    SectionCard(stringResource(R.string.wealth_assets_total)) {
        AmountText(Money(state.netWorth.assetsMinor), style = MaterialTheme.typography.headlineSmall)
        if (shown.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                shown.forEach { b ->
                    Box(
                        Modifier
                            .weight(b.shareBps.coerceAtLeast(1).toFloat())
                            .height(16.dp)
                            .background(BUCKET_COLORS[b.bucket.ordinal]),
                    )
                }
            }
            shown.forEach { b ->
                val label = stringResource(bucketLabel(b.bucket))
                val amount = moneyText(b.amountMinor)
                val pct = PercentFormat.format(b.shareBps, style.arabic, style.digits)
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(12.dp)
                            .padding(end = 8.dp)
                            .background(BUCKET_COLORS[b.bucket.ordinal])
                            .padding(6.dp),
                    )
                    Text(stringResource(R.string.wealth_bucket_row, label, "$amount ($pct)"), modifier = Modifier.weight(1f))
                }
            }
        }
    }
    SectionCard(stringResource(R.string.wealth_assets_list)) {
        if (state.assets.isEmpty()) Text(stringResource(R.string.wealth_assets_none))
        state.assets.forEach { AssetRow(it, actions, style.arabic, style.digits, state.today) }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${asset.name} - ${stringResource(kindLabel(asset.kind))}", modifier = Modifier.weight(1f))
            view.valueMinor?.let { AmountText(Money(it, asset.currency)) }
        }
        val updated = DateFormat.shortDate(CairoTime.dateOf(asset.updatedAtMillis), arabic, digits)
        Text(
            stringResource(R.string.wealth_asset_updated, updated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (asset.kind == "CERTIFICATE") {
            val matured = AssetValuation.isMatured(asset, today, CairoTime::dateOf)
            Text(
                stringResource(if (matured) R.string.wealth_cert_matured else R.string.wealth_cert_monthly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (asset.hiddenFromTotals) R.string.wealth_asset_hidden else R.string.wealth_asset_hide),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = asset.hiddenFromTotals, onCheckedChange = { actions.onHideAsset(asset, it) })
            TextButton(onClick = { actions.onDeleteAsset(asset) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.wealth_asset_delete))
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
    SectionCard(stringResource(R.string.wealth_debt_total)) {
        AmountText(Money(state.netWorth.debtsMinor), style = MaterialTheme.typography.headlineSmall)
        if (state.netWorth.debtLines.isEmpty()) Text(stringResource(R.string.wealth_debt_none))
        state.netWorth.debtLines.forEach { line ->
            DebtLineRow(line, line.id?.let { byId[it] }, actions, style.arabic, style.digits)
        }
    }
    val owed = state.debts.filter { it.direction == "OWED_TO_ME" && it.settledAtMillis == null }
    if (owed.isNotEmpty()) {
        SectionCard(stringResource(R.string.wealth_form_owed_to_me)) {
            owed.forEach { d ->
                DebtLineRow(DebtLine(d.id, DebtSource.DEBT, d.name, d.amountMinor), d, actions, style.arabic, style.digits)
            }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, modifier = Modifier.weight(1f))
            AmountText(Money(line.amountMinor))
        }
        debt?.dueAtMillis?.let {
            Text(
                stringResource(R.string.wealth_debt_due, DateFormat.shortDate(CairoTime.dateOf(it), arabic, digits)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (debt != null) {
            Row {
                TextButton(onClick = { actions.onSettleDebt(debt) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.wealth_debt_settle))
                }
                TextButton(onClick = { actions.onDeleteDebt(debt) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.wealth_debt_delete))
                }
            }
        }
    }
}
