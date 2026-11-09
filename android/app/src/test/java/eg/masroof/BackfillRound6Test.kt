package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RecoveringAead
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.REASON_POSSIBLE_REPEAT
import eg.masroof.ingest.SignatureClaim
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.InboxSms
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

/** What Dagger's scoped provider does: one instance, and an error when it is asked for again while it is being built. */
private class Scoped<T : Any>(
    private val create: () -> T,
) {
    private var value: T? = null
    private var building = false

    fun get(): T {
        value?.let { return it }
        check(!building) { "Scoped provider was invoked recursively" }
        building = true
        try {
            value = create()
        } finally {
            building = false
        }
        return value!!
    }
}

/** An AEAD that never works (the Keystore is down). */
private object DeadAead : Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = throw GeneralSecurityException("down")

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = throw GeneralSecurityException("down")
}

/** STORY-186 after the loop-3 follow-up: a run survives a Keystore error, a lost key never crashes, an unrecorded time is never ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillRound6Test {
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

    private fun pipeline(
        store: BackfillStore,
        c: Clock = clock,
    ) = SmsPipeline(db, settings, MemoryNotifications(), c, gate, FixedKey, eraseLock = lock, answers = FakeAnswerPrefs(), memory = store)

    private fun engine(
        store: BackfillStore,
        inbox: FakeInbox,
    ) = SmsBackfill(db, pipeline(store), inbox, store, settings, FixedKey, gate, clock, eraseLock = lock)

    @Test
    fun `REQ-SMS-102 a run stopped after 2 of 5 messages resumes and records all 5 although the Keystore fails after the restart`() =
        runTest {
            val rows = (1L..5L).map { sms(it, purchase(it.toInt() * 10)) }
            val first = BackfillStore(prefs("r6-resume"), Flaky5(inMemoryAead()))
            first.save(BackfillState(BackfillPhase.RUNNING, "D90", at(90), BACKFILL_NOW + 1))
            val inbox = FakeInbox(rows)
            inbox.cap = 2
            inbox.beforePage = { n -> if (n == 2) throw Killed() }
            try {
                engine(first, inbox).run()
            } catch (_: Killed) {
                // the process died after two messages
            }
            assertEquals(2, db.expenses().all().size)
            // restart: a new store on the same file, and the Keystore fails for the next 6 calls
            val restarted = BackfillStore(prefs("r6-resume"), Flaky5(inMemoryAead(), failures = 6))
            assertEquals(BackfillPhase.RUNNING, restarted.state.value.phase)
            val again = FakeInbox(rows)
            engine(restarted, again).run()
            assertEquals("all 5 were recorded, none twice", 5, db.expenses().all().size)
            assertEquals(BackfillPhase.DONE, restarted.state.value.phase)
            assertEquals(5, restarted.state.value.entries)
            assertEquals(5, restarted.state.value.read)
        }

    @Test
    fun `REQ-SMS-102 the run state in the file is coarse, days only, and a restart reads the last day again`() {
        val file = prefs("r6-coarse")
        val store = BackfillStore(file, inMemoryAead())
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
                lastDateMillis = at(40) + 3_600_000,
                lastId = 7,
                dayRead = 4,
            )
        store.save(state)
        val clear = Regex("\\d{10,}")
        file.all.forEach { (k, v) -> assertFalse("a time in the clear under $k", clear.containsMatchIn(v.toString())) }
        val reloaded = BackfillStore(file, inMemoryAead()).state.value
        val dayStart = { millis: Long ->
            eg.masroof.core.CairoTime
                .dateOf(millis)
                .atStartOfDay(eg.masroof.core.CairoTime.zone)
                .toInstant()
                .toEpochMilli()
        }
        assertEquals(dayStart(at(90)), reloaded.fromMillis)
        assertEquals("the last day is read again from its first message", dayStart(at(40) + 3_600_000), reloaded.lastDateMillis)
        assertEquals("its 4 messages come off the count and are counted again", 16, reloaded.read)
        assertEquals(10, reloaded.entries)
        assertTrue("the end covers today", reloaded.toMillis > BACKFILL_NOW)
    }

    @Test
    fun `REQ-SMS-102 a provably lost key recreated while the store is being built does not recurse, clears the memory and keeps the run`() {
        val file = prefs("r6-wire")
        val before = BackfillStore(file, inMemoryAead())
        repeat(5) { before.mark("old-$it", BACKFILL_NOW) }
        before.save(BackfillState(BackfillPhase.RUNNING, "D90", at(90), BACKFILL_NOW + 1, total = 9, read = 3, entries = 2))
        var lost = true
        // wired like di/BackfillModule: the AEAD is a scoped provider, the store another one built from it (a scoped provider that is
        // asked for again while it is being built fails, as Dagger's DoubleCheck does)
        val aeadProvider =
            Scoped {
                RecoveringAead(
                    build = { inMemoryAead() },
                    destroy = {},
                    recoverIfLost = { lost.also { lost = false } },
                    onKeyRecreated = { BackfillStore.clearMemoryIn(file) },
                )
            }
        val storeProvider = Scoped { BackfillStore(file, aeadProvider.get()) }
        val store = storeProvider.get()
        store.mark("new", BACKFILL_NOW) // the first use of the key finds it lost and recreates it
        assertEquals(
            setOf("seen:new"),
            file.all.keys
                .filter { it.startsWith("seen:") }
                .toSet(),
        )
        assertEquals(BackfillPhase.RUNNING, store.state.value.phase)
        assertEquals(3, BackfillStore(file, inMemoryAead()).state.value.read)
    }

    @Test
    fun `REQ-SMS-102 a time the memory could neither open nor record is marked, and a matching signature is then not a twin`() {
        val flaky = Flaky5(inMemoryAead())
        val store = BackfillStore(prefs("r6-marker"), flaky)
        val t1 = at(5)
        store.markSignature("sig", t1, t1)
        flaky.failures = 2 // opening the old value fails, sealing the new one fails
        store.markSignature("sig", t1 + 60_000, t1 + 60_000)
        val now = t1 + 60_000
        assertTrue("seen, time unknown", store.seenSignature("sig", t1 + 3_600_000, t1 + 3_700_000, now))
        assertEquals(SignatureClaim.ALREADY_CLAIMED, store.claimSignature("sig", t1 + 3_600_000, t1 + 3_700_000, t1 + 3_650_000, now))
        assertFalse("a signature never marked is untouched", store.seenSignature("other", 0L, Long.MAX_VALUE, now))
        val raw = prefs("r6-marker").all["sigx:sig"].toString()
        assertTrue("only a coarse bucket, no time of day: $raw", raw.length <= 6)
    }

    @Test
    fun `REQ-SMS-102 with the Keystore down a purged live entry's SMS goes to review, never recorded and never dropped`() =
        runTest {
            val t = at(20)
            val store = BackfillStore(prefs("r6-down"), DeadAead)
            pipeline(store, Clock.fixed(java.time.Instant.ofEpochMilli(t), ZoneOffset.UTC))
                .accept(IncomingMessage(CaptureSource.SMS, "CIB", purchase(10), t))
            db.expenses().delete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
            )
            db.captured().all().forEach { db.captured().delete(it.localId) }
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", at(90), BACKFILL_NOW + 1))
            engine(store, FakeInbox(listOf(InboxSms(1, "CIB", purchase(10), t + 4_000, 0L)))).run()
            assertTrue("not recorded", db.expenses().all().isEmpty())
            assertEquals(listOf(REASON_POSSIBLE_REPEAT), db.reviews().all().map { it.reason })
        }

    @Test
    fun `REQ-SMS-101 REQ-SMS-102 every fixture row gives the same entries and review items live and backfilled`() =
        runTest(timeout = 15.minutes) {
            val rows =
                File("../../fixtures/sms")
                    .listFiles { f -> f.name.endsWith(".jsonl") }!!
                    .sortedBy { it.name }
                    .flatMap { f -> f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }
                    .mapNotNull { row ->
                        val body = row["body"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        // a row without a sender is read as a CIB message (the comparison is not dropped)
                        (row["sender"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "CIB") to body
                    }
            assertTrue("fixtures found: ${rows.size}", rows.size > 1_000)
            val base = BACKFILL_NOW - 60 * BACKFILL_DAY
            val stamped = rows.mapIndexed { i, (sender, body) -> InboxSms(i + 1L, sender, body, base + i * 60_000L, base + i * 60_000L) }
            // live: the receiver's hand-off (OTP drop, code masking), then the worker's pipeline
            val liveStore = BackfillStore(prefs("r6-parity-live"))
            val scheduler = FakeSmsScheduler()
            val handoff = SmsHandoff(settings, scheduler, gate)
            stamped.forEach { handoff.handOff(listOf(IncomingSms(it.address, it.body, it.dateMillis))) }
            val live = pipeline(liveStore)
            scheduler.intake.forEach { live.accept(it) }
            val liveSnapshot = snapshot()
            db.clearAllTables()
            val store = BackfillStore(prefs("r6-parity-backfill"))
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", base - BACKFILL_DAY, BACKFILL_NOW + 1))
            engine(store, FakeInbox(stamped)).run()
            val backfilled = snapshot()
            println("PARITY rows=${stamped.size} entries=${backfilled.first.size} reviews=${backfilled.second.size}")
            assertEquals(liveSnapshot.first, backfilled.first)
            assertEquals(liveSnapshot.second, backfilled.second)
        }

    private suspend fun snapshot(): Pair<List<String>, List<String>> =
        db
            .expenses()
            .all()
            .map { "${it.type}|${it.amountMinor}|${it.currency}|${it.status}|${it.merchant}|${it.instrumentLast4}|${it.source}" }
            .sorted() to
            db
                .reviews()
                .all()
                .map { "${it.reason}|${it.sugAmountMinor}|${it.text}" }
                .sorted()
}
