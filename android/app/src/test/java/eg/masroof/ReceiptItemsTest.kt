package eg.masroof

import androidx.lifecycle.SavedStateHandle
import eg.masroof.core.Money
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.ExpenseEditor
import eg.masroof.data.edit.ItemInput
import eg.masroof.data.edit.toPatch
import eg.masroof.data.receipts.ItemData
import eg.masroof.data.receipts.canonicalQty
import eg.masroof.data.receipts.cleanItemName
import eg.masroof.data.receipts.parseItemAmount
import eg.masroof.data.receipts.parseQty
import eg.masroof.data.remote.apiJson
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.ItemRow
import eg.masroof.ui.viewmodel.SectionLoad
import eg.masroof.ui.viewmodel.itemIndexOf
import eg.masroof.ui.viewmodel.itemsTotalMinor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private val CONFIRMED_ROW =
    ExpenseEntity(
        "e1",
        125_000,
        "EGP",
        1_760_000_000_000,
        "Carrefour",
        null,
        null,
        null,
        pending = false,
        source = "SMS",
        type = "EXPENSE",
        status = "CONFIRMED",
    )

private class ItemsEditor : ExpenseEditor {
    val updates = mutableListOf<ExpenseChanges>()
    var outcome: EditOutcome = EditOutcome.Saved(CONFIRMED_ROW)

    override suspend fun find(id: String) = CONFIRMED_ROW

    override fun observeCategories() = MutableStateFlow(emptyList<CategoryEntity>())

    override suspend fun update(
        id: String,
        changes: ExpenseChanges,
    ): EditOutcome {
        updates += changes
        return outcome
    }

    override suspend fun delete(id: String) = EditOutcome.Deleted
}

class ItemsParsingTest {
    @Test
    fun `REQ-AND-135 quantity accepts up to three decimals and normalizes`() {
        assertEquals("2", parseQty("2"))
        assertEquals("1.5", parseQty("1.50"))
        assertEquals("0.75", parseQty("٠٫٧٥"))
        assertEquals("1.5", parseQty("1,5"))
        assertEquals("999999", parseQty("999999"))
        assertEquals("0.001", parseQty("0.001"))
    }

    @Test
    fun `REQ-AND-135 quantity rejects zero, too many decimals, too big and junk`() {
        listOf("0", "0.000", "1.2345", "1000000", "-1", "abc", "1.2.3", "", " ", "1e3", "٠").forEach {
            assertNull("'$it'", parseQty(it))
        }
    }

    @Test
    fun `REQ-AND-135 server quantity compares equal to the typed one`() {
        assertEquals(canonicalQty("1.500"), parseQty("1.5"))
        assertNull(canonicalQty(null))
    }

    @Test
    fun `REQ-AND-135 amounts are minor units from digits only and may be zero`() {
        assertEquals(1_250L, parseItemAmount("12.50"))
        assertEquals(1_250L, parseItemAmount("12,5"))
        assertEquals(1_250L, parseItemAmount("١٢٫٥"))
        assertEquals(5L, parseItemAmount("0.05"))
        assertEquals(0L, parseItemAmount("0"))
        assertEquals(0L, parseItemAmount("0.00"))
        assertEquals(1L, parseItemAmount("0.01"))
        assertEquals(99_999_999_999L, parseItemAmount("999999999.99"))
    }

    @Test
    fun `REQ-AND-135 amounts reject junk and a third decimal`() {
        listOf("abc", "1.234", "1.2.3", "-5", ".", "").forEach { assertNull("'$it'", parseItemAmount(it)) }
    }

    @Test
    fun `REQ-AND-135 a table of decimal amounts has no rounding error`() {
        // 0.1 + 0.2 style sums are exact because everything is integer minor units.
        val cases = mapOf("0.10" to 10L, "0.20" to 20L, "19.99" to 1_999L, "1000000.01" to 100_000_001L, "0.07" to 7L)
        cases.forEach { (text, minor) -> assertEquals(text, minor, parseItemAmount(text)) }
        assertEquals(30L, itemsTotalMinor(listOf(ItemRow(1, amount = "0.10"), ItemRow(2, amount = "0.20"))))
    }

    @Test
    fun `REQ-AND-135 item names are trimmed, bounded and NUL free`() {
        assertEquals("Milk", cleanItemName("  Milk "))
        assertNull(cleanItemName("   "))
        assertNull(cleanItemName("a\u0000b"))
        assertNull(cleanItemName("x".repeat(201)))
        assertEquals(200, cleanItemName("x".repeat(200))?.length)
    }

    @Test
    fun `REQ-AND-135 items total ignores blank and invalid amounts`() {
        val rows = listOf(ItemRow(1, amount = "10"), ItemRow(2, amount = ""), ItemRow(3, amount = "x"), ItemRow(4, amount = "2.5"))
        assertEquals(1_250L, itemsTotalMinor(rows))
    }

    @Test
    fun `REQ-AND-135 a 400 field naming items n marks that index`() {
        assertEquals(2, itemIndexOf("items[2].total"))
        assertEquals(0, itemIndexOf("items[0]"))
        assertNull(itemIndexOf("amount"))
        assertNull(itemIndexOf(null))
    }

    @Test
    fun `REQ-AND-135 items go out as the items field and are absent otherwise`() {
        val with = ExpenseChanges(items = listOf(ItemInput("Milk", "2", Money(2_500, "EGP")), ItemInput("Bag", null, null))).toPatch()
        val body =
            apiJson.encodeToString(
                eg.masroof.api.models.ExpensePatch
                    .serializer(),
                with,
            )
        assertTrue(body, body.contains("\"items\""))
        assertTrue(body, body.contains("\"qty\":\"2\""))
        assertTrue(body, body.contains("\"amountMinor\":2500"))
        assertFalse(body, body.contains("unitPrice"))
        assertFalse(body, body.contains("categoryId"))
        val without =
            apiJson.encodeToString(
                eg.masroof.api.models.ExpensePatch
                    .serializer(),
                ExpenseChanges(notes = "x").toPatch(),
            )
        assertFalse(without, without.contains("items"))
    }

    @Test
    fun `REQ-AND-135 clearing all items sends an empty list and counts as a change`() {
        assertFalse(ExpenseChanges(items = emptyList()).isEmpty)
        assertTrue(ExpenseChanges().isEmpty)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EditItemsViewModelTest {
    private val editor = ItemsEditor()
    private val remote = FakeReceiptRemote()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() =
        EditExpenseViewModel(editor, remote, SavedStateHandle(mapOf("id" to "e1"))).also {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.state.collect {} }
        }

    @Test
    fun `REQ-AND-130 the form shows at once and items load from the server`() =
        runTest {
            remote.items = listOf(ItemData("Milk", "1.500", 2_500), ItemData("Bag", null, null))
            val vm = vm()
            val form = vm.state.value.form
            assertTrue(form.loaded)
            assertEquals(SectionLoad.LOADED, form.itemsLoad)
            assertEquals(listOf("Milk", "Bag"), form.items.map { it.name })
            assertEquals("1.5", form.items[0].qty)
            assertEquals("25", form.items[0].amount)
            assertEquals("", form.items[1].amount)
        }

    @Test
    fun `REQ-AND-130 a failed items load shows retry, the rest still works and nothing sends items`() =
        runTest {
            remote.itemsFailure = IOException("offline")
            val vm = vm()
            assertEquals(SectionLoad.FAILED, vm.state.value.form.itemsLoad)
            assertTrue(vm.state.value.form.loaded)
            vm.addItem() // ignored until loaded
            assertTrue(
                vm.state.value.form.items
                    .isEmpty(),
            )
            vm.onNotes("hello")
            vm.save()
            assertNull(editor.updates.single().items)
            assertEquals("hello", editor.updates.single().notes)
        }

    @Test
    fun `REQ-AND-130 retry loads the items`() =
        runTest {
            remote.itemsFailure = IOException("offline")
            val vm = vm()
            remote.itemsFailure = null
            remote.items = listOf(ItemData("Tea", null, null))
            vm.loadItems()
            assertEquals(SectionLoad.LOADED, vm.state.value.form.itemsLoad)
            assertEquals(2, remote.itemCalls)
        }

    @Test
    fun `REQ-AND-135 unchanged items send nothing`() =
        runTest {
            remote.items = listOf(ItemData("Milk", "1.500", 2_500))
            val vm = vm()
            vm.save()
            assertTrue(editor.updates.isEmpty())
            assertTrue(vm.state.value.form.done)
        }

    @Test
    fun `REQ-AND-135 an added item is sent as the full list in the same patch`() =
        runTest {
            remote.items = listOf(ItemData("Milk", null, 2_500))
            val vm = vm()
            vm.addItem()
            val key =
                vm.state.value.form.items
                    .last()
                    .key
            vm.onItemName(key, " Bread ")
            vm.onItemQty(key, "2")
            vm.onItemAmount(key, "7.5")
            vm.onNotes("n")
            vm.save()
            val sent = editor.updates.single()
            assertEquals(
                listOf(ItemInput("Milk", null, Money(2_500, "EGP")), ItemInput("Bread", "2", Money(750, "EGP"))),
                sent.items,
            )
            assertEquals("n", sent.notes)
        }

    @Test
    fun `REQ-AND-135 blank rows are dropped and removing every item clears the list`() =
        runTest {
            remote.items = listOf(ItemData("Milk", null, null))
            val vm = vm()
            vm.addItem() // blank row, never sent
            vm.removeItem(
                vm.state.value.form.items
                    .first()
                    .key,
            )
            vm.save()
            assertEquals(emptyList<ItemInput>(), editor.updates.single().items)
        }

    @Test
    fun `REQ-AND-135 a blank extra row alone changes nothing`() =
        runTest {
            val vm = vm()
            vm.addItem()
            vm.save()
            assertTrue(editor.updates.isEmpty())
        }

    @Test
    fun `REQ-AND-135 an unparsable row is marked and nothing is sent`() =
        runTest {
            val vm = vm()
            vm.addItem()
            val key =
                vm.state.value.form.items
                    .single()
                    .key
            vm.onItemName(key, "Eggs")
            vm.onItemQty(key, "0")
            vm.save()
            assertTrue(editor.updates.isEmpty())
            assertTrue(
                vm.state.value.form.items
                    .single()
                    .invalid,
            )
            vm.onItemQty(key, "12")
            assertFalse(
                vm.state.value.form.items
                    .single()
                    .invalid,
            )
        }

    @Test
    fun `REQ-AND-135 a 400 naming items n marks the sent row`() =
        runTest {
            val vm = vm()
            vm.addItem()
            vm.addItem()
            val rows = vm.state.value.form.items
            vm.onItemName(rows[0].key, "A")
            vm.onItemName(rows[1].key, "B")
            editor.outcome = EditOutcome.Invalid("items[1].name")
            vm.save()
            val after = vm.state.value.form.items
            assertFalse(after[0].invalid)
            assertTrue(after[1].invalid)
        }

    @Test
    fun `REQ-AND-135 the item cap stops adding rows`() =
        runTest {
            val vm = vm()
            repeat(120) { vm.addItem() }
            assertEquals(100, vm.state.value.form.items.size)
        }
}
