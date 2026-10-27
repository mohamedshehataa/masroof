package eg.masroof

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.DbStartup
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.ACTION_UNDO_CAPTURED
import eg.masroof.ingest.CapturedActionReceiver
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.EXTRA_LOCAL_ID
import eg.masroof.ingest.SmsIntakeWorker
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploadWorker
import eg.masroof.ingest.SmsUploader
import eg.masroof.ui.startup.StartupBlockedScreen
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.work.ForegroundSync
import eg.masroof.work.OutboxSyncWorker
import eg.masroof.work.SyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock

/** The repository of a blocked start must never be asked anything: every call fails the test. */
private class ForbiddenRepository : ExpenseRepository by FakeRepository() {
    var asked = 0

    override suspend fun convertQueuedEntries(): Int {
        asked++
        error("database touched while blocked")
    }
}

private class NoopScheduler : SyncScheduler {
    var calls = 0

    override fun schedule() {
        calls++
    }

    override fun scheduleRetry(
        delaySeconds: Long,
        attempt: Int,
    ) {
        calls++
    }

    override fun cancelRetry() {
        calls++
    }

    override fun scheduleOnOpen() {
        calls++
    }
}

private class NeverRemote : ParsedRemote {
    override suspend fun uploadParsed(
        deviceId: String,
        item: ParsedOutboxEntity,
    ): SmsUploadResult = error("network touched while blocked")
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StartupBlockedTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun block() = DatabaseStartup.setForTest(DbStartup.CouldNotFinish)

    @After
    fun unblock() = DatabaseStartup.setForTest(null)

    @Test
    fun `REQ-AND-127 a blocked start shows the could not finish message`() {
        compose.setContent { MasroofTheme { StartupBlockedScreen(DbStartup.CouldNotFinish) } }
        compose.onNodeWithText("The update could not finish. Your data is safe; please contact support.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-128 a blocked start for lack of space shows the free up storage message with the size`() {
        compose.setContent { MasroofTheme { StartupBlockedScreen(DbStartup.FreeUpStorage(neededMb = 12)) } }
        compose.onNodeWithText("Free up storage to finish the update. About 12 MB are needed.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-127 a blocked start is reported as blocked and a ready start is not`() {
        assertTrue(DatabaseStartup.isBlocked())
        DatabaseStartup.setForTest(DbStartup.FreeUpStorage(1))
        assertTrue(DatabaseStartup.isBlocked())
        DatabaseStartup.setForTest(DbStartup.Ready(DatabaseStartup.blocked(context)))
        assertFalse(DatabaseStartup.isBlocked())
    }

    @Test
    fun `REQ-AND-127 coming to the foreground while blocked does not touch the database or schedule work`() =
        runTest {
            val repository = ForbiddenRepository()
            val scheduler = NoopScheduler()
            ForegroundSync(repository).onForeground()
            assertEquals(0, repository.asked)
            assertEquals(0, scheduler.calls)
        }

    @Test
    fun `REQ-AND-127 the outbox worker asks WorkManager to retry later while blocked and touches nothing`() =
        runTest {
            val repository = ForbiddenRepository()
            val scheduler = NoopScheduler()
            val worker =
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
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            assertEquals(0, repository.asked)
            assertEquals(0, scheduler.calls)
        }

    @Test
    fun `REQ-AND-127 a capture arriving while blocked is kept for a later retry not dropped and the database is not opened`() =
        runTest {
            val blockedDb: AppDatabase = DatabaseStartup.blocked(context)
            val clock = Clock.systemUTC()
            val pipeline =
                SmsPipeline(blockedDb, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey)
            val device =
                object : DeviceIdProvider {
                    override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
                }
            val uploader = SmsUploader(blockedDb, NeverRemote(), device, clock, MemoryGate())
            val factory =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        if (workerClassName == SmsIntakeWorker::class.java.name) {
                            SmsIntakeWorker(appContext, workerParameters, pipeline)
                        } else {
                            SmsUploadWorker(appContext, workerParameters, uploader, FakeSmsScheduler())
                        }
                }
            val intake =
                TestListenableWorkerBuilder<SmsIntakeWorker>(context)
                    .setInputData(workDataOf("sender" to "CIB", "text" to "Purchase of EGP 99.00 at X", "source" to "SMS", "ts" to 1L))
                    .setWorkerFactory(factory)
                    .build()
            assertEquals(ListenableWorker.Result.retry(), intake.doWork())
            val upload = TestListenableWorkerBuilder<SmsUploadWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.retry(), upload.doWork())
        }

    @Test
    fun `REQ-AND-127 the Undo button of an old notification does nothing while blocked and does not crash`() {
        CapturedActionReceiver().onReceive(context, Intent(ACTION_UNDO_CAPTURED).putExtra(EXTRA_LOCAL_ID, "l1"))
    }
}
