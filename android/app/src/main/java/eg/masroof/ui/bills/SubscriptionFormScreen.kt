package eg.masroof.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.IncomePreset
import eg.masroof.data.bills.KIND_BILL
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.SubscriptionService

private typealias SubEdit = ((SubscriptionForm) -> SubscriptionForm) -> Unit

@Composable
fun SubscriptionFormRoute(
    onSaved: (String) -> Unit,
    viewModel: SubscriptionFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    SubscriptionFormContent(state, onEdit = viewModel::edit, onSave = viewModel::save)
}

@Composable
private fun incomeName(preset: IncomePreset): String =
    stringResource(
        when (preset) {
            IncomePreset.SALARY -> R.string.subs_income_salary
            IncomePreset.CERTIFICATE -> R.string.subs_income_certificate
            IncomePreset.OTHER -> R.string.subs_income_other
        },
    )

@Composable
private fun kindLabel(kind: String): String =
    stringResource(
        when (kind) {
            KIND_BILL -> R.string.subs_kind_bill
            KIND_INCOME -> R.string.subs_kind_income
            else -> R.string.subs_kind_subscription
        },
    )

@Composable
private fun error(
    state: SubscriptionFormState,
    field: SubField,
    message: Int,
): String? = if (field in state.errors) stringResource(message) else null

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionFormContent(
    state: SubscriptionFormState,
    onEdit: SubEdit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (state.editing) R.string.subs_form_edit else R.string.subs_form_new),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(KIND_SUBSCRIPTION, KIND_BILL, KIND_INCOME).forEach { kind ->
                FilterChip(
                    selected = form.kind == kind,
                    onClick = { onEdit { it.copy(kind = kind, serviceKey = null) } },
                    label = { Text(kindLabel(kind)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(
            stringResource(if (form.isIncome) R.string.subs_income_type else R.string.subs_service),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.isIncome) {
                IncomePreset.entries.forEach { preset ->
                    val label = incomeName(preset)
                    FilterChip(
                        selected = form.serviceKey == preset.key,
                        onClick = { onEdit { it.copy(serviceKey = preset.key, name = label) } },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            } else {
                SubscriptionService.entries
                    .filter {
                        it.kind == form.kind ||
                            it == SubscriptionService.OTHER ||
                            it == SubscriptionService.GYM
                    }.forEach { service ->
                        val label = serviceName(service)
                        FilterChip(
                            selected = form.serviceKey == service.key,
                            onClick = {
                                onEdit {
                                    it.copy(
                                        serviceKey = service.key,
                                        name = if (service == SubscriptionService.OTHER) "" else label,
                                        categoryId = null,
                                    )
                                }
                            },
                            label = { Text(service.emoji + " " + label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
            }
        }
        Field(
            stringResource(R.string.subs_name),
            form.name,
            error(state, SubField.NAME, R.string.subs_error_name),
            { v -> onEdit { it.copy(name = v) } },
            KeyboardType.Text,
        )
        Field(
            stringResource(R.string.subs_amount),
            form.amount,
            error(state, SubField.AMOUNT, R.string.bills_error_amount),
            { v -> onEdit { it.copy(amount = v) } },
            KeyboardType.Decimal,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUBSCRIPTION_CURRENCIES.forEach { currency ->
                FilterChip(
                    selected = form.currency == currency,
                    onClick = { onEdit { it.copy(currency = currency) } },
                    label = { Text(currency) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(stringResource(R.string.subs_cadence), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cadence.entries.forEach { cadence ->
                FilterChip(
                    selected = form.cadence == cadence,
                    onClick = { onEdit { it.copy(cadence = cadence) } },
                    label = { Text(cadenceLabel(cadence)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Field(
            stringResource(if (form.isIncome) R.string.subs_next_payday else R.string.subs_next_date),
            form.date,
            error(state, SubField.DATE, R.string.bills_error_date),
            { v -> onEdit { it.copy(date = v) } },
            KeyboardType.Text,
        )
        Field(
            stringResource(R.string.subs_merchant),
            form.merchant,
            null,
            { v -> onEdit { it.copy(merchant = v) } },
            KeyboardType.Text,
        )
        AccountAndReminder(state, onEdit)
        Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subs_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountAndReminder(
    state: SubscriptionFormState,
    onEdit: SubEdit,
) {
    val form = state.form
    if (state.accounts.isNotEmpty()) {
        Text(stringResource(R.string.bills_account), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.accounts.forEach { a ->
                FilterChip(
                    selected = form.accountId == a.id,
                    onClick = { onEdit { it.copy(accountId = if (it.accountId == a.id) null else a.id) } },
                    label = { Text(a.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    if (form.isIncome) return
    Text(stringResource(R.string.bills_remind_me), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 1, 3, 7).forEach { days ->
            FilterChip(
                selected = form.reminderDays == days,
                onClick = { onEdit { it.copy(reminderDays = days) } },
                label = {
                    Text(
                        when (days) {
                            0 -> stringResource(R.string.bills_reminder_same_day)
                            1 -> stringResource(R.string.bills_reminder_one_day)
                            else -> stringResource(R.string.bills_reminder_n_days, billNumber(days))
                        },
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
