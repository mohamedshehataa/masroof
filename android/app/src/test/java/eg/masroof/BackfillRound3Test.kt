package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.ingest.REASON_POSSIBLE_REPEAT
import eg.masroof.ingest.SignatureClaim
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.InboxSms
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.backfill.SmsInbox
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val R3_PUSH = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
private const val R3_OTHER_TRANSFER = "bda86be1"

/** STORY-186 loop 3: when the app cannot tell, the message goes to review; never silently dropped, never counted twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillRound3Test {
    private lateinit var db: AppDatabase
    private lateinit var store: BackfillStore
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val settings = MemorySettings()
    private val prefsFake = FakeAnswerPrefs()
    private val gate = MemoryGate()
    private val lock = EraseLock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BackfillStore(context.getSharedPreferences("round3", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() = db.close()

    private fun pipelineAt(c: Clock = clock) =
        SmsPipeline(db, settings, MemoryNotifications(), c, gate, FixedKey, eraseLock = lock, answers = prefsFake, memory = store)

    private fun engine(inbox: SmsInbox) = SmsBackfill(db, pipelineAt(), inbox, store, settings, FixedKey, gate, clock, eraseLock = lock)

    private fun begin() = store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))

    private fun at(days: Long) = BACKFILL_NOW - days * BACKFILL_DAY

    private fun clockAt(millis: Long) = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    private suspend fun livePush(at: Long) =
        pipelineAt(clockAt(at)).accept(IncomingMessage(CaptureSource.NOTIFICATION, NotificationApps.INSTAPAY_CANDIDATE, R3_PUSH, at))

    private suspend fun liveSms(
        body: String,
        at: Long,
        sender: String = "CIB",
    ) = pipelineAt(clockAt(at)).accept(IncomingMessage(CaptureSource.SMS, sender, body, at))

    private fun noSentTime(
        id: Long,
        body: String,
        date: Long,
        sender: String = "CIB",
    ) = InboxSms(id, sender, body, date, 0L)

    @Test
    fun `REQ-SMS-102 after a purged notification only its closest SMS is its twin, a second transfer reaches review`() =
        runTest {
            val t = at(20)
            livePush(t)
            db.expenses().delete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
            )
            db.captured().all().forEach { db.captured().delete(it.localId) }
            begin()
            engine(
                FakeInbox(
                    listOf(
                        sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000),
                        sms(2, INSTANT_IN.replace(R3_OTHER_TRANSFER, "c0ffee02"), sender = "BDC", date = t + 90_000),
                    ),
                ),
            ).run()
            assertTrue("the twin made no entry", db.expenses().all().isEmpty())
            assertEquals("the second transfer is not lost", listOf(REASON_POSSIBLE_REPEAT), db.reviews().all().map { it.reason })
            assertEquals(0, store.state.value.entries)
        }

    @Test
    fun `REQ-SMS-102 a notification deleted 40 days ago and purged 10 days ago is still remembered when its SMS is backfilled`() =
        runTest {
            val t = at(40)
            livePush(t)
            db.expenses().softDelete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
                t,
            )
            // the real purger: it removes entries 30 days after their deletion, i.e. at -10 days here
            val purgeClock = clockAt(at(10) + 3_600_000)
            assertEquals(1, DeletedEntryPurger(db, purgeClock, lock, memory = store, idKey = FixedKey).purge())
            assertTrue(db.expenses().all().isEmpty())
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            assertTrue("no entry for a transfer the user deleted", db.expenses().all().isEmpty())
            assertEquals(0, store.state.value.entries)
        }

    @Test
    fun `REQ-SMS-102 a time remembered 35 days ago no longer matches, and old times inside a key are pruned`() {
        val now = BACKFILL_NOW
        val prefs = context.getSharedPreferences("round3", Context.MODE_PRIVATE)
        // a key that was alive through a newer time (as an old build or a long gap would leave it): the old time is still in the file
        prefs.edit().putString("sig:sig", "${at(35)}:${at(35)}:0,${at(1)}:${at(1)}:0").commit()
        assertFalse("the old time is out of the 30 days", store.seenSignature("sig", at(35) - 1_000, at(35) + 1_000, now))
        assertTrue(store.seenSignature("sig", at(1) - 1_000, at(1) + 1_000, now))
        store.forgetBefore(now - 30 * BACKFILL_DAY)
        val raw = prefs.getString("sig:sig", "").orEmpty()
        assertEquals("one time left in the key: $raw", 1, raw.split(',').size)
    }

    @Test
    fun `REQ-SMS-102 claiming consumes the closest remembered time once`() {
        val now = BACKFILL_NOW
        store.markSignature("sig", at(1), at(1))
        store.markSignature("sig", at(1) + 60_000, at(1) + 60_000)
        val window = (at(1) - 300_000)..(at(1) + 360_000)
        assertEquals(SignatureClaim.CLAIMED, store.claimSignature("sig", window.first, window.last, at(1) + 59_000, now))
        assertEquals(SignatureClaim.CLAIMED, store.claimSignature("sig", window.first, window.last, at(1), now))
        assertEquals(SignatureClaim.ALREADY_CLAIMED, store.claimSignature("sig", window.first, window.last, at(1), now))
        assertEquals(SignatureClaim.NONE, store.claimSignature("other", window.first, window.last, at(1), now))
    }

    @Test
    fun `REQ-SMS-102 with a known sent time a different real purchase minutes later is an entry, not a review item`() =
        runTest {
            val t = at(20)
            liveSms(purchase(10), t)
            begin()
            // the provider received it 60 s after it was sent: the ids are exact, so this is simply another message
            engine(FakeInbox(listOf(InboxSms(1, "CIB", purchase(10), t + 93_000, t + 33_000)))).run()
            assertEquals(2, db.expenses().all().size)
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-102 a live message that a backfill read first from a row without a sent time is not a second entry`() =
        runTest {
            val t = at(20)
            begin()
            engine(FakeInbox(listOf(noSentTime(1, purchase(10), t + 3_000)))).run()
            assertEquals(1, db.expenses().all().size)
            // the live PDU of the same SMS is processed now, with its own (earlier) stamp
            liveSms(purchase(10), t)
            assertEquals("still one entry", 1, db.expenses().all().size)
            assertEquals(listOf(REASON_POSSIBLE_REPEAT), db.reviews().all().map { it.reason })
        }

    @Test
    fun `REQ-SMS-102 a notification edited 40 days ago cannot be told from its SMS, so the SMS goes to review, not a second entry`() =
        runTest {
            val t = at(40)
            livePush(t)
            db.openHelper.writableDatabase.execSQL("UPDATE expense SET amountMinor = 250, updatedAtMillis = updatedAtMillis + 1")
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000)))).run()
            assertEquals("no second entry", 1, db.expenses().all().size)
            assertEquals(listOf(REASON_POSSIBLE_REPEAT), db.reviews().all().map { it.reason })
        }

    @Test
    fun `REQ-SMS-102 the memory file holds no readable time, and still answers`() {
        val sealed = BackfillStore(context.getSharedPreferences("round3-sealed", Context.MODE_PRIVATE), inMemoryAead())
        sealed.mark("id-1", at(2))
        sealed.markSignature("sig-1", at(2), at(2))
        val raw = context.getSharedPreferences("round3-sealed", Context.MODE_PRIVATE).all
        assertTrue(raw.isNotEmpty())
        val clear = Regex("\\d{10,}")
        raw.forEach { (k, v) -> assertFalse("a time in the clear under $k", clear.containsMatchIn(v.toString())) }
        assertTrue(sealed.handled("id-1", BACKFILL_NOW))
        assertTrue(sealed.seenSignature("sig-1", at(2) - 1_000, at(2) + 1_000, BACKFILL_NOW))
        val reloaded = BackfillStore(context.getSharedPreferences("round3-sealed", Context.MODE_PRIVATE), inMemoryAead())
        assertFalse("another key cannot read it, and it is not an error", reloaded.handled("id-1", BACKFILL_NOW))
    }

    @Test
    fun `REQ-SMS-102 a dismissed review item of a sender longer than 60 characters does not come back`() =
        runTest {
            val t = at(20)
            val sender = "CIB" + "A".repeat(58)
            liveSms(UNKNOWN, t, sender)
            db.reviews().delete(
                db
                    .reviews()
                    .all()
                    .single()
                    .id,
            )
            begin()
            engine(FakeInbox(listOf(noSentTime(1, UNKNOWN, t + 4_000, sender)))).run()
            assertTrue(db.reviews().all().isEmpty())
        }
}
