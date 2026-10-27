package eg.masroof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MIGRATION_7_8
import eg.masroof.data.db.MIGRATION_8_9
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.components.kindLabelRes
import eg.masroof.ui.viewmodel.toRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import java.time.ZoneOffset

private fun schemaDb(
    context: Context,
    name: String,
    version: Int,
): SQLiteDatabase {
    context.deleteDatabase(name)
    val schema =
        Json
            .parseToJsonElement(File("schemas/eg.masroof.data.db.AppDatabase/$version.json").readText())
            .jsonObject["database"]!!
            .jsonObject
    val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
    for (entity in schema["entities"]!!.jsonArray) {
        val e = entity.jsonObject
        raw.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
    }
    for (q in schema["setupQueries"]!!.jsonArray) raw.execSQL(q.jsonPrimitive.content)
    return raw
}

/** Two channels, one transfer: under any interleaving the server gets one record, and each record pairs at most once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedRaceTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-26T21:05:00Z"), ZoneOffset.UTC)
    private val sms =
        "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 " +
            "رقم المعاملة bda86be1 للمزيد أتصل ب 16990"
    private val push = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
    private val at = Instant.parse("2026-10-26T20:04:10Z").toEpochMilli()
    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pipeline get() =
        SmsPipeline(db, MemorySettings(), MemoryNotifications(), FakeSmsScheduler(), clock, MemoryGate(), FixedKey)

    private fun bankSms(atMillis: Long = at) = IncomingMessage(CaptureSource.SMS, "BDC", sms, atMillis)

    private fun appPush(atMillis: Long) = IncomingMessage(CaptureSource.NOTIFICATION, "com.egyptianbanks.instapay", push, atMillis)

    private suspend fun markUploaded(source: String) {
        for (row in db.parsedOutbox().all().filter { it.source == source }) {
            db.parsedOutbox().delete(row.localId)
            db.captured().setExpenseId(row.localId, "expense-${row.localId.take(4)}")
        }
    }

    @Test
    fun `REQ-SMS-090 the bank SMS arriving while the notification record is being uploaded leaves exactly one server record`() =
        runTest {
            val server = mutableListOf<String>()
            val p = pipeline
            p.accept(appPush(at + 3_000))
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ): SmsUploadResult {
                        // the request is on the wire when the richer SMS of the same transfer is accepted
                        assertEquals(Disposition.QUEUED, p.accept(bankSms()))
                        server += item.source
                        return SmsUploadResult.Sent("expense-1")
                    }
                }
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            assertEquals(listOf("NOTIFICATION"), server)
            assertTrue(db.parsedOutbox().all().isEmpty())
            // the one expense has its local row, so it can still be undone
            val kept = db.captured().all().single()
            assertEquals("expense-1", kept.expenseId)
            assertEquals(CaptureSource.NOTIFICATION.name, kept.source)
        }

    @Test
    fun `REQ-SMS-090 a queued record that nobody is uploading is still replaced by the richer twin`() =
        runTest {
            val p = pipeline
            p.accept(appPush(at + 3_000))
            p.accept(bankSms())
            assertEquals(listOf("SMS"), db.parsedOutbox().all().map { it.source })
            assertEquals(1, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-090 a claim is atomic - the second taker and the replacement both lose, and a row once claimed is never replaced`() =
        runTest {
            pipeline.accept(appPush(at))
            val id =
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .localId
            val now = clock.millis()
            val stale = now - 120_000
            assertEquals(1, db.parsedOutbox().claim(id, now, stale))
            assertEquals(0, db.parsedOutbox().claim(id, now, stale))
            assertEquals(0, db.parsedOutbox().deleteIfUnclaimed(id, stale))
            // a release with someone else's token changes nothing; with its own token it frees the row for the next upload
            db.parsedOutbox().release(id, now - 1)
            assertEquals(0, db.parsedOutbox().claim(id, now, stale))
            db.parsedOutbox().release(id, now)
            // freed, but it was started once: the server may have it, so it is never deleted as a replaced duplicate
            assertEquals(0, db.parsedOutbox().deleteIfUnclaimed(id, stale))
            assertEquals(1, db.parsedOutbox().claim(id, now, stale))
        }

    @Test
    fun `REQ-SMS-090 SMS, a push, then a separate push a minute later are two transfers`() =
        runTest {
            val p = pipeline
            p.accept(bankSms())
            p.accept(appPush(at + 3_000))
            p.accept(appPush(at + 60_000))
            assertEquals(2, db.parsedOutbox().all().size)
            assertEquals(2, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-090 push, push, SMS offline are two transfers`() =
        runTest {
            val p = pipeline
            p.accept(appPush(at))
            p.accept(appPush(at + 60_000))
            p.accept(bankSms(at + 3_000))
            assertEquals(
                listOf("SMS", "NOTIFICATION"),
                db
                    .parsedOutbox()
                    .all()
                    .map { it.source }
                    .sortedDescending(),
            )
            assertEquals(2, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-090 push, push, SMS online are two transfers`() =
        runTest {
            val p = pipeline
            p.accept(appPush(at))
            p.accept(appPush(at + 60_000))
            markUploaded("NOTIFICATION")
            p.accept(bankSms(at + 3_000))
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals(2, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-090 the receive time pairs the channels, not the time written in the SMS body`() =
        runTest {
            val p = pipeline
            p.accept(appPush(at))
            // no time in the body at all, received four minutes after the push
            p.accept(IncomingMessage(CaptureSource.SMS, "BDC", sms.replace(" في 23:04 يوم 10/26/26", ""), at + 4 * 60_000))
            assertEquals(listOf("SMS"), db.parsedOutbox().all().map { it.source })
        }

    @Test
    fun `REQ-SMS-090 migration 7 to 8 keeps every v7 row, gives defaults and backfills the kind of queued records`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val raw = schemaDb(context, "migration78.db", 7)
            raw.execSQL(
                "INSERT INTO captured_tx (localId, expenseId, amountMinor, currency, label, source, templateId, capturedAtMillis) " +
                    "VALUES ('c1', NULL, 100, 'EGP', 'InstaPay', 'NOTIFICATION', NULL, 5000)",
            )
            raw.execSQL(
                "INSERT INTO captured_tx (localId, expenseId, amountMinor, currency, label, source, templateId, capturedAtMillis) " +
                    "VALUES ('c2', 'e-9', 700, 'EGP', 'X', 'SMS', NULL, 6000)",
            )
            raw.execSQL(
                "INSERT INTO parsed_outbox (localId, source, kind, amountMinor, currency, occurredAtMillis, instrumentLast4, senderId, " +
                    "institution, paymentMethod, merchant, attempts, failed, createdAtMillis) " +
                    "VALUES ('c1', 'NOTIFICATION', 'INCOME', 100, 'EGP', 4000, '1094', 'instapay', 'InstaPay', " +
                    "'INSTAPAY', NULL, 2, 0, 5000)",
            )
            raw.execSQL(
                "INSERT INTO outbox (clientId, amountMinor, currency, occurredAtMillis, categoryId, accountId, notes, " +
                    "attempts, failed, createdAtMillis) VALUES ('m1', 2500, 'EGP', 7000, NULL, NULL, NULL, 0, 0, 7000)",
            )
            raw.execSQL(
                "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, " +
                    "failed, source) VALUES ('x1', 900, 'EGP', 8000, 'Shop', NULL, NULL, NULL, 0, 0, 'MANUAL')",
            )
            raw.version = 7
            raw.close()
            val migrated =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, "migration78.db")
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                    .allowMainThreadQueries()
                    .build()
            try {
                val queued = migrated.captured().find("c1")!!
                assertEquals("INCOME", queued.kind)
                assertEquals("1094", queued.instrumentLast4)
                assertEquals(5000L, queued.receivedAtMillis)
                assertFalse(queued.paired)
                assertFalse(queued.undoRequested)
                val sent = migrated.captured().find("c2")!!
                assertNull("an uploaded record stays unmatched", sent.kind)
                assertEquals("e-9", sent.expenseId)
                val out = migrated.parsedOutbox().all().single()
                assertEquals(2, out.attempts)
                assertEquals(0L, out.claimedAtMillis)
                assertEquals(1, migrated.outbox().pending().size)
                val expense =
                    migrated
                        .expenses()
                        .observeAll()
                        .first()
                        .single()
                assertEquals("EXPENSE", expense.type)
                assertEquals("CONFIRMED", expense.status)
            } finally {
                migrated.close()
            }
        }

    @Test
    fun `REQ-SMS-093 list rows say what kind of money movement they are, not just their review state`() {
        fun label(
            type: String,
            status: String,
        ) = kindLabelRes(
            toRows(
                listOf(ExpenseEntity("a", 6_000_000, "EGP", 1L, "X", null, null, null, false, type = type, status = status)),
                emptyList(),
            ).single(),
        )
        assertEquals(R.string.expense_pending_review, label("EXPENSE", "PENDING_REVIEW"))
        assertEquals(R.string.income_pending_review, label("INCOME", "PENDING_REVIEW"))
        assertEquals(R.string.income_received, label("INCOME", "CONFIRMED"))
        assertEquals(R.string.transfer_label, label("TRANSFER", "CONFIRMED"))
        assertNull(label("EXPENSE", "CONFIRMED"))
    }

    @Test
    fun `REQ-SMS-092 totals count only spending - income and transfers are listed but not added`() =
        runTest {
            db.expenses().upsert(
                listOf(
                    ExpenseEntity("e1", 5_000, "EGP", 1_000L, "Shop", null, null, null, pending = false),
                    ExpenseEntity("t1", 75_000, "EGP", 2_000L, "InstaPay", null, null, null, pending = false, type = "TRANSFER"),
                    ExpenseEntity("i1", 100, "EGP", 3_000L, "Bank", null, null, null, pending = false, type = "INCOME"),
                ),
            )
            assertEquals(5_000L, db.expenses().observeTotal(0L, 10_000L).first())
            assertEquals(
                3,
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .size,
            )
        }
}
