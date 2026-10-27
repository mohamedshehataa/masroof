package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.MemberEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.ExpenseEditor
import eg.masroof.data.edit.fieldOf
import eg.masroof.data.edit.toPatch
import eg.masroof.data.remote.apiJson
import eg.masroof.data.remote.blankToNull
import eg.masroof.ui.viewmodel.EditError
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.plainAmount
import eg.masroof.ui.viewmodel.takeCodePoints
import eg.masroof.ui.viewmodel.toRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val OCT_15 = Instant.parse("2026-10-15T09:00:00Z").toEpochMilli()
private val NOW = Instant.parse("2026-10-27T10:00:00Z")
private const val DAY_MS = 86_400_000L

private fun row(
    id: String = "e1",
    amount: Long = 125_000,
    type: String = "INCOME",
    status: String = "PENDING_REVIEW",
    notes: String? = null,
    currency: String = "EGP",
    paidBy: String? = null,
) = ExpenseEntity(
    id,
    amount,
    currency,
    OCT_15,
    "InstaPay",
    null,
    null,
    notes,
    pending = false,
    source = "PUSH",
    type = type,
    status = status,
    updatedAtMillis = 1,
    paidByMemberId = paidBy,
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditExpenseRepositoryTest {
    private lateinit var db: AppDatabase
    private val signal = LocalDataSignal()
    private var clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private lateinit var editor: DefaultExpenseEditor

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        editor = DefaultExpenseEditor(db, signal, clock, EraseLock())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(item: ExpenseEntity = row()) = db.expenses().insert(item)

    private suspend fun seedSource(
        expenseId: String = "e1",
        localSourceId: String = "sms-1",
    ) = db.sources().insert(ExpenseSourceEntity("s-$localSourceId", expenseId, "SMS", localSourceId, OCT_15, null, null))

    @Test
    fun `REQ-EXP-044 confirm sets CONFIRMED and a new updatedAt, fires the signal, no HTTP`() =
        runTest {
            seed()
            val fired = CompletableDeferred<Unit>()
            backgroundScope.launch(Dispatchers.Unconfined) { signal.events.first().also { fired.complete(Unit) } }
            val out = editor.update("e1", ExpenseChanges(status = "CONFIRMED"))
            assertTrue(out is EditOutcome.Saved)
            val saved = db.expenses().find("e1")!!
            assertEquals("CONFIRMED", saved.status)
            assertEquals(NOW.toEpochMilli(), saved.updatedAtMillis)
            assertEquals(125_000L, saved.amountMinor)
            assertTrue(fired.isCompleted)
        }

    @Test
    fun `REQ-EXP-044 confirming an entry deleted meanwhile writes nothing`() =
        runTest {
            seed()
            assertEquals(EditOutcome.Deleted, editor.delete("e1"))
            assertEquals(EditOutcome.NotFound, editor.update("e1", ExpenseChanges(status = "CONFIRMED")))
            assertEquals("PENDING_REVIEW", db.expenses().find("e1")?.status)
            assertEquals(EditOutcome.NotFound, editor.update("missing", ExpenseChanges(status = "CONFIRMED")))
        }

    @Test
    fun `REQ-EXP-045 only changed columns and updatedAt are written, a new day is Cairo noon and time unknown`() =
        runTest {
            seed(row(status = "CONFIRMED", notes = "old").copy(categoryId = "c1"))
            val noon = CairoTime.noonOf(LocalDate.parse("2026-10-14")).toEpochMilli()
            editor.update("e1", ExpenseChanges(amountMinor = 500_000, currency = "EGP", occurredAtMillis = noon, notes = ""))
            val saved = db.expenses().find("e1")!!
            assertEquals(500_000L, saved.amountMinor)
            assertEquals(noon, saved.occurredAtMillis)
            assertFalse(saved.timeKnown)
            assertNull(saved.notes)
            assertEquals("c1", saved.categoryId)
            assertEquals("InstaPay", saved.merchant)
            assertEquals(NOW.toEpochMilli(), saved.updatedAtMillis)
        }

    @Test
    fun `REQ-EXP-045 an unchanged date keeps the instant and timeKnown, nothing changed writes nothing`() =
        runTest {
            seed(row(status = "CONFIRMED"))
            editor.update("e1", ExpenseChanges(merchantRaw = "Shop"))
            val saved = db.expenses().find("e1")!!
            assertEquals(OCT_15, saved.occurredAtMillis)
            assertTrue(saved.timeKnown)
            val before = saved.updatedAtMillis
            clock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)
            val again = DefaultExpenseEditor(db, signal, clock, EraseLock())
            again.update("e1", ExpenseChanges())
            assertEquals(before, db.expenses().find("e1")?.updatedAtMillis)
        }

    @Test
    fun `REQ-EXP-047 paid-by is stored, can be set back to not set, and other fields stay`() =
        runTest {
            seed(row(status = "CONFIRMED"))
            editor.update("e1", ExpenseChanges(paidByMemberId = "m2", paidByChanged = true))
            assertEquals("m2", db.expenses().find("e1")?.paidByMemberId)
            editor.update("e1", ExpenseChanges(notes = "x"))
            assertEquals("m2", db.expenses().find("e1")?.paidByMemberId)
            editor.update("e1", ExpenseChanges(paidByMemberId = null, paidByChanged = true))
            assertNull(db.expenses().find("e1")?.paidByMemberId)
        }

    @Test
    fun `REQ-EXP-046 delete hides the row from lists and totals at once, undo brings it back with its sources`() =
        runTest {
            val (from, to) = CairoTime.monthRangeMillis(Instant.ofEpochMilli(OCT_15))
            seed(row(type = "EXPENSE", status = "CONFIRMED"))
            seedSource()
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
            assertEquals(EditOutcome.Deleted, editor.delete("e1"))
            assertEquals(0L, db.expenses().observeTotal(from, to).first())
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                db
                    .expenses()
                    .observeRecent(5)
                    .first()
                    .isEmpty(),
            )
            assertEquals(NOW.toEpochMilli(), db.expenses().find("e1")?.deletedAtMillis)
            assertTrue(editor.restore("e1"))
            assertNull(db.expenses().find("e1")?.deletedAtMillis)
            assertEquals(
                1,
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(1, db.sources().countByLocalSourceId("sms-1"))
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
        }

    @Test
    fun `REQ-EXP-046 a second delete or a restore of a live row changes nothing`() =
        runTest {
            seed()
            assertEquals(EditOutcome.Deleted, editor.delete("e1"))
            assertEquals(EditOutcome.NotFound, editor.delete("e1"))
            assertTrue(editor.restore("e1"))
            assertFalse(editor.restore("e1"))
            assertNull(editor.find("missing"))
        }

    @Test
    fun `REQ-EXP-046 the purge removes entries deleted over 30 days ago with their sources and keeps newer ones`() =
        runTest {
            seed(row("old"))
            seed(row("recent"))
            seed(row("live"))
            seedSource("old", "sms-old")
            seedSource("recent", "sms-recent")
            val now = NOW.toEpochMilli()
            db.expenses().softDelete("old", now - 31 * DAY_MS)
            db.expenses().softDelete("recent", now - 29 * DAY_MS)
            assertEquals(1, DeletedEntryPurger(db, clock, EraseLock()).purge())
            assertNull(db.expenses().find("old"))
            assertNotNull(db.expenses().find("recent"))
            assertNotNull(db.expenses().find("live"))
            assertEquals(0, db.sources().countByLocalSourceId("sms-old"))
            assertEquals(1, db.sources().countByLocalSourceId("sms-recent"))
        }

    @Test
    fun `REQ-EXP-046 a deleted entry keeps its source row so the same SMS makes no new entry before the purge`() =
        runTest {
            seed()
            seedSource()
            editor.delete("e1")
            assertEquals(1, db.sources().countByLocalSourceId("sms-1"))
            val duplicate = runCatching { seedSource("e1", "sms-1") }
            assertTrue(duplicate.isFailure)
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-AND-134 a write that throws is Failed and leaves the row unchanged`() =
        runTest {
            seed()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER boom BEFORE UPDATE ON expense BEGIN SELECT RAISE(ABORT, 'x'); END")
            assertEquals(EditOutcome.Failed, editor.update("e1", ExpenseChanges(status = "CONFIRMED")))
            assertEquals(EditOutcome.Failed, editor.delete("e1"))
            val kept = db.expenses().find("e1")!!
            assertEquals("PENDING_REVIEW", kept.status)
            assertNull(kept.deletedAtMillis)
            assertEquals(1L, kept.updatedAtMillis)
        }

    @Test
    fun `REQ-AND-126 changing the type moves the amount out of and back into the month total`() =
        runTest {
            val (from, to) = CairoTime.monthRangeMillis(Instant.ofEpochMilli(OCT_15))
            seed(row(type = "EXPENSE", status = "CONFIRMED"))
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
            editor.update("e1", ExpenseChanges(type = "TRANSFER"))
            assertEquals(0L, db.expenses().observeTotal(from, to).first())
            editor.update("e1", ExpenseChanges(type = "EXPENSE"))
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
        }

    @Test
    fun `REQ-AND-124 the field comes from the first problem error and nothing else is kept`() {
        assertEquals("occurredAt", fieldOf("""{"title":"x","errors":[{"field":"occurredAt","message":"secret 125"}]}"""))
        assertNull(fieldOf("not json"))
        assertNull(fieldOf(null))
    }

    @Test
    fun `REQ-AND-121 the frozen import PATCH body holds only the changed fields, money as minor units`() {
        val body =
            apiJson.encodeToString(
                eg.masroof.api.models.ExpensePatch
                    .serializer(),
                ExpenseChanges(amountMinor = 12_550, currency = "EGP", notes = "").toPatch(),
            )
        assertTrue(body, body.contains("\"amountMinor\":12550"))
        assertTrue(body, body.contains("\"notes\":\"\""))
        assertFalse(body, body.contains("status"))
        assertFalse(body, body.contains("categoryId"))
        assertFalse(body, body.contains("version"))
    }

    @Test
    fun `REQ-AND-121 plain amount text uses integer arithmetic`() {
        assertEquals("1250", plainAmount(125_000))
        assertEquals("12.05", plainAmount(1_205))
        assertEquals("0.50", plainAmount(50))
    }

    @Test
    fun `REQ-EXP-047 a missing paid-by member shows no chip and a known one shows its name`() {
        val members = listOf(MemberEntity("m1", "Me", 0), MemberEntity("m2", "Sara", 1))
        val rows = toRows(listOf(row("a", paidBy = "m2"), row("b", paidBy = "gone"), row("c")), emptyList(), members)
        assertEquals(listOf("Sara", null, null), rows.map { it.paidBy })
    }
}

private class FakeEditor(
    var stored: ExpenseEntity? = row(),
    val members: List<MemberEntity> = emptyList(),
) : ExpenseEditor {
    val updates = mutableListOf<ExpenseChanges>()
    var deletes = 0
    var outcome: EditOutcome = EditOutcome.Saved(row(status = "CONFIRMED"))
    var gate: CompletableDeferred<Unit>? = null

    override val deleted: SharedFlow<String> = MutableSharedFlow()

    override suspend fun find(id: String) = stored

    override fun observeCategories() =
        MutableStateFlow(listOf(CategoryEntity("c1", "Food", "أكل", 1), CategoryEntity("c2", "Fun", "ترفيه", 2)))

    override fun observeMembers() = MutableStateFlow(members)

    override suspend fun update(
        id: String,
        changes: ExpenseChanges,
    ): EditOutcome {
        updates += changes
        gate?.await()
        return outcome
    }

    override suspend fun delete(id: String): EditOutcome {
        deletes++
        gate?.await()
        return EditOutcome.Deleted
    }

    override suspend fun restore(id: String) = true
}

@OptIn(ExperimentalCoroutinesApi::class)
class EditExpenseViewModelTest {
    private val editor = FakeEditor()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() =
        EditExpenseViewModel(editor, SavedStateHandle(mapOf("id" to "e1"))).also {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.state.collect {} }
        }

    @Test
    fun `REQ-EXP-044 confirm writes status CONFIRMED only and closes`() =
        runTest {
            val vm = vm()
            vm.confirm()
            assertEquals(listOf(ExpenseChanges(status = "CONFIRMED")), editor.updates)
            assertTrue(vm.state.value.form.done)
            assertTrue(vm.state.value.form.reviewPending)
        }

    @Test
    fun `REQ-EXP-044 confirm on an entry deleted meanwhile says it is gone and closes`() =
        runTest {
            editor.outcome = EditOutcome.NotFound
            val vm = vm()
            vm.confirm()
            assertTrue(vm.state.value.form.gone)
            assertTrue(vm.state.value.form.done)
        }

    @Test
    fun `REQ-EXP-045 save writes only the changed fields and confirms a pending item`() =
        runTest {
            val vm = vm()
            vm.onAmount("1500.5")
            vm.onCategory("c2")
            vm.onType("EXPENSE")
            vm.onDate(LocalDate.parse("2026-10-14"))
            vm.save()
            val sent = editor.updates.single()
            assertEquals(150_050L, sent.amountMinor)
            assertEquals("EGP", sent.currency)
            assertEquals("c2", sent.categoryId)
            assertEquals("EXPENSE", sent.type)
            assertEquals(CairoTime.noonOf(LocalDate.parse("2026-10-14")).toEpochMilli(), sent.occurredAtMillis)
            assertEquals("CONFIRMED", sent.status)
            assertNull(sent.notes)
            assertNull(sent.merchantRaw)
            assertFalse(sent.paidByChanged)
        }

    @Test
    fun `REQ-EXP-045 Arabic-Indic digits are stored as minor units`() =
        runTest {
            editor.stored = row(status = "CONFIRMED")
            val vm = vm()
            vm.onAmount("٥٠٠٠")
            vm.save()
            assertEquals(500_000L, editor.updates.single().amountMinor)
        }

    @Test
    fun `REQ-EXP-045 a foreign currency is kept and shown, never converted`() =
        runTest {
            editor.stored = row(status = "CONFIRMED", currency = "USD", amount = 5_000)
            val vm = vm()
            assertEquals("USD", vm.state.value.form.currency)
            vm.onAmount("60")
            vm.save()
            assertEquals("USD", editor.updates.single().currency)
        }

    @Test
    fun `REQ-EXP-045 save with nothing changed writes nothing`() =
        runTest {
            editor.stored = row(status = "CONFIRMED")
            val vm = vm()
            vm.save()
            assertTrue(editor.updates.isEmpty())
            assertTrue(vm.state.value.form.done)
        }

    @Test
    fun `REQ-EXP-045 zero, empty, negative or too large amounts are flagged, edits kept, nothing written`() =
        runTest {
            val vm = vm()
            for (bad in listOf("abc", "", "0", "-5", "10000000000.01")) {
                vm.onAmount(bad)
                vm.save()
                assertTrue(bad, vm.state.value.form.amountInvalid)
                assertEquals(bad, vm.state.value.form.amountText)
            }
            assertTrue(editor.updates.isEmpty())
            vm.onAmount("10000000000")
            vm.save()
            assertEquals(1_000_000_000_000L, editor.updates.single().amountMinor)
        }

    @Test
    fun `REQ-EXP-045 a cleared note is written as empty and notes over 500 show the error, keep the text, write nothing`() =
        runTest {
            editor.stored = row(status = "CONFIRMED", notes = "old")
            val vm = vm()
            vm.onNotes("")
            vm.save()
            assertEquals("", editor.updates.single().notes)
            val fresh = vm()
            fresh.onNotes("x".repeat(900))
            fresh.save()
            assertTrue(fresh.state.value.form.notesInvalid)
            assertEquals(900, fresh.state.value.form.notes.length)
            assertEquals(1, editor.updates.size)
        }

    @Test
    fun `REQ-EXP-045 confirm with typed edits writes them plus CONFIRMED`() =
        runTest {
            val vm = vm()
            vm.onCategory("c2")
            vm.onNotes("kept")
            vm.confirm()
            val sent = editor.updates.single()
            assertEquals("CONFIRMED", sent.status)
            assertEquals("c2", sent.categoryId)
            assertEquals("kept", sent.notes)
        }

    @Test
    fun `REQ-AND-134 a double tap writes once and the buttons are disabled meanwhile`() =
        runTest {
            editor.gate = CompletableDeferred()
            val vm = vm()
            vm.confirm()
            vm.confirm()
            vm.save()
            assertTrue(vm.state.value.form.busy)
            assertEquals(1, editor.updates.size)
            editor.gate?.complete(Unit)
            assertFalse(vm.state.value.form.busy)
        }

    @Test
    fun `REQ-EXP-046 delete asks first, cancel writes nothing, confirming deletes once and closes`() =
        runTest {
            editor.gate = CompletableDeferred()
            val vm = vm()
            vm.askDelete()
            assertTrue(vm.state.value.form.confirmDelete)
            vm.dismissDelete()
            assertEquals(0, editor.deletes)
            vm.askDelete()
            vm.delete()
            vm.delete()
            assertEquals(1, editor.deletes)
            editor.gate?.complete(Unit)
            assertTrue(vm.state.value.form.done)
        }

    @Test
    fun `REQ-AND-134 a double tap after a fast successful write does not write again`() =
        runTest {
            val vm = vm()
            vm.confirm()
            vm.confirm()
            assertEquals(1, editor.updates.size)
            assertTrue(vm.state.value.form.locked)
            val other = FakeEditor(row(status = "CONFIRMED"))
            val vm2 = EditExpenseViewModel(other, SavedStateHandle(mapOf("id" to "e1")))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm2.state.collect {} }
            vm2.onNotes("a")
            vm2.save()
            vm2.save()
            vm2.delete()
            assertEquals(1, other.updates.size)
            assertEquals(0, other.deletes)
        }

    @Test
    fun `REQ-AND-134 a failed write shows the error, keeps the edits and re-enables the buttons`() =
        runTest {
            editor.outcome = EditOutcome.Failed
            val vm = vm()
            vm.onNotes("keep me")
            vm.save()
            val form = vm.state.value.form
            assertEquals(EditError.FAILED, form.error)
            assertEquals("keep me", form.notes)
            assertFalse(form.busy)
            assertFalse(form.done)
        }

    @Test
    fun `REQ-EXP-047 paid-by changes are written, only members of a household of two or more are offered`() =
        runTest {
            val two = listOf(MemberEntity("m1", "Me", 0), MemberEntity("m2", "Sara", 1))
            val twoEditor = FakeEditor(row(status = "CONFIRMED", paidBy = "m1"), two)
            val vm = EditExpenseViewModel(twoEditor, SavedStateHandle(mapOf("id" to "e1")))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            assertEquals(2, vm.state.value.members.size)
            vm.onPaidBy(null)
            vm.save()
            assertEquals(ExpenseChanges(paidByMemberId = null, paidByChanged = true), twoEditor.updates.single())
            assertEquals(
                0,
                vm()
                    .state.value.members.size,
            )
        }

    @Test
    fun `REQ-EXP-047 a paid-by member that no longer exists shows as not set`() =
        runTest {
            val one = listOf(MemberEntity("m1", "Me", 0), MemberEntity("m2", "Sara", 1))
            val e = FakeEditor(row(status = "CONFIRMED", paidBy = "gone"), one)
            val vm = EditExpenseViewModel(e, SavedStateHandle(mapOf("id" to "e1")))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            assertNull(vm.state.value.form.paidByMemberId)
        }

    @Test
    fun `REQ-AND-121 caps count code points and never split an emoji`() {
        val emoji = "😀"
        assertEquals(3, emoji.repeat(5).takeCodePoints(3).codePointCount(0, emoji.repeat(3).length))
        assertEquals(emoji.repeat(3), emoji.repeat(5).takeCodePoints(3))
        assertEquals("abc", "abc".takeCodePoints(500))
    }

    @Test
    fun `REQ-AND-121 a cleared merchant is cached as no merchant, not as blank`() {
        assertNull(blankToNull("  "))
        assertEquals("x", blankToNull("x"))
    }

    @Test
    fun `REQ-AND-125 a row that is not there closes the screen as gone`() =
        runTest {
            editor.stored = null
            val form = vm().state.value.form
            assertTrue(form.done)
            assertTrue(form.gone)
            assertNotNull(editor.updates)
        }
}
