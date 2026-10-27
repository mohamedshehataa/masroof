package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.data.edit.PurgeDeletedWorker
import eg.masroof.data.edit.PurgeScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurgeSchedulerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun live(name: String) =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(name)
            .get()
            .filter { it.state != WorkInfo.State.CANCELLED }

    private fun entry(id: String) = ExpenseEntity(id, 100, "EGP", 1, null, null, null, null, pending = false)

    @Test
    fun `REQ-EXP-046 scheduling at app start enqueues one start purge and one daily periodic purge, repeated starts keep them`() {
        val scheduler = PurgeScheduler(context)
        scheduler.schedule()
        scheduler.schedule()
        assertEquals(1, live(PurgeScheduler.START_NAME).size)
        val daily = live(PurgeScheduler.DAILY_NAME)
        assertEquals(1, daily.size)
        assertEquals(true, daily.single().tags.any { it.contains("PurgeDeletedWorker") })
    }

    @Test
    fun `REQ-EXP-046 the purge worker removes entries deleted over 30 days ago and succeeds`() =
        runTest {
            db.expenses().insert(entry("old"))
            db.expenses().insert(entry("fresh"))
            val now = CLOCK.millis()
            db.expenses().softDelete("old", now - 31 * 86_400_000L)
            db.expenses().softDelete("fresh", now - 1_000L)
            val purger = DeletedEntryPurger(db, CLOCK, EraseLock())
            val worker =
                TestListenableWorkerBuilder<PurgeDeletedWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = PurgeDeletedWorker(appContext, workerParameters, purger)
                        },
                    ).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertNull(db.expenses().find("old"))
            assertEquals(true, db.expenses().find("fresh") != null)
        }

    @Test
    fun `REQ-EXP-046 three quick deletes each reach the Undo snackbar`() =
        runTest {
            val editor = DefaultExpenseEditor(db, LocalDataSignal(), CLOCK, EraseLock())
            for (id in listOf("a", "b", "c")) db.expenses().insert(entry(id))
            val seen = mutableListOf<String>()
            val job = backgroundScope.launch(Dispatchers.Unconfined) { editor.deleted.toList(seen) }
            // A slow collector (the snackbar is showing) must not lose the later ids.
            for (id in listOf("a", "b", "c")) editor.delete(id)
            job.cancel()
            assertEquals(listOf("a", "b", "c"), seen)
        }
}
