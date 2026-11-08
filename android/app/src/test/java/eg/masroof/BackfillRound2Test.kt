package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.REASON_POSSIBLE_REPEAT
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillNotifier
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillRange
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.InboxSms
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.backfill.SmsInbox
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val R2_PUSH = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"

/** STORY-186 fix round 2: each repro is written to fail on e0ac697 and pass after the fix (clocks move between live and backfill). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillRound2Test {
    private lateinit var db: AppDatabase
    private lateinit var store: BackfillStore
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val settings = MemorySettings()
    private val prefsFake = FakeAnswerPrefs()
    private val gate = MemoryGate()
    private val lock = EraseLock()
    private val summaries = mutableListOf<BackfillState>()
    private val summarySink =
        object : BackfillNotifier {
            override suspend fun finished(state: BackfillState) {
                summaries += state
            }
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BackfillStore(context.getSharedPreferences("round2", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() = db.close()

    private fun pipelineAt(c: Clock = clock) =
        SmsPipeline(db, settings, MemoryNotifications(), c, gate, FixedKey, eraseLock = lock, answers = prefsFake, memory = store)

    private fun engine(inbox: SmsInbox) = SmsBackfill(db, pipelineAt(), inbox, store, settings, FixedKey, gate, clock, summarySink, lock)

    private fun begin() = store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))

    private fun at(days: Long) = BACKFILL_NOW - days * BACKFILL_DAY

    private fun clockAt(millis: Long) = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    private suspend fun livePush(at: Long) =
        pipelineAt(clockAt(at)).accept(IncomingMessage(CaptureSource.NOTIFICATION, NotificationApps.INSTAPAY_CANDIDATE, R2_PUSH, at))

    private suspend fun liveSms(
        body: String,
        at: Long,
        sender: String = "CIB",
    ) = pipelineAt(clockAt(at)).accept(IncomingMessage(CaptureSource.SMS, sender, body, at))

    private fun noSentTime(
        id: Long,
        body: String,
        date: Long,
    ) = InboxSms(id, "CIB", body, date, 0L)

    private fun softDeletedTwin(days: Long) =
        runTest {
            val t = at(days)
            livePush(t)
            db.expenses().softDelete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
                t + 3_600_000,
            )
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            val all = db.expenses().all()
            assertEquals("no new entry", 1, all.size)
            assertNotNull("the deleted entry stays deleted", all.single().deletedAtMillis)
            assertEquals(
                listOf("BACKFILL", "PUSH"),
                db
                    .sources()
                    .all()
                    .map { it.channel }
                    .sorted(),
            )
        }

    @Test
    fun `REQ-SMS-102 a notification the user deleted 3 days ago is not brought back by its backfilled SMS`() = softDeletedTwin(3)

    @Test
    fun `REQ-SMS-102 a notification the user deleted 20 days ago is not brought back by its backfilled SMS`() = softDeletedTwin(20)

    @Test
    fun `REQ-SMS-102 a notification entry purged from the trash is remembered, so its backfilled SMS makes no entry`() =
        runTest {
            val t = at(20)
            livePush(t)
            val expenseId =
                db
                    .expenses()
                    .all()
                    .single()
                    .id
            db.expenses().delete(expenseId)
            db.captured().all().forEach { db.captured().delete(it.localId) }
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            assertTrue("no entry", db.expenses().all().isEmpty())
            assertEquals(0, store.state.value.entries)
        }

    @Test
    fun `REQ-SMS-102 a second identical transfer of the same amount is still an entry when the notification entry exists`() =
        runTest {
            val t = at(20)
            livePush(t)
            begin()
            engine(
                FakeInbox(
                    listOf(
                        sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000),
                        sms(2, INSTANT_IN.replace("bda86be1", "c0ffee02"), sender = "BDC", date = t + 90_000),
                    ),
                ),
            ).run()
            assertEquals("the notification's SMS joined it, the other transfer is its own entry", 2, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-102 a live review item and its backfilled copy without a sent time are one review item`() =
        runTest {
            val t = at(20)
            liveSms(UNKNOWN, t)
            begin()
            engine(FakeInbox(listOf(noSentTime(1, UNKNOWN, t + 4_000)))).run()
            assertEquals(1, db.reviews().all().size)
        }

    @Test
    fun `REQ-SMS-102 a live review item the user dismissed does not come back from a copy without a sent time`() =
        runTest {
            val t = at(20)
            liveSms(UNKNOWN, t)
            db.reviews().delete(
                db
                    .reviews()
                    .all()
                    .single()
                    .id,
            )
            begin()
            engine(FakeInbox(listOf(noSentTime(1, UNKNOWN, t + 4_000)))).run()
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-102 a purged live entry and its copy without a sent time go to review as a possible repeat, never counted`() =
        runTest {
            val t = at(20)
            liveSms(purchase(10), t)
            db.expenses().delete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
            )
            db.captured().all().forEach { db.captured().delete(it.localId) }
            begin()
            engine(FakeInbox(listOf(noSentTime(1, purchase(10), t + 4_000)))).run()
            assertTrue("not re-recorded", db.expenses().all().isEmpty())
            assertEquals(0, store.state.value.entries)
            assertEquals(
                REASON_POSSIBLE_REPEAT,
                db
                    .reviews()
                    .all()
                    .single()
                    .reason,
            )
            assertEquals(1, store.state.value.review)
        }

    @Test
    fun `REQ-SMS-102 a real second purchase 30 seconds after a live one is not lost, it reaches review`() =
        runTest {
            val t = at(20)
            liveSms(purchase(10), t)
            begin()
            engine(FakeInbox(listOf(noSentTime(1, purchase(10), t + 3_000), noSentTime(2, purchase(10), t + 33_000)))).run()
            assertEquals("nothing counted twice", 1, db.expenses().all().size)
            assertEquals(0, store.state.value.entries)
            assertEquals(listOf(REASON_POSSIBLE_REPEAT, REASON_POSSIBLE_REPEAT), db.reviews().all().map { it.reason })
        }

    @Test
    fun `REQ-SMS-102 with a known sent time the cluster rule does not apply, a different purchase is an entry`() =
        runTest {
            val t = at(20)
            liveSms(purchase(10), t)
            begin()
            engine(FakeInbox(listOf(sms(1, purchase(10), date = t + 33_000)))).run()
            assertEquals(2, db.expenses().all().size)
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-102 the 30 day memory holds a month of keys only, however many captures came before`() {
        val old = at(200)
        repeat(50) { store.mark("old-$it", old + it) }
        store.markSignature("sig-old", old, old)
        val prefs = context.getSharedPreferences("round2", Context.MODE_PRIVATE)
        assertEquals("50 ids and one signature", 51, prefs.all.size)
        store.mark("fresh", BACKFILL_NOW)
        assertEquals(setOf("seen:fresh"), prefs.all.keys)
    }

    @Test
    fun `REQ-SMS-102 live captures prune the memory as they go`() =
        runTest {
            repeat(3) { liveSms(purchase(10 + it), at(200) + it * 600_000L) }
            liveSms(purchase(99), at(1))
            val keys = context.getSharedPreferences("round2", Context.MODE_PRIVATE).all.keys
            assertEquals("the id and the signature of the one recent capture: $keys", 2, keys.size)
        }

    @Test
    fun `REQ-SMS-102 a backfilled SMS joining a fresh notification entry gives it the card tail but keeps its name`() =
        runTest {
            val t = at(20)
            livePush(t)
            val before = db.expenses().all().single()
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            val after = db.expenses().all().single()
            assertEquals("1094", after.instrumentLast4)
            assertEquals("the name of the notification stays", before.merchant, after.merchant)
        }

    @Test
    fun `REQ-SMS-102 an old or edited notification entry only gets the source, never a card tail`() =
        runTest {
            val t = at(20)
            livePush(t)
            // a date moved far from the notification's (what a spoofed row aims at)
            db.openHelper.writableDatabase.execSQL("UPDATE expense SET occurredAtMillis = occurredAtMillis - 3600000")
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            val entry = db.expenses().all().single()
            assertNull("no tail", entry.instrumentLast4)
            assertEquals(
                listOf("BACKFILL", "PUSH"),
                db
                    .sources()
                    .all()
                    .map { it.channel }
                    .sorted(),
            )
        }

    @Test
    fun `REQ-SMS-102 an entry that already has another card tail keeps it`() =
        runTest {
            val t = at(20)
            livePush(t)
            db.openHelper.writableDatabase.execSQL("UPDATE expense SET instrumentLast4 = '5555'")
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            assertEquals(
                "5555",
                db
                    .expenses()
                    .all()
                    .single()
                    .instrumentLast4,
            )
        }

    @Test
    fun `REQ-SMS-103 the summary is not posted after an erase`() =
        runTest(timeout = kotlin.time.Duration.parse("3m")) {
            val inbox = FakeInbox(listOf(sms(1, purchase(10))))
            begin()
            lock.mutex.lock()
            val run = launch { engine(inbox).run() }
            withContext(Dispatchers.Default) { delay(500) }
            // an erase happened while the run waited for the lock
            lock.bump()
            store.reset()
            lock.mutex.unlock()
            run.join()
            assertTrue("no summary after an erase", summaries.isEmpty())
            assertTrue(db.expenses().all().isEmpty())
        }

    private fun controller(): Pair<BackfillController, BackfillStore> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val controllerStore = BackfillStore(context.getSharedPreferences("round2-controller", Context.MODE_PRIVATE))
        return BackfillController(WorkManager.getInstance(context), controllerStore, clock, lock) to controllerStore
    }

    @Test
    fun `REQ-SMS-103 Cancel waits for the erase lock and then marks the current state, not an old snapshot`() =
        runTest(timeout = kotlin.time.Duration.parse("3m")) {
            val (controller, controllerStore) = controller()
            controllerStore.save(BackfillState(BackfillPhase.RUNNING, "D90", 10L, 20L, total = 50, read = 30, entries = 20))
            lock.mutex.lock()
            val cancelling = launch { controller.cancel() }
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals("still waiting for the lock", BackfillPhase.RUNNING, controllerStore.state.value.phase)
            // an erase finished meanwhile: the run state is gone, Cancel must not bring a STOPPED run back
            controllerStore.reset()
            lock.mutex.unlock()
            cancelling.join()
            assertEquals(BackfillPhase.IDLE, controllerStore.state.value.phase)
        }

    @Test
    fun `REQ-SMS-101 a start date in the future is limited to today`() {
        val (controller, controllerStore) = controller()
        controller.start(BackfillRange.Since(LocalDate.of(2027, 1, 1)))
        val today =
            LocalDate
                .of(2026, 11, 8)
                .atStartOfDay(eg.masroof.core.CairoTime.zone)
                .toInstant()
                .toEpochMilli()
        assertEquals(today, controllerStore.state.value.fromMillis)
    }
}
