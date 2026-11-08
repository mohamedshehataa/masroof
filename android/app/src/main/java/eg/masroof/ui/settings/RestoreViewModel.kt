package eg.masroof.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RestoreUiState(
    val run: RestoreState = RestoreState.Idle,
    val passphrase: String = "",
    /** The system picker is open (asked for once; a rotation must not open a second one). */
    val picking: Boolean = false,
) {
    val canContinue: Boolean get() = PassphraseKdf.visible(passphrase).isNotBlank()
    val busy: Boolean get() = picking || run !is RestoreState.Idle && run !is RestoreState.Done && run !is RestoreState.Failed
}

private const val STOP_TIMEOUT_MS = 5_000L

/** "Restore from backup" (REQ-SEC-068, REQ-OPS-178): pick file, passphrase, checking, confirmation, restoring, done or error. */
@HiltViewModel
class RestoreViewModel
    @Inject
    constructor(
        private val coordinator: RestoreCoordinator,
    ) : ViewModel() {
        private val passphrase = MutableStateFlow("")
        private val picking = MutableStateFlow(false)

        val state: StateFlow<RestoreUiState> =
            combine(coordinator.state, passphrase, picking) { run, text, pick -> RestoreUiState(run, text, pick) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RestoreUiState())

        fun onRestoreFromBackup() {
            if (!state.value.busy) picking.value = true
        }

        /** Null = the user cancelled the picker: nothing is read. */
        fun onPicked(uri: Uri?) {
            picking.value = false
            coordinator.onPicked(uri)
        }

        fun onPassphrase(text: String) {
            passphrase.value = text
        }

        /** The text leaves the field at once; the coordinator never stores it. */
        fun onContinue() {
            val typed = passphrase.value
            if (!PassphraseKdf.visible(typed).isNotBlank()) return
            passphrase.value = ""
            coordinator.onPassphrase(typed)
        }

        fun onCancel() {
            passphrase.value = ""
            picking.value = false
            coordinator.onCancel()
        }

        fun onConfirm() = coordinator.onConfirm()

        fun onDismiss() = coordinator.onDismiss()
    }
