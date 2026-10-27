package eg.masroof

import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.ui.viewmodel.InsightsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsShownTest {
    private val repo = FakeInsightsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-071 the first show reuses the opening refresh and each later show refreshes once`() =
        runTest {
            val vm = InsightsViewModel(repo)
            runCurrent()
            assertEquals(1, repo.refreshed.size)
            vm.onShown()
            runCurrent()
            assertEquals(1, repo.refreshed.size)
            vm.onShown()
            runCurrent()
            assertEquals(listOf(InsightsPeriod.CURRENT, InsightsPeriod.CURRENT), repo.refreshed)
        }

    @Test
    fun `REQ-AND-071 a burst of local expense changes refreshes Insights once after the debounce`() =
        runTest {
            val signal = eg.masroof.data.LocalDataSignal()
            InsightsViewModel(repo, signal)
            runCurrent()
            assertEquals(1, repo.refreshed.size)
            signal.changed()
            signal.changed()
            advanceTimeBy(1_900)
            assertEquals(1, repo.refreshed.size)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(2, repo.refreshed.size)
        }
}
