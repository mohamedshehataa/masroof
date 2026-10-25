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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val OUTBOX_WORK_NAME = "outbox-sync"
const val OUTBOX_RETRY_WORK_NAME = "outbox-sync-retry"
private const val KEY_ATTEMPT = "attempt"

/** Seam so view models can request a sync without knowing about WorkManager. */
interface SyncScheduler {
    fun schedule()

    /** Next attempt after a transient failure, [delaySeconds] from now; [attempt] feeds the exponential backoff. */
    fun scheduleRetry(
        delaySeconds: Long,
        attempt: Int,
    ) = Unit
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
            workManager.enqueueUniqueWork(OUTBOX_RETRY_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

/**
 * Uploads queued manual entries once the network is reachable. On a transient failure it schedules its own next run
 * with [OutboxBackoff] (exponential, jittered, 6 h ceiling, honouring Retry-After) instead of WorkManager's fixed
 * policy, so a long outage never gives up and never hammers the server.
 */
@HiltWorker
class OutboxSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: ExpenseRepository,
        private val scheduler: SyncScheduler,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val report = repository.syncOutbox()
            if (report.retry) {
                val attempt = inputData.getInt(KEY_ATTEMPT, 0)
                scheduler.scheduleRetry(OutboxBackoff.delaySeconds(attempt, report.retryAfterSeconds), attempt + 1)
            }
            return Result.success()
        }
    }
