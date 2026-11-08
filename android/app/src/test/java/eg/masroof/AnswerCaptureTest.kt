package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
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
import java.time.ZoneOffset

/** STORY-208: ignored cards, card aliases and a remembered "Track it" at capture time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerCaptureTest {
    private lateinit var db: AppDatabase
    private lateinit var prefs: FakeAnswerPrefs
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val purchase = "Your card ending 4512 was charged EGP 1.00 at AMAZON"
    private val atm = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 2,000.00- Your available balance EGP 5,000.00"

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        prefs = FakeAnswerPrefs()
    }

    @After
    fun tearDown() = db.close()

    private fun pipeline() =
        SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey, answers = prefs)

    private fun sms(
        body: String,
        sender: String = "CIB",
    ) = IncomingMessage(CaptureSource.SMS, sender, body, ANSWER_NOW.toEpochMilli() - 60_000)

    @Test
    fun `REQ-EXP-066 a capture from an ignored card goes to the review list marked ignored card, not to an entry`() =
        runTest {
            prefs.edit { it.copy(ignoredCards = setOf("4512")) }
            assertEquals(Disposition.REVIEW, pipeline().accept(sms(purchase)))
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            val item = db.reviews().all().single()
            assertEquals("ignored_card", item.reason)
            assertEquals("4512", item.sugLast4)
            assertFalse("the review text is built from typed fields, never the message", item.text.contains("charged"))
            assertTrue(item.text.contains("**4512"))
        }

    @Test
    fun `REQ-EXP-066 an OTP from an ignored card is still dropped by the secret filter before anything is stored`() =
        runTest {
            prefs.edit { it.copy(ignoredCards = setOf("4512")) }
            val otp = "Your OTP is 889900 for card ending 4512, do not share it"
            assertEquals(Disposition.DROPPED, pipeline().accept(sms(otp)))
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.expenses().all().isEmpty())
        }

    @Test
    fun `REQ-EXP-066 a card from another last four still becomes an entry`() =
        runTest {
            prefs.edit { it.copy(ignoredCards = setOf("9999")) }
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(purchase)))
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-EXP-066 a linked card files its captures under the account`() =
        runTest {
            db.lookups().upsertAccounts(listOf(answerAccount("cib", "CIB", last4 = "1111")))
            prefs.edit { it.copy(aliases = mapOf("4512" to "cib")) }
            pipeline().accept(sms(purchase))
            assertEquals(
                "cib",
                db
                    .expenses()
                    .all()
                    .single()
                    .accountId,
            )
        }

    @Test
    fun `REQ-EXP-067 after Track it a new ATM withdrawal is a transfer pair from the start and Undo removes both`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            val disposition = pipeline().accept(sms(atm, sender = "HSBC"))
            assertEquals(Disposition.RECORDED, disposition)
            val all = db.expenses().all()
            assertEquals(setOf("TRANSFER", "TRANSFER_IN"), all.map { it.type }.toSet())
            assertEquals(1, db.accountBalances().accounts().count { it.kind == "CASH" })
            val out = all.single { it.type == "TRANSFER" }
            assertNotNull(
                prefs.flow.value
                    .linksOf(out.id)
                    .singleOrNull(),
            )
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            CapturedActions(db, clock, answers = prefs).undo(localId)
            assertTrue(db.expenses().all().all { it.deletedAtMillis != null })
        }

    @Test
    fun `REQ-EXP-067 without the choice a withdrawal stays a withdrawal`() =
        runTest {
            pipeline().accept(sms(atm, sender = "HSBC"))
            val only = db.expenses().all().single()
            assertEquals("CASH_WITHDRAWAL", only.type)
            assertNull(prefs.flow.value.atmChoice)
            assertEquals(AnswerState(), prefs.flow.value)
        }
}
