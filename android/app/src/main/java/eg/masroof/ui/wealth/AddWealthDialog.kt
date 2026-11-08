package eg.masroof.ui.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.data.wealth.ASSET_KINDS
import eg.masroof.data.wealth.AssetDraft
import eg.masroof.data.wealth.AssetField
import eg.masroof.data.wealth.AssetForm
import eg.masroof.data.wealth.DebtDraft
import eg.masroof.data.wealth.DebtField
import eg.masroof.data.wealth.DebtForm
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MANUAL_ASSET_KINDS
import eg.masroof.data.wealth.MetalInput
import eg.masroof.data.wealth.WealthValidation

/** One dialog for the two things Wealth -> Add can create: something owned, or a debt (either direction). */
@Composable
fun AddWealthDialog(
    onDismiss: () -> Unit,
    onAsset: (AssetDraft) -> Unit,
    onDebt: (DebtDraft) -> Unit,
    today: java.time.LocalDate,
    onMetal: (HoldingDraft, LotDraft) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(0) }
    var metal by remember { mutableStateOf(MetalForm()) }
    var metalErrors by remember { mutableStateOf(emptySet<MetalInput.Field>()) }
    var asset by remember { mutableStateOf(AssetForm()) }
    var debt by remember { mutableStateOf(DebtForm()) }
    var assetErrors by remember { mutableStateOf(emptySet<AssetField>()) }
    var debtErrors by remember { mutableStateOf(emptySet<DebtField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wealth_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == 0, { mode = 0 }, { Text(stringResource(R.string.wealth_add_asset)) }, Modifier.heightIn(min = 48.dp))
                    FilterChip(mode == 1, { mode = 1 }, { Text(stringResource(R.string.wealth_add_debt)) }, Modifier.heightIn(min = 48.dp))
                    FilterChip(mode == 2, { mode = 2 }, { Text(stringResource(R.string.gold_add_chip)) }, Modifier.heightIn(min = 48.dp))
                }
                when (mode) {
                    1 -> DebtFields(debt, debtErrors) { debt = it }
                    2 -> MetalCreateFields(metal, metalErrors) { metal = it }
                    else -> AssetFields(asset, assetErrors) { asset = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        1 -> {
                            val checked = WealthValidation.debt(debt)
                            debtErrors = checked.errors
                            checked.value?.let(onDebt)
                        }
                        2 -> {
                            val checked = validateMetal(metal, today)
                            metalErrors = checked.errors
                            checked.value?.let { (holding, lot) -> onMetal(holding, lot) }
                        }
                        else -> {
                            val checked = WealthValidation.asset(asset)
                            assetErrors = checked.errors
                            checked.value?.let(onAsset)
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.wealth_form_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wealth_form_cancel)) }
        },
    )
}

@Composable
private fun DebtFields(
    form: DebtForm,
    errors: Set<DebtField>,
    onChange: (DebtForm) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!form.owedToMe, { onChange(form.copy(owedToMe = false)) }, { Text(stringResource(R.string.wealth_form_i_owe)) })
        FilterChip(form.owedToMe, { onChange(form.copy(owedToMe = true)) }, { Text(stringResource(R.string.wealth_form_owed_to_me)) })
    }
    FormField(
        form.name,
        { onChange(form.copy(name = it)) },
        R.string.wealth_form_name,
        error =
            R.string.wealth_error_name.takeIf {
                DebtField.NAME in
                    errors
            },
    )
    FormField(
        form.amount,
        { onChange(form.copy(amount = it)) },
        R.string.wealth_form_amount,
        error = R.string.wealth_error_amount.takeIf { DebtField.AMOUNT in errors },
        keyboard = KeyboardType.Decimal,
    )
    FormField(
        form.due,
        { onChange(form.copy(due = it)) },
        R.string.wealth_form_due,
        error =
            R.string.wealth_error_date.takeIf {
                DebtField.DUE in
                    errors
            },
    )
}

private fun assetKindLabel(kind: String): Int =
    when (kind) {
        "PROPERTY" -> R.string.wealth_kind_property
        "VEHICLE" -> R.string.wealth_kind_vehicle
        "BUSINESS" -> R.string.wealth_kind_business
        "CERTIFICATE" -> R.string.wealth_kind_certificate
        "FUND" -> R.string.wealth_kind_fund
        else -> R.string.wealth_kind_other
    }

@Composable
private fun AssetFields(
    form: AssetForm,
    errors: Set<AssetField>,
    onChange: (AssetForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ASSET_KINDS.chunked(3).forEach { rowKinds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { k ->
                    FilterChip(form.kind == k, { onChange(form.copy(kind = k)) }, { Text(stringResource(assetKindLabel(k))) })
                }
            }
        }
    }
    FormField(
        form.name,
        { onChange(form.copy(name = it)) },
        R.string.wealth_form_name,
        error =
            R.string.wealth_error_name.takeIf {
                AssetField.NAME in
                    errors
            },
    )
    if (form.kind == "CERTIFICATE") {
        Text(stringResource(R.string.wealth_cert_monthly), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
    if (form.kind in MANUAL_ASSET_KINDS) {
        FormField(
            form.value,
            { onChange(form.copy(value = it)) },
            R.string.wealth_form_value,
            error = R.string.wealth_error_amount.takeIf { AssetField.VALUE in errors },
            keyboard = KeyboardType.Decimal,
        )
    } else {
        FormField(
            form.principal,
            { onChange(form.copy(principal = it)) },
            R.string.wealth_form_principal,
            error = R.string.wealth_error_amount.takeIf { AssetField.PRINCIPAL in errors },
            keyboard = KeyboardType.Decimal,
        )
        FormField(
            form.rate,
            { onChange(form.copy(rate = it)) },
            R.string.wealth_form_rate,
            error = R.string.wealth_error_rate.takeIf { AssetField.RATE in errors },
            keyboard = KeyboardType.Decimal,
        )
        FormField(
            form.start,
            { onChange(form.copy(start = it)) },
            R.string.wealth_form_start,
            error = R.string.wealth_error_date.takeIf { AssetField.START in errors },
        )
        if (form.kind == "CERTIFICATE") {
            FormField(
                form.maturity,
                { onChange(form.copy(maturity = it)) },
                R.string.wealth_form_maturity,
                error = R.string.wealth_error_maturity.takeIf { AssetField.MATURITY in errors },
            )
        }
    }
}
