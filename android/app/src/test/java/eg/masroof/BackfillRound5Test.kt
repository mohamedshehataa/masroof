package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RecoveringAead
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.capped
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
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val R5_PUSH = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
private const val CODE_TRANSFER = "تم تحويل 500 جنيه الى AHMED ALI، احذر من مشاركة الرمز 889900 مع اي شخص"

/** An AEAD that fails its next [failures] calls, then works again (a Keystore error that passes). */
private class Flaky5(
    private val inner: Aead,
    var failures: Int = 0,
) : Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = if (failures-- > 0) throw GeneralSecurityException("transient") else inner.encrypt(plaintext, associatedData)

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = if (failures-- > 0) throw GeneralSecurityException("transient") else inner.decrypt(ciphertext, associatedData)
}

/** STORY-186 after loop 3: a value that cannot be opened is never deleted or overwritten; only a provably lost key clears the memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillRound5Test {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val settings = MemorySettings()
    private val gate = MemoryGate()
    private val lock = EraseLock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun at(days: Long) = BACKFILL_NOW - days * BACKFILL_DAY

    @Test
    fun `REQ-SMS-102 a Keystore error during the daily prune deletes nothing, the memory is still there afterwards`() {
        val flaky = Flaky5(inMemoryAead())
        val store = BackfillStore(prefs("r5-prune"), flaky)
        val d0 = at(10)
        store.mark("id", d0)
        store.markSignature("sig", d0, d0)
        flaky.failures = 4
        store.mark("other", d0 + BACKFILL_DAY + 1) // a day later: the prune runs while the key fails
        flaky.failures = 0
        assertTrue("the handled id survived", store.handled("id", d0 + BACKFILL_DAY + 1))
        assertTrue("the signature survived", store.seenSignature("sig", d0 - 1_000, d0 + 1_000, d0 + BACKFILL_DAY + 1))
    }

    @Test
    fun `REQ-SMS-102 a signature that could not be opened is not overwritten by the next mark, and merges back later`() {
        val flaky = Flaky5(inMemoryAead())
        val store = BackfillStore(prefs("r5-sig"), flaky)
        val t1 = at(5)
        store.markSignature("sig", t1, t1)
        flaky.failures = 1
        store.markSignature("sig", t1 + 60_000, t1 + 60_000) // the first value cannot be opened: the new time goes to a second key
        assertTrue("the earlier time is not forgotten", store.seenSignature("sig", t1 - 1_000, t1 + 1_000, t1 + 60_000))
        assertTrue("the new time is remembered too", store.seenSignature("sig", t1 + 59_000, t1 + 61_000, t1 + 60_000))
        store.markSignature("sig", t1 + 120_000, t1 + 120_000) // readable again: everything is merged into one key
        assertFalse("merged back", prefs("r5-sig").all.keys.any { it.startsWith("sig2:") })
        for (offset in listOf(0L, 60_000L, 120_000L)) {
            assertTrue(store.seenSignature("sig", t1 + offset - 1_000, t1 + offset + 1_000, t1 + 120_000))
        }
    }

    @Test
    fun `REQ-SMS-102 a signature whose value and second key both fail is left untouched`() {
        val flaky = Flaky5(inMemoryAead())
        val store = BackfillStore(prefs("r5-both"), flaky)
        val t1 = at(5)
        store.markSignature("sig", t1, t1)
        val before = prefs("r5-both").getString("sig:sig", null)
        flaky.failures = 2
        store.markSignature("sig", t1 + 60_000, t1 + 60_000)
        assertEquals("the stored value is exactly as it was", before, prefs("r5-both").getString("sig:sig", null))
    }

    @Test
    fun `REQ-SMS-102 a key that is provably lost and recreated clears the whole memory at once`() {
        val oldKey = inMemoryAead()
        val newKey = inMemoryAead()
        val old = BackfillStore(prefs("r5-lost"), oldKey)
        repeat(20) { old.mark("id-$it", at(3)) }
        old.markSignature("sig", at(3), at(3))
        lateinit var store: BackfillStore
        val aead =
            RecoveringAead(
                build = { newKey },
                destroy = {},
                recoverIfLost = { true },
                onKeyRecreated = { store.clearMemory() },
            )
        store = BackfillStore(prefs("r5-lost"), aead)
        store.mark("fresh", BACKFILL_NOW)
        assertEquals(
            "only the new value is left",
            setOf("seen:fresh"),
            prefs("r5-lost")
                .all.keys
                .filter {
                    it.startsWith("seen:") ||
                        it.startsWith("sig")
                }.toSet(),
        )
    }

    @Test
    fun `REQ-SMS-102 the times of a run are sealed in the file, and the run still resumes`() {
        val store = BackfillStore(prefs("r5-run"), inMemoryAead())
        val state =
            BackfillState(
                BackfillPhase.RUNNING,
                "D90",
                at(90),
                BACKFILL_NOW + 1,
                total = 50,
                read = 20,
                entries = 10,
                review = 2,
                lastDateMillis = at(40),
                lastId = 7,
            )
        store.save(state)
        val clear = Regex("\\d{10,}")
        prefs("r5-run").all.forEach { (k, v) -> assertFalse("a time in the clear under $k", clear.containsMatchIn(v.toString())) }
        val reloaded = BackfillStore(prefs("r5-run"), inMemoryAead())
        // another key cannot read the times: the run starts from its beginning, not from a wrong position
        assertEquals(0L, reloaded.state.value.fromMillis)
        val same = prefs("r5-run").all
        assertTrue(same.isNotEmpty())
        assertEquals(state, store.state.value)
    }

    @Test
    fun `REQ-SMS-102 a deleted notification does not make a nearby SMS of another amount a review item`() =
        runTest {
            val t = at(20)
            val store = BackfillStore(prefs("r5-del"))

            fun pipelineAt(c: Clock) =
                SmsPipeline(
                    db,
                    settings,
                    MemoryNotifications(),
                    c,
                    gate,
                    FixedKey,
                    eraseLock = lock,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                )
            pipelineAt(Clock.fixed(Instant.ofEpochMilli(t), ZoneOffset.UTC))
                .accept(IncomingMessage(CaptureSource.NOTIFICATION, NotificationApps.INSTAPAY_CANDIDATE, R5_PUSH, t))
            db.expenses().softDelete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
                t + 3_600_000,
            )
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", at(90), BACKFILL_NOW + 1))
            SmsBackfill(
                db,
                pipelineAt(clock),
                FakeInbox(listOf(sms(1, INSTANT_IN.replace("1.00", "2.00"), sender = "BDC", date = t + 3_000))),
                store,
                settings,
                FixedKey,
                gate,
                clock,
                eraseLock = lock,
            ).run()
            assertTrue("no review item", db.reviews().all().isEmpty())
            assertEquals("the different transfer is an entry", 1, db.expenses().all().count { it.deletedAtMillis == null })
        }

    @Test
    fun `REQ-SMS-101 a transfer with a code is classified by the same masked text live and backfill see, and stores no code`() =
        runTest {
            val store = BackfillStore(prefs("r5-code"))
            val pipeline =
                SmsPipeline(
                    db,
                    settings,
                    MemoryNotifications(),
                    clock,
                    gate,
                    FixedKey,
                    eraseLock = lock,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                )
            // cause of the old difference: the receiver masks a code-like number BEFORE the worker parses (IncomingMessage.capped), so the
            // classification sees the masked text; handing the raw text to the pipeline (not what the receiver does) classifies it as secret
            val raw = IncomingMessage(CaptureSource.SMS, "NBE", CODE_TRANSFER, at(2))
            pipeline.accept(raw)
            assertEquals(
                "secret",
                db
                    .reviews()
                    .all()
                    .single()
                    .reason,
            )
            db.clearAllTables()
            pipeline.accept(raw.capped())
            val masked = db.reviews().all().single()
            assertEquals("unconfirmed", masked.reason)
            assertFalse("the masked text keeps no code", "889900" in masked.text)
        }
}
