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
    fun `REQ-AND-010 shows total and recent from the cache and ends ready`() =
        runTest {
            repo.all.value = listOf(expense("a", 12_500), expense("b", 2_500))
            val vm = HomeViewModel(repo, FakeScheduler())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val state = vm.state.value
            assertEquals(15_000L, state.total.amountMinor)
            assertEquals(2, state.recent.size)
            assertEquals(LoadStatus.READY, state.status)
        }

    @Test
    fun `REQ-AND-010 offline refresh keeps cached rows and reports offline`() =
        runTest {
            repo.all.value = listOf(expense("a", 100))
            repo.outcome = RefreshOutcome.Offline
            val vm = HomeViewModel(repo, FakeScheduler())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.OFFLINE, vm.state.value.status)
            assertEquals(1, vm.state.value.recent.size)
        }

    @Test
    fun `REQ-AND-011 server error maps to error state and retry refreshes again`() =
        runTest {
            repo.outcome = RefreshOutcome.Failed
            val vm = TransactionsViewModel(repo, FakeScheduler())
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
    fun `REQ-AND-013 invalid amount is rejected and nothing is saved`() =
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
    fun `REQ-AND-013 invalid date is rejected`() =
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
    fun `REQ-AND-013 valid entry is stored in minor units on the Cairo date and sync is scheduled`() =
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

    @Test
    fun `REQ-AND-013 notes are capped at the contract limit of 2000 characters`() =
        runTest {
            val vm = AddExpenseViewModel(repo, FakeScheduler(), clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onNotes("x".repeat(2_500))
            assertEquals(2_000, vm.state.value.form.notes.length)
        }

    @Test
    fun `REQ-AND-013 double tap on save creates one entry`() =
        runTest {
            val vm = AddExpenseViewModel(repo, FakeScheduler(), clock)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onAmount("10")
            vm.save()
            vm.save()
            assertEquals(1, repo.added.size)
        }

    @Test
    fun `REQ-AND-011 a 401 refresh maps to the sign in again state`() =
        runTest {
            repo.outcome = RefreshOutcome.SignIn
            val vm = TransactionsViewModel(repo, FakeScheduler())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.SIGN_IN, vm.state.value.status)
        }

    @Test
    fun `REQ-AND-015 retry and delete on a failed row go to the repository`() =
        runTest {
            val scheduler = FakeScheduler()
            val vm = TransactionsViewModel(repo, scheduler)
            vm.retryEntry("a")
            vm.deleteEntry("b")
            assertEquals(listOf("a"), repo.retried)
            assertEquals(listOf("b"), repo.deleted)
            assertEquals(1, scheduler.calls)
        }
}
