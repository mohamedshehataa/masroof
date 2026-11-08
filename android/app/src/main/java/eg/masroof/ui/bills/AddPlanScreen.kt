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
import eg.masroof.data.bills.BillProvider
import eg.masroof.data.bills.BillPurpose
import eg.masroof.data.bills.PlanField
import eg.masroof.ui.theme.LocalMoneyStyle

private typealias Edit = ((AddPlanForm) -> AddPlanForm) -> Unit

private val REMINDER_CHOICES = listOf(0, 1, 2, 3, 7)

@Composable
fun AddPlanRoute(
    onSaved: (String) -> Unit,
    viewModel: AddPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    AddPlanContent(state, onEdit = viewModel::edit, onSave = viewModel::save)
}

@Composable
private fun purposeLabel(p: BillPurpose): String =
    stringResource(
        when (p) {
            BillPurpose.PHONE -> R.string.bills_purpose_phone
            BillPurpose.CAR -> R.string.bills_purpose_car
            BillPurpose.LAPTOP -> R.string.bills_purpose_laptop
            BillPurpose.FURNITURE -> R.string.bills_purpose_furniture
            BillPurpose.AC -> R.string.bills_purpose_ac
            BillPurpose.FRIDGE -> R.string.bills_purpose_fridge
            BillPurpose.TV -> R.string.bills_purpose_tv
            BillPurpose.PERSONAL_LOAN -> R.string.bills_purpose_personal_loan
        },
    )

@Composable
private fun errorText(
    errors: Set<PlanField>,
    field: PlanField,
    message: Int,
): String? = if (field in errors) stringResource(message) else null

@Composable
fun AddPlanContent(
    state: AddPlanState,
    onEdit: ((AddPlanForm) -> AddPlanForm) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val errors = state.errors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.bills_add),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        ProviderAndPurpose(form, onEdit)
        Field(
            label = stringResource(R.string.bills_name),
            value = form.title,
            error = errorText(errors, PlanField.TITLE, R.string.bills_error_title),
            onChange = { v -> onEdit { it.copy(title = v) } },
            keyboard = KeyboardType.Text,
        )
        Field(
            label = stringResource(if (form.isGam3eya) R.string.bills_share else R.string.bills_monthly_amount),
            value = form.monthly,
            error = errorText(errors, PlanField.AMOUNT, R.string.bills_error_amount),
            onChange = { v -> onEdit { it.copy(monthly = v) } },
            keyboard = KeyboardType.Decimal,
        )
        if (form.isGam3eya) Gam3eyaFields(form, errors, onEdit) else DurationFields(form, errors, onEdit)
        Field(
            stringResource(R.string.bills_first_payment_date),
            form.firstDate,
            errorText(errors, PlanField.FIRST_DATE, R.string.bills_error_date),
            { v -> onEdit { it.copy(firstDate = v) } },
            KeyboardType.Text,
        )
        if (state.askAlreadyPaid) {
            Field(
                stringResource(R.string.bills_already_paid),
                form.alreadyPaid ?: form.dueBefore(state.today).toString(),
                errorText(errors, PlanField.ALREADY_PAID, R.string.bills_error_paid),
                { v -> onEdit { it.copy(alreadyPaid = v) } },
                KeyboardType.Number,
            )
        }
        if (!form.isGam3eya) ExtraFields(state, onEdit)
        AccountAndReminder(state, onEdit)
        Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bills_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderAndPurpose(
    form: AddPlanForm,
    onEdit: Edit,
) {
    Text(stringResource(R.string.bills_provider), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BillProvider.entries.forEach { p ->
            FilterChip(
                selected = form.provider == p,
                onClick = { onEdit { it.copy(provider = p) } },
                label = { Text(providerLabel(p.key) ?: stringResource(R.string.bills_provider_none)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    if (form.isGam3eya) return
    Text(stringResource(R.string.bills_what_for), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BillPurpose.entries.forEach { purpose ->
            val label = purposeLabel(purpose)
            FilterChip(
                selected = form.purpose == purpose,
                onClick = {
                    onEdit {
                        it.copy(
                            purpose = purpose,
                            title =
                                if (it.title.isBlank() ||
                                    it.purpose != null
                                ) {
                                    label
                                } else {
                                    it.title
                                },
                        )
                    }
                },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun Gam3eyaFields(
    form: AddPlanForm,
    errors: Set<PlanField>,
    onEdit: Edit,
) {
    Field(
        stringResource(R.string.bills_members),
        form.members,
        errorText(errors, PlanField.COUNT, R.string.bills_error_count),
        { v -> onEdit { it.copy(members = v) } },
        KeyboardType.Number,
    )
    Field(
        stringResource(R.string.bills_turn),
        form.turn,
        errorText(errors, PlanField.TURN, R.string.bills_error_turn),
        { v -> onEdit { it.copy(turn = v) } },
        KeyboardType.Number,
    )
    Field(
        stringResource(R.string.bills_payout),
        form.payout,
        errorText(errors, PlanField.PAYOUT, R.string.bills_error_amount),
        { v -> onEdit { it.copy(payout = v) } },
        KeyboardType.Decimal,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationFields(
    form: AddPlanForm,
    errors: Set<PlanField>,
    onEdit: Edit,
) {
    val derived = form.derived()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !form.byEndDate,
            onClick = { onEdit { it.copy(byEndDate = false) } },
            label = { Text(stringResource(R.string.bills_by_months)) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
            selected = form.byEndDate,
            onClick = { onEdit { it.copy(byEndDate = true) } },
            label = { Text(stringResource(R.string.bills_by_end_date)) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
    if (form.byEndDate) {
        Field(
            stringResource(R.string.bills_end_date),
            form.endDate,
            errorText(errors, PlanField.END_DATE, R.string.bills_error_end_date),
            { v -> onEdit { it.copy(endDate = v) } },
            KeyboardType.Text,
        )
        derived.count?.let { Text(stringResource(R.string.bills_computed_count, billNumber(it))) }
    } else {
        Field(
            stringResource(R.string.bills_months),
            form.count,
            errorText(errors, PlanField.COUNT, R.string.bills_error_count),
            { v -> onEdit { it.copy(count = v) } },
            KeyboardType.Number,
        )
        derived.endDate?.let { Text(stringResource(R.string.bills_computed_end, billDate(it))) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraFields(
    state: AddPlanState,
    onEdit: Edit,
) {
    val form = state.form
    val arabic = LocalMoneyStyle.current.arabic
    Field(
        stringResource(R.string.bills_purchase_price_optional),
        form.purchasePrice,
        errorText(state.errors, PlanField.PURCHASE_PRICE, R.string.bills_error_amount),
        { v -> onEdit { it.copy(purchasePrice = v) } },
        KeyboardType.Decimal,
    )
    Field(
        stringResource(R.string.bills_down_payment_optional),
        form.downPayment,
        errorText(state.errors, PlanField.DOWN_PAYMENT, R.string.bills_error_amount),
        { v -> onEdit { it.copy(downPayment = v) } },
        KeyboardType.Decimal,
    )
    Text(stringResource(R.string.bills_category), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.categories.forEach { c ->
            FilterChip(
                selected = form.categoryId == c.id,
                onClick = { onEdit { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } },
                label = { Text(if (arabic) c.nameAr else c.nameEn) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountAndReminder(
    state: AddPlanState,
    onEdit: Edit,
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
    Text(stringResource(R.string.bills_remind_me), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        REMINDER_CHOICES.forEach { days ->
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
