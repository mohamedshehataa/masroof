package eg.masroof.ui.receipts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.edit.MessageLine
import eg.masroof.ui.edit.PDF_MIME
import eg.masroof.ui.edit.ReceiptViewer
import eg.masroof.ui.edit.TARGET
import eg.masroof.ui.edit.Thumbnail
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.ReceiptListViewModel

/** More -> Receipts: receipts without an entry, newest first (REQ-RCPT-023). */
@Composable
fun ReceiptsListRoute(
    onNewEntry: (String) -> Unit,
    viewModel: ReceiptListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.rcpt_list_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        state.message?.let { MessageLine(it, onOpen = viewModel::openDuplicate, onDismiss = viewModel::dismissMessage) }
        if (state.adding > 0) CircularProgressIndicator(Modifier.padding(8.dp))
        when {
            !state.loaded -> CircularProgressIndicator(Modifier.padding(8.dp))
            state.receipts.isEmpty() && state.adding == 0 ->
                Text(
                    stringResource(R.string.rcpt_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.receipts, key = { it.id }) { receipt ->
                        ReceiptRow(
                            receipt = receipt,
                            stale = receipt.createdAtMillis < state.staleBeforeMillis,
                            file = viewModel.fileOf(receipt),
                            onView = { viewModel.view(receipt) },
                            onNewEntry = { onNewEntry(receipt.id) },
                            onAttach = { viewModel.askAttach(receipt) },
                            onDelete = { viewModel.askRemove(receipt) },
                        )
                    }
                }
        }
    }
    state.viewing?.let { receipt ->
        ReceiptViewer(receipt, viewModel.fileOf(receipt), onClose = viewModel::closeViewer, onRemove = viewModel::askRemove)
    }
    state.picking?.let {
        EntryPicker(state.choices, onPick = viewModel::attachTo, onDismiss = viewModel::dismissAttach)
    }
    if (state.removing != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRemove,
            title = { Text(stringResource(R.string.rcpt_remove_title)) },
            text = { Text(stringResource(R.string.rcpt_remove_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemove, modifier = Modifier.heightIn(min = TARGET)) {
                    Text(stringResource(R.string.rcpt_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemove, modifier = Modifier.heightIn(min = TARGET)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        )
    }
}

@Composable
private fun ReceiptRow(
    receipt: ReceiptEntity,
    stale: Boolean,
    file: java.io.File,
    onView: () -> Unit,
    onNewEntry: () -> Unit,
    onAttach: () -> Unit,
    onDelete: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    val date = DateFormat.shortDate(CairoTime.dateOf(receipt.createdAtMillis), style.arabic, style.digits)
    val isPdf = receipt.mimeType == PDF_MIME
    val kind = stringResource(if (isPdf) R.string.rcpt_kind_pdf else R.string.rcpt_kind_photo)
    val description = stringResource(R.string.rcpt_row_desc, kind, date)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Thumbnail(file, isPdf, description, onView)
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.titleSmall)
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stale) {
                    Text(
                        stringResource(R.string.rcpt_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onNewEntry,
                modifier = Modifier.heightIn(min = TARGET),
            ) { Text(stringResource(R.string.rcpt_new_entry)) }
            OutlinedButton(onClick = onAttach, modifier = Modifier.heightIn(min = TARGET)) {
                Text(stringResource(R.string.rcpt_attach_entry))
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = TARGET)) {
                Text(stringResource(R.string.rcpt_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EntryPicker(
    choices: List<ExpenseEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rcpt_pick_title)) },
        text = {
            if (choices.isEmpty()) {
                Text(stringResource(R.string.rcpt_pick_empty))
            } else {
                LazyColumn {
                    items(choices, key = { it.id }) { e ->
                        val amount = MoneyFormat.format(Money(e.amountMinor, e.currency), style.arabic, style.digits)
                        val date = DateFormat.shortDate(CairoTime.dateOf(e.occurredAtMillis), style.arabic, style.digits)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = TARGET)
                                .clickable { onPick(e.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(amount, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(e.merchant?.takeIf { it.isNotBlank() }, date).joinToString(" - "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TARGET)) { Text(stringResource(R.string.edit_cancel)) }
        },
    )
}

/** "Receipt only" on the Add screen (REQ-RCPT-021): camera, photo or PDF stored without an entry, then the Receipts list opens. */
@Composable
fun ReceiptOnlySection(
    onStored: () -> Unit,
    viewModel: ReceiptListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    val camera = rememberCameraLaunch { file -> viewModel.add(after = { file.delete() }) { file.inputStream() } }
    val photo =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.add { context.contentResolver.openInputStream(uri) }
        }
    val pdf =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.add { context.contentResolver.openInputStream(uri) }
        }
    val before = remember { mutableStateOf(-1) }
    // a receipt stored from here is a new row: open the list
    androidx.compose.runtime.LaunchedEffect(state.receipts.size, state.loaded) {
        if (state.loaded) {
            if (before.value >= 0 && state.receipts.size > before.value) onStored()
            before.value = state.receipts.size
        }
    }
    state.message?.let { MessageLine(it, onOpen = { onStored() }, onDismiss = viewModel::dismissMessage) }
    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET)) {
        Text(stringResource(R.string.rcpt_receipt_only))
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.rcpt_choose_title)) },
            text = {
                Column {
                    if (camera != null) {
                        TextButton(
                            onClick = {
                                choosing = false
                                camera()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = TARGET),
                        ) { Text(stringResource(R.string.rcpt_camera), Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = {
                            choosing = false
                            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = TARGET),
                    ) { Text(stringResource(R.string.rcpt_photo), Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            choosing = false
                            pdf.launch(arrayOf(PDF_MIME))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = TARGET),
                    ) { Text(stringResource(R.string.rcpt_pdf), Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }, modifier = Modifier.heightIn(min = TARGET)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        )
    }
}
