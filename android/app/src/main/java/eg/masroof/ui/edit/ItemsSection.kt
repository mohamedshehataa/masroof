package eg.masroof.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.receipts.MAX_ITEMS
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.EditFormState
import eg.masroof.ui.viewmodel.ItemRow
import eg.masroof.ui.viewmodel.SectionLoad
import eg.masroof.ui.viewmodel.itemsTotalMinor

private val TOUCH_TARGET = 48.dp

/** The informational "Items total" (sum of the item amounts) shown beside the transaction amount; nothing when no item has one. */
@Composable
internal fun ItemsTotalLine(form: EditFormState) {
    if (form.items.none { it.amount.isNotBlank() }) return
    val style = LocalMoneyStyle.current
    val total = MoneyFormat.format(Money(itemsTotalMinor(form.items), form.currency), style.arabic, style.digits)
    Text(
        stringResource(R.string.items_total, total),
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ItemsSection(
    form: EditFormState,
    actions: EditActions,
) {
    Text(
        stringResource(R.string.items_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    when (form.itemsLoad) {
        SectionLoad.LOADING -> LoadingLine(stringResource(R.string.items_loading))
        SectionLoad.FAILED -> ErrorLine(stringResource(R.string.items_load_failed), actions.onRetryItems)
        SectionLoad.LOADED -> {
            if (form.items.isEmpty()) {
                Text(
                    stringResource(R.string.items_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            form.items.forEachIndexed { index, row -> ItemRowFields(row, index + 1, !form.busy, form.currency, actions) }
            OutlinedButton(
                onClick = actions.onAddItem,
                enabled = !form.busy && form.items.size < MAX_ITEMS,
                modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
            ) { Text(stringResource(R.string.items_add)) }
        }
    }
}

@Composable
private fun ItemRowFields(
    row: ItemRow,
    number: Int,
    enabled: Boolean,
    currency: String,
    actions: EditActions,
) {
    val arabic = LocalMoneyStyle.current.arabic
    val numberText =
        java.text.NumberFormat
            .getIntegerInstance()
            .format(number)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { actions.onItemName(row.key, it) },
                label = { Text(stringResource(R.string.items_name)) },
                isError = row.invalid,
                supportingText = { if (row.invalid) Text(stringResource(R.string.items_row_error)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = row.qty,
                    onValueChange = { actions.onItemQty(row.key, it) },
                    label = { Text(stringResource(R.string.items_qty)) },
                    isError = row.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.amount,
                    onValueChange = { actions.onItemAmount(row.key, it) },
                    label = { Text(stringResource(R.string.items_amount)) },
                    prefix = { Text(MoneyFormat.symbol(currency, arabic)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    isError = row.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        IconButton(onClick = { actions.onRemoveItem(row.key) }, enabled = enabled, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.items_remove_desc, numberText))
        }
    }
}
