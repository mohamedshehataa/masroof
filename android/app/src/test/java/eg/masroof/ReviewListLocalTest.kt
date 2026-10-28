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
import kotlinx.coroutines.launch
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
@Suppress("LargeClass") // one story, one file of REQ-SMS-098/099/100 tests
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

    @Test
    fun `REQ-SMS-100 a purchase that also mentions a credited cashback is never income`() {
        val texts =
            listOf(
                "Your card ending 4512 was charged EGP 1,250.00 at ZARA. Cashback of EGP 12.50 has been credited to your card",
                "تم خصم 500 جنيه من بطاقتك المنتهية ب 4512 لدى امازون وتم اضافة كاش باك 25 جنيه إلى حسابك",
                "Purchase of EGP 300.00 at NOON with card ending 4512. EGP 15 cashback was credited to your account",
                "Your card ending 4512 was debited EGP 200.00 at UBER, cashback has been credited",
                "Your card ending 4512 was charged EGP 800.00 at NOON. EGP 40 cashback has been credited",
                "Transaction of EGP 500.00 at NOON on card ending 4512. Cashback of EGP 25 has been credited",
                "تم استخدام بطاقتك المنتهية ب 4512 بمبلغ 500 جنيه لدى امازون وتمت اضافة كاش باك 25 جنيه",
                "Your card ending 4512 was used for EGP 500.00 at NOON. Cashback of EGP 25 has been credited",
                "Purchase transaction of EGP 500.00 at NOON. Cashback of EGP 25 has been credited",
                "Debit transaction EGP 500.00 at NOON. Cashback of EGP 25 has been credited",
                "تم استخدام بطاقتكم بمبلغ 500 جنيه لدى نون وتم اضافه كاش باك 25 جنيه",
                "تمت عمليه شرائيه ببطاقتكم بمبلغ 500 جنيه وتم اضافه كاش باك 25 جنيه",
                "تم الخصم من بطاقتكم 500 جنيه وتم اضافه كاش باك 25 جنيه",
                "Your purchase EGP 500.00 at NOON using card 4512 is approved. Cashback of EGP 25 has been credited",
                "مدفوعات 500 جنيه لدى نون وتم اضافه كاش باك 25 جنيه",
                "Purchase at NOON EGP 500.00 - cashback has been credited",
                "Purchase at NOON. Amount EGP 500.00. Cashback has been credited",
                "POS purchase EGP 500.00 - Cashback has been credited",
                "Cashback on EGP 500.00 purchase at NOON has been credited to your card ending 4512",
            )
        for (text in texts) {
            val outcome =
                eg.masroof.ingest.parse.MessageParser
                    .parse(IncomingMessage(CaptureSource.SMS, "CIB", text, at))
            val kind =
                (outcome as? eg.masroof.ingest.parse.ParseOutcome.Review)?.suggestion?.kind
                    ?: (outcome as? eg.masroof.ingest.parse.ParseOutcome.Record)?.record?.kind
            assertTrue("$kind for $outcome", kind == null || kind.name !in INCOMING)
        }
    }

    @Test
    fun `REQ-SMS-100 a genuine cashback-only credit is INCOME with the cashback amount`() {
        val cases =
            mapOf(
                "تم اضافة كاش باك 20 جنيه على عملية شراء من نون إلى حسابك" to 2_000L,
                "Cashback of EGP 20 on the amount you spent at NOON has been credited to your account" to 2_000L,
                "Cashback of EGP 10 on your purchase paid with card ending 4512 has been credited" to 1_000L,
                "EGP 20 cashback has been credited to your card ending 4512 for purchase of goods at NOON" to 2_000L,
                "Cashback reward of EGP 75.00 has been credited to your account ending 7781 for October spending" to 7_500L,
                "Cashback has been credited to your card ending 4512: EGP 40.00" to 4_000L,
            )
        for ((text, minor) in cases) {
            val outcome =
                eg.masroof.ingest.parse.MessageParser
                    .parse(IncomingMessage(CaptureSource.SMS, "CIB", text, at))
            val r =
                (outcome as? eg.masroof.ingest.parse.ParseOutcome.Review)?.suggestion
                    ?: (outcome as? eg.masroof.ingest.parse.ParseOutcome.Record)?.record
            assertEquals("$text -> $outcome", "INCOME", r?.kind?.name)
            assertEquals(minor, r?.amountMinor)
        }
    }

    @Test
    fun `REQ-SMS-098 an SMS twin of a review-saved notification adds its source and last four with no notice and no Undo chip`() =
        runTest {
            db.reviews().insert(
                eg.masroof.data.db.ReviewItemEntity(
                    id = "n-rev",
                    source = "NOTIFICATION",
                    sender = "com.egyptianbanks.instapay",
                    text = "x",
                    reason = "unconfirmed",
                    receivedAtMillis = at,
                    createdAtMillis = at,
                    sugKind = "INCOME",
                    sugAmountMinor = 125_000L,
                    sugCurrency = "EGP",
                    sugOccurredAtMillis = at,
                    sugSenderId = "InstaPay",
                ),
            )
            vm().saveNow("n-rev")
            val notices = mutableListOf<CapturedTxEntity>()
            val notifier =
                object : eg.masroof.ingest.CapturedNotifier {
                    override fun captured(item: CapturedTxEntity) {
                        notices += item
                    }

                    override fun cancel(localId: String) = Unit

                    override fun cancelAll() = Unit
                }
            SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey, notifier)
                .accept(sms("BanqueMisr", r4, receivedAt = at + 3_000))
            val entry = db.expenses().all().single()
            assertEquals("4471", entry.instrumentLast4)
            assertEquals(2, db.sources().all().size)
            assertTrue(
                db
                    .captured()
                    .observeRecent(0L)
                    .first()
                    .isEmpty(),
            )
            assertTrue(notices.isEmpty())
        }

    @Test
    fun `REQ-SMS-100 no purchase fixture row becomes income`() =
        runTest {
            val rows = fixtureRows().filter { (it["expect"] as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull == "PURCHASE" }
            assertTrue("purchase rows: ${rows.size}", rows.size >= 20)
            val p = pipeline()
            val model = vm()
            val offenders = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for ((i, row) in rows.withIndex()) {
                val id = row["id"]!!.jsonPrimitive.content
                val stamp = row["receivedAt"]?.jsonPrimitive?.contentOrNull
                val received = stamp?.let { Instant.parse(it).toEpochMilli() } ?: (at + i * 3_600_000L)
                val source =
                    if (row["source"]?.jsonPrimitive?.contentOrNull ==
                        "NOTIFICATION"
                    ) {
                        CaptureSource.NOTIFICATION
                    } else {
                        CaptureSource.SMS
                    }
                p.accept(IncomingMessage(source, row["sender"]!!.jsonPrimitive.content, row["body"]!!.jsonPrimitive.content, received))
                for (item in db.reviews().all()) model.saveNow(item.id)
                if (db
                        .expenses()
                        .all()
                        .filter { seen.add(it.id) }
                        .any { it.type == "INCOME" }
                ) {
                    offenders += id
                }
            }
            assertTrue("purchase fixtures stored as income: $offenders", offenders.isEmpty())
        }

    @Test
    fun `REQ-SMS-098 a Save that waited for an erase writes nothing and learns nothing`() =
        runTest {
            val lock =
                eg.masroof.data.auth
                    .EraseLock()
            pipeline().accept(sms("NBE", z18))
            val item = db.reviews().all().single()
            lock.mutex.lock()
            val job =
                launch(Dispatchers.Unconfined) { ReviewViewModel(db, Dispatchers.Unconfined, clock, eraseLock = lock).saveNow(item.id) }
            // the erase runs while the Save waits
            db.reviews().delete(item.id)
            lock.mutex.unlock()
            job.join()
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.sources().all().isEmpty())
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-099 a row with a bad amount or a time far in the past or the future is kept for review`() =
        runTest {
            enqueue(queued("q-zero", "PURCHASE", 0L, receivedAt = at))
            enqueue(queued("q-old", "PURCHASE", 5_000L, receivedAt = at - 46L * 24 * 3_600_000))
            enqueue(queued("q-future", "PURCHASE", 6_000L, receivedAt = at + 2L * 24 * 3_600_000))
            enqueue(queued("q-fine", "PURCHASE", 7_000L, receivedAt = at))
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            val byAmount = db.expenses().all().associateBy { it.amountMinor }
            assertEquals(4, byAmount.size)
            for (a in listOf(0L, 5_000L, 6_000L)) assertEquals("PENDING_REVIEW", byAmount.getValue(a).status)
            assertEquals("CONFIRMED", byAmount.getValue(7_000L).status)
        }

    @Test
    fun `REQ-SMS-099 two rows the old pairing already matched, both claimed, become one entry with two sources`() =
        runTest {
            val a = queued("q-a", "TRANSFER_IN", 125_000L, "SMS", receivedAt = at, claims = 1, last4 = null)
            val b = queued("q-b", "TRANSFER_IN", 125_000L, "NOTIFICATION", receivedAt = at + 3_000, claims = 1, last4 = "4471")
            db.parsedOutbox().insert(a)
            db.parsedOutbox().insert(b)
            db.captured().insert(captured("q-a", null, "SMS", 125_000L, "TRANSFER_IN", at).copy(paired = true, pairedLocalId = "q-b"))
            db.captured().insert(
                captured("q-b", null, "NOTIFICATION", 125_000L, "TRANSFER_IN", at + 3_000).copy(paired = true, pairedLocalId = "q-a"),
            )
            CaptureQueueConverter(db, clock, CountingIds()).convertAll()
            val entry = db.expenses().all().single()
            assertEquals(
                setOf("q-a", "q-b"),
                db
                    .sources()
                    .all()
                    .filter { it.expenseId == entry.id }
                    .map { it.localSourceId }
                    .toSet(),
            )
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-098 a notification arriving after a review-saved SMS joins it and no Undo chip is shown for the saved one`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            vm().saveNow(
                db
                    .reviews()
                    .all()
                    .single()
                    .id,
            )
            assertTrue(
                "no chip",
                db
                    .captured()
                    .observeRecent(0L)
                    .first()
                    .isEmpty(),
            )
            val push =
                IncomingMessage(
                    CaptureSource.NOTIFICATION,
                    "com.egyptianbanks.instapay",
                    "InstaPay\nYou received EGP 1,000.00 from Omar K",
                    at + 3_000,
                )
            pipeline().accept(push)
            assertEquals("one entry", 1, db.expenses().all().size)
            assertEquals(2, db.sources().all().size)
        }

    @Test
    fun `REQ-SMS-098 two review items of one transfer saved one after the other make one entry`() =
        runTest {
            pipeline().accept(sms("NBE", z18))
            val first = db.reviews().all().single()
            db.reviews().insert(first.copy(id = "second", source = "NOTIFICATION", receivedAtMillis = at + 3_000))
            val model = vm()
            model.saveNow(first.id)
            model.saveNow("second")
            assertEquals(1, db.expenses().all().size)
            assertEquals(2, db.sources().all().size)
        }

    private companion object {
        val INCOMING = setOf("INCOME", "TRANSFER_IN", "REFUND")
    }
}
