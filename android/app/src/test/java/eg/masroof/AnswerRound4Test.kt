package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.bills.DefaultInstallmentRepository
import eg.masroof.data.bills.NewPlan
import eg.masroof.data.bills.PaymentChoice
import eg.masroof.data.bills.RecordOutcome
import eg.masroof.data.categories.DefaultMerchantRuleRepository
import eg.masroof.data.categories.MerchantKey
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/** STORY-208 fix round 4: linked rows are locked for money fields, one write path, replaced links are final, crash safety. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerRound4Test {
    private lateinit var w: AnswerWorld

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    private suspend fun insertedPair() {
        w.twoBanks()
        w.put(answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER", at = ago(1)))
        w.repo.transferToOwn("out", "nbe")!!
    }

    // ---- p1 / p2 / p3 / p7: linked rows are locked for money fields

    @Test
    fun `REQ-EXP-065 p1 the amount of a linked transfer cannot be edited, so no phantom money appears`() =
        runTest {
            insertedPair()
            val base = w.worth()
            assertFalse(w.editor.update("out", ExpenseChanges(amountMinor = 400_000)) is EditOutcome.Saved)
            assertFalse(w.editor.update("out:pair", ExpenseChanges(amountMinor = 400_000)) is EditOutcome.Saved)
            assertEquals(base, w.worth())
            assertEquals(
                500_000L,
                w.db
                    .expenses()
                    .find("out:pair")!!
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-EXP-067 p2 the amount of a tracked ATM withdrawal cannot be edited`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            w.repo.trackCash("w")!!
            assertFalse(w.editor.update("w", ExpenseChanges(amountMinor = 250_000)) is EditOutcome.Saved)
            assertEquals(
                200_000L,
                w.db
                    .expenses()
                    .find("w:pair")!!
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-EXP-065 p3 a type edit of a converted side is refused, not silently undone`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            assertFalse(w.editor.update("in", ExpenseChanges(type = "EXPENSE")) is EditOutcome.Saved)
            assertEquals("TRANSFER_IN", w.type("in"))
            assertTrue("notes stay editable", w.editor.update("in", ExpenseChanges(notes = "rent")) is EditOutcome.Saved)
        }

    @Test
    fun `REQ-EXP-065 p7 the date of a linked source cannot be edited`() =
        runTest {
            insertedPair()
            val moved =
                w.db
                    .expenses()
                    .find("out")!!
                    .occurredAtMillis - 86_400_000L
            assertFalse(w.editor.update("out", ExpenseChanges(occurredAtMillis = moved)) is EditOutcome.Saved)
            assertEquals(
                w.db
                    .expenses()
                    .find("out")!!
                    .occurredAtMillis,
                w.db
                    .expenses()
                    .find("out:pair")!!
                    .occurredAtMillis,
            )
        }

    // ---- p4: the merchant-rule path

    @Test
    fun `REQ-EXP-064 p4 filing past entries under a new category moves the linked refund with its purchase`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(5), category = "cat-shop"),
            )
            w.repo.linkRefund("r", "p")!!
            val rules =
                DefaultMerchantRuleRepository(
                    w.db,
                    w.clock,
                    eg.masroof.data.UuidIdFactory(),
                    eg.masroof.data.LocalDataSignal(),
                    eg.masroof.data.auth
                        .EraseLock(),
                    w.prefs,
                )
            rules.applyToPast(MerchantKey.of("Amazon"), "cat-elec")
            assertEquals(
                "cat-elec",
                w.db
                    .expenses()
                    .find("p")!!
                    .categoryId,
            )
            assertEquals(
                "cat-elec",
                w.db
                    .expenses()
                    .find("r")!!
                    .categoryId,
            )
        }

    // ---- p5 / p6: replacing a link

    @Test
    fun `REQ-EXP-065 p6 a restored old side after the card was answered again is a plain entry, never a phantom TRANSFER_IN`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.delete("in")
            w.repo.transferToOwn("out", "nbe")!!
            w.editor.restore("in")
            assertEquals("INCOME", w.type("in"))
            assertEquals(
                1,
                w.db
                    .expenses()
                    .all()
                    .count { it.type == "TRANSFER_IN" && it.deletedAtMillis == null },
            )
            assertTrue(
                "the plain entry may ask again",
                w.repo
                    .observeCards()
                    .first()
                    .any { it is eg.masroof.data.answers.AnswerCard.Transfer },
            )
        }

    @Test
    fun `REQ-EXP-065 p5 Undo of the answer that replaced a link brings the replaced link back`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            val old = w.prefs.flow.value.links
            w.editor.delete("in")
            val second = w.repo.transferToOwn("out", "nbe")!!
            w.repo.undo(second)
            assertEquals(old, w.prefs.flow.value.links)
            w.editor.restore("in")
            assertEquals("TRANSFER_IN", w.type("in"))
        }

    // ---- unlink

    @Test
    fun `REQ-EXP-065 p9 Unlink restores both rows to their unlinked state in one go and Undo links them again`() =
        runTest {
            w.announcedTransfer()
            val before = w.db.expenses().find("in")!!
            val undo = w.repo.transferToOwn("out", "nbe")!!
            assertNotNull(undo)
            val token = AnswerPairLookup(w.repo).unlink("in")!!
            assertEquals(before.type, w.type("in"))
            assertTrue(
                w.prefs.flow.value.links
                    .isEmpty(),
            )
            assertTrue(
                "the card may ask again",
                w.repo
                    .observeCards()
                    .first()
                    .isNotEmpty(),
            )
            AnswerPairLookup(w.repo).undoUnlink(token)
            assertEquals("TRANSFER_IN", w.type("in"))
            assertEquals(1, w.prefs.flow.value.links.size)
        }

    // ---- p10: a lost DataStore write

    @Test
    fun `REQ-EXP-065 p10 repeating an answer after the remembered state was lost adds no second TRANSFER_IN`() =
        runTest {
            w.announcedTransfer()
            w.repo.transferToOwn("out", "nbe")!!
            val worth = w.worth()
            w.prefs.clear()
            w.repo.transferToOwn("out", "nbe")
            assertEquals(
                1,
                w.db
                    .expenses()
                    .all()
                    .count { it.type == "TRANSFER_IN" },
            )
            assertEquals(worth, w.worth())
        }

    @Test
    fun `REQ-EXP-067 p10 repeating a Track it after the remembered state was lost adds no second cash side`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            w.repo.trackCash("w")!!
            val worth = w.worth()
            w.prefs.clear()
            w.repo.transferToOwn("w", "nbe")
            assertEquals(
                1,
                w.db
                    .expenses()
                    .all()
                    .count { it.type == "TRANSFER_IN" },
            )
            assertEquals(worth, w.worth())
        }

    // ---- NIT: an answer is not an edit

    @Test
    fun `REQ-EXP-065 p11 Undo of an answer leaves the row as unedited for the twin enrichment`() =
        runTest {
            w.announcedTransfer()
            val before = w.db.expenses().find("in")!!
            val undo = w.repo.transferToOwn("out", "nbe")!!
            w.repo.undo(undo)
            val after = w.db.expenses().find("in")!!
            assertEquals(before.updatedAtMillis, after.updatedAtMillis)
        }

    // ---- p8: gam3eya linking

    @Test
    fun `REQ-EXP-053 p8 a purchase with a linked refund is not offered to a gam3eya payment`() =
        runTest {
            w.put(
                answerEntry("r", type = "INCOME", merchant = "Amazon", amount = 45_000),
                answerEntry(
                    "p",
                    merchant = "Amazon",
                    amount = 120_000,
                    at = ago(1),
                ).copy(occurredAtMillis = BillsFixtures.noon("2026-11-07")),
            )
            w.repo.linkRefund("r", "p")!!
            val repo =
                DefaultInstallmentRepository(
                    w.db,
                    w.clock,
                    object : eg.masroof.data.IdFactory {
                        override fun next() =
                            java.util.UUID
                                .randomUUID()
                                .toString()
                    },
                    eg.masroof.data.LocalDataSignal(),
                    eg.masroof.data.auth
                        .EraseLock(),
                    w.prefs,
                )
            val plan =
                repo.create(
                    NewPlan(
                        title = "Gam3eya",
                        provider = "GAM3EYA",
                        monthlyMinor = 120_000,
                        paymentCount = 10,
                        firstDue = java.time.LocalDate.parse("2026-11-07"),
                        alreadyPaid = 0,
                        collectionTurn = 4,
                    ),
                )
            val payment =
                w.db
                    .installmentPayments()
                    .observeAll()
                    .first()
                    .first { it.planId == plan }
            assertTrue(repo.linkCandidates(payment.id).none { it.id == "p" })
            assertEquals(RecordOutcome.NotLinkable, repo.recordPayment(payment.id, PaymentChoice.LinkExisting("p")))
            assertNull(
                w.db
                    .expenses()
                    .find("p")!!
                    .paymentMethod,
            )
        }
}
