package eg.masroof.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eg.masroof.data.ExpenseRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val OUTBOX_WORK_NAME = "outbox-sync"

/** Seam so view models can request a sync without knowing about WorkManager. */
interface SyncScheduler {
    fun schedule()
}

@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SyncScheduler {
        override fun schedule() {
            val request =
                OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            workManager.enqueueUniqueWork(OUTBOX_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private companion object {
            const val BACKOFF_SECONDS = 30L
        }
    }

/** Uploads queued manual entries once the network is reachable; retries with exponential backoff. */
@HiltWorker
class OutboxSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: ExpenseRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = if (repository.syncOutbox().retry) Result.retry() else Result.success()
    }
