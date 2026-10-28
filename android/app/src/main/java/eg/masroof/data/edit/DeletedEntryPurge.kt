package eg.masroof.data.edit

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
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.di.ReceiptsDir
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Entries soft-deleted longer ago than this are removed for good, with their sources (ADR-0009 D3). */
const val PURGE_AFTER_DAYS = 30L

@Singleton
class DeletedEntryPurger
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val eraseLock: EraseLock,
        @ReceiptsDir private val receiptsDir: File = File("no-receipts-dir"),
    ) {
        /** Returns how many entries were removed. Their receipt rows go in the same transaction, then the files (REQ-RCPT-019). */
        suspend fun purge(): Int {
            val cutoff = clock.millis() - TimeUnit.DAYS.toMillis(PURGE_AFTER_DAYS)
            return eraseLock.mutex.withLock {
                // a restore racing this read could lose its receipt files: the entry was over 30 days deleted, accepted
                val files = db.expenses().receiptsDeletedBefore(cutoff)
                val removed = db.expenses().purgeDeletedBefore(cutoff)
                // a file that cannot be deleted here has no row any more: the startup sweeper removes it
                files.forEach { runCatching { File(receiptsDir, it.fileName).delete() } }
                removed
            }
        }
    }

@HiltWorker
class PurgeDeletedWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val purger: DeletedEntryPurger,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (DatabaseStartup.isBlocked()) return Result.retry()
            return try {
                purger.purge()
                Result.success()
            } catch (_: SQLiteException) {
                Result.retry()
            }
        }
    }

/** Runs the purge at app start and once a day. No network and no charging constraint: it is a small local delete. */
@Singleton
class PurgeScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun schedule() {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(START_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PurgeDeletedWorker>().build())
            wm.enqueueUniquePeriodicWork(
                DAILY_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PurgeDeletedWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        companion object {
            const val START_NAME = "purge-deleted-start"
            const val DAILY_NAME = "purge-deleted-daily"
        }
    }
