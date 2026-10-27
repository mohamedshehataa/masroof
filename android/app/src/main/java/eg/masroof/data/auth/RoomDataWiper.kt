package eg.masroof.data.auth

import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.insights.InsightsCache
import eg.masroof.di.IoDispatcher
import eg.masroof.di.ReceiptsDir
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.NoCaptureData
import eg.masroof.ingest.NoCapturedNotifier
import eg.masroof.ingest.NoopCaptureGate
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.SMS_UPLOAD_RETRY_WORK
import eg.masroof.ingest.SMS_UPLOAD_WORK
import eg.masroof.work.OUTBOX_RETRY_WORK_NAME
import eg.masroof.work.OUTBOX_WORK_NAME
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDataWiper
    @Inject
    constructor(
        private val db: AppDatabase,
        private val insights: InsightsCache,
        private val workManager: WorkManager,
        @IoDispatcher private val io: CoroutineDispatcher,
        private val capture: CaptureData = NoCaptureData,
        private val notifier: CapturedNotifier = NoCapturedNotifier,
        private val gate: CaptureGate = NoopCaptureGate,
        @ReceiptsDir private val receiptsDir: File = File("no-receipts-dir"),
    ) : LocalDataWiper {
        override suspend fun counts(): EraseCounts = withContext(io) { EraseCounts(db.expenses().count(), db.receipts().count()) }

        /**
         * "Erase all data" (REQ-AND-132), the only caller. The erase-in-progress flag (the capture gate) is closed first, so a
         * capture job that is still running re-checks it inside its write transaction and writes nothing. The database part is one
         * transaction: if it throws, nothing was changed and the caller shows the error.
         */
        override suspend fun wipe() {
            gate.close()
            try {
                workManager.cancelUniqueWork(OUTBOX_WORK_NAME).await()
                workManager.cancelUniqueWork(OUTBOX_RETRY_WORK_NAME).await()
                // Queued messages' input data holds text: cancel the jobs and WAIT for the cancellation, so no intake worker can
                // insert after the tables are cleared.
                workManager.cancelUniqueWork(SMS_UPLOAD_WORK).await()
                workManager.cancelUniqueWork(SMS_UPLOAD_RETRY_WORK).await()
                workManager.cancelAllWorkByTag(SMS_INTAKE_TAG).await()
                withContext(io) { awaitIdle() }
                workManager.pruneWork()
                withContext(io) {
                    db.withTransaction {
                        db.clearAllTables()
                        // The defaults (categories, "Me") are not user data: the app must be as after a fresh install.
                        DefaultSeed.seed(db.openHelper.writableDatabase)
                    }
                    deleteReceipts()
                    capture.clear()
                    insights.clear()
                }
                // nothing stays on the lock screen: every "Recorded ... Undo" notification goes
                notifier.cancelAll()
            } finally {
                gate.open()
            }
        }

        private fun deleteReceipts() {
            receiptsDir.listFiles()?.forEach { it.deleteRecursively() }
        }

        /**
         * Best effort: waits at most five seconds for capture jobs that are still RUNNING. It does not guarantee they ended;
         * a job that outlives the wait is stopped by the gate, which the pipeline re-checks inside its write transaction.
         */
        private fun awaitIdle() {
            val deadline = System.nanoTime() + IDLE_TIMEOUT_NANOS
            while (System.nanoTime() < deadline && running()) Thread.sleep(IDLE_POLL_MILLIS)
        }

        private fun running(): Boolean =
            listOf(
                workManager.getWorkInfosByTag(SMS_INTAKE_TAG),
                workManager.getWorkInfosForUniqueWork(SMS_UPLOAD_WORK),
                workManager.getWorkInfosForUniqueWork(SMS_UPLOAD_RETRY_WORK),
            ).any { f -> f.get().any { it.state == WorkInfo.State.RUNNING } }

        private companion object {
            const val IDLE_TIMEOUT_NANOS = 5_000_000_000L
            const val IDLE_POLL_MILLIS = 50L
        }
    }
