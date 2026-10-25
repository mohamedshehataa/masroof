package eg.masroof.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.AddUiState

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
    // Save lives in a bar pinned below the scrolling form and above the keyboard (imePadding), so it never scrolls
    // away or hides behind the IME however many categories there are.
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AddFields(state, arabic, AddFieldActions(onAmount, onCategory, onAccount, onDate, onNotes))
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
            ) { Text(stringResource(R.string.add_save)) }
        }
    }
}

private class AddFieldActions(
    val onAmount: (String) -> Unit,
    val onCategory: (String?) -> Unit,
    val onAccount: (String?) -> Unit,
    val onDate: (String) -> Unit,
    val onNotes: (String) -> Unit,
)

@Composable
private fun ColumnScope.AddFields(
    state: AddUiState,
    arabic: Boolean,
    actions: AddFieldActions,
) {
    val form = state.form
    val onAmount = actions.onAmount
    val onCategory = actions.onCategory
    val onAccount = actions.onAccount
    val onDate = actions.onDate
    val onNotes = actions.onNotes
    run {
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
    }
}

/** Compact wrapping chips (48dp targets) instead of a tall radio list. Names come from the locale-matched field. */
@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            all.forEach { (id, label) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}
