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
 * Runs the restore flow in the application scope, so leaving the screen cannot cancel a restore half way. It holds the erase lock
 * around the replace like every other writer, and refuses while a backup or an SMS scan runs. The passphrase is passed through and
 * never stored; the staging folder is deleted on Cancel, on any failure and after a restore.
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

        private val mutablePending = MutableStateFlow(false)

        /** True while a restore marker is on disk: SMS capture and backups are paused until it is finished (shown as a lasting banner). */
        val pending: StateFlow<Boolean> get() = mutablePending

        private fun refreshPending() {
            mutablePending.value = runCatching { restorer.hasMarker() }.getOrDefault(false)
        }

        init {
            refreshPending()
            // Every state change can move the marker (a failure after the commit keeps it, a finished restore removes it).
            scope.launch { mutableState.collect { refreshPending() } }
        }

        /** The banner's action: runs the recovery now (a broken marker needs the same file restored again, which the message says). */
        fun finishPending() {
            scope.launch(io) {
                val wasPending = restorer.hasMarker()
                lock.mutex.withLock {
                    val error = recoverCore()
                    // Finished: the state says so (it stays "did not finish" otherwise, which would be false).
                    if (error != null) {
                        mutableState.value = RestoreState.Failed(error.failure, error.neededMb)
                    } else if (wasPending && !restorer.hasMarker()) {
                        mutableState.value = RestoreState.Done
                    }
                }
                refreshPending()
            }
        }

        private var uri: Uri? = null
        private var header: BackupHeader? = null
        private var staged: StagedRestore? = null
        private var job: Job? = null

        /** The system picker returned. Null (cancelled) changes nothing and reads nothing. Only the header is read here. */
        @Suppress("ThrowsCount") // one named failure per refusal
        fun onPicked(picked: Uri?) {
            if (picked == null || mutableState.value is RestoreState.Checking || mutableState.value is RestoreState.Restoring) return
            // Picking again at the confirmation: the staged files (and the raw key among them) go first.
            restorer.discard(staged)
            reset()
            uri = picked
            job =
                scope.launch(io) {
                    mutableState.value =
                        try {
                            // A restore that committed and did not finish is finished first: its staging must not be replaced. A marker
                            // that is "broken" is repaired by restoring the same file again, which is what this pick starts.
                            if (restorer.hasMarker() && !restorer.markerBroken()) {
                                // Whether it finishes or not, this pick goes on: restoring a file again is always the way out of a marker.
                                // Only another restore that is running right now stops the pick (BUSY).
                                recoverGuarded()?.let { if (it.failure == RestoreFailure.BUSY) throw it }
                            }
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
                    mutableState.value =
                        try {
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

                            failed(
                                RestoreException(
                                    RestoreFailure.INVALID_CONTENT,
                                ).also { it.addSuppressed(e) },
                            )
                        }
                }
        }

        fun onConfirm() {
            val pending = staged ?: return
            if (mutableState.value !is RestoreState.Confirm) return
            mutableState.value = RestoreState.Restoring
            job =
                scope.launch(io) {
                    mutableState.value =
                        try {
                            withContext(NonCancellable) {
                                lock.mutex.withLock {
                                    // Set BEFORE asking whether a backup runs (a backup checks it AFTER it is marked running, so one sees
                                    // the other) and cleared inside the lock, so a message that gets the lock next is not turned away.
                                    if (!RestoreActivity.begin()) throw RestoreException(RestoreFailure.BUSY)
                                    try {
                                        if (blockers.busy()) throw RestoreException(RestoreFailure.BUSY)
                                        restorer.apply(pending)
                                    } finally {
                                        RestoreActivity.end()
                                    }
                                }
                            }
                            reset()
                            RestoreState.Done
                        } catch (e: RestoreException) {
                            failed(e)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Throwable,
                        ) {
                            // After the data commit the staging must stay (the next start finishes it); before it nothing changed.
                            val committed = restorer.hasMarker()
                            failed(
                                RestoreException(
                                    if (committed) RestoreFailure.INCOMPLETE else RestoreFailure.FAILED,
                                ).also { it.addSuppressed(e) },
                            )
                        }
                }
        }

        /** Cancel at any step: a running check stops, the staging folder is deleted, nothing changed. */
        fun onCancel() {
            if (mutableState.value is RestoreState.Restoring) return
            val running = job
            reset()
            scope.launch(io) {
                running?.cancelAndJoinQuietly()
                restorer.discard(null)
            }
        }

        fun onDismiss() {
            if (mutableState.value is RestoreState.Done || mutableState.value is RestoreState.Failed) mutableState.value = RestoreState.Idle
        }

        private fun failed(e: RestoreException): RestoreState {
            // An unfinished restore (INCOMPLETE) keeps its staging: the settings, the key and the receipts are still in it.
            if (e.failure != RestoreFailure.INCOMPLETE && e.failure != RestoreFailure.PARTIAL) restorer.discard(staged)
            staged = null
            return RestoreState.Failed(e.failure, e.neededMb)
        }

        private fun reset() {
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

        /**
         * Every recovery (app start and a new pick) runs here: under the restore flag (so a backup and the SMS pipeline stand aside),
         * shielded from cancellation, and never lets an exception out: the result is a [RestoreException] to show, or null.
         * The caller holds the erase lock.
         */
        private suspend fun recoverCore(): RestoreException? =
            withContext(NonCancellable) {
                if (!RestoreActivity.begin()) return@withContext RestoreException(RestoreFailure.BUSY)
                try {
                    restorer.recover()
                    null
                } catch (e: RestoreException) {
                    e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    RestoreException(RestoreFailure.INCOMPLETE).also { it.addSuppressed(e) }
                } finally {
                    RestoreActivity.end()
                }
            }

        private suspend fun recoverGuarded(): RestoreException? = lock.mutex.withLock { recoverCore() }

        /**
         * At app start (REQ-OPS-179): finishes a restore that committed, or removes the staging of one that did not. Takes the erase lock
         * before the UI can save anything.
         */
        fun recoverInterrupted(): Job? {
            if (DatabaseStartup.isBlocked()) return null
            // Taken here, on the calling thread, so a save made right after start waits for the recovery (as DataEraser does).
            val taken = lock.mutex.tryLock()
            return scope.launch(io) {
                if (!taken) lock.mutex.lock()
                try {
                    // Never crashes the app: a failure becomes a message the Settings section shows.
                    recoverCore()?.let { mutableState.value = RestoreState.Failed(it.failure, it.neededMb) }
                    refreshPending()
                } finally {
                    lock.mutex.unlock()
                }
            }
        }
    }
