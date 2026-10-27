package eg.masroof

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.BudgetsRemote
import eg.masroof.data.budgets.BudgetsSnapshot
import eg.masroof.data.budgets.DefaultBudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.insights.DefaultInsightsRepository
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.InsightsRemote
import eg.masroof.data.insights.InsightsSnapshot
import eg.masroof.data.remote.withDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshDeadlinesTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class MemoryCache : InsightsCache {
        val map = mutableMapOf<String, String>()

        override fun read(key: String) = map[key]

        override fun write(
            key: String,
            json: String,
        ) {
            map[key] = json
        }

        override fun clear() = map.clear()
    }

    private val hangingInsights =
        object : InsightsRemote {
            override suspend fun fetch(
                period: InsightsPeriod,
                nowMillis: Long,
            ): InsightsSnapshot = withDeadline(30.seconds) { awaitCancellation() }
        }

    private val hangingBudgets =
        object : BudgetsRemote {
            override suspend fun fetch(nowMillis: Long): BudgetsSnapshot = withDeadline(30.seconds) { awaitCancellation() }

            override suspend fun create(draft: BudgetDraft) = withDeadline(30.seconds) { awaitCancellation() }

            override suspend fun update(
                id: String,
                draft: BudgetDraft,
            ) = withDeadline(30.seconds) { awaitCancellation() }

            override suspend fun delete(id: String) = withDeadline(30.seconds) { awaitCancellation() }
        }

    @Test
    fun `REQ-AND-071 a server that never answers ends the Insights refresh in the offline state, not loading`() =
        runTest {
            val repo = DefaultInsightsRepository(MemoryCache(), hangingInsights, Clock.systemUTC(), Dispatchers.Unconfined)
            var outcome: RefreshOutcome? = null
            launch { outcome = repo.refresh(InsightsPeriod.CURRENT) }
            runCurrent()
            assertNull(outcome)
            advanceUntilIdle()
            assertEquals(RefreshOutcome.Offline, outcome)
        }

    @Test
    fun `REQ-AND-071 a server that never answers ends the Budgets refresh and save with an error outcome`() =
        runTest {
            val repo = DefaultBudgetsRepository(MemoryCache(), hangingBudgets, Clock.systemUTC(), Dispatchers.Unconfined)
            var refresh: RefreshOutcome? = null
            var save: MutationOutcome? = null
            launch { refresh = repo.refresh() }
            launch { save = repo.create(BudgetDraft(null, 1_000L, "EGP", false)) }
            advanceUntilIdle()
            assertEquals(RefreshOutcome.Offline, refresh)
            assertEquals(MutationOutcome.Offline, save)
        }

    @Test
    fun `REQ-AND-007 logout under a stuck network still wipes the local session`() =
        runTest {
            val remote =
                object : AuthRemote by FakeAuthRemote() {
                    override suspend fun logout(
                        baseUrl: String,
                        accessToken: String,
                        refreshToken: String,
                    ): Boolean = awaitCancellation()
                }
            val store = FakeSessionStore(LoadedSession.Found(session()))
            val manager = TestAuth.manager(store, remote)
            var done = false
            launch {
                manager.logout()
                done = true
            }
            advanceUntilIdle()
            assertEquals(true, done)
            assertEquals(1, store.clears)
            assertNull(manager.accessToken())
        }
}
