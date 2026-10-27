package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.ExpenseEditRemote
import eg.masroof.data.edit.ExpenseEditor
import eg.masroof.data.edit.fieldOf
import eg.masroof.data.edit.toPatch
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.apiJson
import eg.masroof.data.remote.blankToNull
import eg.masroof.ui.viewmodel.EditError
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.plainAmount
import eg.masroof.ui.viewmodel.takeCodePoints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.io.IOException
import java.time.LocalDate

private val OCT_15 =
    java.time.Instant
        .parse("2026-10-15T09:00:00Z")
        .toEpochMilli()

private fun row(
    id: String = "e1",
    amount: Long = 125_000,
    type: String = "INCOME",
    status: String = "PENDING_REVIEW",
    category: String? = null,
    merchant: String? = "InstaPay",
    notes: String? = null,
) = ExpenseEntity(
    id,
    amount,
    "EGP",
    OCT_15,
    merchant,
    category,
    null,
    notes,
    pending = false,
    source = "PUSH",
    type = type,
    status = status,
)

/** A scripted server: every call is recorded; [reply] decides the answer. */
private class FakeEditRemote : ExpenseEditRemote {
    val patches = mutableListOf<Pair<String, ExpenseChanges>>()
    val deletes = mutableListOf<String>()
    var gets = 0
    var latest: ExpenseEntity = row()
    var failWith: Throwable? = null
    var hang = false
    var result: (ExpenseChanges) -> ExpenseEntity = { latest }

    override suspend fun get(id: String): ExpenseEntity {
        gets++
        return latest
    }

    override suspend fun patch(
        id: String,
        changes: ExpenseChanges,
    ): ExpenseEntity {
        patches += id to changes
        if (hang) awaitCancellation()
        failWith?.let { throw it }
        return result(changes)
    }

    override suspend fun delete(id: String) {
        deletes += id
        failWith?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditExpenseRepositoryTest {
    private lateinit var db: AppDatabase
    private val remote = FakeEditRemote()
    private val signal = LocalDataSignal()
    private lateinit var editor: DefaultExpenseEditor

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        editor = DefaultExpenseEditor(db, remote, signal)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(item: ExpenseEntity = row()) = db.expenses().upsert(item)

    @Test
    fun `REQ-AND-120 confirm sends status only, updates the cache and fires the signal`() =
        runTest {
            seed()
            remote.result = { row(status = "CONFIRMED") }
            val fired = CompletableDeferred<Unit>()
            backgroundScope.launch(Dispatchers.Unconfined) { signal.events.first().also { fired.complete(Unit) } }
            runCurrent()
            val out = editor.update("e1", ExpenseChanges(status = "CONFIRMED"))
            assertTrue(out is EditOutcome.Saved)
            assertEquals(ExpenseChanges(status = "CONFIRMED"), remote.patches.single().second)
            assertEquals("CONFIRMED", db.expenses().find("e1")?.status)
            assertTrue(fired.isCompleted)
        }

    @Test
    fun `REQ-AND-123 a call that never answers ends as offline, not as an endless wait`() =
        runTest {
            seed()
            remote.hang = true
            assertEquals(EditOutcome.Offline, editor.update("e1", ExpenseChanges(notes = "x")))
            assertEquals("PENDING_REVIEW", db.expenses().find("e1")?.status)
        }

    @Test
    fun `REQ-AND-123 unreachable server is offline and 401 asks to sign in`() =
        runTest {
            seed()
            remote.failWith = IOException("down")
            assertEquals(EditOutcome.Offline, editor.update("e1", ExpenseChanges(notes = "x")))
            remote.failWith = HttpFailure(401)
            assertEquals(EditOutcome.SignIn, editor.update("e1", ExpenseChanges(notes = "x")))
        }

    @Test
    fun `REQ-AND-124 409 reloads the latest version into the cache`() =
        runTest {
            seed()
            remote.latest = row(amount = 99_900, status = "CONFIRMED")
            remote.failWith = HttpFailure(409)
            val out = editor.update("e1", ExpenseChanges(notes = "x"))
            assertEquals(99_900L, (out as EditOutcome.Conflict).latest?.amountMinor)
            assertEquals(1, remote.gets)
            assertEquals(99_900L, db.expenses().find("e1")?.amountMinor)
        }

    @Test
    fun `REQ-AND-124 422 names the field and keeps the cache`() =
        runTest {
            seed()
            remote.failWith = HttpFailure(422, "amount")
            assertEquals(EditOutcome.Invalid("amount"), editor.update("e1", ExpenseChanges(amountMinor = 1, currency = "EGP")))
            assertEquals(125_000L, db.expenses().find("e1")?.amountMinor)
        }

    @Test
    fun `REQ-AND-124 the field comes from the first problem error and nothing else is kept`() {
        assertEquals("occurredAt", fieldOf("""{"title":"x","errors":[{"field":"occurredAt","message":"secret 125"}]}"""))
        assertNull(fieldOf("not json"))
        assertNull(fieldOf(null))
    }

    @Test
    fun `REQ-AND-122 delete removes the row and treats 404 as gone`() =
        runTest {
            seed()
            assertEquals(EditOutcome.Deleted, editor.delete("e1"))
            assertEquals(listOf("e1"), remote.deletes)
            assertNull(db.expenses().find("e1"))
            seed()
            remote.failWith = HttpFailure(404)
            assertEquals(EditOutcome.NotFound, editor.delete("e1"))
            assertNull(db.expenses().find("e1"))
        }

    @Test
    fun `REQ-AND-126 changing the type moves the amount out of and back into the month total`() =
        runTest {
            val (from, to) = CairoTime.monthRangeMillis(java.time.Instant.ofEpochMilli(OCT_15))
            seed(row(type = "EXPENSE", status = "CONFIRMED"))
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
            remote.result = { row(type = "TRANSFER", status = "CONFIRMED") }
            editor.update("e1", ExpenseChanges(type = "TRANSFER"))
            assertEquals(0L, db.expenses().observeTotal(from, to).first())
            remote.result = { row(type = "EXPENSE", status = "CONFIRMED") }
            editor.update("e1", ExpenseChanges(type = "EXPENSE"))
            assertEquals(125_000L, db.expenses().observeTotal(from, to).first())
        }

    @Test
    fun `REQ-AND-121 the PATCH body holds only the changed fields, money as minor units`() {
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
}

private class FakeEditor(
    var stored: ExpenseEntity? = row(),
) : ExpenseEditor {
    val updates = mutableListOf<ExpenseChanges>()
    var deletes = 0
    var outcome: EditOutcome = EditOutcome.Saved(row(status = "CONFIRMED"))
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun find(id: String) = stored

    override fun observeCategories() =
        MutableStateFlow(listOf(CategoryEntity("c1", "Food", "أكل", 1), CategoryEntity("c2", "Fun", "ترفيه", 2)))

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
}

@OptIn(ExperimentalCoroutinesApi::class)
class EditExpenseViewModelTest {
    private val editor = FakeEditor()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() =
        EditExpenseViewModel(editor, FakeReceiptRemote(), SavedStateHandle(mapOf("id" to "e1"))).also {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.state.collect {} }
        }

    @Test
    fun `REQ-AND-120 confirm sends status CONFIRMED only and closes`() =
        runTest {
            val vm = vm()
            vm.confirm()
            assertEquals(listOf(ExpenseChanges(status = "CONFIRMED")), editor.updates)
            assertTrue(vm.state.value.form.done)
            assertTrue(vm.state.value.form.reviewPending)
        }

    @Test
    fun `REQ-AND-121 save sends only the changed fields and confirms a pending item`() =
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
        }

    @Test
    fun `REQ-AND-121 save with nothing changed sends nothing on a confirmed row`() =
        runTest {
            editor.stored = row(status = "CONFIRMED")
            val vm = vm()
            vm.save()
            assertTrue(editor.updates.isEmpty())
            assertTrue(vm.state.value.form.done)
        }

    @Test
    fun `REQ-AND-121 a bad amount is flagged and nothing is sent`() =
        runTest {
            val vm = vm()
            vm.onAmount("abc")
            vm.save()
            assertTrue(vm.state.value.form.amountInvalid)
            assertTrue(editor.updates.isEmpty())
        }

    @Test
    fun `REQ-AND-121 a cleared note is sent as an empty string and notes are capped at 500`() =
        runTest {
            editor.stored = row(status = "CONFIRMED", notes = "old")
            val vm = vm()
            vm.onNotes("")
            vm.save()
            assertEquals("", editor.updates.single().notes)
            vm.onNotes("x".repeat(900))
            assertEquals(500, vm.state.value.form.notes.length)
        }

    @Test
    fun `REQ-AND-125 a double tap sends one PATCH and the buttons are disabled meanwhile`() =
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
    fun `REQ-AND-122 delete asks first, then sends one DELETE and closes`() =
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
    fun `REQ-AND-123 offline keeps the edits on screen and re-enables the buttons`() =
        runTest {
            editor.outcome = EditOutcome.Offline
            val vm = vm()
            vm.onNotes("keep me")
            vm.save()
            val form = vm.state.value.form
            assertEquals(EditError.OFFLINE, form.error)
            assertEquals("keep me", form.notes)
            assertFalse(form.busy)
            assertFalse(form.done)
        }

    @Test
    fun `REQ-AND-124 a conflict shows the latest version with a notice`() =
        runTest {
            editor.outcome = EditOutcome.Conflict(row(amount = 77_700, status = "CONFIRMED"))
            val vm = vm()
            vm.onNotes("mine")
            vm.save()
            val form = vm.state.value.form
            assertTrue(form.changedElsewhere)
            assertEquals("777", form.amountText)
            assertEquals("", form.notes)
            assertFalse(form.reviewPending)
            assertFalse(form.busy)
        }

    @Test
    fun `REQ-AND-124 a rejected amount is marked on its field`() =
        runTest {
            editor.outcome = EditOutcome.Invalid("amount.amountMinor")
            val vm = vm()
            vm.onAmount("5")
            vm.save()
            assertEquals(EditError.INVALID, vm.state.value.form.error)
            assertTrue(vm.state.value.form.amountInvalid)
        }

    @Test
    fun `REQ-AND-124 the server's nested amount field names mark the amount, occurredAt marks the date`() =
        runTest {
            val vm = vm()
            for (field in listOf("amount.amountMinor", "amount.currency", "amount")) {
                editor.outcome = EditOutcome.Invalid(field)
                vm.onAmount("6")
                vm.save()
                assertTrue(field, vm.state.value.form.amountInvalid)
            }
            editor.outcome = EditOutcome.Invalid("occurredAt")
            vm.onAmount("7")
            vm.save()
            assertTrue(vm.state.value.form.dateInvalid)
            assertFalse(vm.state.value.form.amountInvalid)
        }

    @Test
    fun `REQ-AND-124 an amount above the server maximum is caught before sending`() =
        runTest {
            val vm = vm()
            vm.onAmount("100000000000.01") // 10^13 + 1 minor units
            vm.save()
            assertTrue(vm.state.value.form.amountInvalid)
            assertTrue(editor.updates.isEmpty())
            vm.onAmount("100000000000")
            vm.save()
            assertEquals(10_000_000_000_000L, editor.updates.single().amountMinor)
        }

    @Test
    fun `REQ-AND-120 confirm with typed edits sends them plus CONFIRMED, without edits status only`() =
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
    fun `REQ-AND-125 a row that is not in the cache closes the screen`() =
        runTest {
            editor.stored = null
            assertTrue(
                vm()
                    .state.value.form.done,
            )
            assertNotNull(editor.updates)
        }
}
