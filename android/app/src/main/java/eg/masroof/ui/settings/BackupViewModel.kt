package eg.masroof.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupLauncher
import eg.masroof.data.backup.BackupRun
import eg.masroof.data.backup.BackupStatusStore
import eg.masroof.data.backup.LastBackup
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PassphraseProblem
import eg.masroof.data.backup.PassphraseRules
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

enum class BackupStep { NONE, EXPLAIN, PASSPHRASE, PICKING }

data class BackupUiState(
    val last: LastBackup? = null,
    val run: BackupRun = BackupRun.Idle,
    val step: BackupStep = BackupStep.NONE,
    val understood: Boolean = false,
    val passphrase: String = "",
    val again: String = "",
    /** The key is being derived (about a second): the picker has returned and the task has not started yet. */
    val preparing: Boolean = false,
    val defaultFileName: String = "",
) {
    val problem: PassphraseProblem? get() = PassphraseRules.check(passphrase, again)
    val canContinue: Boolean get() = problem == null
}

private const val STOP_TIMEOUT_MS = 5_000L

/** "Back up now" (REQ-SEC-067, REQ-OPS-177): explanation on the first backup, a typed-twice passphrase, the system picker, a foreground task. */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val launcher: BackupLauncher,
        private val status: BackupStatusStore,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private data class Local(
            val step: BackupStep = BackupStep.NONE,
            val understood: Boolean = false,
            val passphrase: String = "",
            val again: String = "",
            val preparing: Boolean = false,
        )

        private val local = MutableStateFlow(Local())

        val state: StateFlow<BackupUiState> =
            combine(local, status.last, launcher.run) { l, last, run ->
                BackupUiState(last, run, l.step, l.understood, l.passphrase, l.again, l.preparing, defaultFileName())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState())

        /** `masroof-2026-10-27.masroof`, the Cairo date. */
        fun defaultFileName(): String = "masroof-${CairoTime.today(clock.instant())}.masroof"

        fun onBackUpNow() {
            if (state.value.run is BackupRun.Running || local.value.preparing) return
            local.update { it.copy(step = if (status.last.value == null) BackupStep.EXPLAIN else BackupStep.PASSPHRASE) }
        }

        fun onUnderstood(checked: Boolean) = local.update { it.copy(understood = checked) }

        fun onContinueExplain() =
            local.update {
                if (it.understood &&
                    it.step == BackupStep.EXPLAIN
                ) {
                    it.copy(step = BackupStep.PASSPHRASE)
                } else {
                    it
                }
            }

        fun onPassphrase(text: String) = local.update { it.copy(passphrase = text) }

        fun onAgain(text: String) = local.update { it.copy(again = text) }

        fun onContinuePassphrase() =
            local.update {
                if (it.step == BackupStep.PASSPHRASE &&
                    PassphraseRules.check(it.passphrase, it.again) == null
                ) {
                    it.copy(step = BackupStep.PICKING)
                } else {
                    it
                }
            }

        /** Leaves the flow from any step; the typed text is dropped. */
        fun onDismiss() = local.update { Local() }

        /** The system "Save to" picker returned: null = the user cancelled, so nothing is written and nothing changes. */
        fun onPicked(uri: Uri?) {
            val typed = local.value
            if (uri == null || typed.step != BackupStep.PICKING) {
                local.value = Local()
                return
            }
            local.update { it.copy(preparing = true, passphrase = "", again = "") }
            viewModelScope.launch {
                try {
                    val header = BackupHeader.fresh()
                    val key = withContext(io) { PassphraseKdf.deriveKey(typed.passphrase, header) }
                    launcher.start(uri, BackupInput(header, key))
                } finally {
                    // The field is cleared whether the task started or the derivation failed.
                    local.value = Local()
                }
            }
        }

        fun onCancelBackup() = launcher.cancel()

        /** The user has seen the result (success, failure or cancel). */
        fun onAcknowledge() = launcher.acknowledge()
    }
