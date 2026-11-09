package eg.masroof.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.NetworkMonitor
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ImportProgress
import eg.masroof.data.serverimport.ImportResult
import eg.masroof.data.serverimport.ImportStage
import eg.masroof.data.serverimport.ImportStore
import eg.masroof.data.serverimport.ServerImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** What the Settings "Import from server" card is doing (STORY-182). */
sealed interface ImportPhase {
    data object Idle : ImportPhase

    /** [total] is null while downloading: the server sends no count. */
    data class Running(
        val stage: ImportStage,
        val done: Int,
        val total: Int?,
    ) : ImportPhase

    data class Done(
        val imported: Int,
        val skipped: Int,
        val skippedOther: Int = 0,
    ) : ImportPhase

    /** The entries are stored; only forgetting the server is left. It needs no network, so it can be finished offline. */
    data class Cleaning(
        val imported: Int,
    ) : ImportPhase

    data class Failed(
        val kind: ImportFailureKind,
    ) : ImportPhase
}

data class ImportUiState(
    /** The card shows only while a server session exists (or while it shows a result or asks to sign in again). */
    val visible: Boolean = false,
    val phase: ImportPhase = ImportPhase.Idle,
    val offline: Boolean = false,
    /** The sign-in form (the existing login screen) is open: only ever opened from this card. */
    val signInOpen: Boolean = false,
) {
    val running: Boolean get() = phase is ImportPhase.Running
    val canImport: Boolean get() = visible && (!offline || phase is ImportPhase.Cleaning) && !running && phase !is ImportPhase.Done
}

private data class ImportInputs(
    val phase: ImportPhase,
    val signInOpen: Boolean,
    /** An import was offered and has not completed: the card stays even without a session (REQ-OPS-174, decision in DECISIONS). */
    val pending: Boolean = false,
)

/** Drives the card: the run itself lives in [ServerImporter]; this only maps its progress and result to UI state. */
@HiltViewModel
class ImportViewModel
    @Inject
    constructor(
        private val importer: ServerImporter,
        private val session: SessionManager,
        network: NetworkMonitor,
        private val store: ImportStore,
    ) : ViewModel() {
        private val inputs = MutableStateFlow(ImportInputs(ImportPhase.Idle, signInOpen = false, pending = store.pending))
        private var job: Job? = null

        val state: StateFlow<ImportUiState> =
            combine(session.state, network.online, inputs) { s, online, i ->
                val signedIn = s is SessionState.SignedIn
                val keepsShowing =
                    i.phase is ImportPhase.Running ||
                        i.phase is ImportPhase.Done ||
                        (i.phase as? ImportPhase.Failed)?.kind == ImportFailureKind.SESSION
                // pending and no session: "Sign in again to import", unless only the forgetting is left (then Import finishes it)
                val needsSignIn = !signedIn && i.pending && i.phase == ImportPhase.Idle && !importer.forgetUnfinished()
                ImportUiState(
                    visible = signedIn || i.pending || keepsShowing,
                    phase = if (needsSignIn) ImportPhase.Failed(ImportFailureKind.SESSION) else i.phase,
                    offline = !online,
                    signInOpen = i.signInOpen && !signedIn,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ImportUiState())

        init {
            // reads the stored session once so the card knows whether one exists
            viewModelScope.launch { session.restore() }
            // a finished import whose forgetting was cut short (a Keystore or disk failure): finished now, quietly, at the next open
            if (importer.forgetUnfinished()) {
                inputs.update { it.copy(phase = ImportPhase.Cleaning(store.importedCount)) }
                viewModelScope.launch {
                    when (val r = importer.run {}) {
                        is ImportResult.Completed ->
                            inputs.update { it.copy(phase = ImportPhase.Done(r.imported, r.skipped, r.skippedOther), pending = false) }
                        else -> Unit
                    }
                }
            }
            // a successful sign-in from the card ends "Sign in again": Import can be tapped again
            viewModelScope.launch {
                session.state.collect { s ->
                    if (s is SessionState.SignedIn) {
                        // an import is on offer and stays so until it completes, even if the session is lost before it runs
                        if (!store.pending) store.pending = true
                        inputs.update {
                            if ((it.phase as? ImportPhase.Failed)?.kind == ImportFailureKind.SESSION) {
                                ImportInputs(ImportPhase.Idle, false, pending = true)
                            } else {
                                it.copy(pending = true)
                            }
                        }
                    }
                }
            }
        }

        fun start() {
            val cleaningOnly = importer.forgetUnfinished()
            if (job?.isActive == true || (state.value.offline && !cleaningOnly)) return
            inputs.update { it.copy(phase = ImportPhase.Running(ImportStage.DOWNLOADING, 0, null), signInOpen = false) }
            job =
                viewModelScope.launch {
                    val result =
                        try {
                            importer.run(::onProgress)
                        } catch (e: CancellationException) {
                            // Cancel: nothing is forgotten, the card goes back to Import
                            inputs.update { it.copy(phase = ImportPhase.Idle) }
                            throw e
                        }
                    inputs.update {
                        when (result) {
                            is ImportResult.Completed ->
                                it.copy(
                                    phase = ImportPhase.Done(result.imported, result.skipped, result.skippedOther),
                                    pending = false,
                                )
                            is ImportResult.CleanupPending -> it.copy(phase = ImportPhase.Cleaning(result.imported))
                            is ImportResult.Failed ->
                                it.copy(phase = ImportPhase.Failed(result.kind), signInOpen = result.kind == ImportFailureKind.SESSION)
                        }
                    }
                }
        }

        private fun onProgress(p: ImportProgress) = inputs.update { it.copy(phase = ImportPhase.Running(p.stage, p.done, p.total)) }

        fun cancel() {
            job?.cancel()
        }

        fun openSignIn() = inputs.update { it.copy(signInOpen = true) }

        fun closeSignIn() = inputs.update { it.copy(signInOpen = false) }

        /** "OK" on the result: the card goes away (the session is gone after a completed run). */
        fun dismissResult() = inputs.update { if (it.phase is ImportPhase.Done) it.copy(phase = ImportPhase.Idle) else it }
    }
