package eg.masroof.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.goals.GoalEmoji

@Composable
fun GoalFormRoute(
    onDone: () -> Unit,
    viewModel: GoalFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalFormContent(
        state,
        GoalFormActions(viewModel::onName, viewModel::onEmoji, viewModel::onTarget, viewModel::onDate) { viewModel.save(onDone) },
    )
}

@Composable
fun GoalFormContent(
    state: GoalFormState,
    actions: GoalFormActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (state.editing) R.string.goals_form_edit_title else R.string.goals_form_new_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.goals_name)) },
            isError = state.nameError,
            supportingText = { if (state.nameError) Text(stringResource(R.string.goals_name_error)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.goals_pick_emoji), style = MaterialTheme.typography.titleSmall)
        EmojiGrid(state.emoji, actions.onEmoji)
        OutlinedTextField(
            value = state.targetText,
            onValueChange = actions.onTarget,
            label = { Text(stringResource(R.string.goals_target)) },
            isError = state.targetError != null,
            supportingText = {
                when (state.targetError) {
                    MoneyError.TOO_LARGE -> Text(stringResource(R.string.goals_err_too_large))
                    null -> Unit
                    else -> Text(stringResource(R.string.goals_err_amount))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.dateText,
            onValueChange = actions.onDate,
            label = { Text(stringResource(R.string.goals_target_date)) },
            isError = state.dateError,
            supportingText = { if (state.dateError) Text(stringResource(R.string.goals_err_date)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(actions.onSave, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.saving) {
            Text(stringResource(R.string.goals_save))
        }
    }
}

/** A fixed-height grid of money-life emoji; each cell is a 48dp target announced as "Emoji X". */
@Composable
private fun EmojiGrid(
    selected: String,
    onPick: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = GRID_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(GoalEmoji.ALL) { emoji ->
            val description = stringResource(R.string.goals_emoji_desc, emoji)
            val chosen = emoji == selected
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .border(if (chosen) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { onPick(emoji) }
                    .semantics {
                        contentDescription = description
                        this.selected = chosen
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

private val GRID_HEIGHT = 280.dp
