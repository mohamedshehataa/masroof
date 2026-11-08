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
import eg.masroof.data.backup.RestoreCoordinator
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Feeds the lasting banner shown while a restore marker is on disk. */
@HiltViewModel
class RestorePendingViewModel
    @Inject
    constructor(
        private val coordinator: RestoreCoordinator,
    ) : ViewModel() {
        val pending: StateFlow<Boolean> get() = coordinator.pending

        fun finish() = coordinator.finishPending()
    }

/** "SMS capture and backups are paused until the restore finishes", with the action that finishes it. Nothing is shown otherwise. */
@Composable
fun RestorePendingBanner(
    onOpenSettings: () -> Unit,
    viewModel: RestorePendingViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    RestorePendingBannerContent(pending, onFinish = {
        viewModel.finish()
        onOpenSettings()
    })
}

@Composable
fun RestorePendingBannerContent(
    pending: Boolean,
    onFinish: () -> Unit,
) {
    if (!pending) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.restore_pending_banner), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.restore_pending_finish))
            }
        }
    }
}
