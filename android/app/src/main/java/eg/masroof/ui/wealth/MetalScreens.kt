package eg.masroof.ui.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.wealth.Checked
import eg.masroof.data.wealth.HoldingDetail
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalInput
import eg.masroof.data.wealth.MetalMath
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.LocalDate

/** Grams from milligrams: `35`, `7.5`, `0.125`, in the chosen digits. */
@Composable
internal fun gramsText(milli: Long): String {
    val whole = milli / MetalMath.MILLI_PER_GRAM
    val frac = (milli % MetalMath.MILLI_PER_GRAM).toString().padStart(3, '0').trimEnd('0')
    val style = LocalMoneyStyle.current
    val sep = if (style.digits == eg.masroof.core.DigitStyle.ARABIC_INDIC) "٫" else "."
    val number = DateFormat.digitsAs(if (frac.isEmpty()) "$whole" else "$whole$sep$frac", style.digits)
    return stringResource(R.string.gold_grams, number)
}

@Composable
internal fun karatLabel(
    kind: String,
    karat: Int?,
): String {
    val number = DateFormat.digitsAs((karat ?: 0).toString(), LocalMoneyStyle.current.digits)
    return stringResource(if (kind == MetalMath.SILVER) R.string.gold_karat_silver else R.string.gold_karat_gold, number)
}

/** A plain editable amount: `4800` or `4800.5`, for prefilling a field. */
private fun plainAmount(minor: Long): String {
    val major = minor / 100
    val cents = (minor % 100).toString().padStart(2, '0').trimEnd('0')
    return if (cents.isEmpty()) "$major" else "$major.$cents"
}

class MetalForm(
    val kind: String = MetalMath.GOLD,
    val karat: Int = 21,
    val name: String = "",
    val grams: String = "",
    val amount: String = "",
    val date: String = "",
) {
    fun copy(
        kind: String = this.kind,
        karat: Int = this.karat,
        name: String = this.name,
        grams: String = this.grams,
        amount: String = this.amount,
        date: String = this.date,
    ) = MetalForm(kind, karat, name, grams, amount, date)
}

internal fun validateMetal(
    form: MetalForm,
    today: LocalDate,
): Checked<Pair<HoldingDraft, LotDraft>, MetalInput.Field> {
    val lot = MetalInput.lot(MetalMath.BUY, form.grams, form.amount, form.date, today)
    val errors = lot.errors.toMutableSet()
    if (form.name.isBlank() || form.name.trim().length > MAX_HOLDING_NAME) errors += MetalInput.Field.NAME
    val draft = lot.value
    if (errors.isNotEmpty() || draft == null) return Checked(null, errors)
    return Checked(HoldingDraft(form.kind, form.name.trim(), form.karat) to draft, emptySet())
}

private const val MAX_HOLDING_NAME = 60

/** The Wealth -> Add -> Gold & silver fields: metal, purity, name and the first purchase. */
@Composable
internal fun MetalCreateFields(
    form: MetalForm,
    errors: Set<MetalInput.Field>,
    onChange: (MetalForm) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            form.kind == MetalMath.GOLD,
            { onChange(form.copy(kind = MetalMath.GOLD, karat = 21)) },
            { Text(stringResource(R.string.gold_kind_gold)) },
            Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
            form.kind == MetalMath.SILVER,
            { onChange(form.copy(kind = MetalMath.SILVER, karat = 999)) },
            { Text(stringResource(R.string.gold_kind_silver)) },
            Modifier.heightIn(min = 48.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetalMath.karatsOf(form.kind).forEach { k ->
            FilterChip(
                form.karat == k,
                { onChange(form.copy(karat = k)) },
                { Text(DateFormat.digitsAs("$k", LocalMoneyStyle.current.digits)) },
            )
        }
    }
    FormField(
        form.name,
        { onChange(form.copy(name = it)) },
        R.string.gold_form_name,
        error =
            R.string.gold_error_name.takeIf {
                MetalInput.Field.NAME in
                    errors
            },
    )
    LotFields(
        form.grams,
        form.amount,
        form.date,
        errors,
        R.string.gold_form_paid,
    ) { g, a, d -> onChange(form.copy(grams = g, amount = a, date = d)) }
}

@Composable
private fun LotFields(
    grams: String,
    amount: String,
    date: String,
    errors: Set<MetalInput.Field>,
    amountLabel: Int,
    onChange: (String, String, String) -> Unit,
) {
    FormField(
        grams,
        { onChange(it, amount, date) },
        R.string.gold_form_grams,
        error = R.string.gold_error_grams.takeIf { MetalInput.Field.GRAMS in errors },
        keyboard = KeyboardType.Decimal,
    )
    FormField(
        amount,
        { onChange(grams, it, date) },
        amountLabel,
        error = R.string.gold_error_amount.takeIf { MetalInput.Field.AMOUNT in errors },
        keyboard = KeyboardType.Decimal,
    )
    FormField(
        date,
        { onChange(grams, amount, it) },
        R.string.gold_form_date,
        error =
            R.string.gold_error_date.takeIf {
                MetalInput.Field.DATE in
                    errors
            },
    )
}

@Composable
fun MetalDetailRoute(
    assetId: String,
    onBack: () -> Unit,
    viewModel: MetalViewModel = hiltViewModel(),
) {
    LaunchedEffect(assetId) { viewModel.open(assetId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.loaded, state.holding) { if (state.loaded && state.holding == null) onBack() }
    MetalDetailContent(
        state,
        MetalActions(
            onBack = onBack,
            onSaveLot = viewModel::saveLot,
            onDeleteLot = viewModel::deleteLot,
            onSavePrices = viewModel::savePrices,
            onLoadCandidates = viewModel::loadCandidates,
            onLinkGoal = viewModel::link,
            onUnlinkGoal = viewModel::unlink,
            onUndoEntry = viewModel::undoEntry,
            onDismissUndo = viewModel::dismissUndo,
            onHide = viewModel::hide,
            onDeleteHolding = viewModel::deleteHolding,
        ),
    )
}

@Composable
fun MetalDetailContent(
    state: MetalUiState,
    actions: MetalActions,
    modifier: Modifier = Modifier,
) {
    val holding = state.holding
    if (holding == null) {
        MessageState(stringResource(R.string.gold_not_found), "", modifier.fillMaxSize(), Icons.Filled.Info)
        return
    }
    var lotDialog by remember { mutableStateOf<LotDialogState?>(null) }
    var priceDialog by remember { mutableStateOf(false) }
    var goalPicker by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var confirmDeleteLot by remember { mutableStateOf<AssetLotEntity?>(null) }
    var confirmDeleteHolding by remember { mutableStateOf(false) }
    var lotError by remember { mutableStateOf<MetalOutcome?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_back)) }
        Text(holding.asset.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(karatLabel(holding.asset.kind, holding.asset.karat), style = MaterialTheme.typography.labelLarge)
        ValueCard(holding) { priceDialog = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { lotDialog = LotDialogState(MetalMath.BUY, null) },
                Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.gold_buy_more)) }
            OutlinedButton({ lotDialog = LotDialogState(MetalMath.SELL, null) }, Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.gold_record_sale))
            }
        }
        state.undoLotId?.let { lotId ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gold_entry_excluded), modifier = Modifier.weight(1f))
                TextButton({ actions.onUndoEntry(lotId) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_undo)) }
                TextButton(actions.onDismissUndo, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_ok)) }
            }
        }
        lotError?.let { Text(outcomeText(it), color = MaterialTheme.colorScheme.error) }
        GoalCard(holding, onLink = { goalPicker = true }, onUnlink = { confirmUnlink = true })
        SectionCard(stringResource(R.string.gold_lots)) {
            if (holding.lots.isEmpty()) Text(stringResource(R.string.gold_lots_empty))
            holding.lots.forEach { lot ->
                LotRow(lot, onEdit = { lotDialog = LotDialogState(lot.side, lot) }, onDelete = { confirmDeleteLot = lot })
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gold_hide), modifier = Modifier.weight(1f))
            Switch(checked = holding.asset.hiddenFromTotals, onCheckedChange = actions.onHide)
        }
        TextButton({ confirmDeleteHolding = true }, Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.gold_delete_holding), color = MaterialTheme.colorScheme.error)
        }
    }
    lotDialog?.let { d ->
        LotDialog(
            d,
            holding.today,
            state.candidates,
            actions.onLoadCandidates,
            { draft, done -> actions.onSaveLot(d.lot?.id, draft, done) },
        ) {
            lotDialog = null
        }
    }
    if (priceDialog) {
        PriceDialog(holding, { actions.onSavePrices(it) }, { priceDialog = false })
    }
    if (goalPicker) {
        GoalPickerDialog(state, actions, { goalPicker = false })
    }
    if (confirmUnlink) {
        ConfirmDialog(
            R.string.gold_goal_unlink_title,
            R.string.gold_goal_unlink_body,
            R.string.gold_goal_unlink,
            { confirmUnlink = false },
        ) {
            confirmUnlink = false
            actions.onUnlinkGoal()
        }
    }
    confirmDeleteLot?.let { lot ->
        ConfirmDialog(R.string.gold_delete_lot_title, null, R.string.gold_delete, { confirmDeleteLot = null }) {
            confirmDeleteLot = null
            actions.onDeleteLot(lot.id) { lotError = it.takeUnless { o -> o is MetalOutcome.Saved } }
        }
    }
    if (confirmDeleteHolding) {
        ConfirmDialog(R.string.gold_delete_holding_title, R.string.gold_delete_holding_body, R.string.gold_delete, {
            confirmDeleteHolding =
                false
        }) {
            confirmDeleteHolding = false
            actions.onDeleteHolding()
        }
    }
}

private class LotDialogState(
    val side: String,
    val lot: AssetLotEntity?,
)

@Composable
private fun outcomeText(outcome: MetalOutcome): String =
    when (outcome) {
        is MetalOutcome.OnlyHold -> stringResource(R.string.gold_error_only_hold, gramsText(outcome.heldMilli))
        MetalOutcome.InvalidDate -> stringResource(R.string.gold_error_date)
        MetalOutcome.TooLarge -> stringResource(R.string.gold_error_too_large)
        MetalOutcome.InvalidAmount -> stringResource(R.string.gold_error_amount)
        MetalOutcome.AlreadyLinked -> stringResource(R.string.gold_error_linked)
        MetalOutcome.NotEligible -> stringResource(R.string.gold_error_goal)
        else -> stringResource(R.string.gold_error_grams)
    }

@Composable
private fun ValueCard(
    holding: HoldingDetail,
    onPrice: () -> Unit,
) {
    val v = holding.valuation
    val style = LocalMoneyStyle.current
    SectionCard(stringResource(R.string.gold_held)) {
        Text(gramsText(v.position.heldMilli), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (v.priceState == MetalMath.PriceState.NONE) R.string.gold_value_at_cost else R.string.gold_value),
            style = MaterialTheme.typography.labelMedium,
        )
        AmountText(Money(v.valueMinor, holding.asset.currency), style = MaterialTheme.typography.headlineMedium)
        v.position.averagePerGramMinor?.let {
            Text(stringResource(R.string.gold_average, moneyText(it)), style = MaterialTheme.typography.bodyMedium)
        }
        if (v.priceState != MetalMath.PriceState.NONE) {
            val gain = moneyText(v.gainMinor).let { if (v.gainMinor > 0) "+$it" else it }
            val bp = v.gainBp
            Text(
                if (bp == null) {
                    stringResource(R.string.gold_gain_no_pct, gain)
                } else {
                    stringResource(R.string.gold_gain, gain, gainPercent(bp, style.arabic, style.digits))
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (v.position.realisedMinor != 0L) {
            Text(stringResource(R.string.gold_realised, moneyText(v.position.realisedMinor)), style = MaterialTheme.typography.bodyMedium)
        }
        val priceAt = v.priceAtMillis
        val date = priceAt?.let { DateFormat.shortDate(CairoTime.dateOf(it), style.arabic, style.digits) }
        when {
            v.priceMinor == null || date == null -> Text(stringResource(R.string.gold_add_price), color = MaterialTheme.colorScheme.error)
            v.priceState == MetalMath.PriceState.STALE ->
                Text(stringResource(R.string.gold_price_stale, date), color = MaterialTheme.colorScheme.error)
            else ->
                Text(
                    stringResource(R.string.gold_price_line, moneyText(v.priceMinor), date),
                    style = MaterialTheme.typography.bodyMedium,
                )
        }
        OutlinedButton(onPrice, Modifier.heightIn(min = 48.dp)) {
            Text(
                stringResource(
                    if (holding.asset.kind ==
                        MetalMath.SILVER
                    ) {
                        R.string.gold_price_sheet_silver
                    } else {
                        R.string.gold_price_sheet_gold
                    },
                ),
            )
        }
    }
}

@Composable
private fun GoalCard(
    holding: HoldingDetail,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    SectionCard(stringResource(R.string.gold_goal_section)) {
        val name = holding.goalName
        if (holding.asset.goalId == null || name == null) {
            Text(stringResource(R.string.gold_goal_none))
            Button(onLink, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_goal_link)) }
        } else {
            Text(stringResource(R.string.gold_goal_backing, name))
            OutlinedButton(onUnlink, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_goal_unlink)) }
        }
    }
}

@Composable
private fun LotRow(
    lot: AssetLotEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    val line =
        stringResource(
            if (lot.side == MetalMath.SELL) R.string.gold_lot_sell else R.string.gold_lot_buy,
            gramsText(lot.quantityMilli),
            moneyText(lot.totalMinor),
        )
    Column(Modifier.fillMaxWidth()) {
        Text(line)
        Text(
            DateFormat.shortDate(CairoTime.dateOf(lot.atMillis), style.arabic, style.digits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lot.expenseId != null) {
            Text(
                stringResource(R.string.gold_lot_entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row {
            TextButton(onEdit, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_edit)) }
            TextButton(onDelete, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_delete)) }
        }
    }
}

@Composable
private fun LotDialog(
    d: LotDialogState,
    today: LocalDate,
    candidates: List<ExpenseEntity>,
    onLoadCandidates: () -> Unit,
    onSave: (LotDraft, (MetalOutcome) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val lot = d.lot
    val style = LocalMoneyStyle.current
    var grams by remember { mutableStateOf(lot?.let { milliPlain(it.quantityMilli) }.orEmpty()) }
    var amount by remember { mutableStateOf(lot?.let { plainAmount(it.totalMinor) }.orEmpty()) }
    var date by remember { mutableStateOf(lot?.let { CairoTime.dateOf(it.atMillis).toString() }.orEmpty()) }
    var expenseId by remember { mutableStateOf(lot?.expenseId) }
    var linked by remember { mutableStateOf<ExpenseEntity?>(null) }
    var errors by remember { mutableStateOf(emptySet<MetalInput.Field>()) }
    var failure by remember { mutableStateOf<MetalOutcome?>(null) }
    var picking by remember { mutableStateOf(false) }
    val sell = d.side == MetalMath.SELL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (lot !=
                        null
                    ) {
                        R.string.gold_lot_title_edit
                    } else if (sell) {
                        R.string.gold_lot_title_sell
                    } else {
                        R.string.gold_lot_title_buy
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LotFields(grams, amount, date, errors, if (sell) R.string.gold_form_received else R.string.gold_form_paid) { g, a, dt ->
                    grams = g
                    amount = a
                    date = dt
                }
                failure?.let { Text(outcomeText(it), color = MaterialTheme.colorScheme.error) }
                if (!sell) {
                    if (expenseId == null) {
                        TextButton(
                            onClick = {
                                onLoadCandidates()
                                picking = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.gold_link_entry)) }
                    } else {
                        val label =
                            linked?.let { it.merchant ?: stringResource(R.string.gold_entry_unnamed) }
                                ?: stringResource(R.string.gold_lot_entry)
                        Text(stringResource(R.string.gold_entry_selected, label))
                        TextButton({
                            expenseId = null
                            linked = null
                        }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_unlink_entry)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val checked = MetalInput.lot(d.side, grams, amount, date, today, expenseId.takeIf { !sell })
                    errors = checked.errors
                    checked.value?.let { draft ->
                        onSave(draft) { out -> if (out is MetalOutcome.Saved) onDismiss() else failure = out }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.gold_save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_cancel)) } },
    )
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.gold_entry_picker_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text(stringResource(R.string.gold_entry_none))
                    candidates.forEach { e ->
                        val day = DateFormat.shortDate(CairoTime.dateOf(e.occurredAtMillis), style.arabic, style.digits)
                        TextButton(
                            onClick = {
                                expenseId = e.id
                                linked = e
                                picking = false
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(
                                    R.string.gold_entry_row,
                                    e.merchant ?: stringResource(R.string.gold_entry_unnamed),
                                    "${moneyText(e.amountMinor)} - $day",
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    { picking = false },
                    Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.gold_cancel)) }
            },
        )
    }
}

private fun milliPlain(milli: Long): String {
    val frac = (milli % MetalMath.MILLI_PER_GRAM).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "${milli / MetalMath.MILLI_PER_GRAM}" else "${milli / MetalMath.MILLI_PER_GRAM}.$frac"
}

@Composable
private fun PriceDialog(
    holding: HoldingDetail,
    onSave: (Map<Int, Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val kind = holding.asset.kind
    val karats = MetalMath.karatsOf(kind)
    val fields =
        remember {
            mutableStateOf(
                karats.associateWith { k ->
                    if (k ==
                        holding.asset.karat
                    ) {
                        holding.valuation.priceMinor
                            ?.let(::plainAmount)
                            .orEmpty()
                    } else {
                        ""
                    }
                },
            )
        }
    var bad by remember { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind ==
                        MetalMath.SILVER
                    ) {
                        R.string.gold_price_sheet_silver
                    } else {
                        R.string.gold_price_sheet_gold
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gold_price_hint), style = MaterialTheme.typography.bodySmall)
                karats.forEach { k ->
                    val label = stringResource(R.string.gold_price_field, DateFormat.digitsAs("$k", LocalMoneyStyle.current.digits))
                    androidx.compose.material3.OutlinedTextField(
                        value = fields.value.getValue(k),
                        onValueChange = { fields.value = fields.value + (k to it) },
                        label = { Text(label) },
                        isError = k in bad,
                        supportingText = { if (k in bad) Text(stringResource(R.string.gold_error_amount)) },
                        keyboardOptions =
                            androidx.compose.foundation.text
                                .KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = fields.value.filterValues { it.isNotBlank() }.mapValues { MetalInput.price(it.value) }
                    bad = parsed.filterValues { it == null }.keys
                    if (bad.isEmpty()) {
                        onSave(parsed.mapValues { it.value!! })
                        onDismiss()
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.gold_save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_cancel)) } },
    )
}

@Composable
private fun GoalPickerDialog(
    state: MetalUiState,
    actions: MetalActions,
    onDismiss: () -> Unit,
) {
    var failure by remember { mutableStateOf<MetalOutcome?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gold_goal_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.goals.isEmpty()) Text(stringResource(R.string.gold_goal_no_goals))
                state.goals.forEach { g ->
                    TextButton(
                        onClick = { actions.onLinkGoal(g.id) { out -> if (out is MetalOutcome.Saved) onDismiss() else failure = out } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(listOfNotNull(g.emoji, g.name).joinToString(" ")) }
                }
                failure?.let { Text(outcomeText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: Int,
    body: Int?,
    action: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = body?.let { { Text(stringResource(it)) } },
        confirmButton = {
            TextButton(onConfirm, Modifier.heightIn(min = 48.dp)) { Text(stringResource(action), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gold_cancel)) } },
    )
}

/** Gain as a signed percent with two decimals (1636 bp -> +16.36%), in the chosen digits. */
internal fun gainPercent(
    bp: Int,
    arabic: Boolean,
    digits: eg.masroof.core.DigitStyle,
): String {
    val abs = kotlin.math.abs(bp.toLong())
    val sep = if (digits == eg.masroof.core.DigitStyle.ARABIC_INDIC) "٫" else "."
    val number = "${abs / 100}$sep${(abs % 100).toString().padStart(2, '0')}"
    val sign =
        if (bp > 0) {
            "+"
        } else if (bp < 0) {
            "-"
        } else {
            ""
        }
    return DateFormat.digitsAs("$sign$number", digits) + if (arabic) "٪" else "%"
}
