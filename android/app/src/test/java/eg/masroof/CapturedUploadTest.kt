package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The owner's real case (1.00 EGP InstaPay transfer, announced by a bank SMS and an InstaPay notification): the real pipeline,
 * the real uploader and the real Retrofit client against a local server that applies the rules of ParsedTransactionService.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedUploadTest {
    private lateinit var db: AppDatabase
    private val bodies = mutableListOf<String>()
    private val problems = mutableListOf<String>()
    private val clock = Clock.fixed(Instant.parse("2026-10-26T21:05:00Z"), ZoneOffset.UTC)
    private val scheduler = FakeSmsScheduler()
    private val sms =
        "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 " +
            "رقم المعاملة bda86be1 للمزيد أتصل ب 16990"
    private val push = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
    private val at = Instant.parse("2026-10-26T21:04:10Z").toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The server as a network interceptor: it sees exactly the bytes Retrofit sends. */
    private fun fakeServer(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                val body = buffer.readUtf8()
                bodies += body
                val problem = ServerRules.check(body, clock.instant())
                if (problem != null) problems += problem
                val answer =
                    if (problem == null) {
                        """{"rawMessageId":"${UUID.randomUUID()}","expenseId":"${UUID.randomUUID()}","outcome":"ACCEPTED"}"""
                    } else {
                        """{"type":"about:blank","title":"$problem","status":422}"""
                    }
                okhttp3.Response
                    .Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(if (problem == null) 202 else 422)
                    .message("x")
                    .body(answer.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private fun pipeline() = SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey)

    private fun uploader(): SmsUploader {
        val settings = FakeSettings()
        kotlinx.coroutines.runBlocking {
            settings.update { it.copy(serverUrl = "http://masroof.test/") }
        }
        val session = TestAuth.manager(store = FakeSessionStore(LoadedSession.Found(session())), settings = settings)
        val remote = ApiRemoteSource(settings, fakeServer(), session)
        val device =
            object : DeviceIdProvider {
                override fun get() = "11111111-1111-1111-1111-111111111111"
            }
        return SmsUploader(db, remote, device, clock, MemoryGate())
    }

    @Test
    fun `REQ-SMS-003 REQ-AND-135 an InstaPay transfer announced by the SMS and by the app becomes local entries and no request is made`() =
        runTest {
            val p = pipeline()
            p.accept(IncomingMessage(CaptureSource.SMS, "BDC", sms, at))
            // received more than five minutes apart (pairing is by receive time): two entries
            p.accept(IncomingMessage(CaptureSource.NOTIFICATION, "com.egyptianbanks.instapay", push, at + 6 * 60_000))
            assertEquals(2, db.expenses().all().size)
            assertEquals(2, db.sources().all().size)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.captured().all().all { it.expenseId != null })
            // the same real client that the frozen uploader would use saw nothing: no bytes were sent
            assertTrue("a request was made: ${bodies.size}", bodies.isEmpty())
            assertEquals(0, scheduler.uploads)
        }

    @Test
    fun `REQ-SMS-003 the frozen uploader still sends leftover rows exactly as the server accepts them`() =
        runTest {
            db.parsedOutbox().insert(legacyOutboxRow("a".repeat(64), source = "SMS", kind = "INCOME", last4 = "1094", merchant = null))
            db.parsedOutbox().insert(
                legacyOutboxRow("b".repeat(64), source = "NOTIFICATION", kind = "INCOME", last4 = null, merchant = null),
            )
            val report = uploader().uploadPending()
            assertTrue("server refused: $problems", problems.isEmpty())
            assertEquals(2, report.sent)
            assertTrue(db.parsedOutbox().all().isEmpty())
        }
}

/** The validation of ParsedTransactionService.java, line by line, for a request body. */
internal object ServerRules {
    private val localId = Regex("^[0-9a-f]{64}$")
    private val last4 = Regex("^[0-9]{4}$")
    private val sender = Regex("^[A-Za-z][A-Za-z._&-]{0,19}$")
    private val institution = Regex("^[\\p{L} .&-]{1,40}$")
    private val kinds = setOf("PURCHASE", "FEE", "TRANSFER_OUT", "TRANSFER_IN", "INCOME", "REFUND", "CASH_WITHDRAWAL")
    private val methods = setOf("CARD", "WALLET", "INSTAPAY", "BANK_TRANSFER", "UNKNOWN")
    private val allowed =
        setOf(
            "deviceId",
            "localId",
            "source",
            "kind",
            "amountMinor",
            "currency",
            "occurredAt",
            "instrumentLast4",
            "senderId",
            "institution",
            "paymentMethod",
            "merchant",
        )

    @Suppress("CyclomaticComplexMethod")
    fun check(
        body: String,
        now: Instant,
    ): String? {
        val o = Json.parseToJsonElement(body).jsonObject

        fun s(k: String) = o[k]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content
        return when {
            !allowed.containsAll(o.keys) -> "unknown property"
            s("source") !in setOf("SMS", "NOTIFICATION") -> "source"
            s("localId")?.let { localId.matches(it) } != true -> "localId"
            (s("amountMinor")?.toLong() ?: 0L) < 1 -> "amountMinor"
            s("currency") != "EGP" -> "currency"
            Instant.parse(s("occurredAt")!!).isAfter(now.plusSeconds(86_400)) -> "occurredAt"
            s("instrumentLast4")?.let { !last4.matches(it) } == true -> "instrumentLast4"
            s("senderId")?.let { sender.matches(it) } != true -> "senderId"
            s("institution")?.let { !institution.matches(it) } == true -> "institution"
            s("paymentMethod")?.let { it !in methods } == true -> "paymentMethod"
            s("merchant")?.let { m -> m.any { it.isDigit() } || m.length > 40 } == true -> "merchant"
            s("kind") !in kinds -> "kind"
            else -> null
        }
    }
}
