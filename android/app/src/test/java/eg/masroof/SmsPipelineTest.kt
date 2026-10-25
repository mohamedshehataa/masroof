package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.ProblemInfo
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.data.remote.apiJson
import eg.masroof.data.remote.classifySmsStatus
import eg.masroof.data.remote.parsedRequest
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.NotificationHandoff
import eg.masroof.ingest.NotificationSettings
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.SenderPolicy
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class FakeSmsScheduler : SmsWorkScheduler {
    val intake = mutableListOf<IncomingMessage>()
    var uploads = 0

    override fun enqueueIntake(message: IncomingMessage) {
        intake += message
    }

    override fun scheduleUpload() {
        uploads++
    }

    override fun scheduleUploadRetry(
        delaySeconds: Long,
        attempt: Int,
    ) = Unit
}

internal class MemorySettings(
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

internal class MemoryNotifications(
    var on: Boolean = true,
    private val pkgs: MutableSet<String> = mutableSetOf(NotificationApps.INSTAPAY_CANDIDATE),
) : NotificationSettings {
    override fun enabled() = on

    override fun setEnabled(on: Boolean) {
        this.on = on
    }

    override fun packages(): Set<String> = pkgs

    override fun isMonitored(pkg: String) = pkg in pkgs

    override fun addPackage(pkg: String) {
        pkgs += pkg
    }

    override fun removePackage(pkg: String) {
        pkgs -= pkg
    }
}

class SenderPolicyTest {
    @Test
    fun `REQ-SMS-040 default banks and wallets are allowed by normalized prefix`() {
        val policy = SenderPolicy()
        val allowed =
            listOf("CIB", "CIB-Bank", "cibeg", "NBE", "BanqueMisr", "BM", "Vodafone Cash", "VF-Cash", "e&", "InstaPay", "valU", "Telda")
        for (ok in allowed) assertTrue(ok, policy.isAllowed(ok))
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

    @Test
    fun `REQ-NTF-014 notification capture is off by default, monitors InstaPay and accepts only valid package names`() {
        val s = PrefsNotificationSettings(prefs)
        assertFalse(s.enabled())
        assertTrue(s.isMonitored(NotificationApps.INSTAPAY_CANDIDATE))
        s.addPackage("not a package")
        s.addPackage("com.example.bank")
        assertTrue(s.isMonitored("com.example.bank"))
        assertFalse(s.isMonitored("not a package"))
        s.removePackage(NotificationApps.INSTAPAY_CANDIDATE)
        assertFalse(s.isMonitored(NotificationApps.INSTAPAY_CANDIDATE))
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
    fun `REQ-SMS-030 a plain OTP from an allowlisted sender never reaches WorkManager`() {
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
}

class NotificationHandoffTest {
    private val scheduler = FakeSmsScheduler()

    @Test
    fun `REQ-NTF-010 only enabled and monitored packages are handed over, with title and text joined`() {
        val settings = MemoryNotifications(on = false)
        val handoff = NotificationHandoff(settings, scheduler)
        handoff.handOff(NotificationApps.INSTAPAY_CANDIDATE, "InstaPay", "You sent EGP 250.00 to Sara", 1_000L)
        assertTrue("off by default", scheduler.intake.isEmpty())
        settings.on = true
        handoff.handOff("com.other.app", "x", "You sent EGP 250.00 to Sara", 1_000L)
        assertTrue("other packages are never read", scheduler.intake.isEmpty())
        handoff.handOff(NotificationApps.INSTAPAY_CANDIDATE, "InstaPay", "You sent EGP 250.00 to Sara", 1_000L)
        assertEquals(CaptureSource.NOTIFICATION, scheduler.intake.single().source)
        assertEquals("InstaPay\nYou sent EGP 250.00 to Sara", scheduler.intake.single().text)
    }

    @Test
    fun `REQ-NTF-012 a notification OTP is dropped before WorkManager`() {
        NotificationHandoff(MemoryNotifications(), scheduler)
            .handOff(NotificationApps.INSTAPAY_CANDIDATE, "InstaPay", "Your OTP is 556677", 1_000L)
        assertTrue(scheduler.intake.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsOutboxTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val scheduler = FakeSmsScheduler()
    private val settings = MemorySettings()
    private val notifications = MemoryNotifications()
    private val remote = ScriptedRemote()
    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }

    private class ScriptedRemote : ParsedRemote {
        var next: SmsUploadResult = SmsUploadResult.Sent
        val sent = mutableListOf<ParsedOutboxEntity>()

        override suspend fun uploadParsed(
            deviceId: String,
            item: ParsedOutboxEntity,
        ): SmsUploadResult {
            sent += item
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

    private val pipeline get() = SmsPipeline(db, settings, notifications, scheduler, clock)
    private val uploader get() = SmsUploader(db, remote, device, clock)

    private fun sms(
        body: String,
        sender: String = "CIB",
        at: Long = 1_000L,
    ) = IncomingMessage(CaptureSource.SMS, sender, body, at)

    private val purchase = "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"

    @Test
    fun `REQ-SMS-060 a parsed SMS is queued once as a typed record and uploading removes it`() =
        runTest {
            assertEquals(Disposition.QUEUED, pipeline.accept(sms(purchase)))
            assertEquals(Disposition.QUEUED, pipeline.accept(sms(purchase)))
            assertEquals(1, db.parsedOutbox().all().size)
            assertEquals(2, scheduler.uploads)
            val report = uploader.uploadPending()
            assertEquals(1, report.sent)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals(9_900L, remote.sent.single().amountMinor)
            assertEquals("PHARMACY", remote.sent.single().merchant)
        }

    @Test
    fun `REQ-SMS-062 secrets never reach the outbox or the wire - the serialized request carries only typed fields`() =
        runTest {
            val secrets =
                listOf(
                    "Your OTP for purchase of EGP 500.00 at AMAZON is 889900, do not share it with anyone",
                    "Purchase of EGP 500.00 at AMAZON. Verification value 889900",
                    "كود البطاقة 4821",
                    "تم خصم 500 جنيه لدى امازون. الرقم السري الجديد لبطاقتك 4821 لا تشارك",
                    "Code ****4821",
                    "Code 22/09/88",
                    "Auth code:551234 for purchase of EGP 500.00 at AMAZON",
                )
            for (s in secrets) pipeline.accept(sms(s))
            // genuine messages that carry codes upload structured records without the codes
            pipeline.accept(sms("Purchase of EGP 250.00 at CARREFOUR on 12/03/2026 14:05 card ending 1234. Auth code 123456"))
            uploader.uploadPending()
            assertTrue(db.parsedOutbox().all().isEmpty())
            val wire = remote.sent.map { apiJson.encodeToString(parsedRequest(device.get(), it)) }
            assertEquals(1, wire.size)
            for (json in wire) {
                for (secret in listOf("889900", "4821", "551234", "22/09", "123456", "Auth", "OTP")) {
                    assertFalse("$secret on the wire: $json", json.contains(secret))
                }
                assertFalse("no text field exists", json.contains("\"body\"") || json.contains("\"text\""))
            }
            // the secret-looking purchases stay on the phone only, masked
            val review = db.reviews().all()
            assertTrue("only money-like secrets are kept, masked", review.size in 1..secrets.size)
            for (r in review) assertFalse(r.text, r.text.contains("889900") || r.text.contains("4821"))
        }

    @Test
    fun `REQ-SMS-031 an OTP, a promo and a non-allowlisted sender never reach the outbox or the review list`() =
        runTest {
            assertEquals(Disposition.DROPPED, pipeline.accept(sms("Your OTP is 482915. Do not share it with anyone.")))
            assertEquals(Disposition.DROPPED, pipeline.accept(sms("Get 20% off at Noon this weekend! Offer ends soon")))
            assertEquals(Disposition.DROPPED, pipeline.accept(sms(purchase, sender = "Pizza")))
            assertEquals(Disposition.DROPPED, pipeline.accept(sms("Your card ending 1234 transaction of EGP 200.00 was declined")))
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-063 an unknown template stays on the phone in the review list and is cleared by dismiss`() =
        runTest {
            val text = "Welcome to our bank, your new offer code card activation is ready"
            assertEquals(Disposition.REVIEW, pipeline.accept(sms("Welcome to CIB: statement notice 2026")))
            assertEquals(Disposition.REVIEW, pipeline.accept(sms("Welcome to CIB: statement notice 2026")))
            assertTrue(text.isNotEmpty())
            val items = db.reviews().all()
            assertEquals("deduplicated on the local id", 1, items.size)
            assertEquals("no_template", items.single().reason)
            assertTrue(db.parsedOutbox().all().isEmpty())
            db.reviews().delete(items.single().id)
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-063 old review items are purged after 30 days`() =
        runTest {
            pipeline.accept(sms("Welcome to CIB: nothing to parse here", at = 1L))
            val later = Clock.fixed(Instant.parse("2026-12-30T09:00:00Z"), ZoneOffset.UTC)
            SmsPipeline(db, settings, notifications, scheduler, later).accept(sms("Another unparsed note from CIB", at = 2L))
            assertEquals(1, db.reviews().all().size)
        }

    @Test
    fun `REQ-NTF-013 an allowlisted InstaPay notification becomes a NOTIFICATION record`() =
        runTest {
            val msg =
                IncomingMessage(
                    CaptureSource.NOTIFICATION,
                    NotificationApps.INSTAPAY_CANDIDATE,
                    "InstaPay\nYou sent EGP 250.00 to Sara Ahmed",
                    5_000L,
                )
            assertEquals(Disposition.QUEUED, pipeline.accept(msg))
            uploader.uploadPending()
            val sent = remote.sent.single()
            assertEquals("NOTIFICATION", sent.source)
            assertEquals("TRANSFER_OUT", sent.kind)
            assertEquals("INSTAPAY", sent.paymentMethod)
            assertEquals("Sara Ahmed", sent.merchant)
            notifications.on = false
            assertEquals(Disposition.DROPPED, pipeline.accept(msg.copy(receivedAtMillis = 6_000L)))
        }

    @Test
    fun `REQ-SMS-061 offline keeps the row and asks for a backoff retry, 5xx too`() =
        runTest {
            pipeline.accept(sms(purchase))
            remote.next = SmsUploadResult.Retry(null)
            val report = uploader.uploadPending()
            assertTrue(report.retry)
            assertEquals(
                1,
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .attempts,
            )
        }

    @Test
    fun `REQ-SMS-061 a permanent rejection fails the row, an ambiguous answer fails it after five tries`() =
        runTest {
            pipeline.accept(sms(purchase))
            remote.next = SmsUploadResult.Rejected
            assertEquals(1, uploader.uploadPending().rejected)
            assertTrue(
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .failed,
            )
            assertTrue(db.parsedOutbox().pending().isEmpty())

            pipeline.accept(sms("Purchase of EGP 10.00 at KIOSK", at = 2_000L))
            remote.next = SmsUploadResult.RetryLimited
            repeat(5) { uploader.uploadPending() }
            assertEquals(2, db.parsedOutbox().all().count { it.failed })
        }

    @Test
    fun `REQ-SMS-061 a 401 keeps the row queued and does not spin`() =
        runTest {
            pipeline.accept(sms(purchase))
            remote.next = SmsUploadResult.Unauthorized
            val report = uploader.uploadPending()
            assertFalse(report.retry)
            assertEquals(1, db.parsedOutbox().pending().size)
        }

    @Test
    fun `REQ-SMS-061 failed rows are purged after seven days`() =
        runTest {
            pipeline.accept(sms(purchase))
            db.parsedOutbox().markFailed(
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .localId,
            )
            val later = Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC)
            SmsUploader(db, remote, device, later).uploadPending()
            assertEquals(1, db.parsedOutbox().all().size)
            val muchLater = Clock.fixed(Instant.parse("2026-11-30T09:00:00Z"), ZoneOffset.UTC)
            SmsUploader(db, remote, device, muchLater).uploadPending()
            assertTrue(db.parsedOutbox().all().isEmpty())
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
        assertEquals(SmsUploadResult.Rejected, classifySmsStatus(413))
        val inProgress = ProblemInfo("https://masroof.app/problems/idempotency-in-progress", null)
        assertEquals(SmsUploadResult.RetryLimited, classifySmsStatus(409, inProgress))
    }
}

class NoRawTextUploadTest {
    @Test
    fun `REQ-SMS-062 the app never calls the raw-text ingest endpoints`() {
        val offenders =
            File("src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { f ->
                    val text = f.readText()
                    text.contains(".ingestSms(") ||
                        text.contains(".ingestNotification(") ||
                        text.contains("IngestSmsRequest") ||
                        text.contains("IngestNotificationRequest")
                }.map { it.name }
                .toList()
        assertTrue("raw-text upload referenced from $offenders", offenders.isEmpty())
    }

    @Test
    fun `REQ-SMS-062 the wire request type has no free text field except the digit-free merchant label`() {
        val names =
            eg.masroof.api.models.IngestParsedTransactionRequest::class.java.declaredFields
                .map { it.name }
                .toSet()
        val stringFields = setOf("localId", "currency", "senderId", "instrumentLast4", "institution", "merchant")
        assertTrue(names.containsAll(stringFields))
        assertFalse(names.any { it.equals("body", true) || it.equals("text", true) || it.equals("message", true) })
    }
}
