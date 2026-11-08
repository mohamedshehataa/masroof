package eg.masroof.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.data.db.GoalMovementEntity
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.theme.LocalMoneyStyle

@Composable
fun GoalDetailRoute(
    onEdit: (String) -> Unit,
    onClosed: () -> Unit,
    viewModel: GoalDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val celebrate by viewModel.celebration.collectAsStateWithLifecycle()
    val id = state.card?.id.orEmpty()
    GoalDetailContent(
        state,
        GoalDetailActions(
            onAdd = { viewModel.openDialog(take = false) },
            onTakeOut = { viewModel.openDialog(take = true) },
            onEdit = { onEdit(id) },
            onArchive = { viewModel.archive(onClosed) },
            onDelete = { viewModel.delete(onClosed) },
        ),
        dialog = dialog,
        celebrate = celebrate,
        onSubmit = viewModel::submit,
        onDismissDialog = viewModel::dismissDialog,
        onDismissCelebration = viewModel::dismissCelebration,
    )
}

@Composable
fun GoalDetailContent(
    state: GoalDetailState,
    actions: GoalDetailActions,
    dialog: MoneyDialogState?,
    celebrate: Boolean,
    onSubmit: (String, String, String) -> Unit,
    onDismissDialog: () -> Unit,
    onDismissCelebration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.card
    when {
        state.loading -> SkeletonList(modifier.padding(16.dp))
        card == null ->
            MessageState(
                title = stringResource(R.string.goals_not_found),
                body = stringResource(R.string.goals_nothing_moves),
                icon = Icons.Filled.Warning,
                modifier = modifier,
            )
        else -> {
            var confirmDelete by rememberSaveable { mutableStateOf(false) }
            Column(
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Figures(card)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(actions.onAdd, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.goals_add_money)) }
                    OutlinedButton(actions.onTakeOut, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.goals_take_out))
                    }
                }
                ChartCard(state, card)
                HistoryCard(state.history, card.currency)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(actions.onEdit, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.goals_edit)) }
                    OutlinedButton(actions.onArchive, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.goals_archive))
                    }
                    TextButton({ confirmDelete = true }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.goals_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(stringResource(R.string.goals_delete_title)) },
                    text = { Text(stringResource(R.string.goals_delete_body)) },
                    confirmButton = {
                        TextButton({
                            confirmDelete = false
                            actions.onDelete()
                        }) { Text(stringResource(R.string.goals_delete), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.goals_cancel)) } },
                )
            }
            if (dialog != null) MoneyDialog(dialog, card.currency, onSubmit, onDismissDialog)
            if (celebrate) {
                AlertDialog(
                    onDismissRequest = onDismissCelebration,
                    title = { Text("🎉 " + stringResource(R.string.goals_reached_title)) },
                    text = {
                        val saved = goalMoney(card.progress.savedMinor, card.currency)
                        Text(stringResource(R.string.goals_reached_body, saved, card.name))
                    },
                    confirmButton = { TextButton(onDismissCelebration) { Text(stringResource(R.string.goals_done)) } },
                )
            }
        }
    }
}

@Composable
private fun Figures(card: GoalCardUi) {
    val p = card.progress
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GoalRing(card.emoji, p.percentBp, goalRingDescription(card), size = 128.dp)
        Text(card.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(goalSavedOfTarget(p, card.currency), style = MaterialTheme.typography.titleMedium)
        Text(wholePercent(p.percentBp), style = MaterialTheme.typography.bodyMedium)
        p.daysToGo?.let { Text(goalDaysText(it), style = MaterialTheme.typography.bodyMedium) }
        p.monthlyNeedMinor?.let {
            Text(stringResource(R.string.goals_needs_month, goalMoney(it, card.currency)), style = MaterialTheme.typography.titleSmall)
        }
        val pace = goalPaceText(p, card.currency)
        if (pace.isNotEmpty()) Text(pace, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ChartCard(
    state: GoalDetailState,
    card: GoalCardUi,
) {
    SectionCard(stringResource(R.string.goals_chart_title)) {
        val now = goalMoney(state.steps.lastOrNull() ?: 0L, card.currency)
        GoalStepChart(state.steps, Modifier.clearAndSetSemantics {}.padding(vertical = 4.dp))
        Text(stringResource(R.string.goals_chart_desc, now), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryCard(
    history: List<GoalMovementEntity>,
    currency: String,
) {
    SectionCard(stringResource(R.string.goals_history)) {
        if (history.isEmpty()) {
            Text(stringResource(R.string.goals_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val style = LocalMoneyStyle.current
        history.forEach { m ->
            val taken = m.amountMinor < 0
            val amount = goalMoney(Math.abs(m.amountMinor), currency)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (taken) R.string.goals_history_taken else R.string.goals_history_added, amount))
                    val date = DateFormat.shortDate(CairoTime.dateOf(m.atMillis), style.arabic, style.digits)
                    Text(
                        listOfNotNull(date, m.note).joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoneyDialog(
    dialog: MoneyDialogState,
    currency: String,
    onSubmit: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    val error =
        when (dialog.error) {
            MoneyError.AMOUNT -> stringResource(R.string.goals_err_amount)
            MoneyError.TOO_LARGE -> stringResource(R.string.goals_err_too_large)
            MoneyError.DATE -> stringResource(R.string.goals_err_date)
            MoneyError.ONLY_HAVE -> stringResource(R.string.goals_err_only_have, goalMoney(dialog.onlyHaveMinor, currency))
            null -> null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (dialog.take) R.string.goals_take_out else R.string.goals_add_money)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.goals_amount)) },
                    isError = error != null,
                    supportingText = { if (error != null) Text(error) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.goals_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.goals_date)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({ onSubmit(amount, note, date) }) { Text(stringResource(R.string.goals_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}
