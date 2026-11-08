package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.remote.ExpenseDeleter
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
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

/** STORY-208 last round: the Cash account "Track it" created is removed only when nothing of the user's depends on it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerCashTest {
    private lateinit var w: AnswerWorld
    private val atm = "From HSBC: 24OCT26 ATM cash withdrawal from 012-345***-678 EGP 2,000.00- Your available balance EGP 5,000.00"

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        w = AnswerWorld(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = w.close()

    private suspend fun captureTracked(): Pair<String, String> {
        w.prefs.edit { it.copy(atmChoice = AtmChoice.TRACK) }
        SmsPipeline(w.db, MemorySettings(), MemoryNotifications(), w.clock, MemoryGate(), FixedKey, answers = w.prefs)
            .accept(IncomingMessage(CaptureSource.SMS, "HSBC", atm, ANSWER_NOW.toEpochMilli() - 60_000))
        return w.db
            .accountBalances()
            .accounts()
            .single { it.kind == "CASH" }
            .id to
            w.db
                .captured()
                .all()
                .single()
                .localId
    }

    private suspend fun userCheck(account: String) =
        w.db.accountBalances().insert(AccountBalanceEntity("user-chk", account, 5_000, "EGP", ago(0), "MANUAL", ago(0)))

    private suspend fun checks() =
        w.db
            .accountBalances()
            .observeAll()
            .first()

    private suspend fun undoCapture(localId: String) = CapturedActions(w.db, w.clock, answers = w.prefs).undo(localId)

    // ---- capture Undo

    @Test
    fun `REQ-EXP-067 cash-1 capture Undo keeps a balance check the user added, the Cash account is archived, not deleted`() =
        runTest {
            val (cash, captured) = captureTracked()
            userCheck(cash)
            undoCapture(captured)
            assertTrue("the user's check survives", checks().any { it.id == "user-chk" })
            assertTrue(
                w.db
                    .accountBalances()
                    .accounts()
                    .single { it.id == cash }
                    .archived,
            )
        }

    @Test
    fun `REQ-EXP-067 cash-2 capture Undo with nothing else removes the Cash account and its seed check`() =
        runTest {
            val (cash, captured) = captureTracked()
            undoCapture(captured)
            assertNull(
                w.db
                    .accountBalances()
                    .accounts()
                    .firstOrNull { it.id == cash },
            )
            assertTrue(checks().none { it.accountId == cash })
        }

    @Test
    fun `REQ-EXP-067 cash-3 capture Undo keeps the account when a deleted entry in the trash still points at it`() =
        runTest {
            val (cash, captured) = captureTracked()
            w.put(answerEntry("trash", amount = 100, source = "MANUAL").copy(accountId = cash, deletedAtMillis = ago(1)))
            undoCapture(captured)
            val account =
                w.db
                    .accountBalances()
                    .accounts()
                    .single { it.id == cash }
            assertTrue("restoring the trashed entry finds its account", account.archived)
        }

    // ---- answer Undo

    @Test
    fun `REQ-EXP-067 cash-4 answer Undo keeps a balance check the user added, the Cash account is archived`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            val token = w.repo.trackCash("w")!!
            val cash =
                w.db
                    .accountBalances()
                    .accounts()
                    .single { it.kind == "CASH" }
                    .id
            userCheck(cash)
            w.repo.undo(token)
            assertTrue(checks().any { it.id == "user-chk" })
            assertTrue(
                w.db
                    .accountBalances()
                    .accounts()
                    .single { it.id == cash }
                    .archived,
            )
        }

    @Test
    fun `REQ-EXP-067 cash-5 answer Undo with nothing else removes the Cash account and its seed check`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            val token = w.repo.trackCash("w")!!
            val cash =
                w.db
                    .accountBalances()
                    .accounts()
                    .single { it.kind == "CASH" }
                    .id
            w.repo.undo(token)
            assertNull(
                w.db
                    .accountBalances()
                    .accounts()
                    .firstOrNull { it.id == cash },
            )
            assertTrue(checks().none { it.accountId == cash })
        }

    @Test
    fun `REQ-EXP-066 cash-7 an ignored capture in another currency is not the twin of an earlier one`() =
        runTest {
            val at = ANSWER_NOW.toEpochMilli()
            w.db.reviews().insert(
                eg.masroof.data.db.ReviewItemEntity(
                    id = "i1",
                    source = "SMS",
                    sender = "CIB",
                    text = "x",
                    reason = "ignored_card",
                    receivedAtMillis = at,
                    createdAtMillis = at,
                    sugAmountMinor = 100,
                    sugCurrency = "EGP",
                    sugLast4 = "4512",
                ),
            )
            assertEquals(1, w.db.reviews().countIgnoredTwins("4512", 100, "EGP", at - 1000, at + 1000))
            assertEquals(0, w.db.reviews().countIgnoredTwins("4512", 100, "USD", at - 1000, at + 1000))
        }

    // ---- the frozen upload path never deletes a linked row on the server

    @Test
    fun `REQ-EXP-067 cash-6 a linked row is not deleted on the server and its undo request is cleared`() =
        runTest {
            w.twoBanks()
            w.put(answerEntry("w", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111"))
            w.repo.trackCash("w")!!
            w.db.captured().insert(CapturedTxEntity("c-w", "w", 200_000, "EGP", null, "SMS", null, ago(1), undoRequested = true))
            var serverCalls = 0
            val deleter =
                object : ExpenseDeleter {
                    override suspend fun deleteExpense(id: String): Boolean {
                        serverCalls++
                        return true
                    }
                }
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: eg.masroof.data.db.ParsedOutboxEntity,
                    ) = SmsUploadResult.Sent("x")
                }
            val device =
                object : DeviceIdProvider {
                    override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
                }
            SmsUploader(w.db, remote, device, w.clock, MemoryGate(), deleter = deleter, answers = w.prefs).uploadPending()
            assertEquals("no server delete for a linked row", 0, serverCalls)
            assertNotNull("the row stays on the phone", w.db.expenses().find("w"))
            assertTrue(
                "the request is cleared, not retried",
                w.db
                    .captured()
                    .all()
                    .none { it.undoRequested },
            )
            assertFalse(
                w.db
                    .captured()
                    .all()
                    .any { it.localId == "c-w" },
            )
        }
}
