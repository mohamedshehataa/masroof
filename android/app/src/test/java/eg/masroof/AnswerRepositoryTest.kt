package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.DefaultAnswerRepository
import eg.masroof.data.answers.ExpenseTypes
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.wealth.BalanceMath
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var prefs: FakeAnswerPrefs
    private lateinit var settings: FakeSettings
    private lateinit var repo: DefaultAnswerRepository
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(context)
        prefs = FakeAnswerPrefs()
        settings = FakeSettings()
        repo = newRepo()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newRepo() =
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

    private suspend fun accounts(vararg a: Pair<String, Long>) {
        db.lookups().upsertAccounts(
            listOf(answerAccount("cib", "CIB", last4 = "1111"), answerAccount("nbe", "NBE", last4 = "2222")),
        )
        a.forEach { (id, bal) -> db.accountBalances().insert(AccountBalanceEntity("chk-$id", id, bal, "EGP", ago(20), "MANUAL", ago(20))) }
    }

    private suspend fun put(vararg e: eg.masroof.data.db.ExpenseEntity) = e.forEach { db.expenses().insert(it) }

    private suspend fun balances(): Map<String, Long?> =
        WealthRepository(db, clock)
            .observeAccountViews()
            .first()
            .associate { it.account.id to it.balanceMinor }

    private fun spending(): Pair<Long, Long> {
        val rows =
            runTestBlocking {
                db.expenses().all()
            }.map { AnalyticsRow(it.type, it.status, it.currency, it.amountMinor, it.occurredAtMillis, it.categoryId) }
        val period = AnalyticsPeriod.month(YearMonth.of(2026, 11), 1)
        val out = AnalyticsCalculator.compute(period, LocalDate.of(2026, 11, 8), rows, emptyList(), includeCash = false)
        val withCash = AnalyticsCalculator.compute(period, LocalDate.of(2026, 11, 8), rows, emptyList(), includeCash = true)
        return out.summary.total + out.summary.income to withCash.summary.total
    }

    private fun <T> runTestBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    // ---- REQ-EXP-064

    @Test
    fun `REQ-EXP-064 linking a refund makes it a REFUND in the purchase category and Undo puts the income back exactly`() =
        runTest {
            val income = answerEntry("in", type = "INCOME", merchant = "Amazon", amount = 45_000, at = ago(1), status = "PENDING_REVIEW")
            put(income, answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shopping"))
            val undo = repo.linkRefund("in", "p")!!
            val linked = db.expenses().find("in")!!
            assertEquals("REFUND", linked.type)
            assertEquals("cat-shopping", linked.categoryId)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertEquals(income, db.expenses().find("in"))
            assertTrue(
                prefs.flow.value.answered
                    .isEmpty() &&
                    prefs.flow.value.links
                        .isEmpty(),
            )
            assertEquals(1, repo.observeCards().first().size)
        }

    @Test
    fun `REQ-EXP-064 Not a refund keeps the INCOME and the card never returns, Undo brings it back`() =
        runTest {
            put(
                answerEntry("in", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
            )
            val undo = repo.notRefund("in")!!
            assertEquals("INCOME", db.expenses().find("in")!!.type)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertEquals(1, repo.observeCards().first().size)
        }

    @Test
    fun `REQ-EXP-064 a REFUND that is not a refund becomes income, and a deleted purchase cannot be linked`() =
        runTest {
            put(
                answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
            )
            repo.notRefund("r")
            assertEquals("INCOME", db.expenses().find("r")!!.type)
            db.expenses().softDelete("p", ago(0))
            assertNull(repo.linkRefund("r", "p"))
            assertTrue(repo.pickList("r").isEmpty())
        }

    // ---- REQ-EXP-065

    private suspend fun cibToNbe() {
        accounts("cib" to 2_000_000L, "nbe" to 1_000_000L)
    }

    @Test
    fun `REQ-EXP-065 a transfer pair moves both balances and changes no total`() =
        runTest {
            cibToNbe()
            val before = balances()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER", at = ago(1)))
            val worthBefore = before.values.sumOf { it ?: 0L }
            val spendBefore = spending()
            repo.transferToOwn("out", "nbe")!!
            val inEntry = db.expenses().all().single { it.type == "TRANSFER_IN" }
            assertEquals("nbe", inEntry.accountId)
            assertEquals(500_000L, inEntry.amountMinor)
            val after = balances()
            assertEquals(before["cib"]!! - 500_000L, after["cib"])
            assertEquals(before["nbe"]!! + 500_000L, after["nbe"])
            assertEquals(worthBefore, after.values.sumOf { it ?: 0L })
            assertEquals(spendBefore, spending())
            assertEquals("the pair is linked", inEntry.id, prefs.flow.value.pairPartner("out"))
            assertTrue(repo.observeCards().first().isEmpty())
        }

    @Test
    fun `REQ-EXP-065 an incoming captured transfer asks the mirror question and adds the outgoing side`() =
        runTest {
            cibToNbe()
            val before = balances()
            put(answerEntry("in", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", merchant = "Ahmed"))
            repo.transferToOwn("in", "cib")!!
            assertEquals("TRANSFER_IN", db.expenses().find("in")!!.type)
            val out = db.expenses().all().single { it.type == "TRANSFER" }
            assertEquals("cib", out.accountId)
            val after = balances()
            assertEquals(before["cib"]!! - 500_000L, after["cib"])
            assertEquals(before["nbe"]!! + 500_000L, after["nbe"])
            assertEquals(0L to 0L, spending())
        }

    @Test
    fun `REQ-EXP-065 an entry the other bank already announced is linked instead of inventing a second one`() =
        runTest {
            cibToNbe()
            put(
                answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER", at = ago(1)),
                answerEntry("inc", type = "INCOME", amount = 500_000, last4 = "2222", method = "BANK_TRANSFER", at = ago(1, 1)),
            )
            repo.transferToOwn("out", "nbe")!!
            assertEquals(2, db.expenses().all().size)
            assertEquals("TRANSFER_IN", db.expenses().find("inc")!!.type)
            assertEquals("inc", prefs.flow.value.pairPartner("out"))
            assertEquals(0L to 0L, spending())
        }

    @Test
    fun `REQ-EXP-065 To someone else keeps the TRANSFER as it is and Undo restores the card`() =
        runTest {
            cibToNbe()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            val undo = repo.transferToOther("out")!!
            assertEquals(1, db.expenses().all().size)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertTrue(repo.observeCards().first().any { it is AnswerCard.Transfer })
        }

    @Test
    fun `REQ-EXP-065 Undo of a pair answer removes the new side and restores a converted one exactly`() =
        runTest {
            cibToNbe()
            val inc = answerEntry("inc", type = "INCOME", amount = 500_000, last4 = "2222", method = "BANK_TRANSFER", at = ago(1, 1))
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER", at = ago(1)), inc)
            repo.undo(repo.transferToOwn("out", "nbe")!!)
            assertEquals(inc, db.expenses().find("inc"))
            put(answerEntry("solo", type = "TRANSFER", amount = 70_000, last4 = "1111", method = "BANK_TRANSFER", at = ago(2)))
            val undo = repo.transferToOwn("solo", "nbe")!!
            assertEquals(4, db.expenses().all().size)
            repo.undo(undo)
            assertEquals(3, db.expenses().all().size)
            assertNull(prefs.flow.value.pairPartner("solo"))
        }

    @Test
    fun `REQ-EXP-065 deleting one side of a linked pair deletes both together and Undo restores both`() =
        runTest {
            cibToNbe()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            repo.transferToOwn("out", "nbe")
            val partner = repo.livePartnerOf("out")
            assertNotNull(partner)
            val editor = DefaultExpenseEditor(db, LocalDataSignal(), clock, EraseLock(), AnswerPairLookup(repo))
            editor.deleteWithPartner("out")
            assertTrue(db.expenses().find("out")!!.deletedAtMillis != null && db.expenses().find(partner!!)!!.deletedAtMillis != null)
            assertNull("no live partner once both are gone", repo.livePartnerOf("out"))
            editor.restore("out")
            assertNull(db.expenses().find("out")!!.deletedAtMillis)
            assertNull(db.expenses().find(partner!!)!!.deletedAtMillis)
            // deleting only this one leaves the partner, and Undo restores only this one
            editor.delete("out")
            assertNull(db.expenses().find(partner)!!.deletedAtMillis)
        }

    @Test
    fun `REQ-EXP-065 the edit screen knows a linked entry has a partner so Delete can ask`() =
        runTest {
            cibToNbe()
            put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            repo.transferToOwn("out", "nbe")
            val editor = DefaultExpenseEditor(db, LocalDataSignal(), clock, EraseLock(), AnswerPairLookup(repo))
            val vm = EditExpenseViewModel(editor, SavedStateHandle(mapOf("id" to "out")), AnswerPairLookup(repo))
            backgroundScope.launch { vm.state.collect {} }
            assertTrue(
                vm.state
                    .first { it.form.loaded }
                    .form.hasPartner,
            )
            put(answerEntry("solo", type = "TRANSFER", amount = 1_000, last4 = "1111"))
            val plain = EditExpenseViewModel(editor, SavedStateHandle(mapOf("id" to "solo")), AnswerPairLookup(repo))
            backgroundScope.launch { plain.state.collect {} }
            assertFalse(
                plain.state
                    .first { it.form.loaded }
                    .form.hasPartner,
            )
        }

    @Test
    fun `REQ-EXP-065 TRANSFER_IN is money in, a type this version does not know is a TRANSFER out, neither is spending`() {
        assertEquals(500L, BalanceMath.bankDelta("TRANSFER_IN", 500))
        assertEquals(-500L, BalanceMath.bankDelta("TRANSFER", 500))
        assertEquals(-500L, BalanceMath.bankDelta("FROM_THE_FUTURE", 500))
        assertEquals("TRANSFER", ExpenseTypes.normalize("FROM_THE_FUTURE"))
        assertTrue(ExpenseTypes.isTransfer("TRANSFER_IN"))
        val rows = listOf("TRANSFER_IN", "FROM_THE_FUTURE", "TRANSFER").map { AnalyticsRow(it, "CONFIRMED", "EGP", 500_000, ago(1), null) }
        val period = AnalyticsPeriod.month(YearMonth.of(2026, 11), 1)
        for (cash in listOf(false, true)) {
            val s = AnalyticsCalculator.compute(period, LocalDate.of(2026, 11, 8), rows, emptyList(), cash).summary
            assertEquals(0L, s.total)
            assertEquals(0L, s.income)
        }
    }

    // ---- REQ-EXP-066

    @Test
    fun `REQ-EXP-066 Ignore stores the last four only, restoring brings the card back, a longer number is refused`() =
        runTest {
            put(answerEntry("a", merchant = "Talabat", last4 = "1898"))
            assertNull(repo.ignoreCard("12345678901898"))
            assertTrue(
                prefs.flow.value.ignoredCards
                    .isEmpty(),
            )
            val undo = repo.ignoreCard("1898")!!
            assertEquals(setOf("1898"), prefs.flow.value.ignoredCards)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertEquals(1, repo.observeCards().first().size)
            repo.ignoreCard("1898")
            repo.restoreIgnored("1898")
            assertEquals(1, repo.observeCards().first().size)
        }

    @Test
    fun `REQ-EXP-066 linking a card files its entries under the account and Undo takes them back`() =
        runTest {
            accounts()
            put(answerEntry("a", merchant = "Talabat", last4 = "1898"), answerEntry("b", merchant = "Noon", last4 = "1898", at = ago(3)))
            val undo = repo.linkCard("1898", "cib")!!
            assertEquals(listOf("cib", "cib"), listOf("a", "b").map { db.expenses().find(it)!!.accountId })
            assertEquals("cib", prefs.flow.value.aliases["1898"])
            assertTrue(repo.observeCards().first().none { it is AnswerCard.UnknownCard })
            repo.undo(undo)
            assertNull(db.expenses().find("a")!!.accountId)
            assertTrue(
                prefs.flow.value.aliases
                    .isEmpty(),
            )
        }

    // ---- REQ-EXP-067

    @Test
    fun `REQ-EXP-067 Track it creates a Cash account, makes a transfer pair, remembers the choice, and Undo removes it all`() =
        runTest {
            accounts("cib" to 2_000_000L)
            val w = answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111", at = ago(1))
            put(w)
            val undo = repo.trackCash("w")!!
            assertEquals("TRANSFER", db.expenses().find("w")!!.type)
            val cash =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .single { it.kind == "CASH" }
            assertEquals("Cash", cash.label)
            val inn = db.expenses().all().single { it.type == "TRANSFER_IN" }
            assertEquals(cash.id, inn.accountId)
            assertEquals(AtmChoice.TRACK, prefs.flow.value.atmChoice)
            val b = balances()
            assertEquals(1_800_000L, b["cib"])
            assertEquals(200_000L, b[cash.id])
            assertEquals(0L to 0L, spending())
            repo.undo(undo)
            assertEquals(w, db.expenses().find("w"))
            assertTrue(
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .none { it.kind == "CASH" },
            )
            assertNull(prefs.flow.value.atmChoice)
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-EXP-067 Track it uses the Cash account that exists`() =
        runTest {
            accounts("cib" to 2_000_000L)
            db.lookups().upsertAccounts(listOf(answerAccount("cash", "My cash", kind = "CASH")))
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            repo.trackCash("w")
            assertEquals(
                3,
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .size,
            )
            assertEquals(
                "cash",
                db
                    .expenses()
                    .all()
                    .single { it.type == "TRANSFER_IN" }
                    .accountId,
            )
        }

    @Test
    fun `REQ-EXP-067 Count as spending sets the existing setting on and Undo sets it back`() =
        runTest {
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            val undo = repo.countCash("w")!!
            assertTrue(settings.current().includeCashWithdrawals)
            assertEquals(AtmChoice.COUNT, prefs.flow.value.atmChoice)
            assertEquals("EXPENSE-like cash stays a withdrawal", "CASH_WITHDRAWAL", db.expenses().find("w")!!.type)
            assertTrue(repo.observeCards().first().isEmpty())
            repo.undo(undo)
            assertFalse(settings.current().includeCashWithdrawals)
            assertNull(prefs.flow.value.atmChoice)
        }

    @Test
    fun `REQ-EXP-067 the choice can be changed in Settings`() =
        runTest {
            repo.setAtmChoice(AtmChoice.COUNT)
            assertTrue(settings.current().includeCashWithdrawals)
            repo.setAtmChoice(AtmChoice.TRACK)
            assertFalse(settings.current().includeCashWithdrawals)
            assertEquals(AtmChoice.TRACK, repo.atmChoice.first())
            repo.setAtmChoice(null)
            assertNull(repo.atmChoice.first())
        }

    @Test
    fun `REQ-EXP-067 a withdrawal turned into a pair stays out of spending under both ATM settings`() =
        runTest {
            accounts("cib" to 2_000_000L)
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            assertEquals(0L to 200_000L, spending())
            repo.trackCash("w")
            assertEquals(0L to 0L, spending())
        }

    // ---- erase

    @Test
    fun `REQ-EXP-066 Erase all data clears ignored cards, links, answers, aliases and the ATM choice`() =
        runTest {
            prefs.edit {
                AnswerState(
                    answered = setOf("a"),
                    links =
                        setOf(
                            eg.masroof.data.answers
                                .EntryLink(eg.masroof.data.answers.LinkKind.TRANSFER, "a", "b"),
                        ),
                    ignoredCards = setOf("1898"),
                    aliases = mapOf("1898" to "cib"),
                    atmChoice = AtmChoice.TRACK,
                )
            }
            val wiper =
                RoomDataWiper(
                    db,
                    NoInsightsCache,
                    androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                    Dispatchers.Unconfined,
                    answerPrefs = prefs,
                )
            wiper.wipe()
            assertEquals(AnswerState(), prefs.flow.value)
        }
}
