package eg.masroof.data.auth

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.insights.InsightsCache
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.NoCaptureData
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.SMS_UPLOAD_RETRY_WORK
import eg.masroof.ingest.SMS_UPLOAD_WORK
import eg.masroof.work.OUTBOX_RETRY_WORK_NAME
import eg.masroof.work.OUTBOX_WORK_NAME
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    ) : LocalDataWiper {
        override suspend fun unsyncedCount(): Int = withContext(io) { db.outbox().countAll() + db.parsedOutbox().countAll() }

        override suspend fun retryableCount(): Int = withContext(io) { db.outbox().countRetryable() + db.parsedOutbox().countPending() }

        override suspend fun wipe() {
            workManager.cancelUniqueWork(OUTBOX_WORK_NAME).await()
            workManager.cancelUniqueWork(OUTBOX_RETRY_WORK_NAME).await()
            // Queued messages must never upload into the next account: cancel the jobs (their input data holds text)
            // and WAIT for the cancellation, so no intake worker can insert after the tables are cleared.
            workManager.cancelUniqueWork(SMS_UPLOAD_WORK).await()
            workManager.cancelUniqueWork(SMS_UPLOAD_RETRY_WORK).await()
            workManager.cancelAllWorkByTag(SMS_INTAKE_TAG).await()
            withContext(io) { awaitIdle() }
            workManager.pruneWork()
            withContext(io) { capture.clear() }
            withContext(io) { db.clearAllTables() }
            withContext(io) { insights.clear() }
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
