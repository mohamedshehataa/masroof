package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.RoomBudgetsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.insights.DefaultInsightsRepository
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.InsightsRemote
import eg.masroof.data.insights.InsightsSnapshot
import eg.masroof.data.remote.withDeadline
import eg.masroof.ui.viewmodel.budgetRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    // now: the real repository and engine over an in-memory Room, no network class anywhere, finish on their own.
    @Test
    fun `REQ-AND-071 budgets never wait on a server, the list is built and a save finishes on stored data alone`() =
        runTest {
            val db =
                Room
                    .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            try {
                db.lookups().upsertCategories(listOf(CategoryEntity("c1", "Groceries", "بقالة", 1)))
                val clock = Clock.systemUTC()
                val repo = RoomBudgetsRepository(db, clock, UuidIdFactory(), EraseLock(), Dispatchers.Unconfined)
                val engine = RoomAnalyticsEngine(db, FakeSettings(), clock, Dispatchers.Unconfined)
                assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft("c1", 1_000L, false)))
                val rows = budgetRows(repo, engine, flowOf(0)).first { !it.loading && it.rows.isNotEmpty() }
                assertEquals(listOf("Groceries"), rows.rows.map { it.nameEn })
                assertTrue(rows.rows.single().hasProgress)
                assertEquals(0, rows.rows.single().progressBps)
                assertEquals(1, db.budgets().all().size)
            } finally {
                db.close()
            }
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
