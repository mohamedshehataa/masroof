package eg.masroof

import eg.masroof.api.models.BudgetPeriod
import eg.masroof.core.Money
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.DefaultBudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.toInput
import eg.masroof.data.remote.HttpFailure
import eg.masroof.ui.budgets.BudgetLevel
import eg.masroof.ui.budgets.levelOf
import eg.masroof.ui.budgets.plainAmount
import eg.masroof.ui.budgets.toRows
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsViewModel
import eg.masroof.ui.viewmodel.HomeBudgetsViewModel
import eg.masroof.ui.viewmodel.LoadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsLogicTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-073 levels switch exactly at 50, 80 and 100 percent`() {
        assertEquals(BudgetLevel.OK, levelOf(4_999))
        assertEquals(BudgetLevel.HALF, levelOf(5_000))
        assertEquals(BudgetLevel.HALF, levelOf(7_999))
        assertEquals(BudgetLevel.WARNING, levelOf(8_000))
        assertEquals(BudgetLevel.WARNING, levelOf(9_999))
        assertEquals(BudgetLevel.OVER, levelOf(10_000))
    }

    @Test
    fun `REQ-AND-073 the wire format decodes and rows are sorted closest to the limit first with category names`() {
        val rows = BudgetsFixtures.snapshot().toRows(BudgetsFixtures.categories)
        assertEquals(listOf("Car", "Groceries"), rows.filter { it.categoryId != null }.map { it.nameEn })
        assertEquals(listOf(12_000, 8_250, 5_000), rows.map { it.progressBps })
        assertEquals(BudgetLevel.OVER, rows[0].level)
        assertEquals(10_000, rows[0].barBps)
        assertEquals(Money(-10_000, "EGP"), rows[0].remaining)
        assertNull(rows[1].nameEn)
        assertEquals(6, rows[2].daysLeft)
    }

    @Test
    fun `REQ-AND-073 spent-of uses the effective limit including rollover`() {
        val row = BudgetsFixtures.weeklyRollover().toRows(BudgetsFixtures.categories).single()
        assertEquals(Money(100_000, "USD"), row.limit)
        assertEquals(Money(120_000, "USD"), row.effectiveLimit)
        assertEquals(Money(70_000, "USD"), row.remaining)
        assertEquals(BudgetPeriod.WEEKLY, row.period)
    }

    @Test
    fun `REQ-AN-051 a limit used up by rollover reads as over budget`() {
        val row = BudgetsFixtures.exhausted().toRows(BudgetsFixtures.categories).single()
        assertEquals(Money(0, "EGP"), row.effectiveLimit)
        assertEquals(BudgetLevel.OVER, row.level)
        assertEquals(10_000, row.barBps)
    }

    @Test
    fun `REQ-AND-073 editing a weekly budget keeps it weekly and uses the household currency`() {
        assertEquals(BudgetPeriod.WEEKLY, BudgetDraft(null, 100, "USD", false, BudgetPeriod.WEEKLY).toInput().period)
        assertEquals(BudgetPeriod.MONTHLY, BudgetDraft(null, 100, "EGP", false).toInput().period)
        runTest {
            val repo = FakeBudgetsRepository().apply { snapshot.value = BudgetsFixtures.weeklyRollover() }
            val vm = BudgetsViewModel(repo, FakeRepository().apply { categories.value = BudgetsFixtures.categories })
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.save(BudgetsFixtures.ID_WEEKLY, null, "800", false) {}
            runCurrent()
            val draft = repo.updated.single().second
            assertEquals(BudgetPeriod.WEEKLY, draft.period)
            assertEquals("USD", draft.currency)
        }
    }

    @Test
    fun `REQ-AND-074 home shows the four budgets closest to the limit`() =
        runTest {
            val repo = FakeBudgetsRepository().apply { snapshot.value = BudgetsFixtures.five() }
            val vm = HomeBudgetsViewModel(repo, FakeRepository())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(listOf(5_000, 4_000, 3_000, 2_000), vm.state.value.map { it.progressBps })
        }

    @Test
    fun `REQ-AND-073 plain amounts keep minor units without float math`() {
        assertEquals("1500", plainAmount(Money(150_000)))
        assertEquals("1500.05", plainAmount(Money(150_005)))
        assertEquals("0.50", plainAmount(Money(50)))
    }

    @Test
    fun `REQ-AND-073 view model shows cached rows and ends ready`() =
        runTest {
            val repo = FakeBudgetsRepository().apply { snapshot.value = BudgetsFixtures.snapshot() }
            val expenses = FakeRepository().apply { categories.value = BudgetsFixtures.categories }
            val vm = BudgetsViewModel(repo, expenses)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            assertEquals(LoadStatus.READY, vm.state.value.status)
            assertEquals(3, vm.state.value.rows.size)
            assertEquals(1, repo.refreshes)
        }

    @Test
    fun `REQ-AND-073 offline refresh keeps the cached values and reports offline`() =
        runTest {
            val repo =
                FakeBudgetsRepository().apply {
                    snapshot.value = BudgetsFixtures.snapshot()
                    refreshOutcome = RefreshOutcome.Offline
                }
            val vm = BudgetsViewModel(repo, FakeRepository())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            assertEquals(LoadStatus.OFFLINE, vm.state.value.status)
            assertEquals(3, vm.state.value.rows.size)
        }

    @Test
    fun `REQ-AND-073 saving validates the amount locally and sends minor units`() =
        runTest {
            val repo = FakeBudgetsRepository()
            val vm = BudgetsViewModel(repo, FakeRepository())
            backgroundScope.launch { vm.state.collect {} }
            var saved = 0

            vm.save(null, null, "0", false) { saved++ }
            runCurrent()
            assertEquals(BudgetError.INVALID_AMOUNT, vm.state.value.error)
            assertTrue(repo.created.isEmpty())

            vm.save(null, BudgetsFixtures.GROCERIES, "1,500.50".replace(",", ""), true) { saved++ }
            runCurrent()
            assertEquals(1, saved)
            assertEquals(150_050L, repo.created.single().amountMinor)
            assertEquals("EGP", repo.created.single().currency)
            assertEquals(true, repo.created.single().rollover)
            assertNull(vm.state.value.error)

            vm.save(BudgetsFixtures.ID_CAR, null, "٥٠٠", false) { saved++ }
            runCurrent()
            assertEquals(2, saved)
            assertEquals(BudgetsFixtures.ID_CAR to 50_000L, repo.updated.single().let { it.first to it.second.amountMinor })
        }

    @Test
    fun `REQ-AND-073 a rejected save keeps the form open with the reason`() =
        runTest {
            val repo = FakeBudgetsRepository().apply { mutation = MutationOutcome.Duplicate }
            val vm = BudgetsViewModel(repo, FakeRepository())
            backgroundScope.launch { vm.state.collect {} }
            var saved = 0
            vm.save(null, BudgetsFixtures.CAR, "100", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            vm.clearError()
            assertNull(vm.state.value.error)
        }

    /** The server answers create with the given outcomes in order (then Duplicate) and update with Ok. */
    private class ConflictRepo(
        snapshotNow: eg.masroof.data.budgets.BudgetsSnapshot? = BudgetsFixtures.snapshot(),
        vararg createAnswers: MutationOutcome,
    ) : FakeBudgetsRepository() {
        private val answers = ArrayDeque(createAnswers.toList())

        init {
            snapshot.value = snapshotNow
        }

        override suspend fun create(draft: BudgetDraft): MutationOutcome {
            created += draft
            return answers.removeFirstOrNull() ?: MutationOutcome.Duplicate
        }

        override suspend fun update(
            id: String,
            draft: BudgetDraft,
        ): MutationOutcome {
            updated += id to draft
            return MutationOutcome.Ok
        }
    }

    private fun kotlinx.coroutines.test.TestScope.vmOn(repo: ConflictRepo): BudgetsViewModel {
        val vm = BudgetsViewModel(repo, FakeRepository())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun `REQ-AND-073 a timed-out create that went through counts as saved when the retry gets a duplicate`() =
        runTest {
            val repo = ConflictRepo(null, MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, BudgetsFixtures.CAR, "500", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.OFFLINE, vm.state.value.error)
            // the held request went through: the next refresh shows the budget
            repo.snapshot.value = BudgetsFixtures.snapshot()
            vm.save(null, BudgetsFixtures.CAR, "500", false) { saved++ }
            runCurrent()
            assertEquals(1, saved)
            assertNull(vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
            assertTrue(
                vm.state.value.rows
                    .any { it.id == BudgetsFixtures.ID_CAR },
            )
        }

    @Test
    fun `REQ-AND-073 a timed-out create whose budget exists with other values is updated to what was entered`() =
        runTest {
            val repo = ConflictRepo(BudgetsFixtures.snapshot(), MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, BudgetsFixtures.CAR, "900", true) { saved++ }
            vm.save(null, BudgetsFixtures.CAR, "900", true) { saved++ }
            runCurrent()
            assertEquals(1, saved)
            assertNull(vm.state.value.error)
            val (id, draft) = repo.updated.single()
            assertEquals(BudgetsFixtures.ID_CAR, id)
            assertEquals(90_000L, draft.amountMinor)
            assertEquals(true, draft.rollover)
        }

    @Test
    fun `REQ-AND-073 a duplicate for a budget the user never tried to create before is an error and changes nothing`() =
        runTest {
            val repo = ConflictRepo()
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, null, "900", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
        }

    @Test
    fun `REQ-AND-073 after a timed-out create a duplicate with no matching budget in the fresh list stays an error`() =
        runTest {
            val repo = ConflictRepo(null, MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, BudgetsFixtures.CAR, "500", false) { saved++ }
            vm.save(null, BudgetsFixtures.CAR, "500", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
        }

    @Test
    fun `REQ-AND-073 a timed-out create never adopts a budget of a different category`() =
        runTest {
            val repo = ConflictRepo(BudgetsFixtures.snapshot(), MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, "99999999-9999-4999-8999-999999999999", "500", false) { saved++ }
            // then another scope that exists on the server answers 409: it is not ours
            vm.save(null, BudgetsFixtures.CAR, "900", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
        }

    @Test
    fun `REQ-AND-073 a timed-out monthly create never adopts a weekly budget of the same category`() =
        runTest {
            val repo = ConflictRepo(BudgetsFixtures.weeklyRollover(), MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, BudgetsFixtures.GROCERIES, "500", false) { saved++ }
            vm.save(null, BudgetsFixtures.GROCERIES, "500", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
        }

    @Test
    fun `REQ-AND-073 a timed-out create is not adopted from a stale cache when the refresh fails`() =
        runTest {
            val repo = ConflictRepo(BudgetsFixtures.snapshot(), MutationOutcome.Offline)
            val vm = vmOn(repo)
            var saved = 0
            vm.save(null, BudgetsFixtures.CAR, "900", false) { saved++ }
            repo.refreshOutcome = RefreshOutcome.Offline
            vm.save(null, BudgetsFixtures.CAR, "900", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.DUPLICATE, vm.state.value.error)
            assertTrue(repo.updated.isEmpty())
        }

    @Test
    fun `REQ-AND-073 delete closes on success and when the budget is already gone`() =
        runTest {
            val repo = FakeBudgetsRepository()
            val vm = BudgetsViewModel(repo, FakeRepository())
            backgroundScope.launch { vm.state.collect {} }
            var done = 0
            vm.delete(BudgetsFixtures.ID_CAR) { done++ }
            repo.mutation = MutationOutcome.NotFound
            vm.delete(BudgetsFixtures.ID_CAR) { done++ }
            repo.mutation = MutationOutcome.Offline
            vm.delete(BudgetsFixtures.ID_CAR) { done++ }
            runCurrent()
            assertEquals(2, done)
            assertEquals(BudgetError.OFFLINE, vm.state.value.error)
        }

    @Test
    fun `REQ-AND-074 home shows the three budgets closest to the limit`() =
        runTest {
            val repo = FakeBudgetsRepository().apply { snapshot.value = BudgetsFixtures.snapshot() }
            val vm = HomeBudgetsViewModel(repo, FakeRepository().apply { categories.value = BudgetsFixtures.categories })
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(listOf(12_000, 8_250, 5_000), vm.state.value.map { it.progressBps })
            assertEquals(1, repo.refreshes)
        }

    @Test
    fun `REQ-AND-073 repository caches the last good answer and serves it offline`() =
        runTest {
            val cache = FakeInsightsCache()
            val remote = FakeBudgetsRemote()
            val repo = DefaultBudgetsRepository(cache, remote, clock, StandardTestDispatcher(testScheduler))
            assertNull(repo.observe().first())

            assertEquals(RefreshOutcome.Ok, repo.refresh())
            assertEquals(clock.millis(), repo.observe().first()?.fetchedAtMillis)

            remote.failure = IOException("offline")
            assertEquals(RefreshOutcome.Offline, repo.refresh())
            assertEquals(
                3,
                repo
                    .observe()
                    .first()
                    ?.status
                    ?.items
                    ?.size,
            )
        }

    @Test
    fun `REQ-AND-073 repository maps http failures to outcomes`() =
        runTest {
            val remote = FakeBudgetsRemote()
            val repo = DefaultBudgetsRepository(FakeInsightsCache(), remote, clock, StandardTestDispatcher(testScheduler))
            val draft =
                eg.masroof.data.budgets
                    .BudgetDraft(null, 100, "EGP", false)

            fun outcome(code: Int): MutationOutcome {
                remote.failure = HttpFailure(code)
                return kotlinx.coroutines.runBlocking { repo.create(draft) }
            }
            assertEquals(MutationOutcome.Invalid, outcome(400))
            assertEquals(MutationOutcome.SignIn, outcome(401))
            assertEquals(MutationOutcome.Duplicate, outcome(409))
            assertEquals(MutationOutcome.Invalid, outcome(422))
            assertEquals(MutationOutcome.Failed, outcome(500))
            remote.failure = IOException("down")
            assertEquals(MutationOutcome.Offline, repo.delete("x"))
            remote.failure = null
            assertEquals(MutationOutcome.Ok, repo.update("id-1", draft))
            assertEquals(listOf("update:id-1"), remote.calls)
        }
}
