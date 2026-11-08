package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** STORY-208 loop 5 follow-ups: superseded Undo, survivor type edit, Undo after erase, excluded flag, purge. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerLoop5Test {
    private lateinit var w: AnswerWorld

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    @Test
    fun `REQ-EXP-067 l5-1 an Undo of an older Track answer does nothing after a later Count`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            val track = w.repo.trackCash("w")!!
            w.editor.delete("w:pair")
            assertTrue(w.repo.countCash("w") != null)
            w.repo.undo(track)
            assertTrue("Count stays: the switch is still on", w.settings.current().includeCashWithdrawals)
            assertEquals(AtmChoice.COUNT, w.prefs.flow.value.atmChoice)
            assertTrue("w" in w.prefs.flow.value.answered)
        }

    @Test
    fun `REQ-EXP-065 l5-2 a type edit on the survivor of a broken pair is refused, never saved and reverted`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.delete("out")
            val refused = w.editor.update("in", ExpenseChanges(type = "EXPENSE"))
            assertFalse(refused is EditOutcome.Saved)
            assertEquals("INCOME", w.type("in"))
            // unlinking first lets the edit through, and it sticks
            AnswerPairLookup(w.repo).unlink("in")
            assertTrue(w.editor.update("in", ExpenseChanges(type = "EXPENSE")) is EditOutcome.Saved)
            assertEquals("EXPENSE", w.type("in"))
        }

    @Test
    fun `REQ-EXP-065 l5-3 no Undo applies after Erase all data`() =
        runTest {
            w.announcedTransfer()
            val answer = w.repo.transferToOwn("out", "nbe")!!
            val unlink = AnswerPairLookup(w.repo).unlink("in")!!
            val answer2 = w.repo.transferToOwn("out", "nbe")!!
            RoomDataWiper(
                w.db,
                NoInsightsCache,
                androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                kotlinx.coroutines.Dispatchers.Unconfined,
                answerPrefs = w.prefs,
            ).wipe()
            AnswerPairLookup(w.repo).undoUnlink(unlink)
            assertFalse(w.repo.undo(answer))
            assertFalse(w.repo.undo(answer2))
            assertEquals(AnswerState(), w.prefs.flow.value)
        }

    @Test
    fun `REQ-EXP-064 l5-4 excluding a purchase from totals is refused while a refund is linked to it`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop"),
            )
            w.repo.linkRefund("r", "p")!!
            assertFalse(w.editor.update("p", ExpenseChanges(excludedFromTotals = true)) is EditOutcome.Saved)
            assertFalse(
                w.db
                    .expenses()
                    .find("p")!!
                    .excludedFromTotals,
            )
            AnswerPairLookup(w.repo).unlink("p")
            assertTrue(w.editor.update("p", ExpenseChanges(excludedFromTotals = true)) is EditOutcome.Saved)
        }

    @Test
    fun `REQ-EXP-065 l5-6 purging one side of a pair drops its link`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.db.expenses().softDelete("out", 1L)
            val removed = DeletedEntryPurger(w.db, w.clock, EraseLock(), answers = w.prefs).purge()
            assertTrue(removed >= 1)
            assertTrue(
                w.prefs.flow.value.links
                    .isEmpty(),
            )
            assertFalse("the survivor may ask again", "in" in w.prefs.flow.value.answered)
        }
}
