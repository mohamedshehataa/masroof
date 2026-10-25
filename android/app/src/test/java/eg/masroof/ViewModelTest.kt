package eg.masroof

import eg.masroof.data.RefreshOutcome
import eg.masroof.ui.AddExpenseViewModel
import eg.masroof.ui.HomeViewModel
import eg.masroof.ui.LoadStatus
import eg.masroof.ui.TransactionsViewModel
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
import java.time.Clock
import java.time.Instant
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
    fun `REQ-UI-HOME shows total and recent from the cache and ends ready`() =
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
    fun `REQ-UI-HOME offline refresh keeps cached rows and reports offline`() =
        runTest {
            repo.all.value = listOf(expense("a", 100))
            repo.outcome = RefreshOutcome.Offline
            val vm = HomeViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.OFFLINE, vm.state.value.status)
            assertEquals(1, vm.state.value.recent.size)
        }

    @Test
    fun `REQ-UI-TX server error maps to error state and retry refreshes again`() =
        runTest {
            repo.outcome = RefreshOutcome.Failed
            val vm = TransactionsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.ERROR, vm.state.value.status)
            assertTrue(
                vm.state.value.rows
                    .isEmpty(),
            )
            repo.outcome = RefreshOutcome.Ok
            vm.refresh()
            assertEquals(LoadStatus.READY, vm.state.value.status)
            assertEquals(2, repo.refreshCalls)
        }

    @Test
    fun `REQ-UI-ADD invalid amount is rejected and nothing is saved`() =
        runTest {
            val scheduler = FakeScheduler()
            val vm = AddExpenseViewModel(repo, scheduler, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("abc")
            vm.save()
            assertTrue(vm.state.value.form.amountInvalid)
            assertTrue(repo.added.isEmpty())
            assertEquals(0, scheduler.calls)
        }

    @Test
    fun `REQ-UI-ADD invalid date is rejected`() =
        runTest {
            val vm = AddExpenseViewModel(repo, FakeScheduler(), clock)
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
    fun `REQ-UI-ADD valid entry is stored in minor units on the Cairo date and sync is scheduled`() =
        runTest {
            val scheduler = FakeScheduler()
            val vm = AddExpenseViewModel(repo, scheduler, clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals("2026-10-15", vm.state.value.form.dateText)
            vm.onAmount("125.50")
            vm.onNotes("  lunch ")
            vm.save()
            val saved = repo.added.single()
            assertEquals(12_550L, saved.amount.amountMinor)
            assertEquals("lunch", saved.notes)
            assertEquals(1, scheduler.calls)
            assertTrue(vm.state.value.form.saved)
        }
}
