package eg.masroof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DigitStyle
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.SettingsUiState
import eg.masroof.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsRoute(
    onToggleLanguage: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state,
        SettingsActions(
            viewModel::onUrl,
            viewModel::onDigits,
            viewModel::save,
            viewModel::logout,
            viewModel::confirmLogout,
            viewModel::dismissLogout,
            viewModel::syncNow,
            onToggleLanguage,
        ),
    )
}

class SettingsActions(
    val onUrl: (String) -> Unit,
    val onDigits: (DigitStyle) -> Unit,
    val onSave: () -> Unit,
    val onLogout: () -> Unit,
    val onConfirmLogout: () -> Unit,
    val onDismissLogout: () -> Unit,
    val onSyncNow: () -> Unit,
    val onToggleLanguage: () -> Unit = {},
)

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val onUrl = actions.onUrl
    val onDigits = actions.onDigits
    val onSave = actions.onSave
    val onLogout = actions.onLogout
    state.confirmLogoutUnsynced?.let { count ->
        AlertDialog(
            onDismissRequest = actions.onDismissLogout,
            text = {
                Text(pluralStringResource(R.plurals.logout_unsynced_warning, count, count))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    // Nothing left that can sync (all permanently failed): offering "Sync now" would do nothing.
                    if (state.confirmLogoutRetryable > 0) {
                        TextButton(onClick = actions.onSyncNow, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.logout_sync_now))
                        }
                    }
                    TextButton(onClick = actions.onConfirmLogout, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.logout_anyway))
                    }
                    TextButton(onClick = actions.onDismissLogout, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.logout_cancel))
                    }
                }
            },
        )
    }
    val effectiveDigits = state.digitStyle ?: LocalMoneyStyle.current.digits
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        SectionCard(stringResource(R.string.settings_section_server)) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onUrl,
                label = { Text(stringResource(R.string.settings_server_url)) },
                isError = state.urlInvalid,
                supportingText = { if (state.urlInvalid) Text(stringResource(R.string.settings_server_url_error)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionCard(stringResource(R.string.settings_section_display)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_digits), modifier = Modifier.weight(1f))
                Switch(
                    checked = effectiveDigits == DigitStyle.ARABIC_INDIC,
                    onCheckedChange = { onDigits(if (it) DigitStyle.ARABIC_INDIC else DigitStyle.WESTERN) },
                )
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_language), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = actions.onToggleLanguage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.login_language_switch))
                }
            }
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.settings_save))
        }
        if (state.saved) Text(stringResource(R.string.settings_saved), color = MaterialTheme.colorScheme.primary)
        OutlinedButton(
            onClick = onLogout,
            enabled = !state.loggingOut,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(stringResource(R.string.settings_logout))
        }
    }
}
