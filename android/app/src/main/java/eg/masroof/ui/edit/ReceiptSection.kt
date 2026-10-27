package eg.masroof.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.data.receipts.AttachmentInfo
import eg.masroof.ui.viewmodel.AttachMessage
import eg.masroof.ui.viewmodel.AttachmentsUiState
import eg.masroof.ui.viewmodel.SectionLoad

private val TOUCH_TARGET = 48.dp
private const val BYTES_PER_KB = 1024L

@Composable
internal fun ReceiptSection(
    state: AttachmentsUiState,
    actions: ReceiptActions,
    enabled: Boolean,
) {
    var choosing by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.rcpt_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    when (state.load) {
        SectionLoad.LOADING -> LoadingLine(stringResource(R.string.rcpt_loading))
        SectionLoad.FAILED -> ErrorLine(stringResource(R.string.rcpt_load_failed), actions.onRetry)
        SectionLoad.LOADED -> {
            if (state.list.isEmpty()) {
                Text(
                    stringResource(R.string.rcpt_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.list.forEachIndexed { index, info ->
                AttachmentRow(info, index + 1, enabled && !state.busy, actions)
            }
        }
    }
    state.message?.let { message ->
        Text(
            stringResource(messageRes(message)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable(onClick = actions.onDismissMessage),
        )
    }
    if (state.uploading) LoadingLine(stringResource(R.string.rcpt_uploading))
    OutlinedButton(
        onClick = { choosing = true },
        enabled = enabled && state.canAttach,
        modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
    ) { Text(stringResource(R.string.rcpt_attach)) }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.rcpt_choose_title)) },
            text = {
                Column {
                    ChoiceButton(R.string.rcpt_camera) {
                        choosing = false
                        actions.onCamera()
                    }
                    ChoiceButton(R.string.rcpt_gallery) {
                        choosing = false
                        actions.onGallery()
                    }
                    ChoiceButton(R.string.rcpt_pdf) {
                        choosing = false
                        actions.onPdf()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }, modifier = Modifier.heightIn(min = TOUCH_TARGET)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        )
    }
    state.confirmRemoveId?.let {
        AlertDialog(
            onDismissRequest = actions.onDismissRemove,
            title = { Text(stringResource(R.string.rcpt_remove_title)) },
            text = { Text(stringResource(R.string.rcpt_remove_body)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmRemove, modifier = Modifier.heightIn(min = TOUCH_TARGET)) {
                    Text(stringResource(R.string.rcpt_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissRemove, modifier = Modifier.heightIn(min = TOUCH_TARGET)) {
                    Text(stringResource(R.string.edit_cancel))
                }
            },
        )
    }
    state.viewer?.let { ReceiptViewer(it, actions.onCloseViewer) }
}

@Composable
private fun ChoiceButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET)) {
        Text(stringResource(label), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AttachmentRow(
    info: AttachmentInfo,
    number: Int,
    enabled: Boolean,
    actions: ReceiptActions,
) {
    val label =
        stringResource(
            if (info.isPdf) R.string.rcpt_pdf_file else R.string.rcpt_photo,
            java.text.NumberFormat
                .getIntegerInstance()
                .format(number),
        )
    val kb = (info.sizeBytes + BYTES_PER_KB - 1) / BYTES_PER_KB
    val size =
        stringResource(
            R.string.rcpt_size_kb,
            java.text.NumberFormat
                .getIntegerInstance()
                .format(kb),
        )
    val viewDescription = stringResource(R.string.rcpt_view, label)
    val removeDescription = stringResource(R.string.rcpt_remove_desc, label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = TOUCH_TARGET)
                .clickable(enabled = enabled) { actions.onView(info) }
                .semantics { contentDescription = viewDescription }
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = { actions.onAskRemove(info.id) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = TOUCH_TARGET).semantics { contentDescription = removeDescription },
        ) { Text(stringResource(R.string.rcpt_remove), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ErrorLine(
    text: String,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text(stringResource(R.string.retry)) }
    }
}

private fun messageRes(message: AttachMessage) =
    when (message) {
        AttachMessage.TOO_LARGE -> R.string.rcpt_err_too_large
        AttachMessage.UNSUPPORTED -> R.string.rcpt_err_unsupported
        AttachMessage.TOO_MANY -> R.string.rcpt_err_too_many
        AttachMessage.UNREADABLE -> R.string.rcpt_err_unreadable
        AttachMessage.OFFLINE -> R.string.rcpt_err_offline
        AttachMessage.SIGN_IN -> R.string.rcpt_err_sign_in
        AttachMessage.FAILED -> R.string.rcpt_err_failed
    }
