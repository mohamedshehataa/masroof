package eg.masroof.work

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.wealth.WealthRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Writes today's net worth point (REQ-AN-169). Idempotent: the same Cairo day updates its row. */
@HiltWorker
class NetWorthSnapshotWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val wealth: WealthRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (DatabaseStartup.isBlocked()) return Result.retry()
            return try {
                wealth.snapshotToday()
                Result.success()
            } catch (_: SQLiteException) {
                Result.retry()
            }
        }
    }

/** One snapshot at app start and one a day. Local work only: no network, no charging constraint. */
@Singleton
class NetWorthSnapshotScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun schedule() {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(START_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NetWorthSnapshotWorker>().build())
            wm.enqueueUniquePeriodicWork(
                DAILY_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NetWorthSnapshotWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        companion object {
            const val START_NAME = "net-worth-snapshot-start"
            const val DAILY_NAME = "net-worth-snapshot-daily"
        }
    }
