package eg.masroof.data.auth

import androidx.work.WorkManager
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.insights.InsightsCache
import eg.masroof.di.IoDispatcher
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
    ) : LocalDataWiper {
        override suspend fun unsyncedCount(): Int = withContext(io) { db.outbox().countAll() }

        override suspend fun retryableCount(): Int = withContext(io) { db.outbox().countRetryable() }

        override suspend fun wipe() {
            workManager.cancelUniqueWork(OUTBOX_WORK_NAME)
            workManager.cancelUniqueWork(OUTBOX_RETRY_WORK_NAME)
            withContext(io) { db.clearAllTables() }
            withContext(io) { insights.clear() }
        }
    }
