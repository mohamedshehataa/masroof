package eg.masroof

import android.content.Context
import android.telephony.SmsMessage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.auth.BackfillReset
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.ContentResolverSmsInbox
import eg.masroof.ingest.backfill.InboxSms
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.backfill.SmsInbox
import eg.masroof.ingest.capped
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

private const val PUSH_TEXT = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
private const val NUMERIC_SENDER = "+201000000011"

/** A real 3GPP SMS-DELIVER PDU (GSM 7-bit, alphanumeric sender, SCTS in UTC), so the live path parses it like the receiver does. */
internal fun smsPdu(
    sender: String,
    body: String,
    timestampMillis: Long,
): ByteArray {
    fun pack7(text: String): ByteArray {
        val out = ByteArray((text.length * 7 + 7) / 8)
        text.forEachIndexed { i, c ->
            val v = c.code and 0x7f
            val bit = i * 7
            out[bit / 8] = (out[bit / 8].toInt() or (v shl (bit % 8))).toByte()
            if (bit % 8 > 1) out[bit / 8 + 1] = (out[bit / 8 + 1].toInt() or (v shr (8 - bit % 8))).toByte()
        }
        return out
    }

    fun bcd(n: Int) = (((n % 10) shl 4) or (n / 10)).toByte()
    val t = Instant.ofEpochMilli(timestampMillis).atZone(ZoneOffset.UTC)
    val address = pack7(sender)
    val semiOctets = (sender.length * 7 + 3) / 4
    val user = pack7(body)
    return byteArrayOf(0x00, 0x04, semiOctets.toByte(), 0xD0.toByte()) + address + byteArrayOf(0x00, 0x00) +
        byteArrayOf(bcd(t.year % 100), bcd(t.monthValue), bcd(t.dayOfMonth), bcd(t.hour), bcd(t.minute), bcd(t.second), 0x00) +
        byteArrayOf(body.length.toByte()) + user
}

/** STORY-186 fix round 1: each repro is written to fail on 1d46406 and pass after the fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillFixRoundTest {
    private lateinit var db: AppDatabase
    private lateinit var store: BackfillStore
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val settings = MemorySettings()
    private val prefs = FakeAnswerPrefs()
    private val gate = MemoryGate()
    private val lock = EraseLock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BackfillStore(context.getSharedPreferences("fix-round", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() = db.close()

    private fun pipelineAt(c: Clock = clock) =
        SmsPipeline(
            db,
            settings,
            MemoryNotifications(),
            c,
            gate,
            FixedKey,
            eraseLock = lock,
            answers = prefs,
            memory = store,
        )

    private fun engine(inbox: SmsInbox) = SmsBackfill(db, pipelineAt(), inbox, store, settings, FixedKey, gate, clock, eraseLock = lock)

    private fun begin() = store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))

    @Test
    fun `REQ-SMS-101 only the allowlisted senders' bodies are ever requested from the provider`() =
        runTest {
            val provider = Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms").get()
            val t = BACKFILL_NOW - 10 * BACKFILL_DAY
            provider.add(1, "CIB", purchase(10), t)
            provider.add(2, NUMERIC_SENDER, "Your verification code is 123456", t + 1)
            provider.add(3, "Uber", "Your ride is arriving", t + 2)
            provider.add(4, "WhatsApp", "Code 998877", t + 3)
            provider.add(5, "CIB", purchase(20), t + 4)
            begin()
            engine(ContentResolverSmsInbox(context.contentResolver)).run()
            val bodyQueries = provider.queries.filter { q -> q.projection.any { it.equals("body", ignoreCase = true) } }
            assertTrue("bodies were read", bodyQueries.isNotEmpty())
            for (q in bodyQueries) {
                assertTrue("a body is only read with a sender filter: ${q.selection}", q.selection.orEmpty().contains("address IN"))
                assertFalse("no other sender is asked for", q.args.any { it in setOf(NUMERIC_SENDER, "Uber", "WhatsApp") })
            }
            assertEquals(2, db.expenses().all().size)
        }

    private fun twinProbe(days: Long) =
        runTest {
            val at = BACKFILL_NOW - days * BACKFILL_DAY
            // the notification was recorded live, `days` ago (its Undo row has expired by now)
            val liveClock = Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC)
            pipelineAt(liveClock).accept(IncomingMessage(CaptureSource.NOTIFICATION, NotificationApps.INSTAPAY_CANDIDATE, PUSH_TEXT, at))
            begin()
            engine(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = at + 3_000)))).run()
            assertEquals("one entry", 1, db.expenses().all().size)
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
    fun `REQ-SMS-102 a notification recorded 3 days ago and its backfilled SMS are one entry`() = twinProbe(3)

    @Test
    fun `REQ-SMS-102 a notification recorded 8 days ago and its backfilled SMS are one entry`() = twinProbe(8)

    @Test
    fun `REQ-SMS-102 a notification recorded 20 days ago and its backfilled SMS are one entry`() = twinProbe(20)

    @Test
    fun `REQ-SMS-102 a live SMS whose provider row has no sent time is not recorded again, from a real PDU`() =
        runTest {
            val sentAt = BACKFILL_NOW - 30 * BACKFILL_DAY + 5_000
            val msg = SmsMessage.createFromPdu(smsPdu("CIB", purchase(10), sentAt), "3gpp")
            val live = IncomingSms(msg.originatingAddress.orEmpty(), msg.messageBody.orEmpty(), msg.timestampMillis)
            pipelineAt().accept(live.toMessage().capped())
            assertEquals(1, db.expenses().all().size)
            // the provider stamps its own receipt time a few seconds later and may have no date_sent
            begin()
            engine(FakeInbox(listOf(InboxSms(1, "CIB", purchase(10), sentAt + 4_000, 0L)))).run()
            assertEquals("still one entry", 1, db.expenses().all().size)
            assertEquals(0, store.state.value.entries)
        }

    @Test
    fun `REQ-SMS-102 two genuine identical purchases a minute apart are both kept when the sent time is known`() =
        runTest {
            val t = BACKFILL_NOW - 30 * BACKFILL_DAY
            begin()
            engine(FakeInbox(listOf(sms(1, purchase(10), date = t), sms(2, purchase(10), date = t + 60_000)))).run()
            assertEquals(2, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-102 a review item that live capture created and the user dismissed does not come back`() =
        runTest {
            val at = BACKFILL_NOW - 20 * BACKFILL_DAY
            pipelineAt().accept(IncomingMessage(CaptureSource.SMS, "CIB", UNKNOWN, at))
            db.reviews().delete(
                db
                    .reviews()
                    .all()
                    .single()
                    .id,
            )
            begin()
            engine(FakeInbox(listOf(sms(1, UNKNOWN, date = at)))).run()
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-102 a live entry whose trash was purged is not recorded again within 30 days`() =
        runTest {
            val at = BACKFILL_NOW - 20 * BACKFILL_DAY
            pipelineAt().accept(IncomingMessage(CaptureSource.SMS, "CIB", purchase(10), at))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            db.expenses().delete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
            )
            db.captured().delete(localId)
            begin()
            engine(FakeInbox(listOf(sms(1, purchase(10), date = at)))).run()
            assertTrue(db.expenses().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-103 a system stop of the worker keeps the run RUNNING so it resumes, unlike the user's Cancel`() =
        runTest(timeout = 3.minutes) {
            val inbox = FakeInbox((1L..600L).map { sms(it, purchase(it.toInt())) })
            begin()
            val run: Job = launch { engine(inbox).run() }
            launch {
                store.state.first { it.entries >= 5 }
                run.cancel()
            }
            run.join()
            assertEquals(BackfillPhase.RUNNING, store.state.value.phase)
            engine(inbox).run()
            assertEquals(600, db.expenses().all().size)
            assertEquals(BackfillPhase.DONE, store.state.value.phase)
        }

    @Test
    fun `REQ-SMS-103 the user's Cancel (the controller marks STOPPED) stops the run and it stays STOPPED`() =
        runTest(timeout = 3.minutes) {
            val inbox = FakeInbox((1L..600L).map { sms(it, purchase(it.toInt())) })
            begin()
            val run: Job = launch { engine(inbox).run() }
            launch {
                store.state.first { it.entries >= 5 }
                // what BackfillController.cancel does, before the work is cancelled
                store.save(store.state.value.copy(phase = BackfillPhase.STOPPED))
                run.cancel()
            }
            run.join()
            val s = store.state.value
            assertEquals(BackfillPhase.STOPPED, s.phase)
            assertTrue(s.entries in 5 until 200)
            assertEquals(s.entries, db.expenses().all().size)
        }

    private fun wiper(): RoomDataWiper {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        return RoomDataWiper(
            db,
            NoInsightsCache,
            WorkManager.getInstance(context),
            Dispatchers.Unconfined,
            gate = gate,
            answerPrefs = prefs,
            eraseLock = lock,
            backfillReset = BackfillReset(store::reset),
        )
    }

    private fun assertNothingAfterErase() =
        runTest {
            assertTrue("no entry after the erase", db.expenses().all().isEmpty())
            assertTrue("no review item after the erase", db.reviews().all().isEmpty())
            assertEquals("the screen is back to the start", BackfillPhase.IDLE, store.state.value.phase)
            assertEquals(0, store.state.value.read)
            val file = context.getSharedPreferences("fix-round", Context.MODE_PRIVATE).all
            assertTrue("no run state and no handled id written back: ${file.keys.size}", file.isEmpty())
        }

    @Test
    fun `REQ-SMS-103 Erase all data in the middle of a run stops it and nothing is written after the erase`() =
        runTest(timeout = 5.minutes) {
            val inbox = FakeInbox((1L..600L).map { sms(it, purchase(it.toInt())) })
            begin()
            val run = launch { engine(inbox).run() }
            launch {
                store.state.first { it.read >= 30 }
                // what DataEraser does: the erase lock around the wipe
                lock.mutex.withLock { wiper().wipe() }
            }
            run.join()
            assertNothingAfterErase()
        }

    @Test
    fun `REQ-SMS-103 a run waiting on the erase lock does not resume into the erased database`() =
        runTest(timeout = 5.minutes) {
            val inbox = FakeInbox((1L..50L).map { sms(it, purchase(it.toInt())) })
            begin()
            lock.mutex.lock()
            val run = launch { engine(inbox).run() }
            // the worker is now waiting for the lock; the erase runs and releases it
            withContext(Dispatchers.Default) { delay(500) }
            wiper().wipe()
            lock.mutex.unlock()
            run.join()
            assertNothingAfterErase()
        }
}
