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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PAGE_WIDTH_PX = 1440
private const val MAX_PAGE_PIXELS = 4_000_000 // 16 MB as ARGB_8888, well under Android's 100 MB draw limit
private const val MAX_ZOOM = 5f

/** Width and height for a PDF page bitmap: [widthPx] wide, but never more than [MAX_PAGE_PIXELS] pixels (a 1 x 100 page stays drawable). */
internal fun pageBitmapSize(
    pageWidth: Int,
    pageHeight: Int,
    widthPx: Int,
): Pair<Int, Int> {
    val pw = maxOf(1, pageWidth)
    val ph = maxOf(1, pageHeight)

    fun heightFor(w: Int) = maxOf(1L, ph.toLong() * w / pw)
    var lo = 1
    var hi = maxOf(1, widthPx)
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (mid.toLong() * heightFor(mid) <= MAX_PAGE_PIXELS) lo = mid else hi = mid - 1
    }
    return lo to heightFor(lo).toInt()
}

/**
 * The receipt inside the app only (REQ-RCPT-018): no share, no "open with", no URI grant. The dialog window is
 * FLAG_SECURE like the screen behind it. A row whose file is gone, or a file that cannot be drawn, shows a message with Remove.
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
        value =
            withContext(Dispatchers.Default) {
                try {
                    BitmapFactory.decodeFile(file.path)
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform =
        rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
            // pan only as far as the zoomed picture reaches past the edges
            val maxX = size.width * (scale - 1f) / 2f
            val maxY = size.height * (scale - 1f) / 2f
            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        }
    val shown = bitmap
    if (shown == null) {
        Text(stringResource(R.string.rcpt_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    } else {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = stringResource(R.string.rcpt_viewer_image),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { size = it }
                    .transformable(transform)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
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
            val (w, h) = pageBitmapSize(page.width, page.height, width)
            val bitmap = createBitmap(w, h)
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

private fun openPdf(file: File): PdfDoc? {
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        PdfDoc(PdfRenderer(fd))
    } catch (_: java.io.IOException) {
        fd.close()
        null
    } catch (_: SecurityException) {
        fd.close()
        null
    } catch (_: IllegalArgumentException) {
        fd.close()
        null
    }
}

@Composable
private fun PdfPages(file: File) {
    // first = the open attempt finished, second = the document (null when it could not be opened)
    val state by produceState<Pair<Boolean, PdfDoc?>>(false to null, file) {
        val opened = withContext(Dispatchers.IO) { openPdf(file) }
        value = true to opened
        try {
            awaitCancellation()
        } finally {
            // close() waits for a render in progress: never on the main thread
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }
    val pdf = state.second
    if (pdf == null) {
        if (state.first) {
            Text(stringResource(R.string.rcpt_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.rcpt_opening), Modifier.padding(16.dp))
        }
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
                // null = still drawing, a failed page shows a message instead of crashing the viewer
                val page by produceState<Result<Bitmap>?>(null, pdf, index, widthPx) {
                    value =
                        withContext(Dispatchers.Default) {
                            try {
                                val drawn = pdf.render(index, widthPx)
                                if (drawn != null) Result.success(drawn) else Result.failure(IllegalStateException("page not drawn"))
                            } catch (e: IllegalStateException) {
                                Result.failure(e)
                            } catch (e: IllegalArgumentException) {
                                Result.failure(e)
                            } catch (e: java.io.IOException) {
                                Result.failure(e)
                            } catch (_: OutOfMemoryError) {
                                Result.failure(IllegalStateException("page not drawn"))
                            }
                        }
                }
                val result = page
                val bitmap = result?.getOrNull()
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.rcpt_page_desc, DateFormat.number(index + 1, style.digits)),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (result != null) {
                    Text(stringResource(R.string.rcpt_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
