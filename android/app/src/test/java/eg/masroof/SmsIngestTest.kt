package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.SmsOutboxEntity
import eg.masroof.data.remote.ProblemInfo
import eg.masroof.data.remote.SmsRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.data.remote.classifySmsStatus
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.MessageClass
import eg.masroof.ingest.OtpPromoFilter
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.SenderPolicy
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.ingest.smsId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

private fun fixtureLines(name: String) =
    File("../../fixtures/sms/$name")
        .readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }

private class FakeSmsScheduler : SmsWorkScheduler {
    val intake = mutableListOf<IncomingSms>()
    var uploads = 0

    override fun enqueueIntake(sms: IncomingSms) {
        intake += sms
    }

    override fun scheduleUpload() {
        uploads++
    }

    override fun scheduleUploadRetry(
        delaySeconds: Long,
        attempt: Int,
    ) = Unit
}

private class MemorySettings(
    private var policy: SenderPolicy = SenderPolicy(),
) : SenderSettings {
    val suggested = mutableListOf<String>()

    override fun policy() = policy

    override fun addSender(sender: String) = Unit

    override fun removeSender(sender: String) = Unit

    override fun setDefaultEnabled(
        name: String,
        enabled: Boolean,
    ) = Unit

    override fun suggestions() = suggested.toList()

    override fun suggest(sender: String) {
        suggested += sender
    }

    override fun dismissSuggestion(sender: String) = Unit
}

class OtpPromoFilterTest {
    @Test
    fun `REQ-SMS-031 every OTP fixture is classified OTP - zero false negatives`() {
        val cases = fixtureLines("negatives.jsonl").filter { it["label"]!!.jsonPrimitive.content == "OTP" }
        assertTrue(cases.size >= 10)
        for (c in cases) {
            assertEquals(c["id"]!!.jsonPrimitive.content, MessageClass.OTP, OtpPromoFilter.classify(c["body"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `REQ-SMS-032 promotions are classified PROMO`() {
        for (c in fixtureLines("negatives.jsonl").filter { it["label"]!!.jsonPrimitive.content == "PROMO" }) {
            assertEquals(c["id"]!!.jsonPrimitive.content, MessageClass.PROMO, OtpPromoFilter.classify(c["body"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `REQ-SMS-030 real transaction messages are neither OTP nor promo`() {
        val cases = fixtureLines("cases.jsonl")
        assertTrue(cases.size > 25)
        for (c in cases) {
            assertEquals(c["id"]!!.jsonPrimitive.content, MessageClass.OTHER, OtpPromoFilter.classify(c["body"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `REQ-SMS-030 Arabic-Indic digits, tatweel and diacritics do not hide an OTP`() {
        assertEquals(MessageClass.OTP, OtpPromoFilter.classify("رَمْز التحقق ٤٥٦٧٨٩"))
        assertEquals(MessageClass.OTP, OtpPromoFilter.classify("رـمز"))
        assertEquals(MessageClass.OTHER, OtpPromoFilter.classify(""))
    }
}

class SenderPolicyTest {
    @Test
    fun `REQ-SMS-040 default banks and wallets are allowed by normalized prefix`() {
        val policy = SenderPolicy()
        val allowed =
            listOf("CIB", "CIB-Bank", "cibeg", "NBE", "BanqueMisr", "BM", "Vodafone Cash", "VF-Cash", "e&", "InstaPay", "valU", "Telda")
        for (ok in allowed) {
            assertTrue(ok, policy.isAllowed(ok))
        }
    }

    @Test
    fun `REQ-SMS-040 unknown alphanumeric senders and phone numbers are not read`() {
        val policy = SenderPolicy()
        for (no in listOf("Pizza", "BMW", "19019", "", "   ")) assertFalse(no, policy.isAllowed(no))
        assertFalse(policy.isAllowed("+20 10 0000 0000"))
    }

    @Test
    fun `REQ-SMS-041 senders can be added and defaults switched off`() {
        val policy = SenderPolicy(added = setOf("MYBANK"), disabledDefaults = setOf("CIB"))
        assertTrue(policy.isAllowed("MyBank"))
        assertFalse(policy.isAllowed("CIB"))
        assertTrue(policy.isAllowed("NBE"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SenderSettingsTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t-${System.nanoTime()}", 0)

    @Test
    fun `REQ-SMS-042 a suggestion is only a name, one tap adds it and it stops being suggested`() {
        val settings = PrefsSenderSettings(prefs)
        settings.suggest("NewBank")
        settings.suggest("CIB") // already allowed: never suggested
        assertEquals(listOf("NewBank"), settings.suggestions())
        assertFalse(settings.policy().isAllowed("NewBank"))
        settings.addSender("NewBank")
        assertTrue(settings.policy().isAllowed("NewBank"))
        assertTrue(settings.suggestions().isEmpty())
        settings.removeSender("NewBank")
        assertFalse(settings.policy().isAllowed("NewBank"))
        settings.setDefaultEnabled("CIB", false)
        assertFalse(settings.policy().isAllowed("CIB"))
        settings.setDefaultEnabled("CIB", true)
        assertTrue(settings.policy().isAllowed("CIB"))
    }
}

class SmsHandoffTest {
    private val scheduler = FakeSmsScheduler()
    private val settings = MemorySettings()
    private val handoff = SmsHandoff(settings, scheduler)
    private val purchase = "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"

    @Test
    fun `REQ-SMS-020 an allowlisted message is handed to WorkManager`() {
        handoff.handOff(listOf(IncomingSms("CIB", purchase, 1_000L)))
        assertEquals(1, scheduler.intake.size)
        assertEquals("CIB", scheduler.intake[0].sender)
    }

    @Test
    fun `REQ-SMS-030 an OTP from an allowlisted sender never reaches WorkManager`() {
        handoff.handOff(listOf(IncomingSms("CIB", "Your OTP is 482915. Do not share it.", 1_000L)))
        assertTrue(scheduler.intake.isEmpty())
    }

    @Test
    fun `REQ-SMS-040 a message from a sender that is not allowlisted is not read or queued`() {
        handoff.handOff(listOf(IncomingSms("Pizza", "Your order is on the way", 1_000L)))
        assertTrue(scheduler.intake.isEmpty())
        assertTrue(settings.suggested.isEmpty())
    }

    @Test
    fun `REQ-SMS-042 an unknown money-like sender becomes a suggestion, never an upload`() {
        handoff.handOff(listOf(IncomingSms("NewBank", purchase, 1_000L), IncomingSms("19019", purchase, 1_000L)))
        assertTrue(scheduler.intake.isEmpty())
        assertEquals(listOf("NewBank"), settings.suggested)
    }

    @Test
    fun `REQ-SMS-021 the same message always gets the same id, different ones do not`() {
        val a = IncomingSms("CIB", purchase, 5_000L)
        assertEquals(smsId(a), smsId(IncomingSms("cib", purchase, 5_999L)))
        assertNotEquals(smsId(a), smsId(IncomingSms("CIB", purchase, 7_000L)))
        assertNotEquals(smsId(a), smsId(IncomingSms("CIB", "$purchase!", 5_000L)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsOutboxTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val scheduler = FakeSmsScheduler()
    private val settings = MemorySettings()
    private val remote = ScriptedSmsRemote()
    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }

    private class ScriptedSmsRemote : SmsRemote {
        var next: SmsUploadResult = SmsUploadResult.Sent
        val sent = mutableListOf<String>()

        override suspend fun uploadSms(
            deviceId: String,
            item: SmsOutboxEntity,
        ): SmsUploadResult {
            sent += item.smsId
            return next
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pipeline get() = SmsPipeline(db, settings, scheduler, clock)
    private val uploader get() = SmsUploader(db, remote, device, clock)
    private val purchase = IncomingSms("CIB", "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05", 1_000L)

    @Test
    fun `REQ-SMS-060 a filtered SMS is queued once and uploading removes it`() =
        runTest {
            assertTrue(pipeline.accept(purchase))
            assertFalse("same message twice is stored once", pipeline.accept(purchase))
            assertEquals(1, db.smsOutbox().pending().size)
            assertTrue(scheduler.uploads >= 1)
            val report = uploader.uploadPending()
            assertEquals(1, report.sent)
            assertTrue("body deleted after acknowledgement", db.smsOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-031 an OTP, a promo and a non-allowlisted sender never reach the outbox`() =
        runTest {
            assertFalse(pipeline.accept(IncomingSms("CIB", "Your OTP is 482915", 1L)))
            assertFalse(pipeline.accept(IncomingSms("CIB", "Win EGP 10,000 cashback! Terms apply", 2L)))
            assertFalse(pipeline.accept(IncomingSms("Pizza", purchase.body, 3L)))
            assertTrue(db.smsOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-061 offline keeps the row and asks for a backoff retry, 5xx too`() =
        runTest {
            pipeline.accept(purchase)
            remote.next = SmsUploadResult.Retry(afterSeconds = 120)
            val report = uploader.uploadPending()
            assertTrue(report.retry)
            assertEquals(120L, report.retryAfterSeconds)
            val row = db.smsOutbox().all().single()
            assertEquals(1, row.attempts)
            assertFalse(row.failed)
        }

    @Test
    fun `REQ-SMS-061 a permanent rejection fails the row, an ambiguous answer fails it after five tries`() =
        runTest {
            pipeline.accept(purchase)
            remote.next = SmsUploadResult.Rejected
            assertEquals(1, uploader.uploadPending().rejected)
            assertTrue(
                db
                    .smsOutbox()
                    .all()
                    .single()
                    .failed,
            )
            assertTrue(db.smsOutbox().pending().isEmpty())

            db.smsOutbox().delete(
                db
                    .smsOutbox()
                    .all()
                    .single()
                    .smsId,
            )
            pipeline.accept(purchase)
            remote.next = SmsUploadResult.RetryLimited
            repeat(5) { uploader.uploadPending() }
            assertTrue(
                db
                    .smsOutbox()
                    .all()
                    .single()
                    .failed,
            )
        }

    @Test
    fun `REQ-SMS-061 a 401 keeps the row queued and does not spin`() =
        runTest {
            pipeline.accept(purchase)
            remote.next = SmsUploadResult.Unauthorized
            val report = uploader.uploadPending()
            assertFalse(report.retry)
            assertEquals(1, db.smsOutbox().pending().size)
        }

    @Test
    fun `REQ-SMS-061 failed rows are purged after seven days so bodies do not linger`() =
        runTest {
            db.smsOutbox().insert(SmsOutboxEntity("old", "CIB", "x", 1L, failed = true, createdAtMillis = clock.millis() - 8L * 86_400_000))
            db.smsOutbox().insert(SmsOutboxEntity("new", "CIB", "x", 1L, failed = true, createdAtMillis = clock.millis() - 86_400_000))
            uploader.uploadPending()
            assertEquals(listOf("new"), db.smsOutbox().all().map { it.smsId })
        }
}

class SmsStatusClassificationTest {
    @Test
    fun `REQ-SMS-061 HTTP status maps to retry, reject or sign-in`() {
        assertEquals(SmsUploadResult.Retry(null), classifySmsStatus(503))
        assertEquals(SmsUploadResult.Retry(30), classifySmsStatus(429, retryAfterSeconds = 30))
        assertEquals(SmsUploadResult.Unauthorized, classifySmsStatus(401))
        assertEquals(SmsUploadResult.Rejected, classifySmsStatus(422))
        assertEquals(SmsUploadResult.Rejected, classifySmsStatus(400))
        val inProgress = ProblemInfo("https://masroof.app/problems/idempotency-in-progress", null)
        assertEquals(SmsUploadResult.RetryLimited, classifySmsStatus(409, inProgress))
    }
}
