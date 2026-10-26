package eg.masroof.ui.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.components.ListStates
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.extra
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsState
import eg.masroof.ui.viewmodel.BudgetsViewModel
import eg.masroof.ui.viewmodel.LoadStatus

private const val FULL_BPS_F = 10_000f

@Composable
fun BudgetsRoute(viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BudgetsContent(
        state,
        onRetry = viewModel::refresh,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onClearError = viewModel::clearError,
    )
}

@Composable
fun BudgetsContent(
    state: BudgetsState,
    onRetry: () -> Unit,
    onSave: (editingId: String?, categoryId: String?, amountText: String, rollover: Boolean, onSaved: () -> Unit) -> Unit,
    onDelete: (id: String, onDeleted: () -> Unit) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = closed; a target with a null row is the create form, otherwise the budget being edited.
    var dialog by remember { mutableStateOf<DialogTarget?>(null) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.budgets_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Button(onClick = { dialog = DialogTarget(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.budgets_add))
            }
        }
        if (state.rows.isNotEmpty()) StatusBanner(state.status, onRetry)
        if (state.rows.isEmpty() && state.status == LoadStatus.READY) {
            MessageState(stringResource(R.string.budgets_empty_title), stringResource(R.string.budgets_empty_body))
        } else {
            ListStates(state.status, isEmpty = state.rows.isEmpty(), onRetry = onRetry) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.id }) { row ->
                        BudgetCard(row, onClick = { dialog = DialogTarget(row) })
                    }
                }
            }
        }
    }
    dialog?.let { target ->
        BudgetDialog(
            editing = target.row,
            categories = state.categories,
            takenCategoryIds = state.rows.mapNotNull { it.categoryId }.toSet(),
            hasTotal = state.rows.any { it.categoryId == null },
            saving = state.saving,
            error = state.error,
            onSave = { cat, amount, rollover -> onSave(target.row?.id, cat, amount, rollover) { dialog = null } },
            onDelete = { id -> onDelete(id) { dialog = null } },
            onDismiss = {
                onClearError()
                dialog = null
            },
        )
    }
}

private data class DialogTarget(
    val row: BudgetRowUi?,
)

@Composable
fun budgetName(row: BudgetRowUi): String {
    val arabic = LocalMoneyStyle.current.arabic
    return (if (arabic) row.nameAr else row.nameEn) ?: stringResource(R.string.budgets_all_spending)
}

@Composable
fun levelColor(level: BudgetLevel): Color =
    when (level) {
        BudgetLevel.OK -> MaterialTheme.extra.positive
        BudgetLevel.HALF -> MaterialTheme.colorScheme.primary
        BudgetLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        BudgetLevel.OVER -> MaterialTheme.colorScheme.error
    }

@Composable
fun levelLabel(level: BudgetLevel): String =
    stringResource(
        when (level) {
            BudgetLevel.OK -> R.string.budgets_level_ok
            BudgetLevel.HALF -> R.string.budgets_level_half
            BudgetLevel.WARNING -> R.string.budgets_level_warning
            BudgetLevel.OVER -> R.string.budgets_level_over
        },
    )

/** A progress bar plus its caption, used by the list and the Home card. */
@Composable
fun BudgetProgressBar(
    row: BudgetRowUi,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { row.barBps / FULL_BPS_F },
        modifier = modifier.fillMaxWidth().heightIn(min = 8.dp),
        color = levelColor(row.level),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun BudgetCard(
    row: BudgetRowUi,
    onClick: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    val name = budgetName(row)
    val spent = MoneyFormat.format(row.spent, style.arabic, style.digits)
    val limit = MoneyFormat.format(row.limit, style.arabic, style.digits)
    val percent = PercentFormat.format(row.progressBps, style.arabic, style.digits)
    val label = levelLabel(row.level)
    val description = stringResource(R.string.budgets_row_desc, name, spent, limit, percent, label)
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (row.weekly) "$name · ${stringResource(R.string.budgets_weekly)}" else name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(percent, style = MaterialTheme.typography.titleMedium, color = levelColor(row.level))
            }
            BudgetProgressBar(row)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.budgets_spent_of, spent, limit),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val remaining = row.remaining
                Text(
                    if (remaining.amountMinor >= 0) {
                        stringResource(R.string.budgets_remaining, MoneyFormat.format(remaining, style.arabic, style.digits))
                    } else {
                        stringResource(
                            R.string.budgets_over_by,
                            MoneyFormat.format(Money(-remaining.amountMinor, remaining.currency), style.arabic, style.digits),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = levelColor(row.level))
                row.daysLeft?.let {
                    Text(
                        pluralStringResource(R.plurals.budgets_days_left, it, DateFormat.number(it, style.digits)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetDialog(
    editing: BudgetRowUi?,
    categories: List<CategoryEntity>,
    takenCategoryIds: Set<String>,
    hasTotal: Boolean,
    saving: Boolean,
    error: BudgetError?,
    onSave: (categoryId: String?, amountText: String, rollover: Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    var categoryId by rememberSaveable { mutableStateOf(editing?.categoryId) }
    var amount by rememberSaveable {
        mutableStateOf(editing?.limit?.let { plainAmount(it) }.orEmpty())
    }
    var rollover by rememberSaveable { mutableStateOf(editing?.rollover ?: false) }
    var menuOpen by remember { mutableStateOf(false) }
    val options = categories.filter { it.id == editing?.categoryId || it.id !in takenCategoryIds }
    val selectedName =
        categories
            .firstOrNull { it.id == categoryId }
            ?.let { if (style.arabic) it.nameAr else it.nameEn }
            ?: stringResource(R.string.budgets_all_spending)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing == null) R.string.budgets_add else R.string.budgets_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.budgets_category), style = MaterialTheme.typography.labelLarge)
                if (editing == null) {
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(selectedName)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!hasTotal) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.budgets_all_spending)) },
                                    onClick = {
                                        categoryId = null
                                        menuOpen = false
                                    },
                                )
                            }
                            options.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(if (style.arabic) c.nameAr else c.nameEn) },
                                    onClick = {
                                        categoryId = c.id
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Text(selectedName, style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.budgets_amount)) },
                    singleLine = true,
                    isError = error == BudgetError.INVALID_AMOUNT,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.budgets_rollover),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = rollover, onCheckedChange = { rollover = it })
                }
                error?.let {
                    Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(categoryId, amount, rollover) },
                enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.budgets_cancel))
            }
        },
    )
}

private fun errorText(error: BudgetError): Int =
    when (error) {
        BudgetError.INVALID_AMOUNT -> R.string.budgets_error_amount
        BudgetError.INVALID -> R.string.budgets_error_invalid
        BudgetError.DUPLICATE -> R.string.budgets_error_duplicate
        BudgetError.OFFLINE -> R.string.budgets_error_offline
        BudgetError.SIGN_IN -> R.string.budgets_error_sign_in
        BudgetError.FAILED -> R.string.budgets_error_failed
    }

/** `1500` or `1500.50`: the editable form of a money value, no grouping, always Western digits. */
internal fun plainAmount(money: Money): String {
    val major = money.amountMinor / MINOR_PER_MAJOR
    val minor = (money.amountMinor % MINOR_PER_MAJOR).toInt()
    return if (minor == 0) major.toString() else "$major.${minor.toString().padStart(2, '0')}"
}

private const val MINOR_PER_MAJOR = 100L

/** Home summary: the three budgets closest to their limit, or a prompt to set the first one. */
@Composable
fun HomeBudgetsCard(
    rows: List<BudgetRowUi>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalMoneyStyle.current
    val names = rows.map { budgetName(it) }
    val labels = rows.map { levelLabel(it.level) }
    val summary =
        rows.indices.joinToString(". ") { i ->
            val r = rows[i]
            val percent = PercentFormat.format(r.progressBps, style.arabic, style.digits)
            "${names[i]} $percent, ${labels[i]}"
        }
    val description = stringResource(R.string.budgets_home_desc, summary.ifEmpty { stringResource(R.string.budgets_home_cta) })
    Card(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.budgets_title), style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.budgets_home_cta),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            rows.forEachIndexed { i, r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(names[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            PercentFormat.format(r.progressBps, style.arabic, style.digits),
                            style = MaterialTheme.typography.labelLarge,
                            color = levelColor(r.level),
                        )
                    }
                    BudgetProgressBar(r)
                }
            }
        }
    }
}
