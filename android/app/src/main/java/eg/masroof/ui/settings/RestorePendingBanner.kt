@file:Suppress("MatchingDeclarationName") // the banner and its view model belong together

package eg.masroof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.R
import eg.masroof.data.backup.PendingState
import eg.masroof.data.backup.RestoreCoordinator
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Feeds the lasting banner shown while a restore journal is on disk. */
@HiltViewModel
class RestorePendingViewModel
    @Inject
    constructor(
        private val coordinator: RestoreCoordinator,
    ) : ViewModel() {
        val pending: StateFlow<PendingState> get() = coordinator.pending

        fun finish() = coordinator.finishPending()

        fun keep() = coordinator.keepAsIs()
    }

/**
 * "SMS capture and backups are paused until the restore finishes", with the action that finishes it (Incomplete: Finish, which retries) or
 * the one that repairs it (Partial: Restore again, which opens Settings > Restore and calls no recovery). Nothing is shown otherwise.
 */
@Composable
fun RestorePendingBanner(
    onOpenSettings: () -> Unit,
    viewModel: RestorePendingViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    RestorePendingBannerContent(pending, onFinish = {
        viewModel.finish()
        onOpenSettings()
    }, onRestoreAgain = onOpenSettings, onKeep = viewModel::keep)
}

@Composable
fun RestorePendingBannerContent(
    pending: PendingState,
    onFinish: () -> Unit,
    onRestoreAgain: () -> Unit = {},
    onKeep: () -> Unit = {},
) {
    if (pending is PendingState.None) return
    val partial = pending is PendingState.Partial
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (partial) R.string.restore_pending_partial else R.string.restore_pending_banner),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (partial) Text(stringResource(R.string.restore_pending_keep_warning), color = MaterialTheme.colorScheme.onErrorContainer)
            if (partial) {
                TextButton(
                    onClick = onKeep,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.restore_pending_keep)) }
            }
            TextButton(onClick = if (partial) onRestoreAgain else onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (partial) R.string.restore_pending_again else R.string.restore_pending_finish))
            }
        }
    }
}
