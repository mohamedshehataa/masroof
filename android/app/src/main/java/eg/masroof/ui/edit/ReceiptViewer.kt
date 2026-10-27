package eg.masroof.ui.edit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.graphics.createBitmap
import eg.masroof.R
import eg.masroof.data.receipts.decodeBounded
import eg.masroof.ui.viewmodel.ViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PAGE_WIDTH_PX = 1440

/**
 * The receipt inside the app: a photo full screen or a PDF's pages in a scrolling list. The bytes never leave the app (no share, no
 * "open with"), and the dialog window is FLAG_SECURE like the screen behind it (a Dialog is its own window, so the activity's flag
 * would not cover it).
 */
@Composable
internal fun ReceiptViewer(
    state: ViewerState,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = onClose, modifier = Modifier.padding(8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.rcpt_viewer_close))
                }
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state) {
                        ViewerState.Loading -> LoadingLine(stringResource(R.string.rcpt_viewer_loading))
                        ViewerState.Failed -> Text(stringResource(R.string.rcpt_viewer_failed), color = MaterialTheme.colorScheme.error)
                        is ViewerState.Ready -> if (state.isPdf) PdfPages(state.file) else PhotoView(state.file)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoView(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.Default) { runCatching { decodeBounded(file) }.getOrNull() }
    }
    val shown = bitmap
    if (shown == null) {
        Text(stringResource(R.string.rcpt_viewer_loading), modifier = Modifier.padding(16.dp))
    } else {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = stringResource(R.string.rcpt_viewer_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
            kotlinx.coroutines.awaitCancellation()
        } finally {
            opened?.close()
        }
    }
    val pdf = doc
    if (pdf == null) {
        Text(stringResource(R.string.rcpt_viewer_loading), modifier = Modifier.padding(16.dp))
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, MAX_PAGE_WIDTH_PX)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until pdf.pageCount).toList()) { index ->
                val page by produceState<Bitmap?>(null, pdf, index, widthPx) {
                    value = withContext(Dispatchers.Default) { runCatching { pdf.render(index, widthPx) }.getOrNull() }
                }
                val bitmap = page
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription =
                            stringResource(
                                R.string.rcpt_viewer_page,
                                java.text.NumberFormat.getIntegerInstance().format(
                                    index + 1,
                                ),
                            ),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
