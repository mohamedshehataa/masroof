package eg.masroof.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val TARGET_DP = 48
private const val CSV_MIME = "text/csv"

/** Settings -> "Export CSV": pick a range, choose where to save, write the file (STORY-185). Works offline. */
@Composable
fun ExportCsvSection(viewModel: ExportCsvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The application context, so the lambdas that outlive this screen never hold the Activity.
    val context = LocalContext.current.applicationContext
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
            // Cancelling the picker returns null: nothing is written and nothing is shown.
            if (uri != null) {
                viewModel.export(
                    open = { context.contentResolver.openOutputStream(uri, "wt") },
                    discard = { discardDocument(context, uri) },
                )
            }
        }
    ExportCsvContent(
        state = state,
        onChoose = viewModel::choose,
        onFrom = viewModel::setCustomFrom,
        onTo = viewModel::setCustomTo,
        onExport = { launcher.launch(state.fileName) },
        onDismiss = viewModel::dismissResult,
    )
}

/** Removes the half-written document after a failure; best effort (not every provider can delete). */
private fun discardDocument(
    context: Context,
    uri: Uri,
) {
    DocumentsContract.deleteDocument(context.contentResolver, uri)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportCsvContent(
    state: ExportUiState,
    onChoose: (ExportChoice) -> Unit,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard(stringResource(R.string.export_title)) {
        Text(
            stringResource(R.string.export_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeChip(R.string.export_range_this_month, state.choice == ExportChoice.THIS_MONTH) { onChoose(ExportChoice.THIS_MONTH) }
            RangeChip(R.string.export_range_last_month, state.choice == ExportChoice.LAST_MONTH) { onChoose(ExportChoice.LAST_MONTH) }
            RangeChip(R.string.export_range_this_year, state.choice == ExportChoice.THIS_YEAR) { onChoose(ExportChoice.THIS_YEAR) }
            RangeChip(R.string.export_range_custom, state.choice == ExportChoice.CUSTOM) { onChoose(ExportChoice.CUSTOM) }
        }
        if (state.choice == ExportChoice.CUSTOM) {
            DateButton(R.string.export_from, state.from, onFrom)
            DateButton(R.string.export_to, state.to, onTo)
        }
        if (state.endBeforeStart) {
            Text(
                stringResource(R.string.export_end_before_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onExport,
            enabled = state.canExport,
            modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp),
        ) {
            Text(stringResource(if (state.status == ExportStatus.Exporting) R.string.export_working else R.string.export_action))
        }
        ExportResult(state.status, onDismiss)
    }
}

@Composable
private fun ExportResult(
    status: ExportStatus,
    onDismiss: () -> Unit,
) {
    val text =
        when (status) {
            is ExportStatus.Done -> pluralStringResource(R.plurals.export_done, status.entries, status.entries)
            ExportStatus.Failed -> stringResource(R.string.export_failed)
            else -> null
        } ?: return
    val failed = status == ExportStatus.Failed
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
        Text(stringResource(R.string.export_dismiss))
    }
}

@Composable
private fun RangeChip(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(
    label: Int,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
) {
    val style = LocalMoneyStyle.current
    var open by remember { mutableStateOf(false) }
    val shown = stringResource(label, DateFormat.shortDate(date, style.arabic, style.digits))
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) { Text(shown) }
    if (open) {
        // The picker works in UTC midnights; a plain calendar date goes back out.
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        open = false
                    },
                    modifier = Modifier.heightIn(min = TARGET_DP.dp),
                ) { Text(stringResource(R.string.export_date_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = TARGET_DP.dp).padding(end = 4.dp)) {
                    Text(stringResource(R.string.export_date_cancel))
                }
            },
        ) { DatePicker(state = picker) }
    }
}
