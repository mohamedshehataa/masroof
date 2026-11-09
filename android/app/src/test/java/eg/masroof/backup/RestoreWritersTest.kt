package eg.masroof.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.ANSWER_NOW
import eg.masroof.BACKFILL_DAY
import eg.masroof.BACKFILL_NOW
import eg.masroof.FakeAnswerPrefs
import eg.masroof.FakeInbox
import eg.masroof.FixedKey
import eg.masroof.MemoryGate
import eg.masroof.MemoryNotifications
import eg.masroof.MemorySettings
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.RestoreGate
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.data.receipts.AttachResult
import eg.masroof.data.receipts.PdfProbe
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.Refusal
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillOutcome
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.purchase
import eg.masroof.sms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/** Writers that stand aside while a restore is pending (design section 5): the backfill (R11), receipt attach (R14), the purge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreWritersTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private var blocked = false
    private val gate = RestoreGate { blocked }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `REQ-OPS-179 R11 a backfill that meets a pending restore stops without moving its cursor and resumes after Done`() =
        runTest {
            val store = BackfillStore(context.getSharedPreferences("restore-writers-backfill", Context.MODE_PRIVATE))
            val settings = MemorySettings()
            val pipeline =
                SmsPipeline(
                    db,
                    settings,
                    MemoryNotifications(),
                    clock,
                    MemoryGate(),
                    FixedKey,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                    restoreGate = gate,
                )
            val engine =
                SmsBackfill(
                    db,
                    pipeline,
                    FakeInbox(listOf(sms(1, purchase(10)), sms(2, purchase(20)))),
                    store,
                    settings,
                    FixedKey,
                    MemoryGate(),
                    clock,
                )
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))
            val start = store.state.value
            blocked = true // a restore started after the run did
            assertEquals(BackfillOutcome.DEFERRED, engine.run())
            val stopped = store.state.value
            assertEquals("the run stays RUNNING", BackfillPhase.RUNNING, stopped.phase)
            assertEquals("the cursor did not move", start.lastId, stopped.lastId)
            assertTrue("nothing was recorded", db.expenses().all().isEmpty())
            blocked = false // the restore finished
            assertEquals(BackfillOutcome.DONE, engine.run())
            assertEquals("each message once", 2, db.expenses().all().size)
        }

    @Test
    fun `REQ-OPS-179 a message deferred under the lock is not marked handled and a later backfill records it`() =
        runTest {
            val store = BackfillStore(context.getSharedPreferences("restore-writers-handled", Context.MODE_PRIVATE))
            val settings = MemorySettings()
            var calls = 0
            var blockedNow = false
            var readsWhileBlocked = 0
            val sharedGate =
                RestoreGate {
                    calls++
                    blockedNow = calls == 2 // blocked on its second question only: the restore starts between two checks
                    blockedNow
                }
            val key =
                object : eg.masroof.ingest.LocalIdKey {
                    override fun get(): ByteArray {
                        if (blockedNow) readsWhileBlocked++
                        return FixedKey.get()
                    }

                    override fun wipe() = Unit
                }
            val pipeline =
                SmsPipeline(
                    db,
                    settings,
                    MemoryNotifications(),
                    clock,
                    MemoryGate(),
                    key,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                    restoreGate = sharedGate,
                )
            val inbox = FakeInbox(listOf(sms(1, purchase(10)), sms(2, purchase(20))))
            val engine = SmsBackfill(db, pipeline, inbox, store, settings, key, MemoryGate(), clock, restoreGate = sharedGate)
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))
            assertEquals(BackfillOutcome.DEFERRED, engine.run())
            assertEquals("no key read while blocked", 0, readsWhileBlocked)
            assertTrue("nothing recorded yet", db.expenses().all().isEmpty())
            assertEquals(BackfillOutcome.DONE, engine.run())
            assertEquals("both messages are recorded, none was marked handled while deferred", 2, db.expenses().all().size)
        }

    @Test
    fun `REQ-OPS-179 the pipeline itself never marks a message deferred under the lock as handled`() =
        runTest {
            val store = BackfillStore(context.getSharedPreferences("restore-writers-handled2", Context.MODE_PRIVATE))
            var calls = 0
            val gateOnSecondQuestion = RestoreGate { ++calls == 2 } // open at the early check, blocked under the lock
            val pipeline =
                SmsPipeline(
                    db,
                    MemorySettings(),
                    MemoryNotifications(),
                    clock,
                    MemoryGate(),
                    FixedKey,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                    restoreGate = gateOnSecondQuestion,
                )
            val message =
                eg.masroof.ingest.parse.IncomingMessage(
                    eg.masroof.ingest.parse.CaptureSource.SMS,
                    "CIB",
                    purchase(10),
                    BACKFILL_NOW - BACKFILL_DAY,
                )
            assertEquals(eg.masroof.ingest.Disposition.DEFERRED, pipeline.accept(message))
            val id =
                eg.masroof.ingest.LocalIdHasher
                    .id(FixedKey.get(), message)
            assertTrue("a deferred message is not remembered as handled", !store.handled(id, clock.millis()))
            assertTrue("nor under an empty id", !store.handled("", clock.millis()))
            assertEquals(eg.masroof.ingest.Disposition.RECORDED, pipeline.accept(message))
        }

    @Test
    fun `REQ-OPS-179 R14 a receipt attach is refused and the copy deleted while a restore is pending, and works after`() =
        runTest {
            val dir = File(context.filesDir, "writers-receipts-" + System.nanoTime())
            val store = ReceiptStore(db, dir, PdfProbe { 1 }, clock, EraseLock(), Dispatchers.Unconfined, gate)
            val pdf = "%PDF-1.4\n% fake body\n".toByteArray()
            blocked = true
            assertEquals(AttachResult.Refused(Refusal.RESTORE_PENDING), store.attach("e1") { ByteArrayInputStream(pdf) })
            assertTrue("no copied file is left", dir.listFiles().orEmpty().none { it.isFile })
            assertTrue(db.receipts().count() == 0)
        }

    @Test
    fun `REQ-OPS-179 the daily purge does nothing while a restore is pending`() =
        runTest {
            val purger = DeletedEntryPurger(db, clock, EraseLock(), File(context.filesDir, "writers-purge"), restoreGate = gate)
            blocked = true
            assertEquals(0, purger.purge())
        }
}
