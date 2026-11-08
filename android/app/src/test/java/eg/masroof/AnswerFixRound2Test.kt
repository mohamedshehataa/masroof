package eg.masroof

import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.DefaultAnswerRepository
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.answers.AnswerCardsViewModel
import eg.masroof.ui.settings.AnswersSettingsContent
import eg.masroof.ui.theme.MasroofTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZoneOffset

/** STORY-208 fix round 2: symmetric delete/Undo, one answer per entry, link guards, field-level Undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerFixRound2Test {
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

    private suspend fun bothBanksAnnounced() {
        twoBanks()
        put(
            answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)),
            answerEntry("in", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", at = ago(0, 23)),
        )
    }

    private suspend fun worth(): Long =
        WealthRepository(db, clock)
            .observeAccountViews()
            .first()
            .sumOf { it.balanceMinor ?: 0L }

    // ---- F1

    @Test
    fun `REQ-EXP-065 Undo of a delete puts the converted side back into the pair and keeps the link`() =
        runTest {
            bothBanksAnnounced()
            repo.transferToOwn("out", "nbe")!!
            val linkBefore = prefs.flow.value.links
            editor.delete("out")
            assertEquals("INCOME", db.expenses().find("in")!!.type)
            editor.restore("out")
            val back = db.expenses().find("in")!!
            assertEquals("TRANSFER_IN", back.type)
            assertEquals("nbe", back.accountId)
            assertEquals(linkBefore, prefs.flow.value.links)
        }

    @Test
    fun `REQ-EXP-067 Undo of deleting the cash side puts the tracked withdrawal back as a transfer`() =
        runTest {
            twoBanks()
            put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            repo.trackCash("w")!!
            val cashSide = db.expenses().all().single { it.type == "TRANSFER_IN" }
            editor.delete(cashSide.id)
            assertEquals("CASH_WITHDRAWAL", db.expenses().find("w")!!.type)
            editor.restore(cashSide.id)
            assertEquals("TRANSFER", db.expenses().find("w")!!.type)
        }

    // ---- F2

    @Test
    fun `REQ-EXP-064 Undo of deleting a purchase brings the refund back as a linked REFUND in the purchase category`() =
        runTest {
            put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop"),
            )
            repo.linkRefund("r", "p")!!
            val linkBefore = prefs.flow.value.links
            editor.delete("p")
            assertEquals("INCOME", db.expenses().find("r")!!.type)
            editor.restore("p")
            val refund = db.expenses().find("r")!!
            assertEquals("REFUND", refund.type)
            assertEquals("cat-shop", refund.categoryId)
            assertEquals(linkBefore, prefs.flow.value.links)
        }

    // ---- F3

    @Test
    fun `REQ-EXP-065 answering the same transfer twice is a no-op the second time`() =
        runTest {
            bothBanksAnnounced()
            assertNotNull(repo.transferToOwn("out", "nbe"))
            val worthAfter = worth()
            assertNull(repo.transferToOwn("out", "nbe"))
            assertEquals(1, db.expenses().all().count { it.type == "TRANSFER_IN" })
            assertEquals(worthAfter, worth())
        }

    @Test
    fun `REQ-EXP-064 every other answer is also once per entry`() =
        runTest {
            twoBanks()
            put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
                answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"),
                answerEntry("c", merchant = "Talabat", last4 = "1898"),
            )
            assertNotNull(repo.linkRefund("r", "p"))
            assertNull(repo.linkRefund("r", "p"))
            assertNotNull(repo.trackCash("w"))
            assertNull(repo.trackCash("w"))
            assertNull(repo.countCash("w"))
            assertEquals(1, db.expenses().all().count { it.type == "TRANSFER_IN" })
            assertNotNull(repo.ignoreCard("1898"))
            assertNull(repo.ignoreCard("1898"))
            repo.restoreIgnored("1898")
            assertNotNull(repo.linkCard("1898", "cib"))
            assertNull(repo.linkCard("1898", "cib"))
        }

    // ---- F4

    @Test
    fun `REQ-EXP-064 a purchase with a linked refund cannot be edited below the refund`() =
        runTest {
            put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
            )
            repo.linkRefund("r", "p")!!
            val refused = editor.update("p", ExpenseChanges(amountMinor = 10_000))
            assertFalse(refused is EditOutcome.Saved)
            assertEquals(120_000L, db.expenses().find("p")!!.amountMinor)
            assertFalse(
                "money fields of a linked row are locked",
                editor.update("p", ExpenseChanges(amountMinor = 60_000)) is EditOutcome.Saved,
            )
            assertTrue(editor.update("p", ExpenseChanges(categoryId = "cat-elec")) is EditOutcome.Saved)
            assertFalse(editor.update("r", ExpenseChanges(amountMinor = 90_000)) is EditOutcome.Saved)
        }

    // ---- F5

    @Test
    fun `REQ-EXP-065 Undo of an answer keeps the edits made after it`() =
        runTest {
            bothBanksAnnounced()
            val token = repo.transferToOwn("out", "nbe")!!
            editor.update("in", ExpenseChanges(notes = "rent"))
            repo.undo(token)
            val back = db.expenses().find("in")!!
            assertEquals("INCOME", back.type)
            assertEquals("rent", back.notes)
        }

    // ---- F7

    private fun pipeline() =
        SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), FixedKey, answers = prefs)

    private val atm = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 2,000.00- Your available balance EGP 5,000.00"

    @Test
    fun `REQ-EXP-067 Undo of a captured tracked withdrawal clears its link and removes the Cash account it created`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            pipeline().accept(IncomingMessage(CaptureSource.SMS, "HSBC", atm, ANSWER_NOW.toEpochMilli() - 60_000))
            assertEquals(1, db.accountBalances().accounts().count { it.kind == "CASH" })
            val captured =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            CapturedActions(db, clock, answers = prefs).undo(captured)
            assertEquals(0, db.accountBalances().accounts().count { it.kind == "CASH" })
        }

    @Test
    fun `REQ-EXP-067 the Cash account stays when it has other entries`() =
        runTest {
            prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
            pipeline().accept(IncomingMessage(CaptureSource.SMS, "HSBC", atm, ANSWER_NOW.toEpochMilli() - 60_000))
            val cash = db.accountBalances().accounts().single { it.kind == "CASH" }
            put(answerEntry("spent", amount = 5_000, source = "MANUAL").copy(accountId = cash.id))
            CapturedActions(db, clock, answers = prefs).undo(
                db
                    .captured()
                    .all()
                    .single()
                    .localId,
            )
            assertEquals(1, db.accountBalances().accounts().count { it.kind == "CASH" })
        }

    @Test
    fun `REQ-EXP-065 Undo of the captured converted side lets the other bank's card ask again`() =
        runTest {
            bothBanksAnnounced()
            db.captured().insert(CapturedTxEntity("c-in", "in", 500_000, "EGP", null, "SMS", null, ago(0, 23), instrumentLast4 = "2222"))
            repo.transferToOwn("out", "nbe")!!
            CapturedActions(db, clock, answers = prefs).undo("c-in")
            assertTrue(repo.observeCards().first().any { it is AnswerCard.Transfer })
        }

    // ---- double tap in the view model

    @Test
    fun `REQ-EXP-065 a second tap while an answer runs is ignored by the view model`() =
        runTest {
            // a repository that never finishes: only the view model's own guard can keep the second tap out
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var calls = 0
            val slow =
                object : eg.masroof.data.answers.AnswerRepository by repo {
                    override suspend fun transferToOwn(
                        entryId: String,
                        accountId: String,
                    ): eg.masroof.data.answers.AnswerUndo? {
                        calls++
                        gate.await()
                        return null
                    }
                }
            val vm = AnswerCardsViewModel(slow, WealthRepository(db, clock), db)
            vm.transferToOwn("out", "nbe")
            vm.transferToOwn("out", "nbe")
            assertEquals(1, calls)
            gate.complete(Unit)
            vm.transferToOwn("out", "nbe")
            assertEquals("after the first answer ended a new tap is taken", 2, calls)
        }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class AnswerLinksArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-EXP-066 the linked cards list mirrors in Arabic and offers Remove`() {
        var direction: LayoutDirection? = null
        val removed = mutableListOf<String>()
        compose.setContent {
            MasroofTheme {
                direction = androidx.compose.ui.platform.LocalLayoutDirection.current
                AnswersSettingsContent(null, emptyList(), {}, {}, listOf("1898" to "CIB"), { removed += it })
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الكروت المربوطة").assertExists()
        compose.onNodeWithText("شيل الربط").performClick()
        assertEquals(listOf("1898"), removed)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AnswerLinksLargeFontTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-EXP-066 the linked cards list does not hide Remove at font scale 1_5`() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, 1.5f),
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    MasroofTheme { AnswersSettingsContent(AtmChoice.TRACK, listOf("1898"), {}, {}, listOf("1898" to "CIB savings"), {}) }
                }
            }
        }
        compose.onNodeWithText("Linked cards").assertExists()
        compose.onNodeWithText("Remove").performScrollTo().assertHeightIsAtLeast(48.dp)
    }
}
