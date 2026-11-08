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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Why a backup did not start, shown in the Backup section until the user dismisses it. */
enum class BackupNotice { INTERRUPTED, START_FAILED, ALREADY_RUNNING }

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
    val notice: BackupNotice? = null,
    /** The picked document was not removed (it holds data): "Backup did not start; your existing file was not changed". */
    val fileKept: Boolean = false,
) {
    val problem: PassphraseProblem? get() = PassphraseRules.check(passphrase, again)
    val canContinue: Boolean get() = problem == null
}

private const val STOP_TIMEOUT_MS = 5_000L

/** "Back up now" (REQ-SEC-067, REQ-OPS-177): first-backup explanation, typed-twice passphrase, system picker, foreground task. */
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
            val notice: BackupNotice? = null,
            val fileKept: Boolean = false,
        )

        private val local = MutableStateFlow(Local())

        val state: StateFlow<BackupUiState> =
            combine(local, status.last, launcher.run) { l, last, run ->
                BackupUiState(last, run, l.step, l.understood, l.passphrase, l.again, l.preparing, defaultFileName(), l.notice, l.fileKept)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState())

        /** `masroof-2026-10-27.masroof`, the Cairo date. */
        fun defaultFileName(): String = "masroof-${CairoTime.today(clock.instant())}.masroof"

        fun onBackUpNow() {
            if (state.value.run is BackupRun.Running || local.value.preparing) return
            local.update { it.copy(notice = null, step = if (status.last.value == null) BackupStep.EXPLAIN else BackupStep.PASSPHRASE) }
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

        /**
         * The system "Save to" picker returned. Null = the user cancelled: nothing is written and nothing changes. A file that arrives when
         * this screen is no longer asking for one (the process died while the picker was open) is an interrupted backup: the empty document
         * is removed if the provider allows it, and the user is told to start again.
         */
        fun onPicked(uri: Uri?) {
            val typed = local.value
            if (uri == null) {
                local.value = Local()
                return
            }
            if (typed.step != BackupStep.PICKING) {
                local.value = Local(notice = BackupNotice.INTERRUPTED)
                viewModelScope.launch {
                    val removed = launcher.discard(uri)
                    local.update { it.copy(fileKept = !removed) }
                }
                return
            }
            local.update { it.copy(preparing = true, passphrase = "", again = "") }
            viewModelScope.launch { deriveAndStart(uri, typed.passphrase) }
        }

        // Derivation cannot be interrupted, so it runs to the end and the key is zeroed unless the task took ownership of it.
        // Out of memory at 64 MiB on a small phone must show a message, never crash the app.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private suspend fun deriveAndStart(
            uri: Uri,
            passphrase: String,
        ) {
            var key: ByteArray? = null
            var started = false
            var notice: BackupNotice? = null
            var kept = false
            try {
                val header = BackupHeader.fresh()
                key = withContext(NonCancellable + io) { PassphraseKdf.deriveKey(passphrase, header) }
                currentCoroutineContext().ensureActive()
                started = launcher.start(uri, BackupInput(header, key))
                if (!started) notice = BackupNotice.ALREADY_RUNNING
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice = BackupNotice.START_FAILED
            } finally {
                // Also when this was cancelled: the key is zeroed and an empty document the picker made does not stay behind.
                if (!started) {
                    key?.fill(0)
                    withContext(NonCancellable) { kept = !launcher.discard(uri) }
                }
                // The field is cleared whether the task started or not.
                local.value = Local(notice = notice, fileKept = kept)
            }
        }

        fun onDismissNotice() = local.update { it.copy(notice = null) }

        fun onCancelBackup() = launcher.cancel()

        /** The user has seen the result (success, failure or cancel). */
        fun onAcknowledge() {
            local.update { it.copy(notice = null) }
            viewModelScope.launch { launcher.acknowledge() }
        }
    }
