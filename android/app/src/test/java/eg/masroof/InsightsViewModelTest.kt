package eg.masroof

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.ui.viewmodel.HomeInsightsViewModel
import eg.masroof.ui.viewmodel.InsightsViewModel
import eg.masroof.ui.viewmodel.LoadStatus
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val repo = FakeInsightsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-070 shows the cached snapshot and ends ready after the refresh`() =
        runTest {
            repo.snapshots.value = mapOf(InsightsPeriod.CURRENT to InsightsFixtures.snapshot())
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            val state = vm.state.value
            assertEquals(LoadStatus.READY, state.status)
            assertEquals(InsightsPeriod.CURRENT, state.period)
            assertEquals(125_000L, state.data?.total?.amountMinor)
            assertEquals(listOf(InsightsPeriod.CURRENT), repo.refreshed)
        }

    @Test
    fun `REQ-AND-071 offline refresh keeps the cached numbers and reports offline`() =
        runTest {
            repo.snapshots.value = mapOf(InsightsPeriod.CURRENT to InsightsFixtures.snapshot())
            repo.outcome = RefreshOutcome.Offline
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            assertEquals(LoadStatus.OFFLINE, vm.state.value.status)
            assertNotNull(vm.state.value.data)
        }

    @Test
    fun `REQ-AND-071 nothing cached and an error reports error without data`() =
        runTest {
            repo.outcome = RefreshOutcome.Failed
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            assertEquals(LoadStatus.ERROR, vm.state.value.status)
            assertNull(vm.state.value.data)
        }

    @Test
    fun `REQ-AND-071 sign-in outcome is surfaced`() =
        runTest {
            repo.outcome = RefreshOutcome.SignIn
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(LoadStatus.SIGN_IN, vm.state.value.status)
        }

    @Test
    fun `REQ-AND-070 switching to last month loads that period and swaps the data`() =
        runTest {
            repo.snapshots.value =
                mapOf(
                    InsightsPeriod.CURRENT to InsightsFixtures.snapshot(total = 125_000),
                    InsightsPeriod.PREVIOUS to InsightsFixtures.snapshot(total = 100_000, daysLeft = 0),
                )
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            vm.selectPeriod(InsightsPeriod.PREVIOUS)
            runCurrent()

            assertEquals(InsightsPeriod.PREVIOUS, vm.state.value.period)
            assertEquals(
                100_000L,
                vm.state.value.data
                    ?.total
                    ?.amountMinor,
            )
            assertEquals(listOf(InsightsPeriod.CURRENT, InsightsPeriod.PREVIOUS), repo.refreshed)
        }

    @Test
    fun `REQ-AND-070 selecting the same period again does not refetch`() =
        runTest {
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.selectPeriod(InsightsPeriod.CURRENT)
            runCurrent()
            assertEquals(1, repo.refreshed.size)
        }

    @Test
    fun `REQ-AND-070 retry refreshes the selected period`() =
        runTest {
            repo.outcome = RefreshOutcome.Failed
            val vm = InsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            repo.outcome = RefreshOutcome.Ok
            vm.refresh()
            runCurrent()
            assertEquals(LoadStatus.READY, vm.state.value.status)
            assertEquals(2, repo.refreshed.size)
        }

    @Test
    fun `REQ-AND-074 home insight carries the change, trend and review count from the cache`() =
        runTest {
            repo.snapshots.value = mapOf(InsightsPeriod.CURRENT to InsightsFixtures.snapshot())
            val vm = HomeInsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            val insight = vm.state.value
            assertNotNull(insight)
            assertEquals(2_500, insight?.deltaBps)
            assertEquals(2, insight?.pendingReview)
            assertEquals(6, insight?.trend?.size)
            assertEquals(100_000L, insight?.previousTotal?.amountMinor)
            assertEquals(listOf(InsightsPeriod.CURRENT), repo.refreshed)
        }

    @Test
    fun `REQ-AND-074 home insight is absent until something is cached`() =
        runTest {
            val vm = HomeInsightsViewModel(repo)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertNull(vm.state.value)
            vm.refresh()
            runCurrent()
            assertTrue(repo.refreshed.size >= 2)
        }

    @Test
    fun `REQ-AN-147 a computation failure after data was shown moves the screen to the error state`() =
        runTest {
            val failures = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val failing =
                object : eg.masroof.data.insights.InsightsRepository by repo {
                    override val observeFailures = failures
                }
            repo.snapshots.value = mapOf(InsightsPeriod.CURRENT to InsightsFixtures.snapshot())
            val vm = InsightsViewModel(failing)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            failures.tryEmit(Unit)
            runCurrent()
            assertEquals(LoadStatus.ERROR, vm.state.value.status)
        }
}
