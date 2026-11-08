package eg.masroof.ui.categories

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.RuleOffer
import eg.masroof.ui.viewmodel.RulePromptStage
import eg.masroof.ui.viewmodel.RulePromptViewModel

/** "Always file X under Y?" then "Also change the N past entries?" then Undo, shown after a saved category change. */
@Composable
fun RulePromptHost(
    offer: RuleOffer,
    onFinished: () -> Unit,
    viewModel: RulePromptViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val names by viewModel.categoryNames.collectAsStateWithLifecycle()
    val arabic = LocalMoneyStyle.current.arabic
    LaunchedEffect(offer) { viewModel.begin(offer) }
    LaunchedEffect(finished) { if (finished) onFinished() }
    val category = names[offer.categoryId]
    val categoryName = category?.let { if (arabic) it.nameAr else it.nameEn }.orEmpty()
    RulePromptDialog(stage, offer.merchant, categoryName, viewModel::yes, viewModel::no, viewModel::undo)
}

/** Stateless dialog of one [stage]; nothing is shown while Idle. */
@Composable
fun RulePromptDialog(
    stage: RulePromptStage,
    merchant: String,
    categoryName: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onUndo: () -> Unit,
) {
    val digits = LocalMoneyStyle.current.digits
    val button = Modifier.heightIn(min = 48.dp)
    when (stage) {
        RulePromptStage.Idle -> Unit
        RulePromptStage.AskRule ->
            AlertDialog(
                onDismissRequest = onNo,
                title = { Text(stringResource(R.string.prompt_rule_title, merchant, categoryName)) },
                text = { Text(stringResource(R.string.prompt_rule_body)) },
                confirmButton = { TextButton(onClick = onYes, modifier = button) { Text(stringResource(R.string.prompt_yes_always)) } },
                dismissButton = { TextButton(onClick = onNo, modifier = button) { Text(stringResource(R.string.prompt_not_now)) } },
            )
        is RulePromptStage.AskPast ->
            AlertDialog(
                onDismissRequest = onNo,
                title = {
                    Text(pluralStringResource(R.plurals.prompt_past_title, stage.count, DateFormat.number(stage.count, digits), merchant))
                },
                confirmButton = { TextButton(onClick = onYes, modifier = button) { Text(stringResource(R.string.prompt_change_them)) } },
                dismissButton = { TextButton(onClick = onNo, modifier = button) { Text(stringResource(R.string.prompt_leave_them)) } },
            )
        is RulePromptStage.Changed ->
            AlertDialog(
                onDismissRequest = onNo,
                title = { Text(pluralStringResource(R.plurals.prompt_changed, stage.count, DateFormat.number(stage.count, digits))) },
                confirmButton = { TextButton(onClick = onNo, modifier = button) { Text(stringResource(R.string.prompt_done)) } },
                dismissButton = { TextButton(onClick = onUndo, modifier = button) { Text(stringResource(R.string.prompt_undo)) } },
            )
    }
}
