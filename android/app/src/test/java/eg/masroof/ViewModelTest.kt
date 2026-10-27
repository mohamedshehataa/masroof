package eg.masroof

import eg.masroof.core.Money
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.HomeViewModel
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val repo = FakeRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-130 Home shows total and recent from Room and is ready at once`() =
        runTest {
            repo.all.value = listOf(expense("a", 12_500), expense("b", 2_500))
            val vm = HomeViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val state = vm.state.value
            assertEquals(15_000L, state.total.amountMinor)
            assertEquals(2, state.recent.size)
            assertEquals(LoadStatus.READY, state.status)
        }

    @Test
    fun `REQ-AND-130 Transactions list is ready with an empty list on a fresh install`() =
        runTest {
            val vm = TransactionsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.READY, vm.state.value.status)
            assertTrue(
                vm.state.value.rows
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-AND-133 a row never shows the waiting or failed state`() =
        runTest {
            repo.all.value = listOf(expense("a", 100, pending = true).copy(failed = true))
            val vm = TransactionsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val row =
                vm.state.value.rows
                    .single()
            assertFalse(row.pending)
            assertFalse(row.failed)
        }

    @Test
    fun `REQ-EXP-043 rows get their category name from every category including the phone's defaults`() =
        runTest {
            repo.categories.value = listOf(CategoryEntity("cat-car.fuel", "Fuel", "بنزين", 1, system = true))
            repo.all.value = listOf(expense("a", 100).copy(categoryId = "cat-car.fuel"))
            val vm = TransactionsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(
                "Fuel",
                vm.state.value.rows
                    .single()
                    .category
                    ?.nameEn,
            )
        }

    @Test
    fun `REQ-EXP-043 empty zero negative and text amounts show Enter an amount and write nothing`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            for (text in listOf("", "0", "-5", "abc", "0.00")) {
                vm.onAmount(text)
                vm.save()
                assertTrue("\"$text\"", vm.state.value.form.amountInvalid)
                assertFalse(vm.state.value.form.amountTooLarge)
            }
            assertTrue(repo.added.isEmpty())
        }

    @Test
    fun `REQ-EXP-043 an amount above 1,000,000,000,000 minor units shows Amount is too large`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("10000000000.01")
            vm.save()
            assertTrue(vm.state.value.form.amountTooLarge)
            assertFalse(vm.state.value.form.amountInvalid)
            assertTrue(repo.added.isEmpty())
            // the limit itself is allowed
            vm.onAmount("10000000000")
            vm.save()
            assertEquals(
                1_000_000_000_000L,
                repo.added
                    .single()
                    .amount.amountMinor,
            )
        }

    @Test
    fun `REQ-EXP-043 Arabic-Indic digits and thousands separators are read as the same amount`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("٢٠٠")
            vm.save()
            assertEquals(
                20_000L,
                repo.added
                    .last()
                    .amount.amountMinor,
            )
            val second = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { second.state.collect {} }
            runCurrent()
            second.onAmount("1,450")
            second.save()
            assertEquals(
                145_000L,
                repo.added
                    .last()
                    .amount.amountMinor,
            )
        }

    @Test
    fun `REQ-AND-013 invalid date is rejected`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("10")
            vm.onDate("15/10/2026")
            vm.save()
            assertTrue(vm.state.value.form.dateInvalid)
            assertFalse(vm.state.value.form.amountInvalid)
            assertTrue(repo.added.isEmpty())
        }

    @Test
    fun `REQ-EXP-043 a valid entry is saved in minor units on the picked Cairo date`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals("2026-10-15", vm.state.value.form.dateText)
            vm.onAmount("125.50")
            vm.onNotes("  lunch ")
            vm.onDate("2026-10-10")
            vm.save()
            val saved = repo.added.single()
            assertEquals(Money(12_550L), saved.amount)
            assertEquals(LocalDate.of(2026, 10, 10), saved.date)
            assertEquals("lunch", saved.notes)
            assertTrue(vm.state.value.form.saved)
        }

    @Test
    fun `REQ-EXP-043 a failed write keeps the sheet open with the input and says Couldn't save`() =
        runTest {
            repo.failAdd = IOException("disk")
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("200")
            vm.onNotes("benzin")
            vm.save()
            val form = vm.state.value.form
            assertTrue(form.saveFailed)
            assertFalse(form.saved)
            assertFalse(form.saving)
            assertEquals("200", form.amountText)
            assertEquals("benzin", form.notes)
            // the user can try again and it works
            repo.failAdd = null
            vm.save()
            assertTrue(vm.state.value.form.saved)
            assertFalse(vm.state.value.form.saveFailed)
        }

    @Test
    fun `REQ-AND-013 notes are capped at 500 characters`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onNotes("x".repeat(2_500))
            assertEquals(500, vm.state.value.form.notes.length)
        }

    @Test
    fun `REQ-AND-013 double tap on save creates one entry`() =
        runTest {
            val vm = AddExpenseViewModel(repo, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("10")
            vm.save()
            vm.save()
            assertEquals(1, repo.added.size)
        }
}
