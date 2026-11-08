package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.LocalDate
import java.time.YearMonth

/** STORY-208 fix round 3: pair and refund fields are a pure function of the link and the rows' liveness. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerReconcileTest {
    private lateinit var w: AnswerWorld

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    private suspend fun totals(includeCash: Boolean = false): Triple<Long, Long, Map<String, Long>> {
        val rows =
            w.db.expenses().all().filter { it.deletedAtMillis == null }.map {
                AnalyticsRow(it.type, it.status, it.currency, it.amountMinor, it.occurredAtMillis, it.categoryId, it.merchant)
            }
        val categories = listOf("cat-shop", "cat-elec").map { AnalyticsCategory(it, null, it, it, null, false) }
        val r =
            AnalyticsCalculator.compute(
                AnalyticsPeriod.month(YearMonth.of(2026, 11), 1),
                LocalDate.of(2026, 11, 8),
                rows,
                categories,
                includeCash,
            )
        return Triple(r.summary.total, r.summary.income, r.categories.associate { (it.categoryId ?: "") to it.total })
    }

    // ---- p21 / p07 / p08

    @Test
    fun `REQ-EXP-065 delete both sides then restore out then in rebuilds the pair`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.delete("out")
            w.editor.delete("in")
            w.editor.restore("out")
            w.editor.restore("in")
            assertEquals("TRANSFER_IN", w.type("in"))
            assertEquals(
                "nbe",
                w.db
                    .expenses()
                    .find("in")!!
                    .accountId,
            )
            assertEquals(0L, totals().second)
        }

    @Test
    fun `REQ-EXP-065 delete both sides then restore in then out rebuilds the pair`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.delete("out")
            w.editor.delete("in")
            w.editor.restore("in")
            assertEquals("the survivor alone is plain income again", "INCOME", w.type("in"))
            w.editor.restore("out")
            assertEquals("TRANSFER_IN", w.type("in"))
            assertEquals(0L, totals().second)
        }

    @Test
    fun `REQ-EXP-065 an unrelated edit between delete and restore does not break the rebuild`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.delete("out")
            w.editor.update("in", ExpenseChanges(notes = "rent"))
            w.editor.restore("out")
            assertEquals("TRANSFER_IN", w.type("in"))
            assertEquals(
                "rent",
                w.db
                    .expenses()
                    .find("in")!!
                    .notes,
            )
        }

    // ---- p15 / p22

    @Test
    fun `REQ-EXP-064 delete the purchase and the refund, restore both in order, and the refund nets again`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop"),
            )
            w.repo.linkRefund("r", "p")!!
            w.editor.delete("p")
            w.editor.delete("r")
            w.editor.restore("p")
            w.editor.restore("r")
            assertEquals("REFUND", w.type("r"))
            assertEquals(75_000L, totals().first)
        }

    @Test
    fun `REQ-EXP-067 delete both tracked sides and restore both gives the pair back`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            w.repo.trackCash("w")!!
            val cash =
                w.db
                    .expenses()
                    .all()
                    .single { it.type == "TRANSFER_IN" }
                    .id
            w.editor.delete(cash)
            w.editor.delete("w")
            w.editor.restore("w")
            w.editor.restore(cash)
            assertEquals("TRANSFER", w.type("w"))
            assertEquals("TRANSFER_IN", w.type(cash))
        }

    // ---- p23

    @Test
    fun `REQ-EXP-064 recategorizing the purchase moves its refund and the category list matches the total`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop"),
            )
            w.repo.linkRefund("r", "p")!!
            w.editor.update("p", ExpenseChanges(categoryId = "cat-elec"))
            assertEquals(
                "cat-elec",
                w.db
                    .expenses()
                    .find("r")!!
                    .categoryId,
            )
            val (total, _, byCategory) = totals()
            assertEquals(75_000L, total)
            assertEquals(75_000L, byCategory["cat-elec"])
            assertEquals(total, byCategory.values.sum())
        }

    @Test
    fun `REQ-EXP-064 a linked purchase cannot change currency`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5)),
            )
            w.repo.linkRefund("r", "p")!!
            assertFalse(w.editor.update("p", ExpenseChanges(currency = "USD")) is EditOutcome.Saved)
            assertEquals(
                "EGP",
                w.db
                    .expenses()
                    .find("p")!!
                    .currency,
            )
        }

    // ---- an inserted row lives only with its source

    @Test
    fun `REQ-EXP-067 deleting the withdrawal this only takes its cash side and net worth is unchanged, restore brings it back`() =
        runTest {
            w.twoBanks()
            val base = w.worth()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            w.repo.trackCash("w")!!
            val cash =
                w.db
                    .expenses()
                    .all()
                    .single { it.type == "TRANSFER_IN" }
                    .id
            w.editor.delete("w")
            assertNotNull(
                w.db
                    .expenses()
                    .find(cash)!!
                    .deletedAtMillis,
            )
            assertEquals(base, w.worth())
            w.editor.restore("w")
            assertNull(
                w.db
                    .expenses()
                    .find(cash)!!
                    .deletedAtMillis,
            )
            assertEquals("TRANSFER", w.type("w"))
        }

    @Test
    fun `REQ-EXP-065 deleting the source of an inserted TRANSFER_IN this only takes it too, net worth is unchanged`() =
        runTest {
            w.twoBanks()
            val base = w.worth()
            w.put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER"))
            w.repo.transferToOwn("out", "nbe")!!
            val inserted =
                w.db
                    .expenses()
                    .all()
                    .single { it.type == "TRANSFER_IN" }
                    .id
            w.editor.delete("out")
            assertNotNull(
                w.db
                    .expenses()
                    .find(inserted)!!
                    .deletedAtMillis,
            )
            assertEquals(base, w.worth())
            w.editor.restore("out")
            assertNull(
                w.db
                    .expenses()
                    .find(inserted)!!
                    .deletedAtMillis,
            )
            assertTrue(totals().first == 0L)
        }
}
