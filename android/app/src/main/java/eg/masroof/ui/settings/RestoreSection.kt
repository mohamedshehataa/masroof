@file:Suppress("MatchingDeclarationName") // the section and its actions class belong together

package eg.masroof.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.DigitStyle
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreInfo
import eg.masroof.data.backup.RestoreState
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Restore section can do; a plain class so tests pass fakes. */
class RestoreActions(
    val onRestoreFromBackup: () -> Unit,
    val onPicked: (android.net.Uri?) -> Unit,
    val onPassphrase: (String) -> Unit,
    val onContinue: () -> Unit,
    val onCancel: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Settings -> Restore from backup (REQ-SEC-068, REQ-OPS-178). [onDone] returns to Home, which shows the restored data from Room. */
@Composable
fun RestoreSection(
    onDone: () -> Unit = {},
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RestoreSectionContent(
        state,
        RestoreActions(
            viewModel::onRestoreFromBackup,
            viewModel::onPicked,
            viewModel::onPassphrase,
            viewModel::onContinue,
            viewModel::onCancel,
            viewModel::onConfirm,
            { viewModel.onDismiss().also { onDone() } },
        ),
        onFailureDismiss = viewModel::onDismiss,
    )
}

@Composable
fun RestoreSectionContent(
    state: RestoreUiState,
    actions: RestoreActions,
    onFailureDismiss: () -> Unit = actions.onDismiss,
) {
    var launched by rememberSaveable { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            launched = false
            actions.onPicked(uri)
        }
    LaunchedEffect(state.picking) {
        if (state.picking && !launched) {
            launched = true
            picker.launch(arrayOf("*/*"))
        }
    }
    val style = LocalMoneyStyle.current
    when (val run = state.run) {
        RestoreState.NeedsPassphrase -> RestorePassphrase(state, actions)
        is RestoreState.Confirm -> ConfirmDialog(run.info, actions, style.arabic, style.digits)
        else -> Unit
    }
    SectionCard(stringResource(R.string.restore_title)) {
        Text(stringResource(R.string.restore_explain))
        when (val run = state.run) {
            is RestoreState.Checking -> Progress(stringResource(R.string.restore_checking), run.progress, actions.onCancel)
            RestoreState.Restoring -> Progress(stringResource(R.string.restore_restoring), null, null)
            RestoreState.Done -> {
                Text(stringResource(R.string.restore_done), color = MaterialTheme.colorScheme.primary)
                TextButton(
                    onClick = actions.onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.restore_ok)) }
            }
            is RestoreState.Failed -> {
                Text(failureText(run), color = MaterialTheme.colorScheme.error)
                TextButton(
                    onClick = onFailureDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.restore_ok)) }
            }
            else -> Unit
        }
        Button(
            onClick = actions.onRestoreFromBackup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.restore_button)) }
    }
}

@Composable
private fun failureText(run: RestoreState.Failed): String =
    when (run.failure) {
        RestoreFailure.NOT_A_BACKUP -> stringResource(R.string.restore_err_not_backup)
        RestoreFailure.NEWER_VERSION -> stringResource(R.string.restore_err_newer)
        RestoreFailure.CANNOT_OPEN, RestoreFailure.INVALID_CONTENT -> stringResource(R.string.restore_err_cannot_open)
        RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED -> stringResource(R.string.restore_err_wrong)
        RestoreFailure.TOO_OLD -> stringResource(R.string.restore_err_too_old)
        RestoreFailure.STORAGE_FULL ->
            stringResource(R.string.restore_err_space, DateFormat.digitsAs(run.neededMb.toString(), LocalMoneyStyle.current.digits))
        RestoreFailure.CANNOT_READ -> stringResource(R.string.restore_err_read)
        RestoreFailure.BUSY -> stringResource(R.string.restore_err_busy)
        RestoreFailure.FAILED -> stringResource(R.string.restore_err_failed)
        RestoreFailure.INCOMPLETE -> stringResource(R.string.restore_err_incomplete)
    }

@Composable
private fun Progress(
    label: String,
    percent: Int?,
    onCancel: (() -> Unit)?,
) {
    val desc = stringResource(R.string.restore_progress_desc)
    Text(label)
    val modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc }
    if (percent ==
        null
    ) {
        LinearProgressIndicator(modifier = modifier)
    } else {
        LinearProgressIndicator(progress = { percent / PERCENT_MAX }, modifier = modifier)
    }
    if (onCancel != null) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.restore_cancel)) }
    }
}

/** The passphrase screen: one password field, FLAG_SECURE on this window and the activity behind it, cleared after use. */
@Composable
private fun RestorePassphrase(
    state: RestoreUiState,
    actions: RestoreActions,
) {
    Dialog(
        onDismissRequest = actions.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        SecureWindow()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.restore_pass_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = actions.onPassphrase,
                    label = { Text(stringResource(R.string.restore_pass_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    textStyle = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.restore_cancel))
                    }
                    Button(onClick = actions.onContinue, enabled = state.canContinue, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.restore_continue))
                    }
                }
            }
        }
    }
}

/** "Backup from 27 Oct 2026, 14:05 - 1,240 entries, 37 receipts, 4 budgets. This replaces all data on this phone (N entries)." */
@Composable
private fun ConfirmDialog(
    info: RestoreInfo,
    actions: RestoreActions,
    arabic: Boolean,
    digits: DigitStyle,
) {
    val entries =
        pluralStringResource(R.plurals.backup_entries, info.entries.toInt(), DateFormat.digitsAs(groupedCount(info.entries), digits))
    val receipts = pluralStringResource(R.plurals.backup_receipts, info.receipts, DateFormat.number(info.receipts, digits))
    val budgets =
        pluralStringResource(R.plurals.restore_budgets, info.budgets.toInt(), DateFormat.digitsAs(groupedCount(info.budgets), digits))
    val current =
        pluralStringResource(
            R.plurals.backup_entries,
            info.currentEntries.toInt(),
            DateFormat.digitsAs(groupedCount(info.currentEntries), digits),
        )
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.restore_confirm,
                    restoreDate(info.createdAtMillis, arabic, digits),
                    entries,
                    receipts,
                    budgets,
                    current,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.restore_confirm_button)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancel,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.restore_cancel)) }
        },
    )
}

/** `27 Oct 2026, 14:05` in Africa/Cairo, with the digit setting. */
internal fun restoreDate(
    millis: Long,
    arabic: Boolean,
    digits: DigitStyle,
): String {
    val at = Instant.ofEpochMilli(millis).atZone(CairoTime.zone)
    val locale = if (arabic) Locale.forLanguageTag("ar") else Locale.ENGLISH
    val day = DateFormat.digitsAs(DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(at), digits)
    return day + ", " + DateFormat.timeOfDay(millis, digits)
}

private const val PERCENT_MAX = 100f
