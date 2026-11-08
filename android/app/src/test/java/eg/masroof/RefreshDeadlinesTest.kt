package eg.masroof

import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.BudgetCalculator
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetSpec
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.BudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.insights.DefaultInsightsRepository
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.InsightsRemote
import eg.masroof.data.insights.InsightsSnapshot
import eg.masroof.data.remote.withDeadline
import eg.masroof.ui.viewmodel.BudgetRows
import eg.masroof.ui.viewmodel.budgetRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

    // was: a server that never answers ends the Budgets refresh and save with an error outcome. Budgets are on the phone
    // now, so there is nothing to wait for: the list and a save finish without any server.
    @Test
    fun `REQ-AND-071 budgets never wait on a server, the list is built and a save finishes on stored data alone`() =
        runTest {
            val today = java.time.LocalDate.of(2026, 10, 27)
            val stored = MutableStateFlow(listOf(budget("b1")))
            val repo =
                object : BudgetsRepository {
                    override fun observe() = stored

                    override fun observeCategories() = MutableStateFlow(listOf(CategoryEntity("c1", "Groceries", "بقالة", 1)))

                    override suspend fun create(draft: BudgetDraft): MutationOutcome {
                        stored.value = stored.value + budget("b2")
                        return MutationOutcome.Ok
                    }

                    override suspend fun update(
                        id: String,
                        amountMinor: Long,
                        rollover: Boolean,
                    ) = MutationOutcome.Ok

                    override suspend fun delete(id: String) = MutationOutcome.Ok
                }
            val engine =
                object : AnalyticsEngine {
                    override fun observe(spec: String) = emptyFlow<PeriodAnalytics>()

                    override suspend fun compute(spec: String): PeriodAnalytics = error("not used")

                    override fun observeBudgets() =
                        flowOf(
                            BudgetCalculator.statuses(
                                listOf(BudgetSpec("b1", "c1", BudgetPeriodKind.MONTHLY, 1_000, false, createdAtMillis = 1)),
                                AnalyticsPeriod.currentMonth(today, 1),
                                1,
                                today,
                                emptyList(),
                                emptyList(),
                                false,
                            ),
                        )
                }
            var rows: BudgetRows? = null
            launch { rows = budgetRows(repo, engine, flowOf(0)).first { !it.loading } }
            advanceUntilIdle()
            assertEquals(listOf("b1"), rows?.rows?.map { it.id })
            var save: MutationOutcome? = null
            launch { save = repo.create(BudgetDraft(null, 1_000L, false)) }
            advanceUntilIdle()
            assertEquals(MutationOutcome.Ok, save)
        }

    private fun budget(id: String) = BudgetEntity(id, "c1", "MONTHLY", 1_000, "EGP", createdAtMillis = 1, updatedAtMillis = 1)

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
