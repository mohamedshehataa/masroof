package eg.masroof.data.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The foreground task that writes the backup to the file the user picked (REQ-OPS-177). */
@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val env: BackupEnvironmentFactory,
        private val pending: PendingBackupKey,
        private val status: BackupStatusStore,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(context, id, 0)

        @Suppress("TooGenericExceptionCaught") // a provider can throw anything unchecked; all of it is a failed backup
        override suspend fun doWork(): Result {
            val destination = inputData.getString(KEY_URI)?.let(Uri::parse)
            val input = pending.take(id)
            if (destination == null || input == null) {
                input?.key?.fill(0)
                return failure(BackupFailure.KEY_LOST)
            }
            // A restore swaps the receipts directory: a backup that ran across it would fail with "receipt changed" (STORY-184).
            if (RestoreActivity.isActive() || RestoreStage.marker(java.io.File(context.filesDir, "receipts")).exists()) {
                input.key.fill(0)
                return failure(BackupFailure.RESTORE_RUNNING)
            }
            try {
                // Leftovers of a backup that was killed: the unique work guarantees nothing else uses the folder.
                BackupStage.sweep(BackupStage.dir(context.noBackupFilesDir))
                setForeground(foregroundInfo(context, id, 0))
                val sink = context.contentResolver.openOutputStream(destination, "wt") ?: throw FileNotFoundException()
                val result =
                    BackupWriter(env.create(clock.millis())).write(sink, input) { pct ->
                        setProgressAsync(workDataOf(KEY_PERCENT to pct))
                        notify(context, foregroundInfo(context, id, pct).notification)
                    }
                // Only now, with the final segment written and the provider's stream closed, is it a backup.
                status.record(LastBackup(clock.millis(), result.entries, result.receiptsStored, result.fileBytes))
                return Result.success(
                    workDataOf(
                        KEY_ENTRIES to result.entries,
                        KEY_RECEIPTS to result.receiptsStored,
                        KEY_MISSING to result.receiptsMissing,
                        KEY_BYTES to result.fileBytes,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return failure(BackupFailure.classify(e))
            } catch (e: RuntimeException) {
                return failure(BackupFailure.classify(e))
            } finally {
                input.key.fill(0)
            }
        }

        private fun failure(reason: BackupFailure) = Result.failure(workDataOf(KEY_REASON to reason.name))

        companion object {
            const val UNIQUE_NAME = "masroof-backup"
            const val KEY_URI = "uri"
            const val KEY_PERCENT = "pct"
            const val KEY_ENTRIES = "entries"
            const val KEY_RECEIPTS = "receipts"
            const val KEY_MISSING = "missing"
            const val KEY_BYTES = "bytes"
            const val KEY_REASON = "reason"
            const val CHANNEL = "masroof_backup"
            private const val NOTIFICATION_ID = 0x0B4C

            private fun notify(
                context: Context,
                notification: Notification,
            ) {
                // Posting can be refused when notifications are off: the task still runs, only its notification is hidden.
                runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
            }

            fun foregroundInfo(
                context: Context,
                id: java.util.UUID,
                percent: Int,
            ): ForegroundInfo {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW),
                )
                // No amount, no merchant, no file name: this text is visible on the lock screen.
                val notification =
                    Notification
                        .Builder(context, CHANNEL)
                        .setSmallIcon(context.applicationInfo.icon)
                        .setContentTitle(context.getString(R.string.backup_notif_title))
                        .setContentText(context.getString(R.string.backup_notif_progress, percent))
                        .setProgress(PERCENT_MAX, percent, false)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .addAction(
                            Notification.Action
                                .Builder(
                                    null,
                                    context.getString(R.string.backup_cancel),
                                    WorkManager.getInstance(context).createCancelPendingIntent(id),
                                ).build(),
                        ).build()
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notification)
                }
            }

            private const val PERCENT_MAX = 100
        }
    }

/** Builds the [BackupEnvironment] for one run from the live app (database, preference stores, receipts, local-id key). */
class BackupEnvironmentFactory
    @Inject
    constructor(
        private val db: eg.masroof.data.db.AppDatabase,
        @ApplicationContext private val context: Context,
        @eg.masroof.di.ReceiptsDir private val receiptsDir: java.io.File,
        private val localIdKey: eg.masroof.ingest.LocalIdKey,
    ) {
        fun create(nowMillis: Long) =
            BackupEnvironment(
                db = RoomBackupDatabase(db),
                prefs = AndroidBackupPrefs(context),
                localIdKey = { localIdKey.get() },
                receipts = DirectoryReceiptFiles(receiptsDir),
                appVersion = eg.masroof.BuildConfig.VERSION_NAME,
                nowMillis = nowMillis,
                tempDir = BackupStage.dir(context.noBackupFilesDir),
            )
    }

/** What the Settings section shows about a backup run, from WorkManager's own record so it survives leaving the app. */
sealed interface BackupRun {
    data object Idle : BackupRun

    data class Running(
        val percent: Int,
    ) : BackupRun

    data class Succeeded(
        val entries: Long,
        val receipts: Int,
        val missing: Int,
        val bytes: Long,
    ) : BackupRun

    data class Failed(
        val reason: BackupFailure,
    ) : BackupRun

    /** Cancelled by the user: the file may be incomplete. */
    data object Cancelled : BackupRun
}

/** What the screen needs from the backup task; the real one is WorkManager, tests use a fake. */
interface BackupLauncher {
    /**
     * The key is derived already; the foreground task takes it from memory. False when a backup is already queued or running: nothing is
     * started and the key is not kept, so a queued request can never write with another request's passphrase.
     */
    suspend fun start(
        destination: Uri,
        input: BackupInput,
    ): Boolean

    fun cancel()

    /** The user has seen the result of the finished run: it is shown as idle from now on. Other work is not touched. */
    suspend fun acknowledge()

    /**
     * Removes the document the picker returned, but ONLY if it is still empty (0 bytes): the picker lets the user choose an existing file
     * and confirm "Overwrite", and a good earlier backup must never be deleted because a new one did not start. True when an empty
     * document was removed; false when it was left alone (it holds data, its size is unknown, or the provider refused).
     */
    suspend fun discard(destination: Uri): Boolean

    val run: Flow<BackupRun>
}

@Singleton
class WorkBackupLauncher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val workManager: WorkManager,
        private val pending: PendingBackupKey,
        private val status: BackupStatusStore,
    ) : BackupLauncher {
        private val startLock = Mutex()
        private val documents = DocumentDiscarder(AndroidDocumentGate(context))

        override suspend fun start(
            destination: Uri,
            input: BackupInput,
        ): Boolean =
            startLock.withLock {
                startLocked(destination, input).also { started -> if (!started) input.key.fill(0) }
            }

        private suspend fun startLocked(
            destination: Uri,
            input: BackupInput,
        ): Boolean {
            val active = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME).first().any { !it.state.isFinished }
            if (active) return false
            val request =
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInputData(Data.Builder().putString(BackupWorker.KEY_URI, destination.toString()).build())
                    // Local work, no network and no charging constraint: the user asked for it now and the provider uploads by itself.
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            pending.put(request.id, input)
            try {
                workManager.enqueueUniqueWork(BackupWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request).await()
            } catch (e: IllegalStateException) {
                pending.discard(request.id)
                throw e
            }
            // KEEP drops a request when other work got in first: then this key must not stay in memory.
            val queued = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME).first().any { it.id == request.id }
            if (!queued) pending.discard(request.id)
            return queued
        }

        override fun cancel() {
            workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
        }

        override suspend fun acknowledge() {
            val latest = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME).first().lastOrNull() ?: return
            if (latest.state.isFinished) status.acknowledge(latest.id.toString())
        }

        override suspend fun discard(destination: Uri): Boolean = documents.discard(destination)

        override val run: Flow<BackupRun> =
            combine(workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME), status.acknowledged) { infos, ack ->
                val latest = infos.lastOrNull()
                if (latest != null && latest.state.isFinished && latest.id.toString() == ack) BackupRun.Idle else latest.toRun()
            }
    }

internal fun WorkInfo?.toRun(): BackupRun =
    when (this?.state) {
        null -> BackupRun.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BackupRun.Running(0)
        WorkInfo.State.RUNNING -> BackupRun.Running(progress.getInt(BackupWorker.KEY_PERCENT, 0))
        WorkInfo.State.SUCCEEDED ->
            BackupRun.Succeeded(
                entries = outputData.getLong(BackupWorker.KEY_ENTRIES, 0L),
                receipts = outputData.getInt(BackupWorker.KEY_RECEIPTS, 0),
                missing = outputData.getInt(BackupWorker.KEY_MISSING, 0),
                bytes = outputData.getLong(BackupWorker.KEY_BYTES, 0L),
            )
        WorkInfo.State.FAILED ->
            BackupRun.Failed(
                outputData.getString(BackupWorker.KEY_REASON)?.let { r -> BackupFailure.entries.firstOrNull { it.name == r } }
                    ?: BackupFailure.OTHER,
            )
        WorkInfo.State.CANCELLED -> BackupRun.Cancelled
    }

/** The two things the discard rule needs from a document provider; faked in tests. */
interface DocumentGate {
    /** The document's size in bytes, null when the provider does not say. */
    fun size(uri: Uri): Long?

    fun delete(uri: Uri): Boolean
}

class AndroidDocumentGate(
    private val context: Context,
) : DocumentGate {
    override fun size(uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull()

    override fun delete(uri: Uri): Boolean =
        runCatching {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                uri,
            )
        }.getOrDefault(false)
}

/** Deletes a document only when it is known to be empty: an existing backup the user chose to overwrite is never lost. */
class DocumentDiscarder(
    private val gate: DocumentGate,
) {
    suspend fun discard(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            if (gate.size(uri) == 0L) gate.delete(uri) else false
        }
}
