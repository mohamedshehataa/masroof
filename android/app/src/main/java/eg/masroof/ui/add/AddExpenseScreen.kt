package eg.masroof.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.AddExpenseViewModel
import eg.masroof.ui.AddUiState
import eg.masroof.ui.theme.LocalMoneyStyle

@Composable
fun AddExpenseRoute(
    onDone: () -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.form.saved) { if (state.form.saved) onDone() }
    AddExpenseContent(
        state = state,
        onAmount = viewModel::onAmount,
        onCategory = viewModel::onCategory,
        onAccount = viewModel::onAccount,
        onDate = viewModel::onDate,
        onNotes = viewModel::onNotes,
        onSave = viewModel::save,
    )
}

@Composable
fun AddExpenseContent(
    state: AddUiState,
    onAmount: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onAccount: (String?) -> Unit,
    onDate: (String) -> Unit,
    onNotes: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arabic = LocalMoneyStyle.current.arabic
    val form = state.form
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = form.amountText,
            onValueChange = onAmount,
            label = { Text(stringResource(R.string.add_amount)) },
            isError = form.amountInvalid,
            supportingText = { if (form.amountInvalid) Text(stringResource(R.string.add_amount_error)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.dateText,
            onValueChange = onDate,
            label = { Text(stringResource(R.string.add_date)) },
            isError = form.dateInvalid,
            supportingText = { if (form.dateInvalid) Text(stringResource(R.string.add_date_error)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OptionGroup(
            title = stringResource(R.string.add_category),
            options = state.categories.map { it.id to (if (arabic) it.nameAr else it.nameEn) },
            selected = form.categoryId,
            onSelect = onCategory,
        )
        OptionGroup(
            title = stringResource(R.string.add_account),
            options = state.accounts.map { it.id to it.label },
            selected = form.accountId,
            onSelect = onAccount,
        )
        OutlinedTextField(
            value = form.notes,
            onValueChange = onNotes,
            label = { Text(stringResource(R.string.add_note)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.add_offline_hint), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.add_save)) }
    }
}

@Composable
private fun OptionGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        val all = listOf<Pair<String?, String>>(null to stringResource(R.string.add_none)) + options
        all.forEach { (id, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected == id, onClick = { onSelect(id) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == id, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
