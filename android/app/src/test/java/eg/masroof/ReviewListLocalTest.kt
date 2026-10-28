package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.ingest.CaptureQueueConverter
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.viewmodel.ReviewSaveResult
import eg.masroof.ui.viewmodel.ReviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private class CountingIds(
    private val failOn: Int = Int.MAX_VALUE,
) : IdFactory {
    var calls = 0

    override fun next(): String {
        calls++
        check(calls != failOn) { "process killed" }
        return UUID.randomUUID().toString()
    }
}

/** STORY-172: review Save is local (REQ-SMS-098), the old upload queue becomes entries (REQ-SMS-099), direction guard (REQ-SMS-100). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewListLocalTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)
    private val at = Instant.parse("2026-10-26T09:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun pipeline() = SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey)

    private fun vm() = ReviewViewModel(db, Dispatchers.Unconfined, clock)

    private fun sms(
        sender: String,
        body: String,
        receivedAt: Long = at,
    ) = IncomingMessage(CaptureSource.SMS, sender, body, receivedAt)

    private val z18 = "تمت اضافة مبلغ 1,000 جنيه الى حسابكم ***7781 تحويل من AHMED ALI"
    private val z26 = "تم استلام تحويل لحظي بمبلغ 900 جنيه من AHMED ALI الى حسابك ***7781"
    private val r4 = "تم استقبال تحويل لحظي إلى حسابكم 4471 بمبلغ 1,250.00 جم من OMAR KHALED SAMIR"

    // ---- REQ-SMS-098

    @Test
    fun `REQ-SMS-098 Save of c-r11x-Z18 writes one confirmed INCOME with one source, deletes the text and learns the format`() =
        runTest {
            assertEquals(Disposition.REVIEW, pipeline().accept(sms("NBE", z18)))
            val item = db.reviews().all().single()
            assertEquals("INCOME", item.sugKind)
            assertEquals(100_000L, item.sugAmountMinor)
            val learnedBefore = item.shape
            assertEquals(ReviewSaveResult.SAVED, vm().saveNow(item.id))
            val entry = db.expenses().all().single()
            assertEquals("INCOME", entry.type)
            assertEquals(100_000L, entry.amountMinor)
            assertEquals("CONFIRMED", entry.status)
            val source = db.sources().all().single()
            assertEquals("SMS", source.channel)
            assertEquals(item.id, source.localSourceId)
            assertEquals(entry.id, source.expenseId)
            assertTrue("the text is gone", db.reviews().all().isEmpty())
            val learned = if (learnedBefore != null) 1 else 0
            assertEquals("learned exactly when the item had a format", learned, db.learnedTemplates().all().size)
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-098 Dismiss deletes the item and its text and writes no expense`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            val item = db.reviews().all().single()
            vm().dismiss(item.id)
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-098 a message whose local id already exists is not recorded twice and says already recorded`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            val item = db.reviews().all().single()
            val model = vm()
            assertEquals(ReviewSaveResult.SAVED, model.saveNow(item.id))
            // the same message comes back as a review item (backfill after live capture)
            db.reviews().insert(item)
            assertEquals(ReviewSaveResult.ALREADY_RECORDED, model.saveNow(item.id))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, db.sources().all().size)
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-098 an amount of 0 or above 1000000000000 minor units writes nothing and keeps the item`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            val item = db.reviews().all().single()
            for (bad in listOf(0L, 1_000_000_000_001L)) {
                db.openHelper.writableDatabase.execSQL("UPDATE review_item SET sugAmountMinor = $bad")
                assertEquals(ReviewSaveResult.AMOUNT_INVALID, vm().saveNow(item.id))
                assertTrue(db.expenses().all().isEmpty())
                assertEquals(1, db.reviews().all().size)
            }
        }

    @Test
    fun `REQ-SMS-098 a write failure keeps the item and writes nothing`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            val item = db.reviews().all().single()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_source BEFORE INSERT ON expense_source BEGIN SELECT RAISE(ABORT, 'disk'); END",
            )
            assertEquals(ReviewSaveResult.FAILED, vm().saveNow(item.id))
            assertEquals("rolled back with the source", 0, db.expenses().all().size)
            assertEquals(1, db.reviews().all().size)
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-098 a date-only review item is saved at Cairo noon with the time unknown, one with a time keeps it`() =
        runTest {
            pipeline().accept(sms("CIB", "Purchase of EGP 99.00 at PHARMACY on 25/10/2026"))
            pipeline().accept(sms("CIB", "Purchase of EGP 77.00 at KIOSK on 25/10/2026 14:05", receivedAt = at + 1))
            for (item in db.reviews().all()) vm().saveNow(item.id)
            val byAmount = db.expenses().all().associateBy { it.amountMinor }
            val cairo = ZoneId.of("Africa/Cairo")
            val dateOnly = byAmount.getValue(9_900L)
            assertFalse(dateOnly.timeKnown)
            assertEquals(LocalTime.NOON, Instant.ofEpochMilli(dateOnly.occurredAtMillis).atZone(cairo).toLocalTime())
            assertTrue(byAmount.getValue(7_700L).timeKnown)
        }

    @Test
    fun `REQ-SMS-098 a review-saved SMS joins the notification twin that was already recorded as a second source`() =
        runTest {
            val twinExpense = expense("e-twin", 100_000L, "INCOME")
            db.expenses().insert(twinExpense)
            db.captured().insert(captured("n-1", "e-twin", CaptureSource.NOTIFICATION.name, 100_000L, "INCOME", at + 3_000))
            db.sources().insert(ExpenseSourceEntity("s-twin", "e-twin", "PUSH", "n-1", at + 3_000, "x", null))
            pipeline().accept(sms("NBE", z18))
            vm().saveNow(
                db
                    .reviews()
                    .all()
                    .single()
                    .id,
            )
            assertEquals("one entry, not two", 1, db.expenses().all().size)
            assertEquals(2, db.sources().all().count { it.expenseId == "e-twin" })
        }

    // ---- REQ-SMS-099

    private fun expense(
        id: String,
        amount: Long,
        type: String,
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = "EGP",
        occurredAtMillis = at,
        merchant = null,
        categoryId = null,
        accountId = null,
        notes = null,
        pending = false,
        source = "PUSH",
        type = type,
        status = "PENDING_REVIEW",
        createdAtMillis = at,
        updatedAtMillis = at,
    )

    private fun captured(
        id: String,
        expenseId: String?,
        source: String,
        amount: Long,
        kind: String,
        receivedAt: Long,
    ) = CapturedTxEntity(
        localId = id,
        expenseId = expenseId,
        amountMinor = amount,
        currency = "EGP",
        label = null,
        source = source,
        templateId = null,
        capturedAtMillis = at,
        kind = kind,
        receivedAtMillis = receivedAt,
    )

    @Suppress("LongParameterList") // typed fields of one row
    private fun queued(
        id: String,
        kind: String,
        amount: Long,
        source: String = "SMS",
        receivedAt: Long = at,
        claims: Int = 0,
        failed: Boolean = false,
        last4: String? = "4512",
        merchant: String? = "AMAZON",
    ) = ParsedOutboxEntity(
        localId = id,
        source = source,
        kind = kind,
        amountMinor = amount,
        currency = "EGP",
        occurredAtMillis = receivedAt,
        instrumentLast4 = last4,
        senderId = "CIB",
        institution = "CIB",
        paymentMethod = "CARD",
        merchant = merchant,
        failed = failed,
        createdAtMillis = receivedAt,
        claims = claims,
    )

    private suspend fun enqueue(
        row: ParsedOutboxEntity,
        withCaptured: Boolean = true,
    ) {
        db.parsedOutbox().insert(row)
        if (withCaptured) db.captured().insert(captured(row.localId, null, row.source, row.amountMinor, row.kind, row.createdAtMillis))
    }

    @Test
    fun `REQ-SMS-099 unsent, failed and claimed rows each become one local entry with their source, and the queue is empty`() =
        runTest {
            enqueue(queued("q-unsent", "PURCHASE", 5_000L, receivedAt = at))
            enqueue(queued("q-failed", "PURCHASE", 6_000L, receivedAt = at + 10_000_000, failed = true))
            enqueue(queued("q-claimed", "FEE", 700L, receivedAt = at + 20_000_000, claims = 2))
            val converted = CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            assertEquals(3, converted)
            assertTrue(db.parsedOutbox().all().isEmpty())
            val entries = db.expenses().all()
            assertEquals(3, entries.size)
            entries.forEach {
                assertNull("no server id", it.serverId)
                assertEquals("CONFIRMED", it.status)
                assertEquals("AMAZON", it.merchant)
                assertEquals("4512", it.instrumentLast4)
                assertEquals("CARD", it.paymentMethod)
                assertEquals("EGP", it.currency)
            }
            val sources = db.sources().all().associateBy { it.localSourceId }
            assertEquals(setOf("q-unsent", "q-failed", "q-claimed"), sources.keys)
            sources.values.forEach { assertEquals("SMS", it.channel) }
            for (row in db.captured().all()) {
                assertEquals(sources.getValue(row.localId).expenseId, row.expenseId)
            }
        }

    @Test
    fun `REQ-SMS-099 income, an incoming transfer and more than 50000 EGP wait for review, a purchase does not`() =
        runTest {
            enqueue(queued("q-income", "INCOME", 100_000L, receivedAt = at))
            enqueue(queued("q-in", "TRANSFER_IN", 200_000L, receivedAt = at + 10_000_000))
            enqueue(queued("q-big", "PURCHASE", 5_000_001L, receivedAt = at + 20_000_000))
            enqueue(queued("q-small", "PURCHASE", 5_000L, receivedAt = at + 30_000_000))
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            val byAmount = db.expenses().all().associateBy { it.amountMinor }
            assertEquals("PENDING_REVIEW", byAmount.getValue(100_000L).status)
            assertEquals("INCOME", byAmount.getValue(100_000L).type)
            assertEquals("PENDING_REVIEW", byAmount.getValue(200_000L).status)
            assertEquals("PENDING_REVIEW", byAmount.getValue(5_000_001L).status)
            assertEquals("CONFIRMED", byAmount.getValue(5_000L).status)
            assertEquals(5_000L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
        }

    @Test
    fun `REQ-SMS-099 an SMS and a notification of one transfer 3 seconds apart become one entry with two sources`() =
        runTest {
            enqueue(queued("q-sms", "TRANSFER_IN", 125_000L, "SMS", receivedAt = at, last4 = null))
            enqueue(queued("q-push", "TRANSFER_IN", 125_000L, "NOTIFICATION", receivedAt = at + 3_000, last4 = "4471"))
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            val entry = db.expenses().all().single()
            assertEquals("4471", entry.instrumentLast4)
            assertEquals(
                setOf("q-sms", "q-push"),
                db
                    .sources()
                    .all()
                    .filter { it.expenseId == entry.id }
                    .map { it.localSourceId }
                    .toSet(),
            )
            assertEquals(
                setOf(entry.id),
                db
                    .captured()
                    .all()
                    .map { it.expenseId }
                    .toSet(),
            )
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-099 a conversion killed half way is finished by the next start without a duplicate`() =
        runTest {
            enqueue(queued("q-1", "PURCHASE", 1_000L, receivedAt = at))
            enqueue(queued("q-2", "PURCHASE", 2_000L, receivedAt = at + 10_000_000))
            enqueue(queued("q-3", "PURCHASE", 3_000L, receivedAt = at + 20_000_000))
            // each row takes two ids: the third call is the second row's expense id
            val killed = runCatching { CaptureQueueConverter(db, clock, CountingIds(failOn = 3)).convertAll() }
            assertTrue(killed.isFailure)
            assertEquals("the first row is done, the others untouched", 1, db.expenses().all().size)
            assertEquals(2, db.parsedOutbox().all().size)
            assertEquals(2, CaptureQueueConverter(db, clock, CountingIds()).convertAll())
            assertEquals(0, CaptureQueueConverter(db, clock, CountingIds()).convertAll())
            assertEquals(3, db.expenses().all().size)
            assertEquals(3, db.sources().all().size)
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-099 a row whose source already exists only loses its queue row`() =
        runTest {
            enqueue(queued("q-1", "PURCHASE", 1_000L))
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            db.parsedOutbox().insert(queued("q-1", "PURCHASE", 1_000L))
            assertEquals(0, CaptureQueueConverter(db, clock, CountingIds()).convertAll())
            assertEquals(1, db.expenses().all().size)
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-099 a row of an unknown kind is kept for review, never dropped`() =
        runTest {
            enqueue(queued("q-odd", "WHAT", 1_000L))
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            assertEquals(
                "PENDING_REVIEW",
                db
                    .expenses()
                    .all()
                    .single()
                    .status,
            )
        }

    // ---- REQ-SMS-100

    @Test
    fun `REQ-SMS-100 r4-own-shape-in is a pending INCOME of 1250 EGP with last four 4471 and is not spending`() =
        runTest {
            pipeline().accept(sms("BanqueMisr", r4))
            val entry = db.expenses().all().single()
            assertEquals("INCOME", entry.type)
            assertEquals("PENDING_REVIEW", entry.status)
            assertEquals(125_000L, entry.amountMinor)
            assertEquals("4471", entry.instrumentLast4)
            assertEquals(0L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
        }

    @Test
    fun `REQ-SMS-100 c-r11x-Z26 is a review item suggesting INCOME, no expense until saved, and saved it is INCOME`() =
        runTest {
            assertEquals(Disposition.REVIEW, pipeline().accept(sms("InstaPay", z26)))
            val item = db.reviews().all().single()
            assertTrue(item.sugKind in setOf("INCOME", "TRANSFER_IN", "REFUND"))
            assertEquals("INCOME", item.sugKind)
            assertTrue(db.expenses().all().isEmpty())
            vm().saveNow(item.id)
            assertEquals(
                "INCOME",
                db
                    .expenses()
                    .all()
                    .single()
                    .type,
            )
        }

    private fun fixtureRows(): List<JsonObject> =
        File("../../fixtures/sms")
            .listFiles { f -> f.name.endsWith(".jsonl") }!!
            .sortedBy { it.name }
            .flatMap { f -> f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }

    private fun incomingKinds(row: JsonObject): Set<String>? {
        val expect = row["expect"] as? JsonObject ?: return null
        val kinds =
            expect["kind"]?.jsonPrimitive?.contentOrNull?.let { setOf(it) }
                ?: ((expect["suggest"] as? JsonObject)?.get("kinds") as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
                ?: return null
        return kinds.takeIf { it.isNotEmpty() && it.all { k -> k in INCOMING } }
    }

    @Test
    fun `REQ-SMS-100 no INCOME, TRANSFER_IN or REFUND fixture row is ever stored as an expense or an outgoing transfer`() =
        runTest {
            val rows = fixtureRows().filter { incomingKinds(it) != null }
            assertTrue("fixtures found: ${rows.size}", rows.size >= 20)
            var stored = 0
            val offenders = linkedSetOf<String>()
            val seen = mutableSetOf<String>()
            val p = pipeline()
            val model = vm()
            for ((i, row) in rows.withIndex()) {
                val id = row["id"]!!.jsonPrimitive.content
                val isPush = row["source"]?.jsonPrimitive?.contentOrNull == "NOTIFICATION"
                val source = if (isPush) CaptureSource.NOTIFICATION else CaptureSource.SMS
                val stamp = row["receivedAt"]?.jsonPrimitive?.contentOrNull
                val received = stamp?.let { Instant.parse(it).toEpochMilli() } ?: (at + i * 3_600_000L)
                val message =
                    IncomingMessage(source, row["sender"]!!.jsonPrimitive.content, row["body"]!!.jsonPrimitive.content, received)
                p.accept(message)
                for (item in db.reviews().all()) model.saveNow(item.id)
                for (entry in db.expenses().all().filter { seen.add(it.id) }) {
                    if (entry.type !in setOf("INCOME", "REFUND")) offenders += "$id:${entry.type}"
                }
                stored = db.expenses().all().size
            }
            assertTrue("stored as money out: $offenders", offenders.isEmpty())
            assertTrue("the rows were really exercised: $stored", stored >= 10)
        }

    private companion object {
        val INCOMING = setOf("INCOME", "TRANSFER_IN", "REFUND")
    }
}
