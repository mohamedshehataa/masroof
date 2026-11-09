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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import eg.masroof.data.backup.BackupFailure
import eg.masroof.data.backup.BackupFormat
import eg.masroof.data.backup.BackupRun
import eg.masroof.data.backup.LastBackup
import eg.masroof.data.backup.PassphraseProblem
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Settings -> Backup (REQ-OPS-177): last backup, running, success and failure lines; the explanation and passphrase dialogs. */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackupSectionContent(
        state,
        BackupActions(
            viewModel::onBackUpNow,
            viewModel::onUnderstood,
            viewModel::onContinueExplain,
            viewModel::onPassphrase,
            viewModel::onAgain,
            viewModel::onContinuePassphrase,
            viewModel::onDismiss,
            viewModel::onPicked,
            viewModel::onCancelBackup,
            viewModel::onAcknowledge,
        ),
    )
}

@Composable
fun BackupSectionContent(
    state: BackupUiState,
    actions: BackupActions,
    now: Instant = Instant.now(),
) {
    // The system "Save to" picker: opened once per request (a rotation while it is open must not open a second one).
    var launched by rememberSaveable { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            launched = false
            actions.onPicked(uri)
        }
    LaunchedEffect(state.step) {
        if (state.step == BackupStep.PICKING && !launched) {
            launched = true
            picker.launch(state.defaultFileName)
        }
    }
    when (state.step) {
        BackupStep.EXPLAIN -> ExplainDialog(state, actions)
        BackupStep.PASSPHRASE, BackupStep.PICKING -> PassphraseScreen(state, actions)
        BackupStep.NONE -> Unit
    }
    val style = LocalMoneyStyle.current
    SectionCard(stringResource(R.string.backup_title)) {
        val last = state.last
        if (last == null) {
            Text(stringResource(R.string.backup_never), color = MaterialTheme.colorScheme.error)
        } else {
            Text(lastBackupLine(last, now, style.arabic, style.digits))
        }
        state.notice?.let { notice ->
            Text(
                stringResource(
                    when (notice) {
                        BackupNotice.INTERRUPTED -> R.string.backup_interrupted
                        BackupNotice.START_FAILED -> R.string.backup_start_failed
                        BackupNotice.ALREADY_RUNNING -> R.string.backup_already_running
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            if (state.fileKept) Text(stringResource(R.string.backup_file_kept))
            TextButton(
                onClick = actions.onAcknowledge,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.backup_ok)) }
        }
        RunStatus(state, actions, style.digits)
        Button(
            onClick = actions.onBackUpNow,
            enabled = state.run !is BackupRun.Running && !state.preparing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.backup_now))
        }
    }
}

@Composable
private fun RunStatus(
    state: BackupUiState,
    actions: BackupActions,
    digits: DigitStyle,
) {
    val progressLabel = stringResource(R.string.backup_progress_desc)
    when (val run = state.run) {
        is BackupRun.Running -> {
            Text(stringResource(R.string.backup_running, DateFormat.number(run.percent, digits)))
            LinearProgressIndicator(
                progress = { run.percent / PERCENT_MAX },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
            )
            OutlinedButton(onClick = actions.onCancelBackup, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_cancel))
            }
        }
        is BackupRun.Succeeded -> {
            Text(stringResource(R.string.backup_done), color = MaterialTheme.colorScheme.primary)
            if (run.missing > 0) {
                Text(pluralStringResource(R.plurals.backup_missing, run.missing, DateFormat.number(run.missing, digits)))
            }
            AcknowledgeButton(actions)
        }
        is BackupRun.Failed -> {
            Text(stringResource(R.string.backup_failed), color = MaterialTheme.colorScheme.error)
            Text(stringResource(reasonText(run.reason)))
            AcknowledgeButton(actions)
        }
        BackupRun.Cancelled -> {
            Text(stringResource(R.string.backup_cancelled), color = MaterialTheme.colorScheme.error)
            AcknowledgeButton(actions)
        }
        BackupRun.Idle -> if (state.preparing) Text(stringResource(R.string.backup_preparing))
    }
}

@Composable
private fun AcknowledgeButton(actions: BackupActions) {
    TextButton(onClick = actions.onAcknowledge, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.backup_ok))
    }
}

private fun reasonText(reason: BackupFailure): Int =
    when (reason) {
        BackupFailure.STORAGE_FULL -> R.string.backup_reason_storage_full
        BackupFailure.CANNOT_OPEN -> R.string.backup_reason_cannot_open
        BackupFailure.RECEIPT_CHANGED -> R.string.backup_reason_receipt_changed
        BackupFailure.KEY_LOST -> R.string.backup_reason_key_lost
        BackupFailure.KEY_UNAVAILABLE -> R.string.backup_reason_key_unavailable
        BackupFailure.OTHER -> R.string.backup_reason_other
    }

/** The first-backup explanation: the loss warning and a checkbox that must be ticked before Continue (REQ-SEC-067). */
@Composable
private fun ExplainDialog(
    state: BackupUiState,
    actions: BackupActions,
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.backup_explain_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_explain_body))
                Text(
                    stringResource(R.string.backup_explain_warning),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = state.understood, role = Role.Checkbox, onValueChange = actions.onUnderstood),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.understood, onCheckedChange = null)
                    Text(stringResource(R.string.backup_explain_check), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onContinueExplain, enabled = state.understood, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

/**
 * The passphrase screen: two fields, the reason Continue is off, and the residual-risk note. FLAG_SECURE is set on this window and
 * on the activity behind it: no screenshot, no screen recording, blank recents thumbnail. Mixed-direction text is shown as typed.
 */
@Composable
fun PassphraseScreen(
    state: BackupUiState,
    actions: BackupActions,
) {
    Dialog(
        onDismissRequest = actions.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        SecureWindow()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.backup_pass_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.backup_explain_warning), color = MaterialTheme.colorScheme.error)
                PassphraseField(state.passphrase, actions.onPassphrase, stringResource(R.string.backup_pass_field))
                PassphraseField(state.again, actions.onAgain, stringResource(R.string.backup_pass_again))
                val digits = LocalMoneyStyle.current.digits
                val reason =
                    when (val p = state.problem) {
                        null -> null
                        PassphraseProblem.Empty ->
                            stringResource(R.string.backup_pass_empty, DateFormat.number(BackupFormat.MIN_PASSPHRASE_CODE_POINTS, digits))
                        is PassphraseProblem.TooShort ->
                            stringResource(
                                R.string.backup_pass_too_short,
                                DateFormat.number(BackupFormat.MIN_PASSPHRASE_CODE_POINTS, digits),
                                DateFormat.number(p.have, digits),
                            )
                        PassphraseProblem.Mismatch -> stringResource(R.string.backup_pass_mismatch)
                    }
                if (reason != null) Text(reason, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.backup_pass_not_saved), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.backup_pass_not_covered), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.backup_cancel))
                    }
                    Button(
                        onClick = actions.onContinuePassphrase,
                        enabled = state.canContinue && state.step == BackupStep.PASSPHRASE,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.backup_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun PassphraseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = TextStyle(textDirection = TextDirection.Content),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** `Last backup: today 14:05, 1,240 entries, 37 receipts, 58 MB`, the time in Africa/Cairo and the digits as set. */
@Composable
private fun lastBackupLine(
    last: LastBackup,
    now: Instant,
    arabic: Boolean,
    digits: DigitStyle,
): String {
    val at = Instant.ofEpochMilli(last.atMillis).atZone(CairoTime.zone)
    val time = DateFormat.digitsAs(DateTimeFormatter.ofPattern("HH:mm").format(at), digits)
    val day =
        if (at.toLocalDate() == CairoTime.today(now)) {
            stringResource(R.string.backup_when_today, time)
        } else {
            stringResource(R.string.backup_when_date, DateFormat.shortDate(at.toLocalDate(), arabic, digits), time)
        }
    val entries =
        pluralStringResource(R.plurals.backup_entries, last.entries.toInt(), DateFormat.digitsAs(groupedCount(last.entries), digits))
    val receipts = pluralStringResource(R.plurals.backup_receipts, last.receipts, DateFormat.number(last.receipts, digits))
    val size = stringResource(R.string.backup_size_mb, DateFormat.digitsAs(backupMegabytes(last.bytes), digits))
    return stringResource(R.string.backup_last, day, entries, receipts, size)
}

/** `1,240` with Western grouping; the digit setting rewrites the digits afterwards. */
internal fun groupedCount(n: Long): String = "%,d".format(java.util.Locale.ENGLISH, n)

private const val BYTES_PER_MB = 1024L * 1024L
private const val WHOLE_MB_FROM = 10L

/** `58` from ten megabytes up, one decimal below it (`2.4`); integer arithmetic. */
internal fun backupMegabytes(bytes: Long): String =
    if (bytes / BYTES_PER_MB >=
        WHOLE_MB_FROM
    ) {
        (bytes / BYTES_PER_MB).toString()
    } else {
        megabytes(bytes)
    }

private const val BACKUP_MIME = "application/octet-stream"
private const val PERCENT_MAX = 100f
