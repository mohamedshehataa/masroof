package eg.masroof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.answers.AtmChoice
import eg.masroof.ui.answers.AnswersSettingsViewModel
import eg.masroof.ui.components.SectionCard

private const val TARGET_DP = 48

/** Settings -> "Needs an answer": change the ATM choice and restore ignored cards (STORY-208). */
@Composable
fun AnswersSettingsSection(viewModel: AnswersSettingsViewModel = hiltViewModel()) {
    val atm by viewModel.atmChoice.collectAsStateWithLifecycle()
    val ignored by viewModel.ignored.collectAsStateWithLifecycle()
    AnswersSettingsContent(atm, ignored, viewModel::setAtmChoice, viewModel::restore)
}

@Composable
fun AnswersSettingsContent(
    atm: AtmChoice?,
    ignored: List<String>,
    onAtm: (AtmChoice?) -> Unit,
    onRestore: (String) -> Unit,
) {
    SectionCard(stringResource(R.string.answers_settings_title)) {
        Text(stringResource(R.string.answers_atm_setting), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AtmOption(R.string.answers_atm_opt_ask, atm == null) { onAtm(null) }
            AtmOption(R.string.answers_atm_opt_track, atm == AtmChoice.TRACK) { onAtm(AtmChoice.TRACK) }
            AtmOption(R.string.answers_atm_opt_count, atm == AtmChoice.COUNT) { onAtm(AtmChoice.COUNT) }
        }
        Text(
            stringResource(R.string.answers_ignored_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (ignored.isEmpty()) {
            Text(
                stringResource(R.string.answers_ignored_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                pluralStringResource(R.plurals.answers_ignored_count, ignored.size, ignored.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ignored.forEach { last4 ->
                val description = stringResource(R.string.answers_restore_desc, last4)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.answers_ignored_item, last4),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onRestore(last4) },
                        modifier = Modifier.heightIn(min = TARGET_DP.dp).semantics { contentDescription = description },
                    ) { Text(stringResource(R.string.answers_restore)) }
                }
            }
        }
    }
}

@Composable
private fun AtmOption(
    label: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(label)) },
        modifier = Modifier.heightIn(min = TARGET_DP.dp),
    )
}
