package eg.masroof.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.RenewalMath
import eg.masroof.ui.components.SectionCard

@Composable
fun SubscriptionDetailRoute(
    onClose: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: SubscriptionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    SubscriptionDetailContent(
        state,
        SubscriptionDetailActions(
            onEdit = { state.item?.let { onEdit(it.row.id) } },
            onSetActive = viewModel::setActive,
            onDelete = viewModel::delete,
        ),
    )
}

@Composable
fun SubscriptionDetailContent(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
    modifier: Modifier = Modifier,
) {
    val item = state.item
    if (item == null) {
        if (state.loaded) {
            Column(modifier.fillMaxSize().padding(24.dp)) {
                Text(stringResource(R.string.subs_not_found), style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val row = item.row
    val cadence = Cadence.of(row.cadence)
    val perYear = RenewalMath.perYearMinor(row.amountMinor, cadence)
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(row.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        SectionCard(stringResource(R.string.subs_details)) {
            Text(billMoney(row.amountMinor, row.currency) + " - " + cadenceLabel(cadence), style = MaterialTheme.typography.titleLarge)
            if (row.active) {
                Text(stringResource(R.string.subs_renews, billDate(item.date), renewalIn(item.daysUntil)))
            } else {
                Text(stringResource(R.string.subs_paused))
            }
            item.lastPaid?.let { Text(stringResource(R.string.subs_paid_on, billDate(it))) }
            Text(
                stringResource(R.string.subs_year_cost, billMoney(perYear, row.currency)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = actions.onEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.subs_edit)) }
        OutlinedButton(onClick = { actions.onSetActive(!row.active) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (row.active) R.string.subs_pause else R.string.subs_resume))
        }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subs_delete))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.subs_delete_title)) },
            text = { Text(stringResource(R.string.subs_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.subs_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.subs_cancel))
                }
            },
        )
    }
}
