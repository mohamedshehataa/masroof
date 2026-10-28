package eg.masroof.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.graphics.createBitmap
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.ui.theme.LocalMoneyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PAGE_WIDTH_PX = 1440
private const val MAX_ZOOM = 5f

/**
 * The receipt inside the app only (REQ-RCPT-018): no share, no "open with", no URI grant. The dialog window is
 * FLAG_SECURE like the screen behind it. A row whose file is gone shows "missing" with Remove instead of crashing.
 */
@Composable
internal fun ReceiptViewer(
    receipt: ReceiptEntity,
    file: File,
    onClose: () -> Unit,
    onRemove: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rcpt_close)) }
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.rcpt_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        !file.isFile ->
                            Text(
                                stringResource(R.string.rcpt_missing),
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        receipt.mimeType == PDF_MIME -> PdfPages(file)
                        else -> PhotoView(file)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoView(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, MAX_ZOOM) }
    val shown = bitmap
    if (shown == null) {
        Text(stringResource(R.string.rcpt_failed), Modifier.padding(16.dp))
    } else {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = stringResource(R.string.rcpt_viewer_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().transformable(transform).graphicsLayer(scaleX = scale, scaleY = scale),
        )
    }
}

/** A PdfRenderer allows one open page at a time and must not be closed mid-render: every access takes this lock. */
private class PdfDoc(
    private val renderer: PdfRenderer,
) {
    private var closed = false

    val pageCount: Int = renderer.pageCount

    @Synchronized
    fun render(
        index: Int,
        width: Int,
    ): Bitmap? {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val height = maxOf(1, (page.height.toLong() * width / maxOf(1, page.width)).toInt())
            val bitmap = createBitmap(width, height)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    @Synchronized
    fun close() {
        if (!closed) renderer.close()
        closed = true
    }
}

@Composable
private fun PdfPages(file: File) {
    val doc by produceState<PdfDoc?>(null, file) {
        val opened =
            withContext(Dispatchers.IO) {
                runCatching { PdfDoc(PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))) }.getOrNull()
            }
        value = opened
        try {
            awaitCancellation()
        } finally {
            opened?.close()
        }
    }
    val pdf = doc
    if (pdf == null) {
        Text(stringResource(R.string.rcpt_failed), Modifier.padding(16.dp))
        return
    }
    val style = LocalMoneyStyle.current
    val list = rememberLazyListState()
    val firstVisible by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val current = DateFormat.number(firstVisible + 1, style.digits)
    val total = DateFormat.number(pdf.pageCount, style.digits)
    Text(stringResource(R.string.rcpt_page_of, current, total), Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, MAX_PAGE_WIDTH_PX)
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pdf.pageCount) { index ->
                val page by produceState<Bitmap?>(null, pdf, index, widthPx) {
                    value = withContext(Dispatchers.Default) { runCatching { pdf.render(index, widthPx) }.getOrNull() }
                }
                val bitmap = page
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.rcpt_page_desc, DateFormat.number(index + 1, style.digits)),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
