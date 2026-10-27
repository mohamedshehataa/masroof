package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.ExpenseRepository
import eg.masroof.work.ForegroundSync
import eg.masroof.work.OUTBOX_OPEN_WORK_NAME
import eg.masroof.work.OUTBOX_RETRY_WORK_NAME
import eg.masroof.work.OutboxSyncWorker
import eg.masroof.work.WorkManagerSyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class StubRepository : ExpenseRepository by FakeRepository() {
    var conversions = 0

    override suspend fun convertQueuedEntries(): Int {
        conversions++
        return 0
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkSchedulingTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler(workManager)
    }

    private fun live(name: String) =
        workManager
            .getWorkInfosForUniqueWork(name)
            .get()
            .filter { it.state == WorkInfo.State.ENQUEUED }

    private fun worker(repository: ExpenseRepository) =
        TestListenableWorkerBuilder<OutboxSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = OutboxSyncWorker(appContext, workerParameters, repository)
                },
            ).build()

    // ---- bounded retry chain

    @Test
    fun `REQ-AND-015 many failing runs leave at most one pending retry job`() {
        repeat(6) { scheduler.scheduleRetry(60L * (it + 1), it + 1) }
        assertEquals(1, live(OUTBOX_RETRY_WORK_NAME).size)
    }

    @Test
    fun `REQ-AND-015 the newest retry replaces the older one`() {
        scheduler.scheduleRetry(21_600, 9)
        val first = live(OUTBOX_RETRY_WORK_NAME).single().id
        scheduler.scheduleRetry(30, 1)
        val now = live(OUTBOX_RETRY_WORK_NAME).single().id
        assertTrue(first != now)
    }

    @Test
    fun `REQ-AND-015 cancelRetry removes the pending retry`() {
        scheduler.scheduleRetry(600, 3)
        scheduler.cancelRetry()
        assertEquals(0, live(OUTBOX_RETRY_WORK_NAME).size)
    }

    // ---- foreground and worker only convert; nothing is uploaded or scheduled (REQ-AND-133)

    @Test
    fun `REQ-AND-133 opening the app converts the old queue and schedules no upload`() =
        runTest {
            val repo = StubRepository()
            ForegroundSync(repo).onForeground()
            assertEquals(1, repo.conversions)
            assertEquals(0, live(OUTBOX_OPEN_WORK_NAME).size)
        }

    @Test
    fun `REQ-AND-133 the old sync worker only converts and succeeds`() =
        runTest {
            val repo = StubRepository()
            val result = worker(repo).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, repo.conversions)
        }
}
