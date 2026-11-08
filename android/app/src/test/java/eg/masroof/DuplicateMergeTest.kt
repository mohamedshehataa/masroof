package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.DataStoreAnswerPrefs
import eg.masroof.data.answers.EntryLink
import eg.masroof.data.answers.LinkKind
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.MergeResult
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

/** STORY-173: the "Possible duplicate" cards, Merge, Undo and Keep both against real Room and a clock that moves. */
@Suppress("LargeClass") // one story, one file of REQ-DEDUP-063/064/065 tests
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicateMergeTest {
    private lateinit var w: AnswerWorld
    private lateinit var repo: DefaultDuplicateRepository

    @Before
    fun setUp() {
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        repo = newRepo()
    }

    @After
    fun tearDown() = w.close()

    private fun newRepo() = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())

    private suspend fun add(vararg entries: ExpenseEntity) =
        entries.forEach {
            w.db.expenses().insert(it)
            w.db.sources().insert(if (it.source == "MANUAL") manualSource(it.id) else captureSource(it.id, it.source.orEmpty()))
        }

    private suspend fun keys() = repo.observeCards().first().map { it.key }

    private suspend fun total(): Long {
        val (from, to) = CairoTime.monthRangeMillis(ANSWER_NOW)
        return w.db
            .expenses()
            .observeTotal(from, to)
            .first()
    }

    private suspend fun merged(
        m: String = "m",
        c: String = "c",
    ) = (repo.merge(m, c) as MergeResult.Merged).undo

    // ---- REQ-DEDUP-063 -------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-DEDUP-063 the capture arriving second makes a card and both entries stay in the total`() =
        runTest {
            add(manualEntry("m"))
            assertEquals(emptyList<String>(), keys())
            add(captureEntry("c"))
            val card = repo.observeCards().first().single()
            assertEquals("m", card.manual.id)
            assertEquals("c", card.capture.id)
            assertEquals("CIB", card.captureSources.senderId)
            // nothing was merged: both entries are live and both count
            assertEquals(2 * DUP_AMOUNT, total())
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-063 the manual entry saved second makes the same card`() =
        runTest {
            add(captureEntry("c"))
            assertEquals(emptyList<String>(), keys())
            add(manualEntry("m"))
            assertEquals(listOf("m|c"), keys())
            assertEquals(2 * DUP_AMOUNT, total())
        }

    @Test
    fun `REQ-DEDUP-063 amounts that differ, a capture the day before and two typed entries make no card`() =
        runTest {
            add(manualEntry("m"), captureEntry("c", amount = 145_050), captureEntry("yesterday", at = cairo("2026-11-05", "23:55")))
            add(manualEntry("m2", amount = 99_000), manualEntry("m3", amount = 99_000))
            assertEquals(emptyList<String>(), keys())
        }

    @Test
    fun `REQ-DEDUP-063 an untimed typed entry of the 6th and a capture at 00-01 Cairo on the 7th make no card`() =
        runTest {
            add(manualEntry("m"), captureEntry("c", at = cairo("2026-11-07", "00:01")))
            assertEquals(emptyList<String>(), keys())
        }

    @Test
    fun `REQ-DEDUP-063 a typed entry that matches two captures gets two cards, and merging one removes the other`() =
        runTest {
            add(manualEntry("m"), captureEntry("c1"), captureEntry("c2", source = "PUSH", at = cairo(DUP_DAY, "09:00")))
            assertEquals(listOf("m|c1", "m|c2"), keys().sorted())
            merged("m", "c1")
            assertEquals(emptyList<String>(), keys())
            // the second capture was never merged: it still counts on its own
            assertNull(
                w.db
                    .expenses()
                    .find("c2")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-063 a capture that matches two typed entries is merged into at most once`() =
        runTest {
            add(manualEntry("m1"), manualEntry("m2"), captureEntry("c"))
            assertEquals(listOf("m1|c", "m2|c"), keys().sorted())
            merged("m1", "c")
            // the survivor carries a typed source now: it is nobody's twin any more
            assertEquals(emptyList<String>(), keys())
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m2", "c")))
            assertNull(
                w.db
                    .expenses()
                    .find("m2")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-063 an entry of a refund or transfer link gets no card and cannot be merged`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            assertEquals(listOf("m|c"), keys())
            w.prefs.edit { it.copy(links = it.links + EntryLink(LinkKind.TRANSFER, "c", "elsewhere", nonce = "n")) }
            assertEquals(emptyList<String>(), keys())
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(
                "c",
                w.db
                    .sources()
                    .forExpense("c")
                    .single()
                    .expenseId,
            )
        }

    @Test
    fun `REQ-DEDUP-063 an entry a gold lot points at is never merged away`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            w.db.assets().insert(
                AssetEntity(
                    "a",
                    "GOLD",
                    "Gold",
                    "EGP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            w.db.assetLots().insert(AssetLotEntity("l", "a", "BUY", 1000, DUP_AMOUNT, "EGP", 1L, "m", null))
            assertEquals(emptyList<String>(), keys())
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
        }

    // ---- REQ-DEDUP-064 -------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-DEDUP-064 Merge keeps the capture and fills category, notes and paid-by from the typed entry`() =
        runTest {
            val member = "member-1"
            add(
                manualEntry("m", category = "food", notes = "lunch", paidBy = member, merchant = "Koshary"),
                captureEntry("c", last4 = "1111", method = "CARD"),
            )
            val before = w.db.expenses().find("c")!!
            assertEquals(2 * DUP_AMOUNT, total())
            merged()
            val survivor = w.db.expenses().find("c")!!
            // the capture's money, time, last four and method
            assertEquals(before.amountMinor, survivor.amountMinor)
            assertEquals(before.occurredAtMillis, survivor.occurredAtMillis)
            assertEquals("1111", survivor.instrumentLast4)
            assertEquals("CARD", survivor.paymentMethod)
            assertTrue(survivor.timeKnown)
            // the typed entry's category, notes and paid-by, where the capture had none
            assertEquals("food", survivor.categoryId)
            assertEquals("lunch", survivor.notes)
            assertEquals(member, survivor.paidByMemberId)
            // the typed entry is in the trash, the purchase counts once, the card is gone
            assertNotNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(DUP_AMOUNT, total())
            assertEquals(emptyList<String>(), keys())
        }

    @Test
    fun `REQ-DEDUP-064 Merge never overwrites what the capture already has`() =
        runTest {
            add(
                manualEntry("m", category = "food", notes = "typed note"),
                captureEntry("c", category = "transport").copy(notes = "from capture"),
            )
            merged()
            val survivor = w.db.expenses().find("c")!!
            assertEquals("transport", survivor.categoryId)
            assertEquals("from capture", survivor.notes)
        }

    @Test
    fun `REQ-DEDUP-064 every source of the typed entry is re-pointed and its receipts are attached to the survivor`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            w.db.receipts().insert(ReceiptEntity("r1", "m", "r1.jpg", "image/jpeg", 10, "sha-r1", null, 1L))
            w.db.receipts().insert(ReceiptEntity("r2", "c", "r2.jpg", "image/jpeg", 10, "sha-r2", null, 2L))
            merged()
            assertEquals(
                setOf("m:src", "c:src"),
                w.db
                    .sources()
                    .forExpense("c")
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                emptyList<String>(),
                w.db
                    .sources()
                    .forExpense("m")
                    .map { it.id },
            )
            assertEquals(
                "c",
                w.db
                    .receipts()
                    .find("r1")!!
                    .expenseId,
            )
            assertEquals(
                "c",
                w.db
                    .receipts()
                    .find("r2")!!
                    .expenseId,
            )
        }

    @Test
    fun `REQ-DEDUP-064 Undo restores both entries, their sources and the receipt exactly, even long after`() =
        runTest {
            add(manualEntry("m", category = "food", notes = "lunch"), captureEntry("c"))
            w.db.receipts().insert(ReceiptEntity("r1", "m", "r1.jpg", "image/jpeg", 10, "sha-r1", null, 1L))
            val entriesBefore =
                w.db
                    .expenses()
                    .all()
                    .sortedBy { it.id }
            val sourcesBefore =
                w.db
                    .sources()
                    .all()
                    .sortedBy { it.id }
            val token = merged()
            assertEquals(DUP_AMOUNT, total())
            repeat(50) { w.clock.instant() } // the clock moves on while the snackbar is up
            assertTrue(repo.undo(token))
            assertEquals(
                entriesBefore,
                w.db
                    .expenses()
                    .all()
                    .sortedBy { it.id },
            )
            assertEquals(
                sourcesBefore,
                w.db
                    .sources()
                    .all()
                    .sortedBy { it.id },
            )
            assertEquals(
                "m",
                w.db
                    .receipts()
                    .find("r1")!!
                    .expenseId,
            )
            assertEquals(2 * DUP_AMOUNT, total())
            // the pair is a question again: nothing was kept apart
            assertEquals(listOf("m|c"), keys())
        }

    @Test
    fun `REQ-DEDUP-064 Undo applies once and a second Undo does nothing`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            val token = merged()
            assertTrue(repo.undo(token))
            assertFalse(repo.undo(token))
            assertEquals(2 * DUP_AMOUNT, total())
        }

    @Test
    fun `REQ-DEDUP-064 Undo is refused once the survivor was edited, and nothing moves`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            val token = merged()
            w.editor.update("c", ExpenseChanges(notes = "edited after the merge"))
            assertFalse(repo.undo(token))
            assertNotNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(
                setOf("m:src", "c:src"),
                w.db
                    .sources()
                    .forExpense("c")
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                "edited after the merge",
                w.db
                    .expenses()
                    .find("c")!!
                    .notes,
            )
        }

    @Test
    fun `REQ-DEDUP-064 Undo is refused once the survivor was deleted or the typed entry was brought back by hand`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            val token = merged()
            w.editor.restore("m")
            assertFalse(repo.undo(token))
            // the typed entry was restored by the trash Undo: it is live next to the survivor, sources untouched
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            w.editor.delete("c")
            assertFalse(repo.undo(token))
        }

    @Test
    fun `REQ-DEDUP-064 Undo is refused after Erase all data`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            val token = merged()
            w.prefs.clear()
            assertFalse(repo.undo(token))
            assertNotNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-064 a card whose typed entry was deleted merges nothing and says out of date`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            val staleCards = repo.observeCards().first()
            assertEquals(1, staleCards.size)
            w.editor.delete("m")
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
            assertEquals(emptyList<String>(), keys())
            assertEquals(
                "c",
                w.db
                    .sources()
                    .forExpense("c")
                    .single()
                    .expenseId,
            )
            assertEquals(DUP_AMOUNT, total())
        }

    @Test
    fun `REQ-DEDUP-064 a card whose captured entry was deleted merges nothing`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            w.editor.delete("c")
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-064 a card whose typed entry got another amount merges nothing`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            w.editor.update("m", ExpenseChanges(amountMinor = 150_000))
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(150_000L + DUP_AMOUNT, total())
        }

    @Test
    fun `REQ-DEDUP-064 a second tap on Merge finds the first one done and merges nothing more`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            merged()
            val after =
                w.db
                    .expenses()
                    .all()
                    .sortedBy { it.id }
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c")))
            assertEquals(
                after,
                w.db
                    .expenses()
                    .all()
                    .sortedBy { it.id },
            )
            assertEquals(DUP_AMOUNT, total())
        }

    // ---- REQ-DEDUP-065 -------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-DEDUP-065 Keep both removes the card, keeps both entries and the pair never comes back after a restart`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            repo.keepBoth("m", "c")
            assertEquals(emptyList<String>(), keys())
            assertEquals(2 * DUP_AMOUNT, total())
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            // a restart: new repository over the same remembered state; even a later edit of the entries does not bring it back
            val restarted = newRepo()
            assertEquals(emptyList<String>(), restarted.observeCards().first().map { it.key })
            w.editor.update("c", ExpenseChanges(notes = "touched"))
            assertEquals(emptyList<String>(), restarted.observeCards().first().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-065 Keep both is for that pair only, and the pair cannot be merged afterwards`() =
        runTest {
            add(manualEntry("m"), captureEntry("c1"), captureEntry("c2", source = "PUSH", at = cairo(DUP_DAY, "10:00")))
            repo.keepBoth("m", "c1")
            assertEquals(listOf("m|c2"), keys())
            assertEquals(DuplicateOutcome.OUT_OF_DATE, outcome(repo.merge("m", "c1")))
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-065 nothing is merged without a tap, however many look-alikes arrive`() =
        runTest {
            val before = listOf(manualEntry("m"), captureEntry("c1"), captureEntry("c2", source = "PUSH"))
            add(*before.toTypedArray())
            assertEquals(2, repo.observeCards().first().size)
            // the cards are only offered: rows, sources and receipts are exactly what was written
            assertEquals(
                before.sortedBy { it.id },
                w.db
                    .expenses()
                    .all()
                    .sortedBy { it.id },
            )
            assertEquals(
                listOf("c1", "c2", "m"),
                w.db
                    .sources()
                    .all()
                    .map { it.expenseId }
                    .sorted(),
            )
            assertEquals(3 * DUP_AMOUNT, total())
        }

    @Test
    fun `REQ-DEDUP-065 Erase all data forgets every Keep both`() =
        runTest {
            add(manualEntry("m"), captureEntry("c"))
            repo.keepBoth("m", "c")
            assertEquals(emptyList<String>(), keys())
            w.prefs.clear()
            assertEquals(listOf("m|c"), keys())
        }

    @Test
    fun `REQ-DEDUP-065 the stored Keep both survives a restart of the real DataStore file and Erase clears it`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val first = DataStoreAnswerPrefs(context)
            first.edit { it.copy(keptApart = it.keptApart + "m|c") }
            // a second instance reads the same file, as after a restart of the process
            val second = DataStoreAnswerPrefs(context)
            assertEquals(setOf("m|c"), second.current().keptApart)
            second.clear()
            assertEquals(emptySet<String>(), DataStoreAnswerPrefs(context).current().keptApart)
        }

    private enum class DuplicateOutcome { MERGED, OUT_OF_DATE, FAILED }

    private fun outcome(r: MergeResult) =
        when (r) {
            is MergeResult.Merged -> DuplicateOutcome.MERGED
            MergeResult.OutOfDate -> DuplicateOutcome.OUT_OF_DATE
            MergeResult.Failed -> DuplicateOutcome.FAILED
        }
}
