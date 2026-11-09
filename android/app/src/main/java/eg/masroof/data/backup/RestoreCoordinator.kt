package eg.masroof.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.di.AppScope
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where the restore flow stands (REQ-SEC-068/OPS-178): pick file, passphrase, checking, confirmation, restoring, done or error. */
sealed interface RestoreState {
    data object Idle : RestoreState

    /** The header is fine (checks 1 and 2): ask for the passphrase. Nothing beyond the header was read. */
    data object NeedsPassphrase : RestoreState

    /** [progress] is 0..1, null while the file size is unknown. */
    data class Checking(
        val progress: Int?,
    ) : RestoreState

    data class Confirm(
        val info: RestoreInfo,
    ) : RestoreState

    data object Restoring : RestoreState

    data object Done : RestoreState

    data class Failed(
        val failure: RestoreFailure,
        val neededMb: Long = 0L,
        /** Failed finishes so far (INCOMPLETE): from three on the message says it still cannot finish. */
        val attempts: Int = 0,
    ) : RestoreState
}

/** Opens the picked document; faked in tests. */
interface RestoreSources {
    fun open(uri: Uri): InputStream

    /** Size in bytes, 0 when the provider does not say. */
    fun size(uri: Uri): Long
}

class AndroidRestoreSources
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RestoreSources {
        override fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")

        override fun size(uri: Uri): Long =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                }
            }.getOrNull() ?: 0L
    }

/** True while something that writes the same data is running: a backup (it reads the receipts) or an SMS scan (STORY-186). */
fun interface RestoreBlockers {
    suspend fun busy(): Boolean
}

class WorkRestoreBlockers
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : RestoreBlockers {
        override suspend fun busy(): Boolean =
            listOf(BackupWorker.UNIQUE_NAME, SMS_BACKFILL_WORK_NAME).any { name ->
                workManager.getWorkInfosForUniqueWorkFlow(name).first().any { !it.state.isFinished }
            }

        private companion object {
            // STORY-186's unique work name (ingest/backfill/BackfillWork.kt); a name with no work behind it simply never blocks.
            const val SMS_BACKFILL_WORK_NAME = "sms-backfill"
        }
    }

/**
 * Runs the restore flow in the application scope, so leaving the screen cannot cancel a restore half way. The SESSION (one file being
 * picked, checked and confirmed) lives here in memory and never reads or writes the journal until Confirm; the DURABLE state (journal plus
 * `application_id`) is owned by [RestoreRecovery]. A pick, a wrong passphrase, a failed check or a Cancel never change an unfinished
 * restore. Everything durable runs under the erase lock, the restore flag and NonCancellable, and never throws past this class.
 */
@Singleton
class RestoreCoordinator
    @Inject
    constructor(
        private val restorer: BackupRestorer,
        private val lock: EraseLock,
        private val blockers: RestoreBlockers,
        private val sources: RestoreSources,
        @AppScope private val scope: CoroutineScope,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val mutableState = MutableStateFlow<RestoreState>(RestoreState.Idle)
        val state: StateFlow<RestoreState> get() = mutableState

        private val mutablePending = MutableStateFlow(initialPending())

        /** None, or what is unfinished: Incomplete (the banner offers Finish) or Partial (the banner says Restore again). */
        val pending: StateFlow<PendingState> get() = mutablePending

        private fun initialPending(): PendingState =
            try {
                // Before the start recovery has run an existing journal is shown as Incomplete (conservative); a corrupt one as Partial.
                restorer.pendingState()
            } catch (_: Exception) {
                PendingState.None
            }

        private var uri: Uri? = null
        private var header: BackupHeader? = null
        private var staged: StagedRestore? = null
        private var job: Job? = null
        private var startJob: Job? = null

        /** The system picker returned. Null (cancelled) reads nothing. Only the header is read; the journal is never touched. */
        fun onPicked(picked: Uri?) {
            if (picked == null || mutableState.value is RestoreState.Checking || mutableState.value is RestoreState.Restoring) return
            dropSession()
            uri = picked
            job =
                scope.launch(io) {
                    mutableState.value =
                        try {
                            header = sources.open(picked).use { restorer.checkHeader(it) }
                            RestoreState.NeedsPassphrase
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RestoreException) {
                            reset()
                            RestoreState.Failed(e.failure, e.neededMb)
                        } catch (_: IOException) {
                            reset()
                            RestoreState.Failed(RestoreFailure.CANNOT_READ)
                        } catch (_: SecurityException) {
                            reset()
                            RestoreState.Failed(RestoreFailure.CANNOT_READ)
                        }
                }
        }

        fun onPassphrase(passphrase: String) {
            val picked = uri
            val head = header
            if (picked == null || head == null || mutableState.value != RestoreState.NeedsPassphrase) return
            mutableState.value = RestoreState.Checking(null)
            job =
                scope.launch(io) {
                    // The start recovery sweeps staging nobody names: it must be over before this session creates its own.
                    startJob?.join()
                    mutableState.value =
                        try {
                            // From here to Done, Cancel or a failure the restore flag is held: intake is deferred.
                            acquire()
                            if (blockers.busy()) throw RestoreException(RestoreFailure.BUSY)
                            val result =
                                restorer.stage({ sources.open(picked) }, sources.size(picked), head, passphrase) { p ->
                                    mutableState.value = RestoreState.Checking(p)
                                }
                            staged = result
                            RestoreState.Confirm(result.info)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RestoreException) {
                            failed(e)
                        } catch (e: OutOfMemoryError) {
                            failed(RestoreException(RestoreFailure.LOW_MEMORY).also { it.addSuppressed(e) })
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Throwable,
                        ) {
                            // Whatever a crafted file provoked (stack, memory, a provider): a message, never a crash; nothing changed.
                            failed(RestoreException(RestoreFailure.INVALID_CONTENT).also { it.addSuppressed(e) })
                        }
                }
        }

        fun onConfirm() {
            val pendingRestore = staged ?: return
            if (mutableState.value !is RestoreState.Confirm) return
            mutableState.value = RestoreState.Restoring
            job =
                scope.launch(io) {
                    startJob?.join()
                    var failure: RestoreException? = null
                    try {
                        withContext(NonCancellable) {
                            lock.mutex.withLock {
                                // The restore flag has been held since Checking (acquire): intake is deferred, so nothing lands in the DB
                                // that is about to be replaced. A backup or scan started since Staged makes this BUSY (checked again here).
                                if (blockers.busy()) throw RestoreException(RestoreFailure.BUSY)
                                restorer.apply(pendingRestore)
                            }
                        }
                    } catch (e: RestoreException) {
                        failure = e
                    } catch (e: RestoreKilled) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Throwable,
                    ) {
                        failure = RestoreException(RestoreFailure.INCOMPLETE).also { it.addSuppressed(e) }
                    }
                    staged = null
                    job = null
                    uri = null
                    header = null
                    afterOutcome(failure?.failure)
                    release()
                    mutableState.value = if (failure == null) RestoreState.Done else RestoreState.Failed(failure.failure, failure.neededMb)
                }
        }

        /** Cancel at any step: a running check stops, the session's own staging is deleted, the journal and its staging are untouched. */
        fun onCancel() {
            if (mutableState.value is RestoreState.Restoring) return
            val running = job
            val gen = staged?.gen
            reset()
            scope.launch(io) {
                running?.cancelAndJoinQuietly()
                gen?.let { restorer.discardSession(it) }
            }
        }

        fun onDismiss() {
            if (mutableState.value is RestoreState.Done || mutableState.value is RestoreState.Failed) mutableState.value = RestoreState.Idle
        }

        /** A failed session: only the session's own staging goes (apply already deleted it when it refused). */
        private fun failed(e: RestoreException): RestoreState {
            staged?.let { restorer.discardSession(it.gen) }
            staged = null
            release()
            return RestoreState.Failed(e.failure, e.neededMb)
        }

        /** Drops the session (a new pick): its own staging is deleted; the journal and any slot's staging are not touched. */
        private fun dropSession() {
            staged?.let { restorer.discardSession(it.gen) }
            reset()
        }

        private var holding = false

        /** Holds the restore flag for this session (Checking through Restoring); BUSY when another restore already holds it. */
        private fun acquire() {
            if (!holding) {
                if (!RestoreActivity.begin()) throw RestoreException(RestoreFailure.BUSY)
                holding = true
            }
        }

        private fun release() {
            if (holding) {
                RestoreActivity.end()
                holding = false
            }
        }

        private fun reset() {
            release()
            uri = null
            header = null
            staged = null
            job = null
            mutableState.value = RestoreState.Idle
        }

        private suspend fun Job.cancelAndJoinQuietly() {
            cancel()
            join()
        }

        /** After an outcome: the banner state follows the journal (None when it is gone). */
        private fun afterOutcome(outcome: RestoreFailure?) {
            val journalState =
                try {
                    restorer.pendingState()
                } catch (_: Exception) {
                    PendingState.Incomplete(0)
                }
            mutablePending.value =
                when {
                    journalState is PendingState.None -> PendingState.None
                    outcome == RestoreFailure.PARTIAL -> PendingState.Partial
                    outcome == RestoreFailure.FAILED && mutablePending.value is PendingState.Partial -> PendingState.Partial
                    else -> journalState
                }
        }

        /**
         * Every recovery (app start and Finish) runs here: under the restore flag (so a backup and the pipeline stand aside),
         * shielded from cancellation, never letting an exception out. The caller holds the erase lock.
         */
        private suspend fun recoverCore(atStart: Boolean): RestoreOutcome =
            withContext(NonCancellable) {
                if (!RestoreActivity.begin()) return@withContext RestoreOutcome.INCOMPLETE
                try {
                    restorer.recover(atStart)
                } catch (e: RestoreKilled) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Throwable,
                ) {
                    RestoreOutcome.INCOMPLETE
                } finally {
                    RestoreActivity.end()
                }
            }

        private fun report(
            outcome: RestoreOutcome,
            wasPending: Boolean,
        ) {
            when (outcome) {
                RestoreOutcome.DONE -> {
                    mutablePending.value = PendingState.None
                    if (wasPending) mutableState.value = RestoreState.Done
                }
                RestoreOutcome.INCOMPLETE -> {
                    afterOutcome(RestoreFailure.INCOMPLETE)
                    val attempts = (mutablePending.value as? PendingState.Incomplete)?.attempts ?: 0
                    mutableState.value = RestoreState.Failed(RestoreFailure.INCOMPLETE, attempts = attempts)
                }
                RestoreOutcome.PARTIAL -> {
                    mutablePending.value = PendingState.Partial
                    mutableState.value = RestoreState.Failed(RestoreFailure.PARTIAL)
                }
                RestoreOutcome.FAILED -> afterOutcome(RestoreFailure.FAILED)
            }
        }

        /** "Keep the restored data as it is": the third way out of PARTIAL (deletes the journal and staging, keeps the current key). */
        fun keepAsIs() {
            scope.launch(io) {
                startJob?.join()
                lock.mutex.withLock {
                    if (!restorer.keepAsIs()) return@withLock
                    mutablePending.value = PendingState.None
                    mutableState.value = RestoreState.Idle
                }
            }
        }

        /** The banner's Finish: runs the recovery now (no sweep); a finished restore moves the state to Done. */
        fun finishPending() {
            scope.launch(io) {
                startJob?.join()
                val wasPending = mutablePending.value !is PendingState.None
                lock.mutex.withLock { report(recoverCore(atStart = false), wasPending) }
            }
        }

        /**
         * At app start (REQ-OPS-179): decides from the journal and `application_id` what is left to do and does it. Takes the erase lock on
         * the calling thread before the UI can save anything. A kill at any point is handled by the next start.
         */
        fun recoverInterrupted(): Job? {
            if (DatabaseStartup.isBlocked()) return null
            val taken = lock.mutex.tryLock()
            val started =
                scope.launch(io) {
                    if (!taken) lock.mutex.lock()
                    try {
                        val wasPending = mutablePending.value !is PendingState.None
                        report(recoverCore(atStart = true), wasPending = false)
                    } finally {
                        lock.mutex.unlock()
                    }
                }
            startJob = started
            return started
        }
    }
