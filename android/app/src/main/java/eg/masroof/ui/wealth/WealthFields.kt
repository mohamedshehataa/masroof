package eg.masroof.ui.wealth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.theme.LocalMoneyStyle

/** A single-line form field with an optional error message under it. */
@Composable
internal fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    modifier: Modifier = Modifier,
    error: Int? = null,
    enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = { if (error != null) Text(stringResource(error)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    )
}

/** An amount in the active locale and digit setting, for places that need a plain string (descriptions, labels). */
@Composable
internal fun moneyText(minor: Long): String {
    val s = LocalMoneyStyle.current
    return MoneyFormat.format(Money(minor), s.arabic, s.digits)
}
