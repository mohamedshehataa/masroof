package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.TemplateLearning
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.TemplateShape
import eg.masroof.ui.viewmodel.ReviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Round 15: the confidence gate and on-device template learning. A message outside the proven families is a review item with
 * a suggestion; saving it teaches the phone that format; the next message of that sender in exactly that format is recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsLearningTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val scheduler = FakeSmsScheduler()
    private val settings = MemorySettings()
    private val notifications = MemoryNotifications()
    private val gate = MemoryGate()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pipeline get() = SmsPipeline(db, settings, notifications, scheduler, clock, gate, FixedKey)
    private val viewModel get() = ReviewViewModel(db, Dispatchers.Unconfined, scheduler, clock)

    private fun sms(
        body: String,
        sender: String = "CIB",
        at: Long = 1_000L,
    ) = IncomingMessage(CaptureSource.SMS, sender, body, at)

    private suspend fun saveFirstReview(): String {
        val id =
            db
                .reviews()
                .all()
                .first()
                .id
        viewModel.saveNow(id)
        return id
    }

    @Test
    fun `REQ-SMS-070 a format outside the proven families is a review item with a suggestion and nothing is queued`() =
        runTest {
            val text = "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"
            assertEquals(Disposition.REVIEW, pipeline.accept(sms(text)))
            assertTrue(db.parsedOutbox().all().isEmpty())
            val item = db.reviews().all().single()
            assertEquals("unconfirmed", item.reason)
            assertEquals("PURCHASE", item.sugKind)
            assertEquals(9_900L, item.sugAmountMinor)
            assertEquals("PHARMACY", item.sugMerchant)
            val outcome = MessageParser.parse(sms(text))
            assertTrue(outcome is ParseOutcome.Review && outcome.suggestion != null)
        }

    @Test
    fun `REQ-SMS-070 saving a review item uploads the suggestion once as a normal expense and learns the format`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            val id = saveFirstReview()
            val queued = db.parsedOutbox().all().single()
            assertEquals(id, queued.localId)
            assertEquals(9_900L, queued.amountMinor)
            assertEquals("SMS", queued.source)
            assertEquals(1, scheduler.uploads)
            assertEquals(1, db.learnedTemplates().all().size)
        }

    @Test
    fun `REQ-SMS-071 confirm once and the next message in the same format is recorded`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            saveFirstReview()
            val next = pipeline.accept(sms("Purchase of EGP 45.50 at BAKERY on 13/03/2026 09:30", at = 2_000L))
            assertEquals(Disposition.QUEUED, next)
            val rows = db.parsedOutbox().all().sortedBy { it.createdAtMillis }
            assertEquals(2, rows.size)
            assertEquals(listOf(9_900L, 4_550L), rows.map { it.amountMinor }.sortedByDescending { it })
            assertTrue(db.reviews().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-071 a different sender or a changed shape still goes to review`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            saveFirstReview()
            assertEquals(
                "another sender",
                Disposition.REVIEW,
                pipeline.accept(sms("Purchase of EGP 45.50 at BAKERY on 13/03/2026 09:30", sender = "NBE", at = 3_000L)),
            )
            assertEquals(
                "a changed shape (a balance clause was added)",
                Disposition.REVIEW,
                pipeline.accept(sms("Purchase of EGP 45.50 at BAKERY on 13/03/2026 09:30. Balance EGP 800.00", at = 4_000L)),
            )
            assertEquals(
                "a different kind of message (credited, not purchase)",
                Disposition.REVIEW,
                pipeline.accept(sms("Credited EGP 45.50 at BAKERY on 13/03/2026 09:30", at = 5_000L)),
            )
        }

    @Test
    fun `REQ-SMS-071 the confirmed kind wins - a transfer format teaches TRANSFER_OUT`() =
        runTest {
            pipeline.accept(sms("Sent EGP 300.00 to SARA via bank app on 12/03/2026 14:05", sender = "QNB"))
            val item = db.reviews().all().single()
            assertNotNull(item.sugKind)
            saveFirstReview()
            assertEquals(
                item.sugKind,
                db
                    .learnedTemplates()
                    .all()
                    .single()
                    .kind,
            )
            val again = pipeline.accept(sms("Sent EGP 80.00 to OMAR via bank app on 14/03/2026 10:00", sender = "QNB", at = 2_000L))
            assertEquals(Disposition.QUEUED, again)
            assertEquals(
                item.sugKind,
                db
                    .parsedOutbox()
                    .all()
                    .maxByOrNull { it.createdAtMillis }!!
                    .kind,
            )
        }

    @Test
    fun `REQ-SMS-072 an edited amount remembers which number slot held it`() =
        runTest {
            val first = "Purchase of EGP 500.00 at SHOP. Paid EGP 120.00 after discount"
            pipeline.accept(sms(first))
            val item = db.reviews().all().single()
            assertEquals("the engine took the amount after 'Paid'", 12_000L, item.sugAmountMinor)
            viewModel.confirmEditedNow(item.id, 50_000L)
            val template = db.learnedTemplates().all().single()
            assertEquals("the first number holds the amount", 0, template.amountSlot)
            assertEquals(
                Disposition.QUEUED,
                pipeline.accept(sms("Purchase of EGP 700.00 at CAFE. Paid EGP 250.00 after discount", at = 2_000L)),
            )
            assertEquals(
                70_000L,
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-SMS-072 an edit that matches no slot keeps the engine's own pick`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            val item = db.reviews().all().single()
            TemplateLearning.learn(db, item, 1L, amountMinor = 7_777L)
            assertEquals(
                -1,
                db
                    .learnedTemplates()
                    .all()
                    .single()
                    .amountSlot,
            )
        }

    @Test
    fun `REQ-SMS-073 a secret-looking message has no shape and is never learned`() =
        runTest {
            assertNull(TemplateShape.of("Your OTP is 123456 for purchase of EGP 50.00"))
            assertNull(TemplateShape.of("Purchase of EGP 90.00 at AMAZON. Verification value 889900"))
            pipeline.accept(sms("Your OTP for purchase of EGP 500.00 at AMAZON is 889900, do not share it with anyone"))
            val secretItem = db.reviews().all().single()
            assertNull(secretItem.shape)
            assertNull(secretItem.sugKind)
            TemplateLearning.learn(db, secretItem, 1L)
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-073 a signature holds no digit and no name`() {
        val samples =
            listOf(
                "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05",
                "تم تحويل 500 جنيه الى احمد علي من حسابكم ***7781 بتاريخ 12/03/2026",
                "Transfer of EGP 1,250.50 from AHMED ALI to your account ending 7781 at 21:14",
                "CIB: You sent EGP 75.00 to Mona Adel via InstaPay",
            )
        for (text in samples) {
            val shape = TemplateShape.of(text)
            assertNotNull(text, shape)
            val signature = shape!!.signature
            assertFalse("digit in $signature", signature.any { it.isDigit() })
            for (name in listOf("pharmacy", "ahmed", "ali", "mona", "adel", "احمد", "علي", "cib")) {
                assertFalse("$name in $signature", signature.contains(name))
            }
            assertTrue(signature.contains("<N>"))
        }
    }

    @Test
    fun `REQ-SMS-074 logout and Clear forget every learned format`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            saveFirstReview()
            assertEquals(1, db.learnedTemplates().all().size)
            db.learnedTemplates().clear()
            assertEquals(Disposition.REVIEW, pipeline.accept(sms("Purchase of EGP 45.50 at BAKERY on 13/03/2026 09:30", at = 2_000L)))
            saveFirstReview()
            assertEquals(1, db.learnedTemplates().all().size)
            // the logout wipe clears every table, learned formats included
            db.clearAllTables()
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-075 a proven family is recorded without any confirmation and never learned from`() =
        runTest {
            val family = "Your card ending 4512 was charged EGP 560.00 at ZOOBA ZAMALEK"
            assertEquals(Disposition.QUEUED, pipeline.accept(sms(family)))
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.learnedTemplates().all().isEmpty())
        }
}
