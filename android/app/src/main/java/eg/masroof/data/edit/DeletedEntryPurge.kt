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
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.NoAnswerPrefs
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.di.ReceiptsDir
import eg.masroof.ingest.HandledMemory
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.NoHandledMemory
import eg.masroof.ingest.RepeatGuard
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
        private val answers: AnswerPrefs = NoAnswerPrefs,
        private val memory: HandledMemory = NoHandledMemory,
        private val idKey: LocalIdKey? = null,
        private val restoreGate: eg.masroof.data.backup.RestoreGate = eg.masroof.data.backup.OpenRestoreGate,
    ) {
        /** What the 30-day memory of "Read past SMS" must know of the entries that are about to disappear (STORY-186). */
        private suspend fun rememberPurged(cutoff: Long) {
            val key = idKey ?: return
            val guard = RepeatGuard(db, memory, clock) { key.get() }
            val now = clock.millis()
            db.sources().sourcesOfPurgeable(cutoff).forEach { row ->
                guard.marksOfPurged(row).forEach { (signature, at) -> memory.markSignature(signature, at, now) }
            }
        }

        /** A pair or refund link whose row was purged has nothing left to restore: drop it, and let the survivor ask again. */
        private suspend fun dropLinksOfPurgedRows() {
            val dead =
                answers
                    .current()
                    .links
                    .filter { db.expenses().find(it.first) == null || db.expenses().find(it.second) == null }
                    .toSet()
            if (dead.isEmpty()) return
            val ids = dead.flatMapTo(HashSet()) { listOf(it.first, it.second) }
            answers.edit { s -> s.copy(links = s.links - dead, answered = s.answered - ids) }
        }

        /** Returns how many entries were removed. Their receipt rows go in the same transaction, then the files (REQ-RCPT-019). */
        suspend fun purge(): Int {
            val cutoff = clock.millis() - TimeUnit.DAYS.toMillis(PURGE_AFTER_DAYS)
            return eraseLock.mutex.withLock {
                // While a restore runs or is unfinished nothing is hashed with a stale key and no file is deleted: the daily job retries.
                if (restoreGate.blocked()) return@withLock 0
                val files = db.expenses().receiptsDeletedBefore(cutoff)
                rememberPurged(cutoff)
                val removed = db.expenses().purgeDeletedBefore(cutoff)
                // a file that cannot be deleted here has no row any more: the startup sweeper removes it
                files.forEach { runCatching { File(receiptsDir, it.fileName).delete() } }
                if (removed > 0) dropLinksOfPurgedRows()
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
