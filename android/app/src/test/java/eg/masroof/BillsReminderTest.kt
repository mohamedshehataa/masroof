package eg.masroof

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.core.Money
import eg.masroof.data.bills.BillsReminderSource
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.NotificationPermission
import eg.masroof.work.AndroidReminderNotifier
import eg.masroof.work.BillsReminderScheduler
import eg.masroof.work.BillsReminderWorker
import eg.masroof.work.DueReminder
import eg.masroof.work.EXTRA_OPEN_ROUTE
import eg.masroof.work.PrefsReminderLog
import eg.masroof.work.ReminderLog
import eg.masroof.work.ReminderNotifier
import eg.masroof.work.ReminderRunner
import eg.masroof.work.ReminderSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

private class RecordingReminderNotifier(
    var allowed: Boolean = true,
) : ReminderNotifier {
    val posted = mutableListOf<Pair<DueReminder, Int>>()

    override fun canPost() = allowed

    override fun post(
        reminder: DueReminder,
        daysUntil: Int,
    ) {
        posted += reminder to daysUntil
    }
}

private class MemoryReminderLog : ReminderLog {
    val keys = mutableSetOf<String>()

    override fun wasReminded(key: String) = key in keys

    override fun markReminded(key: String) {
        keys += key
    }

    override fun clear() {
        keys.clear()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillsReminderTest {
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

    private suspend fun seed(
        first: String,
        count: Int = 3,
        paid: Int = 0,
        reminderDays: Int = 1,
        status: String = "ACTIVE",
        id: String = "p1",
    ) {
        val plan = BillsFixtures.plan(id, 325_000, count, first, title = "iPhone 17 Pro", reminderDays = reminderDays, status = status)
        db.installmentPlans().insert(plan)
        BillsFixtures.payments(plan, paid).forEach { db.installmentPayments().insert(it) }
    }

    private fun runner(
        notifier: ReminderNotifier,
        log: ReminderLog,
        now: String = "2026-11-08T07:00:00Z",
        extra: Set<ReminderSource> = emptySet(),
    ) = ReminderRunner(setOf<ReminderSource>(BillsReminderSource(db, context)) + extra, log, notifier, clockAt(now))

    @Test
    fun `REQ-AND-148 a payment due tomorrow with 1 day before posts one reminder for it`() =
        runTest {
            seed(first = "2026-11-09")
            val notifier = RecordingReminderNotifier()
            assertEquals(1, runner(notifier, MemoryReminderLog()).run())
            val (reminder, days) = notifier.posted.single()
            assertEquals("valU - iPhone 17 Pro", reminder.label)
            assertEquals(Money(325_000, "EGP"), reminder.amount)
            assertEquals(LocalDate.parse("2026-11-09"), reminder.dueDate)
            assertEquals("bills/p1", reminder.route)
            assertEquals(1, days)
        }

    @Test
    fun `REQ-AND-148 each payment is reminded once, across runs and across a restart`() =
        runTest {
            seed(first = "2026-11-09")
            val prefs = context.getSharedPreferences("test_reminders", Context.MODE_PRIVATE)
            val first = RecordingReminderNotifier()
            assertEquals(1, runner(first, PrefsReminderLog(prefs)).run())
            // the same day again, then a new process (a new log over the same preferences): nothing more
            val again = RecordingReminderNotifier()
            assertEquals(0, runner(again, PrefsReminderLog(prefs)).run())
            assertEquals(
                0,
                runner(
                    again,
                    PrefsReminderLog(context.getSharedPreferences("test_reminders", Context.MODE_PRIVATE)),
                    "2026-11-09T07:00:00Z",
                ).run(),
            )
            assertTrue(again.posted.isEmpty())
        }

    @Test
    fun `REQ-AND-148 no reminder when the payment is already paid`() =
        runTest {
            seed(first = "2026-11-09", count = 1, paid = 1)
            val notifier = RecordingReminderNotifier()
            assertEquals(0, runner(notifier, MemoryReminderLog()).run())
        }

    @Test
    fun `REQ-AND-148 no reminder for a completed plan and none before the reminder window opens`() =
        runTest {
            seed(first = "2026-11-09", status = "COMPLETED", id = "done")
            seed(first = "2026-11-10", id = "later")
            val notifier = RecordingReminderNotifier()
            val log = MemoryReminderLog()
            assertEquals(0, runner(notifier, log).run())
            // the next morning the payment due on the 10th is inside its 1 day window
            assertEquals(1, runner(notifier, log, "2026-11-09T07:00:00Z").run())
            assertEquals(
                "bills/later",
                notifier.posted
                    .single()
                    .first.route,
            )
        }

    @Test
    fun `REQ-AND-148 a longer reminder window reminds earlier`() =
        runTest {
            seed(first = "2026-11-11", reminderDays = 3)
            val notifier = RecordingReminderNotifier()
            assertEquals(1, runner(notifier, MemoryReminderLog()).run())
            assertEquals(3, notifier.posted.single().second)
        }

    @Test
    fun `REQ-AND-148 with notifications denied nothing is posted or remembered and nothing crashes`() =
        runTest {
            seed(first = "2026-11-09")
            val notifier = RecordingReminderNotifier(allowed = false)
            val log = MemoryReminderLog()
            assertEquals(0, runner(notifier, log).run())
            assertTrue(log.keys.isEmpty())
            notifier.allowed = true
            assertEquals(1, runner(notifier, log).run())
        }

    @Test
    fun `REQ-AND-148 another feature's ReminderSource is collected without editing the worker`() =
        runTest {
            seed(first = "2026-11-09")
            val other =
                object : ReminderSource {
                    override suspend fun dueOn(today: LocalDate) =
                        listOf(DueReminder("sub:1", "Netflix", Money(19_900, "EGP"), today.plusDays(1), "bills/none"))
                }
            val notifier = RecordingReminderNotifier()
            assertEquals(2, runner(notifier, MemoryReminderLog(), extra = setOf(other)).run())
            assertEquals(setOf("Netflix", "valU - iPhone 17 Pro"), notifier.posted.map { it.first.label }.toSet())
        }

    @Test
    fun `REQ-AND-148 the worker runs the daily pass and succeeds, also with notifications denied`() =
        runTest {
            seed(first = "2026-11-09")
            val notifier = RecordingReminderNotifier()
            val runner = runner(notifier, MemoryReminderLog())
            val factory =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = BillsReminderWorker(appContext, workerParameters, runner)
                }
            val worker = TestListenableWorkerBuilder<BillsReminderWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(1, notifier.posted.size)
            notifier.allowed = false
            assertEquals(
                ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<BillsReminderWorker>(context).setWorkerFactory(factory).build().doWork(),
            )
        }

    @Test
    fun `REQ-AND-148 scheduling enqueues one unique daily job and repeated starts keep it`() {
        val scheduler = BillsReminderScheduler(context, clockAt("2026-11-08T05:00:00Z"))
        scheduler.schedule()
        scheduler.schedule()
        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(BillsReminderScheduler.DAILY_NAME)
                .get()
                .filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, infos.size)
        assertTrue(infos.single().tags.any { it.contains("BillsReminderWorker") })
    }

    private fun postAndRead(
        arabic: Boolean,
        days: Int,
    ): String {
        val ctx = if (arabic) ApplicationProvider.getApplicationContext<Context>() else context
        val granted =
            object : NotificationPermission {
                override fun granted() = true
            }
        val notifier = AndroidReminderNotifier(ctx, granted)
        notifier.post(DueReminder("bill:x", "valU - iPhone 17 Pro", Money(325_000, "EGP"), LocalDate.parse("2026-11-09"), "bills/p1"), days)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val note = shadowOf(manager).allNotifications.last()
        return note.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
    }

    @Test
    fun `REQ-AND-148 the notification reads 'valU - iPhone 17 Pro - 3,250 EGP due tomorrow'`() {
        assertEquals("valU - iPhone 17 Pro: 3,250 EGP due tomorrow", postAndRead(arabic = false, days = 1))
        assertEquals("valU - iPhone 17 Pro: 3,250 EGP due today", postAndRead(arabic = false, days = 0))
        assertEquals("valU - iPhone 17 Pro: 3,250 EGP due in 3 days", postAndRead(arabic = false, days = 3))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-148 the notification is in Arabic when the app is Arabic`() {
        assertEquals("valU - iPhone 17 Pro: ٣٬٢٥٠ ج.م مستحق بكرة", postAndRead(arabic = true, days = 1))
    }

    @Test
    fun `REQ-AND-148 tapping the notification opens the plan through an allow-listed route`() {
        val granted =
            object : NotificationPermission {
                override fun granted() = true
            }
        AndroidReminderNotifier(context, granted).post(
            DueReminder(
                "bill:y",
                "valU - iPhone 17 Pro",
                Money(325_000, "EGP"),
                LocalDate.parse("2026-11-09"),
                "bills/0b9f3a52-6c1e-4d7b-9a3e-5f2d8c1b7a40",
            ),
            1,
        )
        val note = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.last()
        val intent = shadowOf(note.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("bills/0b9f3a52-6c1e-4d7b-9a3e-5f2d8c1b7a40", intent.getStringExtra(EXTRA_OPEN_ROUTE))
        assertEquals("bills/0b9f3a52-6c1e-4d7b-9a3e-5f2d8c1b7a40", MainActivity.routeFrom(intent))
        // the public version of the notification carries no provider, title or amount
        assertTrue(
            !note.publicVersion.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
                .contains("valU"),
        )
    }

    @Test
    fun `REQ-AND-148 only bills routes are accepted from an incoming intent`() {
        fun with(route: String?) = Intent().apply { if (route != null) putExtra(EXTRA_OPEN_ROUTE, route) }
        val uuid = "0b9f3a52-6c1e-4d7b-9a3e-5f2d8c1b7a40"
        assertEquals("bills/$uuid", MainActivity.routeFrom(with("bills/$uuid")))
        assertNull(MainActivity.routeFrom(with("bills/abc-123")))
        assertNull(MainActivity.routeFrom(with("bills/add")))
        assertNull(MainActivity.routeFrom(with("bills/$uuid/extra")))
        assertNull(MainActivity.routeFrom(with("bills/../settings")))
        assertNull(MainActivity.routeFrom(with("settings")))
        assertNull(MainActivity.routeFrom(with("bills/add?x=1")))
        assertNull(MainActivity.routeFrom(with(null)))
        assertNull(MainActivity.routeFrom(null))
    }

    @Test
    fun `REQ-AND-148 the notification is private, its tap intent is immutable and two reminders never collide`() {
        val granted =
            object : NotificationPermission {
                override fun granted() = true
            }
        val notifier = AndroidReminderNotifier(context, granted)
        val uuid1 = "0b9f3a52-6c1e-4d7b-9a3e-5f2d8c1b7a40"
        val uuid2 = "1c0a4b63-7d2f-4e8c-8b4f-6a3e9d2c8b51"
        listOf(uuid1, uuid2).forEach {
            notifier.post(DueReminder("bill:$it", "valU - X", Money(100, "EGP"), LocalDate.parse("2026-11-09"), "bills/$it"), 1)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val notes = shadowOf(manager).allNotifications
        assertEquals(2, notes.size)
        notes.forEach {
            assertEquals(Notification.VISIBILITY_PRIVATE, it.visibility)
            assertTrue(shadowOf(it.contentIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        }
        val routes = notes.map { shadowOf(it.contentIntent).savedIntent.getStringExtra(EXTRA_OPEN_ROUTE) }
        assertEquals(setOf("bills/$uuid1", "bills/$uuid2"), routes.toSet())
        assertTrue(notes.map { shadowOf(it.contentIntent).savedIntent.data }.toSet().size == 2)
    }

    @Test
    fun `REQ-AND-148 Erase all data clears the already-reminded memory`() =
        runTest {
            val prefs = context.getSharedPreferences("masroof_reminders_test", Context.MODE_PRIVATE)
            val log = PrefsReminderLog(prefs)
            log.markReminded("bill:a")
            assertTrue(prefs.all.isNotEmpty())
            val wiper =
                eg.masroof.data.auth.RoomDataWiper(
                    db,
                    NoInsightsCache,
                    WorkManager.getInstance(context),
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    reminders = log,
                )
            wiper.wipe()
            assertTrue(prefs.all.isEmpty())
            assertEquals(false, log.wasReminded("bill:a"))
        }
}
