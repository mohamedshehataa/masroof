package eg.masroof.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.CountFormat
import eg.masroof.core.DateFormat
import eg.masroof.core.DigitStyle
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillRange
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.BackfillViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Read past SMS" (REQ-SMS-101..103): the permission plumbing around [BackfillContent]. READ_SMS is asked only here, only after the
 * user tapped Start and read the explanation.
 */
@Composable
fun BackfillSection(viewModel: BackfillViewModel = hiltViewModel()) {
    val context = LocalContext.current

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    var permitted by remember { mutableStateOf(granted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // back from the system settings page: the permission may have been given or taken away there
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = granted()
        viewModel.refresh()
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            permitted = ok
            denied = !ok
            if (ok) viewModel.start()
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    BackfillContent(
        state = state,
        range = range,
        permitted = permitted,
        denied = denied,
        onChoose = viewModel::choose,
        onStart = {
            denied = false
            viewModel.start()
        },
        onRequestPermission = {
            denied = false
            launcher.launch(Manifest.permission.READ_SMS)
        },
        onCancel = viewModel::cancel,
        onDismiss = viewModel::dismiss,
        onOpenSettings = {
            val uri = Uri.fromParts("package", context.packageName, null)
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
}

/** The whole section as a function of its state (so it is testable and previewable without Hilt or a real permission). */
@Suppress("LongParameterList") // one callback per user action
@Composable
internal fun BackfillContent(
    state: BackfillState,
    range: BackfillRange,
    permitted: Boolean,
    denied: Boolean,
    onChoose: (BackfillRange) -> Unit,
    onStart: () -> Unit,
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var explain by rememberSaveable { mutableStateOf(false) }
    val digits = LocalMoneyStyle.current.digits
    val running = state.phase == BackfillPhase.RUNNING
    val noPermission = denied || state.phase == BackfillPhase.PERMISSION_LOST
    // "Continue" only for the run that was stopped; another range starts a new one
    val continues = state.resumable && state.rangeKey == range.key
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backfill_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backfill_body), style = MaterialTheme.typography.bodySmall)
        if (running) {
            BackfillProgress(state, digits)
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backfill_cancel))
            }
        } else {
            RangeChoices(range, digits, onChoose)
            BackfillStatus(state, digits, permissionDenied = noPermission)
            if (noPermission) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backfill_open_settings))
                }
            }
            Button(
                onClick = { if (permitted) onStart() else explain = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (continues) R.string.backfill_resume else R.string.backfill_start)) }
            if (state.phase == BackfillPhase.DONE) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backfill_ok))
                }
            }
        }
    }
    if (explain) {
        // the explanation comes first; the system dialog follows only on Continue
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.backfill_rationale_title)) },
            text = { Text(stringResource(R.string.backfill_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explain = false
                        onRequestPermission()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.backfill_rationale_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { explain = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backfill_rationale_cancel))
                }
            },
        )
    }
}

@Composable
private fun BackfillProgress(
    state: BackfillState,
    digits: DigitStyle,
) {
    val description = stringResource(R.string.backfill_progress_desc)
    if (state.total < 0) {
        Text(stringResource(R.string.backfill_counting), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = description })
    } else {
        Text(progressLine(state, digits), style = MaterialTheme.typography.bodyMedium)
        val fraction = barFraction(state.read, state.total)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
    }
}

@Composable
private fun progressLine(
    state: BackfillState,
    digits: DigitStyle,
) = pluralStringResource(
    R.plurals.backfill_progress,
    state.entries,
    CountFormat.format(state.read, digits),
    CountFormat.format(state.total, digits),
    CountFormat.format(state.entries, digits),
    CountFormat.format(state.review, digits),
)

@Composable
private fun BackfillStatus(
    state: BackfillState,
    digits: DigitStyle,
    permissionDenied: Boolean,
) {
    if (permissionDenied) {
        Text(stringResource(R.string.backfill_denied), color = MaterialTheme.colorScheme.error)
    }
    when {
        state.phase == BackfillPhase.DONE && state.total == 0 -> Text(stringResource(R.string.backfill_empty))
        state.phase == BackfillPhase.DONE ->
            Text(
                pluralStringResource(
                    R.plurals.backfill_done,
                    state.entries,
                    CountFormat.format(state.entries, digits),
                    CountFormat.format(state.review, digits),
                ),
            )
        state.phase == BackfillPhase.STOPPED || state.phase == BackfillPhase.PERMISSION_LOST ->
            Text(pluralStringResource(R.plurals.backfill_stopped, state.entries, CountFormat.format(state.entries, digits)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeChoices(
    selected: BackfillRange,
    digits: DigitStyle,
    onChoose: (BackfillRange) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val style = LocalMoneyStyle.current
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(stringResource(R.string.backfill_range_title), style = MaterialTheme.typography.titleSmall)
        BackfillRange.CHOICES.forEach { days ->
            val label =
                stringResource(
                    when (days) {
                        30 -> R.string.backfill_range_30
                        90 -> R.string.backfill_range_90
                        else -> R.string.backfill_range_180
                    },
                )
            RangeRow(label, selected == BackfillRange.Days(days)) { onChoose(BackfillRange.Days(days)) }
        }
        val custom = selected as? BackfillRange.Since
        val customLabel =
            custom?.let { stringResource(R.string.backfill_range_since, DateFormat.shortDate(it.date, style.arabic, digits)) }
                ?: stringResource(R.string.backfill_range_custom)
        RangeRow(customLabel, custom != null) { picking = true }
    }
    if (picking) {
        val todayUtcMillis =
            LocalDate
                .now(CairoTime.zone)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val initial = (selected as? BackfillRange.Since)?.date ?: LocalDate.now(CairoTime.zone).minusDays(DEFAULT_DAYS)
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                // the start is at most today
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtcMillis
                    },
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            onChoose(BackfillRange.Since(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                        }
                        picking = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.backfill_pick_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backfill_pick_cancel))
                }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = { Text(stringResource(R.string.backfill_pick_date), Modifier.padding(16.dp)) },
            )
        }
    }
}

@Composable
private fun RangeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

private const val DEFAULT_DAYS = 90L

/** The progress bar's fill: messages read out of those to read (a bar position, not money). */
private fun barFraction(
    read: Int,
    of: Int,
): Float = if (of <= 0) 1f else (read.toFloat() / of).coerceIn(0f, 1f)
