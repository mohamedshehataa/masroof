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
