package eg.masroof.ui.receipts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import eg.masroof.MainActivity
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.DbStartup
import eg.masroof.data.receipts.ShareIntentParser
import eg.masroof.ui.AppLanguage
import eg.masroof.ui.edit.errorRes
import eg.masroof.ui.startup.StartupBlockedScreen
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.ShareOutcome
import eg.masroof.ui.viewmodel.ShareReceiptViewModel
import eg.masroof.ui.viewmodel.ShareState

/**
 * REQ-RCPT-022, trust boundary L2 (ADR-0009): the only activity another app can send content to. It reads only `EXTRA_STREAM`
 * (see [ShareIntentParser]), copies each item while the grant is valid, and shows per-item reasons. It never shows or logs names,
 * URIs or content, and is FLAG_SECURE.
 */
@AndroidEntryPoint
class ShareReceiptActivity : ComponentActivity() {
    private val viewModel: ShareReceiptViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val startup = DatabaseStartup.state()
        if (startup !is DbStartup.Ready) {
            setContent { MasroofTheme { StartupBlockedScreen(startup) } }
            return
        }
        viewModel.start(intent, contentResolver, packageName)
        setContent {
            MasroofTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.allGood) { if (state.allGood) openReceipts() }
                ShareResult(state, onOpen = ::openReceipts)
            }
        }
    }

    /** Starts Masroof on the Receipts list; a fixed boolean is the only thing that crosses (MainActivity reads nothing else). */
    private fun openReceipts() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_RECEIPTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

@Composable
private fun ShareResult(
    state: ShareState,
    onOpen: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.share_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (!state.finished) CircularProgressIndicator()
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.finished && state.total == 0) Text(stringResource(R.string.share_none))
                if (state.overflow) {
                    Text(
                        stringResource(R.string.share_max, DateFormat.number(ShareIntentParser.MAX_ITEMS, LocalMoneyStyle.current.digits)),
                    )
                }
                state.outcomes.forEachIndexed { index, outcome ->
                    val n = DateFormat.number(index + 1, LocalMoneyStyle.current.digits)
                    Text(stringResource(R.string.share_item, n, stringResource(outcomeRes(outcome))))
                }
            }
            if (state.finished) {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.share_open))
                }
            }
        }
    }
}

private fun outcomeRes(outcome: ShareOutcome): Int =
    when (outcome) {
        ShareOutcome.Stored -> R.string.share_stored
        ShareOutcome.Duplicate -> R.string.rcpt_duplicate_waiting
        ShareOutcome.CantShare -> R.string.share_cant
        is ShareOutcome.Refused -> errorRes(outcome.reason)
    }
