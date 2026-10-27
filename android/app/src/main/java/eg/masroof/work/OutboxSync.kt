package eg.masroof.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.db.DatabaseStartup
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val OUTBOX_WORK_NAME = "outbox-sync"
const val OUTBOX_RETRY_WORK_NAME = "outbox-sync-retry"
const val OUTBOX_OPEN_WORK_NAME = "outbox-sync-open"
private const val KEY_ATTEMPT = "attempt"

/** Seam so view models can request a sync without knowing about WorkManager. */
interface SyncScheduler {
    fun schedule()

    /** Next attempt after a transient failure, [delaySeconds] from now; [attempt] feeds the exponential backoff. */
    fun scheduleRetry(
        delaySeconds: Long,
        attempt: Int,
    ) = Unit

    /** Drops the pending retry (a sync just succeeded), which also resets the backoff attempt counter. */
    fun cancelRetry() = Unit

    /** One immediate sync on app start/foreground; never stacks (unique work, KEEP). */
    fun scheduleOnOpen() = Unit
}

/** Called when the app comes to the foreground: finishes converting entries left in the old upload queue (REQ-AND-133). */
@Singleton
class ForegroundSync
    @Inject
    constructor(
        private val repository: ExpenseRepository,
    ) {
        suspend fun onForeground() {
            if (DatabaseStartup.isBlocked()) return
            repository.convertQueuedEntries()
        }
    }

@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SyncScheduler {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        override fun schedule() {
            val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>().setConstraints(constraints).build()
            workManager.enqueueUniqueWork(OUTBOX_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        override fun scheduleRetry(
            delaySeconds: Long,
            attempt: Int,
        ) {
            val request =
                OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setInputData(Data.Builder().putAll(workDataOf(KEY_ATTEMPT to attempt)).build())
                    .build()
            // REPLACE: at most one pending retry job. A failing run is the freshest information about the outage, so
            // its delay supersedes whatever was pending (it never grows a chain, whatever triggered the run).
            workManager.enqueueUniqueWork(OUTBOX_RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        override fun cancelRetry() {
            workManager.cancelUniqueWork(OUTBOX_RETRY_WORK_NAME)
        }

        override fun scheduleOnOpen() {
            val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>().setConstraints(constraints).build()
            workManager.enqueueUniqueWork(OUTBOX_OPEN_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

/**
 * Frozen server-era worker (ADR-0009): it no longer uploads anything. It only converts entries that are still in the old
 * queue into local entries, so work enqueued by a previous version finishes harmlessly.
 */
@HiltWorker
class OutboxSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: ExpenseRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Blocked start (REQ-AND-127/128): the database is not touched; WorkManager retries with its own backoff.
            if (DatabaseStartup.isBlocked()) return Result.retry()
            repository.convertQueuedEntries()
            return Result.success()
        }
    }
