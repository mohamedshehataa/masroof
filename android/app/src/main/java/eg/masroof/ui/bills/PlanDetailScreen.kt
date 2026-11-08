@file:Suppress("MatchingDeclarationName")

package eg.masroof.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.data.bills.PLAN_COMPLETED
import eg.masroof.data.bills.PaymentChoice
import eg.masroof.data.bills.PlanProgress
import eg.masroof.data.bills.UndoToken
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.InstallmentPaymentEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class PlanDetailActions(
    val onRecordStart: () -> Unit,
    val onRecordDismiss: () -> Unit,
    val onRecord: (PaymentChoice) -> Unit,
    val onCollect: () -> Unit,
    val onUndo: () -> Unit,
    val onDismissUndo: () -> Unit,
    val onComplete: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
fun PlanDetailRoute(
    onClose: () -> Unit,
    viewModel: PlanDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    PlanDetailContent(
        state,
        PlanDetailActions(
            viewModel::startRecord,
            viewModel::dismissRecord,
            viewModel::record,
            viewModel::collect,
            viewModel::undo,
            viewModel::dismissUndo,
            viewModel::complete,
            viewModel::delete,
        ),
    )
}

@Composable
fun PlanDetailContent(
    state: PlanDetailState,
    actions: PlanDetailActions,
    modifier: Modifier = Modifier,
) {
    val p = state.progress
    if (p == null) {
        if (state.loaded) {
            Column(modifier.fillMaxSize().padding(24.dp)) {
                Text(stringResource(R.string.bills_not_found), style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val completed = p.plan.status == PLAN_COMPLETED || p.nextDue == null
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                planLabel(p.plan.provider, p.plan.title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        state.undo?.let { token -> item { UndoBanner(token, actions) } }
        item { Hero(p, state.today) }
        item { AmountsCard(p) }
        item { DetailsBlock(p) }
        item { ActionsBlock(p, completed, actions, onConfirm = { confirm = it }) }
        item {
            Text(
                stringResource(R.string.bills_schedule),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        items(p.payments, key = { it.id }) { PaymentRow(it, p.plan.currency) }
    }
    state.candidates?.let { RecordDialog(p, it, state.linkFailed, actions) }
    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (which == Confirm.COMPLETE) R.string.bills_complete_title else R.string.bills_delete_title)) },
            text = { Text(stringResource(if (which == Confirm.COMPLETE) R.string.bills_complete_body else R.string.bills_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        if (which == Confirm.COMPLETE) actions.onComplete() else actions.onDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (which ==
                                Confirm.COMPLETE
                            ) {
                                R.string.bills_complete_confirm
                            } else {
                                R.string.bills_delete_confirm
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirm = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.bills_cancel)) }
            },
        )
    }
}

private enum class Confirm { COMPLETE, DELETE }

@Composable
private fun AmountsCard(p: PlanProgress) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.plan.purchasePriceMinor?.let { LabeledAmount(stringResource(R.string.bills_purchase_price), billMoney(it, p.plan.currency)) }
            LabeledAmount(stringResource(R.string.bills_monthly), billMoney(p.plan.monthlyAmountMinor, p.plan.currency))
            LabeledAmount(stringResource(R.string.bills_remaining), billMoney(p.remainingMinor, p.plan.currency))
            PlanProgressLine(p)
        }
    }
}

@Composable
private fun DetailsBlock(p: PlanProgress) {
    val first = CairoTime.dateOf(p.plan.firstDueAtMillis)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.bills_details),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.bills_first_payment, billDate(first)))
        Text(reminderText(p.plan.reminderDaysBefore))
        p.plan.payoutAtMillis?.let { payout ->
            val amount = billMoney(p.plan.payoutAmountMinor ?: 0L, p.plan.currency)
            val date = billDate(CairoTime.dateOf(payout))
            val received = p.plan.payoutReceivedAtMillis != null
            Text(
                stringResource(if (received) R.string.bills_collection_received else R.string.bills_collection_on, amount, date),
            )
        }
    }
}

@Composable
private fun ActionsBlock(
    p: PlanProgress,
    completed: Boolean,
    actions: PlanDetailActions,
    onConfirm: (Confirm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val next = p.nextDue
        if (completed || next == null) {
            Text(
                stringResource(R.string.bills_completed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = actions.onRecordStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_record_payment, billNumber(next.seq), billNumber(p.total)))
            }
        }
        if (p.plan.payoutAtMillis != null && p.plan.payoutReceivedAtMillis == null) {
            OutlinedButton(onClick = actions.onCollect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_record_collection))
            }
        }
        if (!completed) {
            OutlinedButton(onClick = { onConfirm(Confirm.COMPLETE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bills_mark_complete))
            }
        }
        OutlinedButton(
            onClick = { onConfirm(Confirm.DELETE) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.bills_delete)) }
    }
}

@Composable
private fun UndoBanner(
    token: UndoToken,
    actions: PlanDetailActions,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (token.payment != null) R.string.bills_recorded else R.string.bills_collection_recorded),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bills_undo)) }
            TextButton(
                onClick = actions.onDismissUndo,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.bills_ok)) }
        }
    }
}

@Composable
private fun reminderText(days: Int): String =
    when (days) {
        0 -> stringResource(R.string.bills_reminder_same_day)
        1 -> stringResource(R.string.bills_reminder_one_day)
        else -> stringResource(R.string.bills_reminder_n_days, billNumber(days))
    }

@Composable
private fun LabeledAmount(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Hero(
    p: PlanProgress,
    today: LocalDate,
) {
    val next = p.nextDue
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (next == null || p.plan.status == PLAN_COMPLETED) {
                Text(stringResource(R.string.bills_completed), style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(billMoney(next.amountMinor, p.plan.currency), style = MaterialTheme.typography.headlineMedium)
                val due = CairoTime.dateOf(next.dueAtMillis)
                val days = ChronoUnit.DAYS.between(today, due).toInt()
                Text(
                    when {
                        days < 0 -> stringResource(R.string.bills_overdue, billDate(due))
                        days == 0 -> stringResource(R.string.bills_due_today)
                        days == 1 -> stringResource(R.string.bills_due_tomorrow)
                        else -> stringResource(R.string.bills_due_in_days, billNumber(days))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun PaymentRow(
    payment: InstallmentPaymentEntity,
    currency: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(billNumber(payment.seq) + ".  " + billDate(CairoTime.dateOf(payment.dueAtMillis)), modifier = Modifier.weight(1f))
        Text(billMoney(payment.amountMinor, currency))
        Text(
            stringResource(if (payment.paidAtMillis != null) R.string.bills_row_paid else R.string.bills_row_due),
            style = MaterialTheme.typography.labelMedium,
            color = if (payment.paidAtMillis != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Link an entry that already exists, or make a new one, or only mark paid: exactly one of them. */
@Composable
private fun RecordDialog(
    p: PlanProgress,
    candidates: List<ExpenseEntity>,
    linkFailed: Boolean,
    actions: PlanDetailActions,
) {
    val next = p.nextDue ?: return
    AlertDialog(
        onDismissRequest = actions.onRecordDismiss,
        title = { Text(stringResource(R.string.bills_record_payment, billNumber(next.seq), billNumber(p.total))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (linkFailed) Text(stringResource(R.string.bills_link_failed), color = MaterialTheme.colorScheme.error)
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.bills_no_candidates), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.bills_link_existing), style = MaterialTheme.typography.titleSmall)
                    candidates.forEach { e ->
                        TextButton(
                            onClick = { actions.onRecord(PaymentChoice.LinkExisting(e.id)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                (e.merchant ?: e.notes ?: "") + "  " + billDate(CairoTime.dateOf(e.occurredAtMillis)) + "  " +
                                    billMoney(e.amountMinor, e.currency),
                            )
                        }
                    }
                }
                Button(
                    onClick = { actions.onRecord(PaymentChoice.CreateEntry) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.bills_create_entry))
                }
                OutlinedButton(
                    onClick = { actions.onRecord(PaymentChoice.MarkOnly) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.bills_mark_only))
                }
                Text(
                    stringResource(R.string.bills_mark_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = actions.onRecordDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.bills_cancel)) }
        },
    )
}
