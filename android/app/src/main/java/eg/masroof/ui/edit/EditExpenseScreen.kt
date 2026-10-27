package eg.masroof.ui.edit

import android.widget.Toast
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.EditError
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.EditUiState
import eg.masroof.ui.viewmodel.TYPE_EXPENSE
import eg.masroof.ui.viewmodel.TYPE_INCOME
import eg.masroof.ui.viewmodel.TYPE_REFUND
import eg.masroof.ui.viewmodel.TYPE_TRANSFER
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditExpenseRoute(
    onDone: () -> Unit,
    viewModel: EditExpenseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow()
    val context = LocalContext.current
    LaunchedEffect(state.form.done) {
        if (state.form.done) {
            if (state.form.gone) Toast.makeText(context, R.string.edit_gone, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }
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
            viewModel::onPaidBy,
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
            "WHATSAPP" -> R.string.source_whatsapp
            "IOS_SHORTCUT" -> R.string.source_ios_shortcut
            "UPLOAD" -> R.string.source_upload
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
            TYPE_REFUND to stringResource(R.string.edit_type_refund),
        ),
        selected = form.type,
        enabled = !form.locked,
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
        enabled = !form.locked,
        modifier = Modifier.fillMaxWidth(),
    )
    DateField(form.date, form.dateInvalid, !form.locked, actions.onDate)
    Text(stringResource(R.string.add_category), style = MaterialTheme.typography.titleSmall)
    ChipRow(
        state.categories.map { it.id to (if (arabic) it.nameAr else it.nameEn) },
        selected = form.categoryId,
        enabled = !form.locked,
        tinted = true,
        onSelect = { actions.onCategory(it) },
    )
    // Only with two or more members; a missing member shows as "Not set" (REQ-EXP-047).
    if (state.members.size >= 2) {
        Text(stringResource(R.string.paid_by), style = MaterialTheme.typography.titleSmall)
        ChipRow(
            listOf("" to stringResource(R.string.paid_by_not_set)) + state.members.map { it.id to it.displayName },
            selected = form.paidByMemberId.orEmpty(),
            enabled = !form.locked,
            onSelect = { actions.onPaidBy(it.ifEmpty { null }) },
        )
    }
    OutlinedTextField(
        value = form.merchant,
        onValueChange = actions.onMerchant,
        label = { Text(stringResource(R.string.edit_merchant)) },
        singleLine = true,
        enabled = !form.locked,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.notes,
        onValueChange = actions.onNotes,
        label = { Text(stringResource(R.string.add_note)) },
        isError = form.notesInvalid,
        supportingText = { if (form.notesInvalid) Text(stringResource(R.string.edit_notes_error)) },
        enabled = !form.locked,
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
                    form.error == EditError.FAILED -> R.string.edit_error_failed
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
                    enabled = !form.locked,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(stringResource(R.string.edit_confirm)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onSave,
                    enabled = !form.locked,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.edit_save)) }
                OutlinedButton(
                    onClick = actions.onAskDelete,
                    enabled = !form.locked,
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** The date as a tappable field that opens a Material 3 date picker; no free-text parsing. Shown per locale and digit setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    date: LocalDate,
    invalid: Boolean,
    enabled: Boolean,
    onDate: (LocalDate) -> Unit,
) {
    val style = LocalMoneyStyle.current
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.edit_date)
    val shown = DateFormat.shortDate(date, style.arabic, style.digits)
    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "$label: $shown" },
    ) {
        Text("$label: $shown", color = if (invalid) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    if (invalid) {
        Text(stringResource(R.string.edit_date_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (open) {
        // The picker works in UTC midnights; the business date is a plain calendar date (Cairo noon is applied on save).
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        open = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.edit_date_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        ) { DatePicker(state = pickerState) }
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
