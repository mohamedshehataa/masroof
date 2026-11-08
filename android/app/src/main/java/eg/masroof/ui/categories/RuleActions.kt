package eg.masroof.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.categories.MAX_MERCHANT
import eg.masroof.ui.components.CategoryAvatar
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.MerchantRulesUiState
import eg.masroof.ui.viewmodel.MerchantRulesViewModel
import eg.masroof.ui.viewmodel.takeCodePoints

class RuleActions(
    val onAdd: (merchant: String, categoryId: String?, onSaved: () -> Unit) -> Unit = { _, _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onClearError: () -> Unit = {},
)

@Composable
fun MerchantRulesRoute(viewModel: MerchantRulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MerchantRulesContent(state, RuleActions(viewModel::add, viewModel::delete, viewModel::clearError))
}

/** Settings -> Merchant rules: the list (delete), an empty state, and the "Add rule" form (merchant text + category). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MerchantRulesContent(
    state: MerchantRulesUiState,
    actions: RuleActions,
    modifier: Modifier = Modifier,
) {
    val arabic = LocalMoneyStyle.current.arabic
    var merchant by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.rules_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.rules_add_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = merchant,
            onValueChange = {
                merchant = it.takeCodePoints(MAX_MERCHANT)
                actions.onClearError()
            },
            label = { Text(stringResource(R.string.rules_merchant)) },
            isError = state.emptyKeyError,
            supportingText = { if (state.emptyKeyError) Text(stringResource(R.string.rules_error_empty)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.rules_category), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEach { c ->
                FilterChip(
                    selected = categoryId == c.id,
                    onClick = { categoryId = c.id },
                    label = { Text(listOfNotNull(c.emoji, if (arabic) c.nameAr else c.nameEn).joinToString(" ")) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Button(
            onClick = {
                actions.onAdd(merchant, categoryId) {
                    merchant = ""
                    categoryId = null
                }
            },
            enabled = categoryId != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.rules_add)) }
        when {
            !state.loaded -> Text(stringResource(R.string.rules_loading))
            state.rules.isEmpty() -> Text(stringResource(R.string.rules_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ->
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.rules, key = { it.rule.id }) { row ->
                        val name = row.category?.let { if (arabic) it.nameAr else it.nameEn }.orEmpty()
                        val del = stringResource(R.string.rules_delete_desc, row.rule.merchantKey)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryAvatar(name, row.category?.id, size = 40.dp, emoji = row.category?.emoji)
                            Text(
                                stringResource(R.string.rules_line, row.rule.merchantKey, name),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { actions.onDelete(row.rule.id) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = del },
                            ) { Text(stringResource(R.string.cat_delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
        }
    }
}
