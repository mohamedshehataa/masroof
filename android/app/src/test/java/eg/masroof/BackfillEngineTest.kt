package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.ingest.CaptureChannel
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.SenderPolicy
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillNotifier
import eg.masroof.ingest.backfill.BackfillOutcome
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.InboxPosition
import eg.masroof.ingest.backfill.InboxSms
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.backfill.SmsInbox
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

internal const val BACKFILL_MINUTE = 60_000L
internal const val BACKFILL_DAY = 24L * 60 * BACKFILL_MINUTE
internal val BACKFILL_NOW = ANSWER_NOW.toEpochMilli()

/** A power cut: not a cancellation and not a permission problem, so the run just stops where it is. */
private class Killed : Error()

/** The inbox in memory: sorted like the system one, with hooks to fail or cancel at a page. */
internal class FakeInbox(
    rows: List<InboxSms> = emptyList(),
) : SmsInbox {
    var rows: List<InboxSms> = rows.sortedWith(compareBy({ it.dateMillis }, { it.id }))
    var pages = 0
    var largestPage = 0
    var beforePage: (Int) -> Unit = {}
    var denied = false
    val pageStarts = mutableListOf<InboxPosition?>()

    private fun inRange(
        r: InboxSms,
        from: Long,
        to: Long,
    ) = r.dateMillis in from until to

    override fun forEachAddress(
        fromMillis: Long,
        toMillis: Long,
        visit: (String) -> Unit,
    ) {
        if (denied) throw SecurityException("READ_SMS")
        rows.filter { inRange(it, fromMillis, toMillis) }.forEach { visit(it.address) }
    }

    val requested = mutableSetOf<String>()

    override fun page(
        fromMillis: Long,
        toMillis: Long,
        addresses: Set<String>,
        after: InboxPosition?,
        limit: Int,
    ): List<InboxSms> {
        pages++
        requested += addresses
        beforePage(pages)
        if (denied) throw SecurityException("READ_SMS")
        pageStarts += after
        val out =
            rows
                .filter { inRange(it, fromMillis, toMillis) && it.address in addresses }
                .filter { after == null || it.dateMillis > after.dateMillis || (it.dateMillis == after.dateMillis && it.id > after.id) }
                .take(limit)
        largestPage = maxOf(largestPage, out.size)
        return out
    }
}

internal const val PURCHASE_LAST4 = "4512"
internal const val ATM = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 2,000.00- Your available balance EGP 5,000.00"
internal const val UNKNOWN = "Welcome to CIB: statement notice 2026"

internal const val INSTANT_IN =
    "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 " +
        "رقم المعاملة bda86be1 للمزيد أتصل ب 16990"

internal fun purchase(amount: Int) = "Your card ending $PURCHASE_LAST4 was charged EGP $amount.00 at AMAZON"

internal fun sms(
    id: Long,
    body: String,
    sender: String = "CIB",
    date: Long = BACKFILL_NOW - 60 * BACKFILL_DAY + id * BACKFILL_MINUTE,
) = InboxSms(id, sender, body, date, date)

/** STORY-186: "Read past SMS" through the same pipeline as live capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillEngineTest {
    private lateinit var db: AppDatabase
    private lateinit var store: BackfillStore
    private lateinit var prefs: FakeAnswerPrefs
    private lateinit var settings: MemorySettings
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val notices = mutableListOf<CapturedTxEntity>()
    private val summaries = mutableListOf<BackfillState>()
    private val gate = MemoryGate()
    private val noticeSink =
        object : CapturedNotifier {
            override fun captured(item: CapturedTxEntity) {
                notices += item
            }

            override fun cancel(localId: String) = Unit

            override fun cancelAll() = Unit
        }
    private val summarySink =
        object : BackfillNotifier {
            override suspend fun finished(state: BackfillState) {
                summaries += state
            }
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BackfillStore(context.getSharedPreferences("backfill-test", Context.MODE_PRIVATE))
        prefs = FakeAnswerPrefs()
        settings = MemorySettings()
    }

    @After
    fun tearDown() = db.close()

    private fun pipeline() =
        SmsPipeline(
            db,
            settings,
            MemoryNotifications(),
            clock,
            gate,
            FixedKey,
            notifier = noticeSink,
            answers = prefs,
            memory = store,
        )

    private fun engine(inbox: SmsInbox) = SmsBackfill(db, pipeline(), inbox, store, settings, FixedKey, gate, clock, summarySink)

    private fun begin(key: String = "D90") =
        store.save(BackfillState(BackfillPhase.RUNNING, key, BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))

    private suspend fun runAll(inbox: SmsInbox) = engine(inbox).run()

    @Test
    fun `REQ-SMS-101 only allowlisted senders in the range are read, oldest first, as BACKFILL entries`() =
        runTest {
            val inbox =
                FakeInbox(
                    listOf(
                        sms(3, purchase(30)),
                        sms(1, purchase(10)),
                        sms(2, purchase(20), date = BACKFILL_NOW - 100 * BACKFILL_DAY),
                        sms(4, purchase(40), sender = "Pizza"),
                        sms(5, "Your order is on the way", sender = "Pizza"),
                    ),
                )
            begin()
            assertEquals(BackfillOutcome.DONE, runAll(inbox))
            val s = store.state.value
            assertEquals(BackfillPhase.DONE, s.phase)
            assertEquals(2, s.total)
            assertEquals(2, s.read)
            assertEquals(2, s.entries)
            assertEquals(0, s.review)
            val sources = db.sources().all()
            assertEquals(setOf("BACKFILL"), sources.map { it.channel }.toSet())
            assertEquals("oldest first", sources.map { it.receivedAtMillis }.sorted(), sources.map { it.receivedAtMillis })
            assertEquals(
                listOf(1_000L, 3_000L),
                db
                    .expenses()
                    .all()
                    .map { it.amountMinor }
                    .sorted(),
            )
            // the entry looks like any captured one (answers, filters and screens read the entry's own channel)
            assertEquals(
                setOf("SMS"),
                db
                    .expenses()
                    .all()
                    .map { it.source }
                    .toSet(),
            )
            assertTrue("nothing of the other sender was kept", db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-101 a sender that is not allowed is never read, not even to suggest it`() =
        runTest {
            val inbox =
                FakeInbox(listOf(sms(1, purchase(10), sender = "NewBank"), sms(2, "Hello there", sender = "Chatty"), sms(3, purchase(5))))
            begin()
            runAll(inbox)
            assertTrue("no text of a stranger was read for a suggestion", settings.suggested.isEmpty())
            assertEquals(setOf("CIB"), inbox.requested)
            assertEquals(1, db.expenses().all().size)
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-101 REQ-SMS-031 an OTP and a promo are dropped, an unknown format goes to review, counts are shown`() =
        runTest {
            val inbox =
                FakeInbox(
                    listOf(
                        sms(1, purchase(10)),
                        sms(2, "Your OTP is 889900, do not share it"),
                        sms(3, "Get 20% off at Noon this weekend! Offer ends soon"),
                        sms(4, UNKNOWN),
                        sms(5, purchase(20)),
                    ),
                )
            begin()
            runAll(inbox)
            val s = store.state.value
            assertEquals(5, s.total)
            assertEquals(5, s.read)
            assertEquals(2, s.entries)
            assertEquals(1, s.review)
            assertEquals(2, db.expenses().all().size)
            val review = db.reviews().all().single()
            assertFalse("no OTP text anywhere", db.reviews().all().any { it.text.contains("889900") })
            assertEquals("text kept 30 days from now", BACKFILL_NOW, review.createdAtMillis)
        }

    @Test
    fun `REQ-SMS-101 no Recorded notice per message and exactly one summary at the end`() =
        runTest {
            begin()
            runAll(FakeInbox((1L..5L).map { sms(it, purchase(it.toInt() * 10)) }))
            assertTrue("no per-message notice", notices.isEmpty())
            assertEquals(1, summaries.size)
            assertEquals(5, summaries.single().entries)
            // the same pipeline still notifies for live capture
            assertEquals(
                Disposition.RECORDED,
                pipeline().accept(
                    IncomingMessage(
                        CaptureSource.SMS,
                        "CIB",
                        purchase(999),
                        BACKFILL_NOW - 1_000L,
                    ),
                ),
            )
            assertEquals(1, notices.size)
        }

    @Test
    fun `REQ-SMS-101 5000 messages are read in batches of bounded size`() =
        runTest(timeout = 5.minutes) {
            val inbox = FakeInbox((1L..5_000L).map { sms(it, purchase(it.toInt())) })
            begin()
            assertEquals(BackfillOutcome.DONE, runAll(inbox))
            assertEquals(5_000, store.state.value.entries)
            assertEquals(5_000, db.expenses().all().size)
            assertTrue("batches, not one read: ${inbox.pages}", inbox.pages >= 25)
            assertTrue("memory bounded by the batch: ${inbox.largestPage}", inbox.largestPage <= 200)
        }

    @Test
    fun `REQ-SMS-101 a range with no bank messages ends done with a total of zero`() =
        runTest {
            begin()
            runAll(FakeInbox(listOf(sms(1, "Hello", sender = "Chatty"))))
            val s = store.state.value
            assertEquals(BackfillPhase.DONE, s.phase)
            assertEquals(0, s.total)
        }

    @Test
    fun `REQ-SMS-101 an ignored card behaves as live - review item marked ignored card, no entry`() =
        runTest {
            prefs.edit { it.copy(ignoredCards = setOf(PURCHASE_LAST4)) }
            begin()
            runAll(FakeInbox(listOf(sms(1, purchase(10)), sms(2, "Your OTP is 889900 for card ending $PURCHASE_LAST4, do not share it"))))
            assertTrue(db.expenses().all().isEmpty())
            val item = db.reviews().all().single()
            assertEquals("ignored_card", item.reason)
            assertEquals(1, store.state.value.review)
        }

    @Test
    fun `REQ-SMS-101 a remembered Track it makes the withdrawal a transfer pair to Cash as live does`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            begin()
            runAll(FakeInbox(listOf(sms(1, ATM, sender = "HSBC"))))
            val all = db.expenses().all()
            assertEquals(setOf("TRANSFER", "TRANSFER_IN"), all.map { it.type }.toSet())
            assertEquals(1, db.accountBalances().accounts().count { it.kind == "CASH" })
            assertEquals(1, db.sources().all().count { it.channel == "BACKFILL" })
        }

    @Test
    fun `REQ-SMS-101 the outcome of a mixed set equals live capture of the same messages`() =
        runTest {
            val bodies =
                listOf(
                    purchase(10),
                    UNKNOWN,
                    "Your OTP is 889900, do not share it",
                    ATM,
                    purchase(77),
                    // two fixtures whose outcome once differed between live and backfill (a code next to a transaction)
                    "تم تحويل 500 جنيه الى AHMED ALI، احذر من مشاركة الرمز 889900 مع اي شخص",
                    "Approve purchase EGP 500.00 at AMAZON with 482913",
                )
            val senders = mapOf(ATM to "HSBC", bodies[5] to "NBE")
            val rows = bodies.mapIndexed { i, b -> sms(i + 1L, b, sender = senders[b] ?: "CIB") }
            begin()
            runAll(FakeInbox(rows))
            val backfilled = snapshot()
            db.clearAllTables()
            // live capture as the receiver does it: the hand-off (OTP drop, code masking), then the worker's pipeline
            val live = pipeline()
            val scheduler = FakeSmsScheduler()
            val handoff = SmsHandoff(settings, scheduler, gate)
            rows.forEach { handoff.handOff(listOf(IncomingSms(it.address, it.body, it.stampMillis))) }
            scheduler.intake.forEach { live.accept(it, CaptureChannel.LIVE) }
            assertEquals(snapshot(), backfilled)
            assertTrue("no code stays readable in a review item", db.reviews().all().none { "889900" in it.text || "482913" in it.text })
        }

    private suspend fun snapshot(): Pair<List<String>, List<String>> =
        db
            .expenses()
            .all()
            .map { "${it.type}|${it.amountMinor}|${it.status}|${it.merchant}|${it.source}" }
            .sorted() to
            db
                .reviews()
                .all()
                .map { "${it.reason}|${it.sugAmountMinor}|${it.text}" }
                .sorted()

    @Test
    fun `REQ-SMS-102 a message live capture already recorded is not stored again and is not counted as new`() =
        runTest {
            val live = pipeline()
            val inboxRow = sms(1, purchase(10))
            assertEquals(Disposition.RECORDED, live.accept(IncomingMessage(CaptureSource.SMS, "CIB", inboxRow.body, inboxRow.stampMillis)))
            begin()
            runAll(FakeInbox(listOf(inboxRow)))
            assertEquals(1, db.expenses().all().size)
            val source = db.sources().all().single()
            assertEquals("the live row stays as it was", "SMS", source.channel)
            val s = store.state.value
            assertEquals(1, s.read)
            assertEquals(0, s.entries)
        }

    @Test
    fun `REQ-SMS-102 the live and the backfill id of one message are the same`() =
        runTest {
            val row = sms(1, purchase(10))
            begin()
            runAll(FakeInbox(listOf(row)))
            val backfillId =
                db
                    .sources()
                    .all()
                    .single()
                    .localSourceId
            db.clearAllTables()
            pipeline().accept(IncomingMessage(CaptureSource.SMS, "CIB", row.body, row.stampMillis))
            assertEquals(
                backfillId,
                db
                    .sources()
                    .all()
                    .single()
                    .localSourceId,
            )
        }

    @Test
    fun `REQ-SMS-102 a backfilled SMS without its notification is one entry`() =
        runTest {
            begin()
            runAll(FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC"))))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, db.sources().all().size)
        }

    @Test
    fun `REQ-SMS-102 a run killed after 400 messages continues from there with no duplicate`() =
        runTest {
            val rows = (1L..900L).map { sms(it, purchase(it.toInt())) }
            val inbox = FakeInbox(rows)
            inbox.beforePage = { n -> if (n == 3) throw Killed() }
            begin()
            try {
                runAll(inbox)
                fail("the run should have been killed")
            } catch (
                @Suppress("SwallowedException") _: Killed,
            ) {
                // the process died here
            }
            val killed = store.state.value
            assertEquals(BackfillPhase.RUNNING, killed.phase)
            assertEquals(400, killed.read)
            assertEquals(400, db.expenses().all().size)
            // WorkManager starts the worker again: a new engine on the same saved state
            inbox.beforePage = {}
            inbox.pageStarts.clear()
            assertEquals(BackfillOutcome.DONE, runAll(inbox))
            assertEquals("continued after the 400th, not from the start", 400L, inbox.pageStarts.first()?.id)
            assertEquals(900, db.expenses().all().size)
            assertEquals(
                900,
                db
                    .sources()
                    .all()
                    .map { it.localSourceId }
                    .toSet()
                    .size,
            )
            assertEquals(900, store.state.value.entries)
        }

    @Test
    fun `REQ-SMS-102 a kill in the middle of a message replays it without a duplicate`() =
        runTest {
            val rows = (1L..10L).map { sms(it, purchase(it.toInt())) }
            begin()
            runAll(FakeInbox(rows))
            // the position was lost with the kill (state written before the last message), the entries are there
            store.save(
                store.state.value.copy(
                    phase = BackfillPhase.RUNNING,
                    read = 8,
                    entries = 8,
                    lastDateMillis = rows[7].dateMillis,
                    lastId = rows[7].id,
                ),
            )
            runAll(FakeInbox(rows))
            assertEquals(10, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-102 an entry the user deleted is not recorded again within 30 days, but is after`() =
        runTest {
            val row = sms(1, purchase(10))
            begin()
            runAll(FakeInbox(listOf(row)))
            val expenseId =
                db
                    .expenses()
                    .all()
                    .single()
                    .id
            db.expenses().delete(expenseId)
            assertTrue(db.expenses().all().isEmpty())
            // reading the same range again
            begin()
            runAll(FakeInbox(listOf(row)))
            assertTrue("not recorded again", db.expenses().all().isEmpty())
            assertEquals(0, store.state.value.entries)
            // 31 days later the memory is gone
            val later = Clock.fixed(ANSWER_NOW.plusSeconds(31 * 24 * 3600L), ZoneOffset.UTC)
            val pipelineLater = SmsPipeline(db, settings, MemoryNotifications(), later, gate, FixedKey, answers = prefs, memory = store)
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))
            SmsBackfill(db, pipelineLater, FakeInbox(listOf(row)), store, settings, FixedKey, gate, later).run()
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-102 a dismissed review item does not come back when the range is read again`() =
        runTest {
            val row = sms(1, UNKNOWN)
            begin()
            runAll(FakeInbox(listOf(row)))
            val id =
                db
                    .reviews()
                    .all()
                    .single()
                    .id
            db.reviews().delete(id)
            begin()
            runAll(FakeInbox(listOf(row)))
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-103 READ_SMS not granted at the start stops with the permission state and stores nothing`() =
        runTest {
            val inbox = FakeInbox(listOf(sms(1, purchase(10))))
            inbox.denied = true
            begin()
            assertEquals(BackfillOutcome.PERMISSION_LOST, runAll(inbox))
            assertEquals(BackfillPhase.PERMISSION_LOST, store.state.value.phase)
            assertTrue(db.expenses().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-103 READ_SMS revoked during a run stops without a crash and keeps what was stored`() =
        runTest {
            val inbox = FakeInbox((1L..600L).map { sms(it, purchase(it.toInt())) })
            inbox.beforePage = { n -> if (n == 2) inbox.denied = true }
            begin()
            assertEquals(BackfillOutcome.PERMISSION_LOST, runAll(inbox))
            val s = store.state.value
            assertEquals(BackfillPhase.PERMISSION_LOST, s.phase)
            assertEquals(200, s.entries)
            assertEquals(200, db.expenses().all().size)
            // permission given again: the same run continues, nothing is doubled
            inbox.denied = false
            inbox.beforePage = {}
            store.save(s.copy(phase = BackfillPhase.RUNNING))
            assertEquals(BackfillOutcome.DONE, runAll(inbox))
            assertEquals(600, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-103 Cancel stops within a batch, keeps what was stored, and the run continues later`() =
        runTest {
            val inbox = FakeInbox((1L..600L).map { sms(it, purchase(it.toInt())) })
            begin()
            val run: Job = launch { runAll(inbox) }
            launch {
                store.state.first { it.entries >= 5 }
                // what BackfillController.cancel does: the mark first, then the work
                store.save(store.state.value.copy(phase = BackfillPhase.STOPPED))
                run.cancel()
            }
            run.join()
            val stopped = store.state.value
            assertEquals(BackfillPhase.STOPPED, stopped.phase)
            assertTrue("stopped inside the first batch: ${stopped.entries}", stopped.entries in 5 until 200)
            assertEquals(stopped.entries, db.expenses().all().size)
            // Start again with the same range: it continues
            store.save(stopped.copy(phase = BackfillPhase.RUNNING))
            assertEquals(BackfillOutcome.DONE, runAll(inbox))
            assertEquals(600, db.expenses().all().size)
            assertEquals(
                600,
                db
                    .sources()
                    .all()
                    .map { it.localSourceId }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `REQ-SMS-103 a run that was cancelled before the worker started reads nothing`() =
        runTest {
            store.save(BackfillState(BackfillPhase.STOPPED, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))
            val inbox = FakeInbox(listOf(sms(1, purchase(10))))
            assertEquals(BackfillOutcome.STOPPED, runAll(inbox))
            assertEquals(0, inbox.pages)
            assertTrue(db.expenses().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-103 an erase running stops the backfill and stores no more`() =
        runTest {
            val inbox = FakeInbox((1L..10L).map { sms(it, purchase(it.toInt())) })
            begin()
            gate.open = false
            assertEquals(BackfillOutcome.STOPPED, runAll(inbox))
            assertTrue(db.expenses().all().isEmpty())
            assertEquals(BackfillPhase.STOPPED, store.state.value.phase)
        }

    @Test
    fun `REQ-SMS-101 the allowlist is the one live capture uses, including a sender the user added`() =
        runTest {
            settings = MemorySettings(SenderPolicy(added = setOf("MYBANK")))
            begin()
            runAll(FakeInbox(listOf(sms(1, purchase(10), sender = "MyBank"), sms(2, purchase(20), sender = "Other"))))
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-SMS-101 backfill works even when the live SMS capture switch is off`() =
        runTest {
            settings.smsOn = false
            begin()
            runAll(FakeInbox(listOf(sms(1, purchase(10)))))
            assertEquals(1, db.expenses().all().size)
            // while live capture stays off
            assertEquals(
                Disposition.DROPPED,
                pipeline().accept(
                    IncomingMessage(
                        CaptureSource.SMS,
                        "CIB",
                        purchase(11),
                        BACKFILL_NOW - 5_000L,
                    ),
                ),
            )
        }
}
