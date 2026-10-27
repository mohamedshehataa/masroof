package eg.masroof.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.EditError
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.EditUiState
import eg.masroof.ui.viewmodel.TYPE_EXPENSE
import eg.masroof.ui.viewmodel.TYPE_INCOME
import eg.masroof.ui.viewmodel.TYPE_TRANSFER

@Composable
fun EditExpenseRoute(
    onDone: () -> Unit,
    viewModel: EditExpenseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow()
    LaunchedEffect(state.form.done) { if (state.form.done) onDone() }
    EditExpenseContent(
        state,
        EditActions(
            viewModel::onType,
            viewModel::onAmount,
            viewModel::onDate,
            viewModel::onCategory,
            viewModel::onMerchant,
            viewModel::onNotes,
            viewModel::confirm,
            viewModel::save,
            viewModel::askDelete,
            viewModel::delete,
            viewModel::dismissDelete,
        ),
    )
}

@Composable
fun EditExpenseContent(
    state: EditUiState,
    actions: EditActions,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.edit_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (form.loaded) EditFields(state, actions)
        }
        if (form.loaded) EditBar(state, actions)
    }
    if (form.confirmDelete) {
        AlertDialog(
            onDismissRequest = actions.onDismissDelete,
            title = { Text(stringResource(R.string.edit_delete_title)) },
            text = { Text(stringResource(R.string.edit_delete_body)) },
            confirmButton = {
                TextButton(onClick = actions.onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditFields(
    state: EditUiState,
    actions: EditActions,
) {
    val form = state.form
    val arabic = LocalMoneyStyle.current.arabic
    val statusRes = if (form.reviewPending) R.string.edit_status_pending else R.string.edit_status_confirmed
    val sourceRes =
        when (form.source) {
            "SMS" -> R.string.source_sms
            "PUSH" -> R.string.source_notification
            else -> R.string.source_manual
        }
    Text(
        stringResource(statusRes),
        style = MaterialTheme.typography.titleSmall,
        color = if (form.reviewPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.edit_source, stringResource(sourceRes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.edit_type), style = MaterialTheme.typography.titleSmall)
    ChipRow(
        listOf(
            TYPE_EXPENSE to stringResource(R.string.edit_type_expense),
            TYPE_INCOME to stringResource(R.string.edit_type_income),
            TYPE_TRANSFER to stringResource(R.string.edit_type_transfer),
        ),
        selected = form.type,
        enabled = !form.busy,
        onSelect = { actions.onType(it) },
    )
    OutlinedTextField(
        value = form.amountText,
        onValueChange = actions.onAmount,
        label = { Text(stringResource(R.string.add_amount)) },
        prefix = { Text(MoneyFormat.symbol(form.currency, arabic)) },
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
        isError = form.amountInvalid,
        supportingText = { if (form.amountInvalid) Text(stringResource(R.string.add_amount_error)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.dateText,
        onValueChange = actions.onDate,
        label = { Text(stringResource(R.string.add_date)) },
        isError = form.dateInvalid,
        supportingText = { if (form.dateInvalid) Text(stringResource(R.string.add_date_error)) },
        singleLine = true,
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.add_category), style = MaterialTheme.typography.titleSmall)
    ChipRow(
        state.categories.map { it.id to (if (arabic) it.nameAr else it.nameEn) },
        selected = form.categoryId,
        enabled = !form.busy,
        tinted = true,
        onSelect = { actions.onCategory(it) },
    )
    OutlinedTextField(
        value = form.merchant,
        onValueChange = actions.onMerchant,
        label = { Text(stringResource(R.string.edit_merchant)) },
        singleLine = true,
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.notes,
        onValueChange = actions.onNotes,
        label = { Text(stringResource(R.string.add_note)) },
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditBar(
    state: EditUiState,
    actions: EditActions,
) {
    val form = state.form
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val message =
                when {
                    form.error == EditError.OFFLINE -> R.string.edit_error_offline
                    form.error == EditError.SIGN_IN -> R.string.edit_error_sign_in
                    form.error == EditError.INVALID -> R.string.edit_error_invalid
                    form.error == EditError.FAILED -> R.string.edit_error_failed
                    form.changedElsewhere -> R.string.edit_changed_elsewhere
                    form.busy -> R.string.edit_working
                    else -> null
                }
            if (message != null) {
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (form.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (form.reviewPending) {
                Button(
                    onClick = actions.onConfirm,
                    enabled = !form.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(stringResource(R.string.edit_confirm)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onSave,
                    enabled = !form.busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.edit_save)) }
                OutlinedButton(
                    onClick = actions.onAskDelete,
                    enabled = !form.busy,
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    tinted: Boolean = false,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon =
                    if (tinted) {
                        {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(CategoryPalette.colorFor(id)),
                            )
                        }
                    } else {
                        null
                    },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
