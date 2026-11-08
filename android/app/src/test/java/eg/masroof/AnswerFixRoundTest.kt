package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerCards
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.DefaultAnswerRepository
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.settings.AnalyticsSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val CAIRO: ZoneId = ZoneId.of("Africa/Cairo")

private fun cairo(
    date: String,
    hour: Int,
): Long = ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 0), CAIRO).toInstant().toEpochMilli()

/** STORY-208 fix round 1: converted real entries, unattributed income, one ATM setting, windows, idempotence. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerFixRoundTest {
    private lateinit var db: AppDatabase
    private lateinit var prefs: FakeAnswerPrefs
    private lateinit var settings: FakeSettings
    private lateinit var repo: DefaultAnswerRepository
    private lateinit var editor: DefaultExpenseEditor
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        prefs = FakeAnswerPrefs()
        settings = FakeSettings()
        repo =
            DefaultAnswerRepository(
                db,
                prefs,
                settings,
                clock,
                object : IdFactory {
                    override fun next() = "gen-${counter++}"
                },
                LocalDataSignal(),
                EraseLock(),
                { "Cash" },
            )
        editor = DefaultExpenseEditor(db, LocalDataSignal(), clock, EraseLock(), AnswerPairLookup(repo))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun twoBanks() {
        db.lookups().upsertAccounts(listOf(answerAccount("cib", "CIB", last4 = "1111"), answerAccount("nbe", "NBE", last4 = "2222")))
        db.accountBalances().insert(AccountBalanceEntity("c1", "cib", 2_000_000, "EGP", ago(20), "MANUAL", ago(20)))
        db.accountBalances().insert(AccountBalanceEntity("c2", "nbe", 1_000_000, "EGP", ago(20), "MANUAL", ago(20)))
    }

    private suspend fun put(vararg e: eg.masroof.data.db.ExpenseEntity) = e.forEach { db.expenses().insert(it) }

    private suspend fun worth(): Long =
        WealthRepository(db, clock)
            .observeAccountViews()
            .first()
            .sumOf { it.balanceMinor ?: 0L }

    private fun pipeline(withSettings: Boolean = false) =
        SmsPipeline(
            db,
            MemorySettings(),
            MemoryNotifications(),
            clock,
            MemoryGate(),
            FixedKey,
            answers = prefs,
            appSettings = if (withSettings) settings else null,
        )

    private fun sms(
        body: String,
        sender: String = "CIB",
        at: Long = ANSWER_NOW.toEpochMilli() - 60_000,
    ) = IncomingMessage(CaptureSource.SMS, sender, body, at)

    private val atm = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 2,000.00- Your available balance EGP 5,000.00"

    // ---- MAJOR 1: a converted real entry is never deleted by Undo or Delete

    @Test
    fun `REQ-EXP-065 Undo of the captured converted side does not delete the other bank's real entry`() =
        runTest {
            twoBanks()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)),
                answerEntry("in", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", at = ago(0, 23)),
            )
            db.captured().insert(CapturedTxEntity("c-in", "in", 500_000, "EGP", null, "SMS", null, ago(0, 23), instrumentLast4 = "2222"))
            repo.transferToOwn("out", "nbe")!!
            assertEquals("TRANSFER_IN", db.expenses().find("in")!!.type)
            assertEquals(2, db.expenses().all().size)
            CapturedActions(db, clock, answers = prefs).undo("c-in")
            assertNotNull("in is the undone one", db.expenses().find("in")!!.deletedAtMillis)
            assertNull("the other bank's real entry survives", db.expenses().find("out")!!.deletedAtMillis)
        }

    @Test
    fun `REQ-EXP-065 Undo of the captured send removes the TRANSFER_IN the app inserted and nothing else`() =
        runTest {
            twoBanks()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)))
            db.captured().insert(CapturedTxEntity("c-out", "out", 500_000, "EGP", null, "SMS", null, ago(1), instrumentLast4 = "1111"))
            repo.transferToOwn("out", "nbe")!!
            CapturedActions(db, clock, answers = prefs).undo("c-out")
            assertTrue(db.expenses().all().all { it.deletedAtMillis != null })
        }

    @Test
    fun `REQ-EXP-067 deleting the cash side puts the withdrawal back as a withdrawal`() =
        runTest {
            twoBanks()
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            repo.trackCash("w")!!
            val cashSide = db.expenses().all().single { it.type == "TRANSFER_IN" }
            editor.delete(cashSide.id)
            assertEquals("CASH_WITHDRAWAL", db.expenses().find("w")!!.type)
            assertNull(db.expenses().find("w")!!.deletedAtMillis)
        }

    @Test
    fun `REQ-EXP-065 deleting a converted income side restores it to INCOME and its old account`() =
        runTest {
            twoBanks()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)),
                answerEntry(
                    "in",
                    type = "INCOME",
                    amount = 500_000,
                    last4 = "2222",
                    method = "INSTAPAY",
                    at = ago(0, 23),
                    category = "cat-x",
                ),
            )
            val before = db.expenses().find("in")!!
            repo.transferToOwn("out", "nbe")!!
            editor.delete("out")
            val back = db.expenses().find("in")!!
            assertEquals(before.type, back.type)
            assertEquals(before.accountId, back.accountId)
            assertEquals(before.categoryId, back.categoryId)
        }

    // ---- MAJOR 2: real income is never converted unless certainly the user's

    @Test
    fun `REQ-EXP-065 an unattributed income of the same amount is left alone and a new TRANSFER_IN is added`() =
        runTest {
            twoBanks()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(0, 1)),
                answerEntry("friend", type = "INCOME", amount = 500_000, merchant = "Ahmed", method = "INSTAPAY", at = ago(0, 6)),
            )
            repo.transferToOwn("out", "nbe")!!
            assertEquals("INCOME", db.expenses().find("friend")!!.type)
            assertEquals(500_000L, db.expenses().find("friend")!!.amountMinor)
            assertEquals(1, db.expenses().all().count { it.type == "TRANSFER_IN" })
        }

    @Test
    fun `REQ-EXP-065 an unattributed income that arrived after the send is left alone too, and so is an income before the send`() =
        runTest {
            twoBanks()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(0, 5)),
                answerEntry("later", type = "INCOME", amount = 500_000, merchant = "Ahmed", method = "INSTAPAY", at = ago(0, 4)),
                answerEntry("attributedBefore", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", at = ago(0, 9)),
            )
            repo.transferToOwn("out", "nbe")!!
            assertEquals("INCOME", db.expenses().find("later")!!.type)
            assertEquals("INCOME", db.expenses().find("attributedBefore")!!.type)
        }

    // ---- MAJOR 3: one ATM setting

    @Test
    fun `REQ-EXP-067 with the switch on a remembered Track it no longer pairs and the withdrawal counts as spending`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            settings.update { it.copy(includeCashWithdrawals = true) }
            assertEquals(Disposition.RECORDED, pipeline(withSettings = true).accept(sms(atm, sender = "HSBC")))
            val only = db.expenses().all().single()
            assertEquals("CASH_WITHDRAWAL", only.type)
            val rows = listOf(AnalyticsRow(only.type, only.status, only.currency, only.amountMinor, only.occurredAtMillis, null))
            val total =
                AnalyticsCalculator
                    .compute(AnalyticsPeriod.month(YearMonth.of(2026, 10), 1), LocalDate.of(2026, 10, 31), rows, emptyList(), true)
                    .summary.total
            assertEquals(200_000L, total)
        }

    @Test
    fun `REQ-EXP-067 turning the spending switch on makes the choice COUNT and both settings show the same state`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            val vm = AnalyticsSettingsViewModel(settings, prefs)
            vm.setCountCash(true)
            assertEquals(AtmChoice.COUNT, prefs.flow.value.atmChoice)
            assertEquals(AtmChoice.COUNT, repo.atmChoice.first())
            vm.setCountCash(false)
            assertNull(prefs.flow.value.atmChoice)
            assertNull(repo.atmChoice.first())
        }

    @Test
    fun `REQ-EXP-067 the effective choice reads COUNT whenever the switch is on, and Track it turns the switch off`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            settings.update { it.copy(includeCashWithdrawals = true) }
            assertEquals(AtmChoice.COUNT, repo.atmChoice.first())
            twoBanks()
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            settings.update { it.copy(includeCashWithdrawals = false) }
            repo.trackCash("w")!!
            assertFalse(settings.current().includeCashWithdrawals)
            assertEquals(AtmChoice.TRACK, repo.atmChoice.first())
        }

    // ---- security MINOR: the cash side copies the withdrawal's status

    @Test
    fun `REQ-EXP-067 an over-ceiling withdrawal with Track it makes two pending rows and deleting one removes the other`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            val big = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 60,000.00- Your available balance EGP 5,000.00"
            pipeline().accept(sms(big, sender = "HSBC"))
            val rows = db.expenses().all()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.status == "PENDING_REVIEW" })
            val withdrawal = rows.single { it.type == "TRANSFER" }
            val cash = rows.single { it.type == "TRANSFER_IN" }
            // confirming the withdrawal confirms its cash side
            editor.update(withdrawal.id, ExpenseChanges(status = "CONFIRMED"))
            assertEquals("CONFIRMED", db.expenses().find(cash.id)!!.status)
            editor.deleteWithPartner(withdrawal.id)
            assertNotNull(db.expenses().find(cash.id)!!.deletedAtMillis)
        }

    // ---- MINOR a

    @Test
    fun `REQ-EXP-064 Yes a refund keeps a refund with no known purchase and closes its card for good`() =
        runTest {
            put(answerEntry("r", type = "REFUND", merchant = "Souq", amount = 45_000, status = "PENDING_REVIEW"))
            assertEquals(1, repo.observeCards().first().size)
            val undo = repo.confirmRefund("r")!!
            assertEquals("REFUND", db.expenses().find("r")!!.type)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertEquals(1, repo.observeCards().first().size)
        }

    // ---- MINOR b

    @Test
    fun `REQ-EXP-065 a transfer with an unknown own account asks nothing and answers nothing, so net worth never changes`() =
        runTest {
            twoBanks()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "7777", method = "BANK_TRANSFER"),
                answerEntry("in", type = "INCOME", amount = 300_000, method = "BANK_TRANSFER", merchant = "CIB"),
            )
            assertTrue(
                repo
                    .observeCards()
                    .first()
                    .filterIsInstance<AnswerCard.Transfer>()
                    .isEmpty(),
            )
            val before = worth()
            assertNull(repo.transferToOwn("out", "nbe"))
            assertNull(repo.transferToOwn("in", "cib"))
            assertEquals(before, worth())
        }

    @Test
    fun `REQ-EXP-065 a pair always leaves net worth unchanged`() =
        runTest {
            twoBanks()
            val before = worth()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            repo.transferToOwn("out", "nbe")!!
            assertEquals(before, worth() - 0L)
        }

    // ---- MINOR c

    @Test
    fun `REQ-EXP-066 a redelivered message that was already recorded is not turned into a review item`() =
        runTest {
            val purchase = "Your card ending 4512 was charged EGP 1.00 at AMAZON"
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(purchase)))
            prefs.edit { it.copy(ignoredCards = setOf("4512")) }
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(purchase)))
            assertTrue(db.reviews().all().isEmpty())
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-EXP-066 two copies of one ignored purchase make one review item`() =
        runTest {
            prefs.edit { it.copy(ignoredCards = setOf("4512")) }
            pipeline().accept(sms("Your card ending 4512 was charged EGP 1.00 at AMAZON", at = ANSWER_NOW.toEpochMilli() - 120_000))
            pipeline().accept(sms("Your card ending 4512 was charged EGP 1.00 at AMAZON", at = ANSWER_NOW.toEpochMilli() - 60_000))
            assertEquals(1, db.reviews().all().size)
        }

    // ---- MINOR d: Cairo calendar days

    @Test
    fun `REQ-EXP-064 the 60 day refund window counts Cairo calendar days`() {
        val refund = answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 100, at = cairo("2026-11-07", 9))
        val edge = answerEntry("edge", merchant = "Amazon", amount = 1_000, at = cairo("2026-09-08", 8))
        val out = answerEntry("out", merchant = "Amazon", amount = 1_000, at = cairo("2026-09-07", 23))
        assertEquals(listOf("edge"), AnswerCards.candidates(refund, listOf(edge, out)).map { it.id })
    }

    @Test
    fun `REQ-EXP-064 the 30 day card window counts Cairo calendar days`() {
        val now = cairo("2026-11-08", 11)
        val edge = answerEntry("edge", type = "CASH_WITHDRAWAL", at = cairo("2026-10-09", 10), last4 = "1111")
        val out = answerEntry("out", type = "CASH_WITHDRAWAL", at = cairo("2026-10-08", 23), last4 = "1111")
        val accounts = listOf(answerAccount("cib", "CIB", last4 = "1111"))
        assertEquals(1, AnswerCards.generate(listOf(edge), accounts, AnswerState(), false, now).size)
        assertTrue(AnswerCards.generate(listOf(out), accounts, AnswerState(), false, now).isEmpty())
    }

    // ---- MINOR e

    @Test
    fun `REQ-EXP-064 Pick another offers only purchases at least as large as the refund`() =
        runTest {
            put(
                answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 45_000),
                answerEntry("big", merchant = "Noon", amount = 50_000, at = ago(3)),
                answerEntry("small", merchant = "Noon", amount = 10_000, at = ago(3)),
            )
            assertEquals(listOf("big"), repo.pickList("r").map { it.id })
            assertNull("a smaller purchase cannot be linked either", repo.linkRefund("r", "small"))
        }

    @Test
    fun `REQ-EXP-064 deleting a purchase with a linked refund turns the refund into income, or deletes both and Undo restores both`() =
        runTest {
            put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
            )
            repo.linkRefund("r", "p")!!
            editor.delete("p")
            assertEquals("INCOME", db.expenses().find("r")!!.type)
            editor.restore("p")
            // link again and delete both
            repo.linkRefund("r", "p")
            editor.deleteWithPartner("p")
            assertNotNull(db.expenses().find("r")!!.deletedAtMillis)
            editor.restore("p")
            assertNull(db.expenses().find("r")!!.deletedAtMillis)
            assertNull(db.expenses().find("p")!!.deletedAtMillis)
        }

    // ---- MINOR f

    @Test
    fun `REQ-EXP-065 repeating an answer after the remembered state was lost inserts nothing twice`() =
        runTest {
            twoBanks()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            repo.transferToOwn("out", "nbe")!!
            val worthAfterFirst = worth()
            prefs.clear()
            repo.transferToOwn("out", "nbe")!!
            assertEquals(1, db.expenses().all().count { it.type == "TRANSFER_IN" })
            assertEquals(worthAfterFirst, worth())
        }

    // ---- NIT: a link to an archived account is dropped

    @Test
    fun `REQ-EXP-066 a card linked to an account that was archived asks again`() =
        runTest {
            twoBanks()
            put(answerEntry("a", merchant = "Talabat", last4 = "1898"))
            repo.linkCard("1898", "cib")!!
            assertTrue(repo.observeCards().first().none { it is AnswerCard.UnknownCard })
            assertEquals(mapOf("1898" to "cib"), repo.cardLinks.first())
            db.accountBalances().setAccountArchived("cib", true)
            db.expenses().update(db.expenses().find("a")!!.copy(accountId = null))
            assertTrue(repo.cardLinks.first().isEmpty())
            assertTrue(repo.observeCards().first().any { it is AnswerCard.UnknownCard })
            repo.linkCard("1898", "nbe")
            repo.removeCardLink("1898")
            assertTrue(
                prefs.flow.value.aliases
                    .isEmpty(),
            )
        }
}
