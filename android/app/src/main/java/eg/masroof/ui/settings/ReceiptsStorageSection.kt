package eg.masroof.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.ReceiptTotalsViewModel

private const val BYTES_PER_MB = 1024L * 1024L
private const val TENTHS = 10L

/** "Receipts: N files, X MB" (REQ-RCPT-019). Self-contained: its own view model, one line in Settings. */
@Composable
fun ReceiptsStorageSection(viewModel: ReceiptTotalsViewModel = hiltViewModel()) {
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val style = LocalMoneyStyle.current
    SectionCard(stringResource(R.string.rcpt_title)) {
        Text(
            pluralStringResource(
                R.plurals.rcpt_storage,
                totals.files,
                DateFormat.number(totals.files, style.digits),
                DateFormat.digitsAs(megabytes(totals.bytes), style.digits),
            ),
        )
    }
}

/** One decimal place, integer arithmetic only: `0.0`, `2.4`. */
internal fun megabytes(bytes: Long): String {
    val tenths = bytes * TENTHS / BYTES_PER_MB
    return "${tenths / TENTHS}.${tenths % TENTHS}"
}
