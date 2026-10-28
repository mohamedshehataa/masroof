package eg.masroof.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import eg.masroof.data.receipts.Refusal
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.ReceiptMessage
import eg.masroof.ui.viewmodel.ReceiptsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val TARGET = 48.dp
private val THUMB = 72.dp
private const val THUMB_PX = 160

/** The receipts of one entry on its edit screen: thumbnails in the order added, "Add receipt", messages (REQ-RCPT-015..020). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptsSection(
    enabled: Boolean,
    viewModel: ReceiptsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val style = LocalMoneyStyle.current
    var choosing by remember { mutableStateOf(false) }
    // A cancelled picker returns null: nothing is stored and nothing is shown.
    val photo =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.add { context.contentResolver.openInputStream(uri) }
        }
    val pdf =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.add { context.contentResolver.openInputStream(uri) }
        }
    Text(
        stringResource(R.string.rcpt_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.receipts.forEachIndexed { index, receipt ->
            val kind = stringResource(if (receipt.mimeType == PDF_MIME) R.string.rcpt_kind_pdf else R.string.rcpt_kind_photo)
            val description =
                stringResource(
                    R.string.rcpt_thumb_desc,
                    DateFormat.number(index + 1, style.digits),
                    DateFormat.number(state.receipts.size, style.digits),
                    kind,
                )
            Thumbnail(viewModel.fileOf(receipt), receipt.mimeType == PDF_MIME, description) { viewModel.view(receipt) }
        }
        repeat(state.adding) {
            Box(
                Modifier.size(THUMB),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
    }
    if (state.receipts.isEmpty() && state.adding == 0) {
        Text(
            stringResource(R.string.rcpt_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.message?.let { message ->
        MessageLine(message, onOpen = viewModel::openDuplicate, onDismiss = viewModel::dismissMessage)
    }
    OutlinedButton(
        onClick = { choosing = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = TARGET),
    ) { Text(stringResource(R.string.rcpt_add)) }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.rcpt_choose_title)) },
            text = {
                Column {
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
    state.viewing?.let { receipt ->
        ReceiptViewer(receipt, viewModel.fileOf(receipt), onClose = viewModel::closeViewer, onRemove = viewModel::askRemove)
    }
    if (state.confirmRemove) {
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

internal const val PDF_MIME = "application/pdf"

@Composable
private fun MessageLine(
    message: ReceiptMessage,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LocalMoneyStyle.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (message) {
            is ReceiptMessage.Error ->
                Text(
                    stringResource(errorRes(message.reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).clickable(onClick = onDismiss),
                )
            is ReceiptMessage.Duplicate -> {
                val d = message.result
                val text =
                    if (d.occurredAtMillis != null && d.amountMinor != null && d.currency != null) {
                        stringResource(
                            R.string.rcpt_duplicate,
                            DateFormat.shortDate(CairoTime.dateOf(d.occurredAtMillis), style.arabic, style.digits),
                            MoneyFormat.format(Money(d.amountMinor, d.currency), style.arabic, style.digits),
                        )
                    } else {
                        stringResource(R.string.rcpt_duplicate_unknown)
                    }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).clickable(onClick = onDismiss))
                TextButton(onClick = { onOpen(d.receiptId) }, modifier = Modifier.heightIn(min = TARGET)) {
                    Text(stringResource(R.string.rcpt_open))
                }
            }
        }
    }
}

private fun errorRes(reason: Refusal?) =
    when (reason) {
        Refusal.UNSUPPORTED -> R.string.rcpt_err_unsupported
        Refusal.TOO_LARGE -> R.string.rcpt_err_too_large
        Refusal.TOO_MANY_PAGES -> R.string.rcpt_err_too_many_pages
        Refusal.PDF_UNREADABLE -> R.string.rcpt_err_pdf_unreadable
        Refusal.IMAGE_UNREADABLE -> R.string.rcpt_err_image_unreadable
        null -> R.string.rcpt_err_failed
    }

@Composable
private fun Thumbnail(
    file: File,
    isPdf: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, file, isPdf) {
        value = if (isPdf) null else withContext(Dispatchers.IO) { runCatching { decodeThumb(file) }.getOrNull() }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier =
            Modifier.size(THUMB).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).semantics {
                contentDescription =
                    description
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            val shown = bitmap
            if (shown != null) {
                Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB))
            } else {
                Text(if (isPdf) "PDF" else "", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun decodeThumb(file: File): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
