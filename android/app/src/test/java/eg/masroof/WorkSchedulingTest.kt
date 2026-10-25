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
import eg.masroof.data.SyncReport
import eg.masroof.work.ForegroundSync
import eg.masroof.work.OUTBOX_OPEN_WORK_NAME
import eg.masroof.work.OUTBOX_RETRY_WORK_NAME
import eg.masroof.work.OutboxSyncWorker
import eg.masroof.work.SyncScheduler
import eg.masroof.work.WorkManagerSyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingScheduler : SyncScheduler {
    var retries = mutableListOf<Pair<Long, Int>>()
    var cancels = 0
    var opens = 0

    override fun schedule() = Unit

    override fun scheduleRetry(
        delaySeconds: Long,
        attempt: Int,
    ) {
        retries += delaySeconds to attempt
    }

    override fun cancelRetry() {
        cancels++
    }

    override fun scheduleOnOpen() {
        opens++
    }
}

private class StubRepository(
    private val report: SyncReport = SyncReport(0, 0, false),
    private val retryable: Boolean = false,
) : ExpenseRepository by FakeRepository() {
    override suspend fun syncOutbox() = report

    override suspend fun hasRetryable() = retryable
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

    private fun worker(
        repository: ExpenseRepository,
        recorder: SyncScheduler,
        attempt: Int = 0,
    ) = TestListenableWorkerBuilder<OutboxSyncWorker>(context)
        .setInputData(androidx.work.workDataOf("attempt" to attempt))
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = OutboxSyncWorker(appContext, workerParameters, repository, recorder)
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

    // ---- sync on open

    @Test
    fun `REQ-AND-015 opening the app with retryable entries enqueues exactly one immediate sync`() =
        runTest {
            val sync = ForegroundSync(StubRepository(retryable = true), scheduler)
            repeat(4) { sync.onForeground() }
            assertEquals(1, live(OUTBOX_OPEN_WORK_NAME).size)
        }

    @Test
    fun `REQ-AND-015 opening the app with nothing retryable enqueues nothing`() =
        runTest {
            ForegroundSync(StubRepository(retryable = false), scheduler).onForeground()
            assertEquals(0, live(OUTBOX_OPEN_WORK_NAME).size)
        }

    @Test
    fun `REQ-AND-015 foreground sync asks the scheduler only when something can sync`() =
        runTest {
            val rec = RecordingScheduler()
            ForegroundSync(StubRepository(retryable = true), rec).onForeground()
            ForegroundSync(StubRepository(retryable = false), rec).onForeground()
            assertEquals(1, rec.opens)
        }

    // ---- worker: backoff reset on success

    @Test
    fun `REQ-AND-015 a successful sync cancels the pending retry`() =
        runTest {
            val rec = RecordingScheduler()
            val result = worker(StubRepository(SyncReport(2, 0, false)), rec, attempt = 7).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, rec.cancels)
            assertTrue(rec.retries.isEmpty())
        }

    @Test
    fun `REQ-AND-015 a failing sync schedules one retry with the next attempt number and Retry-After`() =
        runTest {
            val rec = RecordingScheduler()
            worker(StubRepository(SyncReport(0, 0, true, retryAfterSeconds = 900)), rec, attempt = 2).doWork()
            assertEquals(1, rec.retries.size)
            assertEquals(3, rec.retries.single().second)
            assertTrue(rec.retries.single().first >= 900)
            assertEquals(0, rec.cancels)
        }

    @Test
    fun `REQ-AND-015 an unauthorized sync neither retries nor cancels`() =
        runTest {
            val rec = RecordingScheduler()
            worker(StubRepository(SyncReport(0, 0, false, unauthorized = true)), rec).doWork()
            assertTrue(rec.retries.isEmpty())
            assertEquals(0, rec.cancels)
        }
}
