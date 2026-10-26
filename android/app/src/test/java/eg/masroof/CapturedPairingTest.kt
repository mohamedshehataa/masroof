package eg.masroof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MIGRATION_7_8
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.ExpenseDeleter
import eg.masroof.data.remote.NoExpenseDeleter
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class TickClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

private fun v7Database(
    context: Context,
    name: String,
): SQLiteDatabase {
    context.deleteDatabase(name)
    val schema =
        Json
            .parseToJsonElement(File("schemas/eg.masroof.data.db.AppDatabase/7.json").readText())
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

/** The lost-answer, in-flight, repeated-message, old-queue and undo cases of one transfer announced by two channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedPairingTest {
    private lateinit var db: AppDatabase
    private val start = Instant.parse("2026-10-26T21:05:00Z")
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

    private fun pipelineOn(clock: Clock) =
        SmsPipeline(
            db,
            MemorySettings(),
            MemoryNotifications(),
            FakeSmsScheduler(),
            clock,
            MemoryGate(),
            FixedKey,
        )

    private fun bankSms() = IncomingMessage(CaptureSource.SMS, "BDC", sms, at)

    private fun appPush(atMillis: Long = at + 3_000) =
        IncomingMessage(
            CaptureSource.NOTIFICATION,
            "com.egyptianbanks.instapay",
            push,
            atMillis,
        )

    private class RecordingRemote(
        val answer: suspend (ParsedOutboxEntity) -> SmsUploadResult,
    ) : ParsedRemote {
        val requests = mutableListOf<ParsedOutboxEntity>()

        override suspend fun uploadParsed(
            deviceId: String,
            item: ParsedOutboxEntity,
        ): SmsUploadResult {
            requests += item
            return answer(item)
        }

        /** What the server holds: one record per distinct local id (it dedups on (device, local id)). */
        val serverRecords get() = requests.map { it.localId }.distinct().size
    }

    @Test
    fun `REQ-SMS-090 a lost server answer does not let the SMS twin replace the push - the server still holds one record`() =
        runTest {
            val clock = TickClock(start)
            val p = pipelineOn(clock)
            p.accept(appPush())
            val lost = RecordingRemote { SmsUploadResult.Retry(null) }
            assertTrue(SmsUploader(db, lost, device, clock, MemoryGate()).uploadPending().retry)
            clock.now = clock.now.plusSeconds(30)
            assertEquals(Disposition.QUEUED, p.accept(bankSms()))
            val retried = RecordingRemote { SmsUploadResult.Sent("expense-1") }
            SmsUploader(db, retried, device, clock, MemoryGate()).uploadPending()
            assertEquals(
                "the retry is the same local id, idempotent on the server",
                lost.requests.single().localId,
                retried.requests.single().localId,
            )
            assertEquals(1, db.captured().all().size)
            assertEquals(
                "expense-1",
                db
                    .captured()
                    .all()
                    .single()
                    .expenseId,
            )
        }

    @Test
    fun `REQ-SMS-090 a request that outlives the claim time still cannot be replaced by the twin`() =
        runTest {
            val clock = TickClock(start)
            val p = pipelineOn(clock)
            p.accept(appPush())
            val remote =
                RecordingRemote {
                    clock.now = clock.now.plusSeconds(400) // longer than the claim time
                    assertEquals(Disposition.QUEUED, p.accept(bankSms()))
                    SmsUploadResult.Sent("expense-1")
                }
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            assertEquals(1, remote.serverRecords)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals(
                "expense-1",
                db
                    .captured()
                    .all()
                    .single()
                    .expenseId,
            )
        }

    @Test
    fun `REQ-SMS-090 the reverse race - the push accepted while the SMS upload is on the wire - leaves one record`() =
        runTest {
            val clock = TickClock(start)
            val p = pipelineOn(clock)
            p.accept(bankSms())
            val remote =
                RecordingRemote {
                    p.accept(appPush())
                    SmsUploadResult.Sent("expense-1")
                }
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            assertEquals(1, remote.serverRecords)
            assertEquals(
                "expense-1",
                db
                    .captured()
                    .all()
                    .single()
                    .expenseId,
            )
        }

    @Test
    fun `REQ-SMS-090 accepting the same message again after it was skipped as the duplicate makes no second record`() =
        runTest {
            val p = pipelineOn(TickClock(start))
            p.accept(bankSms())
            p.accept(appPush())
            p.accept(appPush()) // WorkManager runs the same intake job again
            assertEquals(1, db.parsedOutbox().all().size)
            assertEquals(1, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-090 accepting the replaced message again after the richer one took its place makes no second record`() =
        runTest {
            val p = pipelineOn(TickClock(start))
            p.accept(appPush())
            p.accept(bankSms())
            p.accept(appPush())
            p.accept(bankSms())
            assertEquals(listOf("SMS"), db.parsedOutbox().all().map { it.source })
            assertEquals(1, db.captured().all().size)
        }

    private fun seedOwnerPair(
        name: String,
        smsAttempts: Int,
        pushAttempts: Int,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = v7Database(context, name)

        fun queued(
            id: String,
            source: String,
            last4: String?,
            attempts: Int,
            capturedAt: Long,
        ) {
            raw.execSQL(
                "INSERT INTO captured_tx (localId, expenseId, amountMinor, currency, label, source, templateId, capturedAtMillis) " +
                    "VALUES ('$id', NULL, 100, 'EGP', 'Bank', '$source', NULL, $capturedAt)",
            )
            raw.execSQL(
                "INSERT INTO parsed_outbox (localId, source, kind, amountMinor, currency, occurredAtMillis, instrumentLast4, senderId, " +
                    "institution, paymentMethod, merchant, attempts, failed, createdAtMillis) " +
                    "VALUES ('$id', '$source', 'INCOME', 100, 'EGP', $capturedAt, ${last4?.let { "'$it'" }}, 'BDC', 'Bank', " +
                    "'INSTAPAY', NULL, $attempts, 0, $capturedAt)",
            )
        }
        queued("s1", "SMS", "1094", smsAttempts, at)
        queued("n1", "NOTIFICATION", null, pushAttempts, at + 3_000)
        raw.version = 7
        raw.close()
        db.close()
        db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_7_8)
                .allowMainThreadQueries()
                .build()
    }

    @Test
    fun `REQ-SMS-090 the owner's two records queued by the old version are paired before the first upload - one server record`() =
        runTest {
            seedOwnerPair("owner78.db", smsAttempts = 0, pushAttempts = 0)
            val remote = RecordingRemote { SmsUploadResult.Sent("expense-1") }
            val report = SmsUploader(db, remote, device, TickClock(start), MemoryGate()).uploadPending()
            assertEquals(1, report.sent)
            assertEquals("the richer SMS is kept", listOf("s1"), remote.requests.map { it.localId })
            assertEquals(listOf("s1"), db.captured().all().map { it.localId })
        }

    @Test
    fun `REQ-SMS-090 of two old queued records the one already tried stays and the other is dropped`() =
        runTest {
            seedOwnerPair("owner78b.db", smsAttempts = 3, pushAttempts = 0)
            val remote = RecordingRemote { SmsUploadResult.Sent("expense-1") }
            SmsUploader(db, remote, device, TickClock(start), MemoryGate()).uploadPending()
            assertEquals(listOf("s1"), remote.requests.map { it.localId })
            assertEquals(listOf("s1"), db.captured().all().map { it.localId })
        }

    @Test
    fun `REQ-SMS-090 a row held by a live claim is not skipped silently - a retry is scheduled for when the claim runs out`() =
        runTest {
            val clock = TickClock(start)
            pipelineOn(clock).accept(appPush())
            val id =
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .localId
            // an upload killed 10 seconds ago left its claim
            db.parsedOutbox().claim(id, clock.millis() - 10_000, clock.millis())
            val remote = RecordingRemote { SmsUploadResult.Sent("expense-1") }
            val uploader = SmsUploader(db, remote, device, clock, MemoryGate())
            val first = uploader.uploadPending()
            assertEquals(0, first.sent)
            assertTrue(first.retry)
            assertTrue("waits for the rest of the claim", (first.retryAfterSeconds ?: 0) in 280..300)
            clock.now = clock.now.plusSeconds(301)
            assertEquals(1, uploader.uploadPending().sent)
        }

    @Test
    fun `REQ-SMS-090 Undo of a queued row that an upload already started is remembered and goes through the server`() =
        runTest {
            val clock = TickClock(start)
            pipelineOn(clock).accept(appPush())
            val id =
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .localId
            SmsUploader(db, RecordingRemote { SmsUploadResult.Retry(null) }, device, clock, MemoryGate()).uploadPending()
            val deleted = mutableListOf<String>()
            val deleter =
                object : ExpenseDeleter {
                    override suspend fun deleteExpense(id: String): Boolean {
                        deleted += id
                        return true
                    }
                }
            val result = CapturedActions(db, deleter, clock).undo(id)
            assertTrue(result.deferred)
            assertEquals("the row stays, so the retry is idempotent", 1, db.parsedOutbox().all().size)
            SmsUploader(db, RecordingRemote { SmsUploadResult.Sent("expense-1") }, device, clock, MemoryGate(), deleter).uploadPending()
            assertEquals(listOf("expense-1"), deleted)
        }

    @Test
    fun `REQ-SMS-090 Undo of a queued row that was never sent just forgets it`() =
        runTest {
            val clock = TickClock(start)
            pipelineOn(clock).accept(appPush())
            val id =
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .localId
            val result = CapturedActions(db, NoExpenseDeleter, clock).undo(id)
            assertTrue(result.removed)
            assertFalse(result.deferred)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
        }

    private fun expensePage(
        type: String,
        n: Int,
    ) = """{"items":[{"id":"00000000-0000-4000-8000-00000000000$n","householdId":"00000000-0000-4000-8000-000000000001",""" +
        """"type":"$type","amount":{"amountMinor":75000,"currency":"EGP"},"occurredAt":"2026-10-26T20:00:00Z",""" +
        """"status":"CONFIRMED","confidence":1,"paymentMethod":"INSTAPAY","tags":[],"isRecurring":false,"splitMode":"NONE",""" +
        """"items":[],"sources":[],"splits":[],"createdAt":"2026-10-26T20:00:00Z","updatedAt":"2026-10-26T20:00:00Z"}],""" +
        """"nextCursor":null}"""

    private fun remoteWith(
        asked: MutableList<String>,
        failOn: String? = null,
    ): ApiRemoteSource {
        val settings = FakeSettings()
        kotlinx.coroutines.runBlocking { settings.update { it.copy(serverUrl = "http://masroof.test/") } }
        val session = TestAuth.manager(store = FakeSessionStore(LoadedSession.Found(session())), settings = settings)
        val server =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val type = chain.request().url.queryParameter("type") ?: ""
                    asked += type
                    if (type == failOn) throw IOException("offline")
                    okhttp3.Response
                        .Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("ok")
                        .body(expensePage(type, asked.size).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        return ApiRemoteSource(settings, server, session)
    }

    @Test
    fun `REQ-SMS-092 the refresh fetches expenses, income and transfers and keeps the transfer type`() =
        runTest {
            val asked = mutableListOf<String>()
            val rows = remoteWith(asked).fetchExpenses()
            assertEquals(listOf("EXPENSE", "INCOME", "TRANSFER"), asked)
            assertEquals(listOf("EXPENSE", "INCOME", "TRANSFER"), rows.map { it.type })
            assertEquals(75_000L, rows.last().amountMinor)
        }

    @Test
    fun `REQ-SMS-092 a failed refresh leaves the cached list intact`() =
        runTest {
            db.expenses().upsert(listOf(ExpenseEntity("cached", 5_000, "EGP", 1_000L, "Shop", null, null, null, pending = false)))
            val repo = DefaultExpenseRepository(db, remoteWith(mutableListOf(), failOn = "TRANSFER"), TickClock(start))
            assertEquals(RefreshOutcome.Offline, repo.refresh())
            assertEquals(
                listOf("cached"),
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .map { it.id },
            )
        }
}
