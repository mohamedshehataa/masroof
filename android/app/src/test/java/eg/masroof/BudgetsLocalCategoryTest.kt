package eg.masroof

import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.DefaultBudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.toInput
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** STORY-207 fix round 2: a category made on the phone must never reach the still server-backed Budgets form. */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsLocalCategoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val custom = CategoryEntity("custom-1234", "Coffee", "قهوة", 5)
    private val seeded = CategoryEntity("cat-groceries", "Groceries", "بقالة", 6, system = true)

    @Test
    fun `REQ-EXP-060 the Budgets picker offers server categories only, never a custom or built-in local one`() =
        runTest {
            val expenses = FakeRepository().apply { categories.value = BudgetsFixtures.categories + custom + seeded }
            val vm = BudgetsViewModel(FakeBudgetsRepository(), expenses)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(
                BudgetsFixtures.categories.map { it.id },
                vm.state.value.categories
                    .map { it.id },
            )
        }

    @Test
    fun `REQ-EXP-060 saving a draft with a non-UUID category id is an error state, not a crash, and a UUID still saves`() =
        runTest {
            val repo =
                object : FakeBudgetsRepository() {
                    override suspend fun create(draft: BudgetDraft): MutationOutcome {
                        draft.toInput() // throws IllegalArgumentException for a local id, like the real remote
                        return super.create(draft)
                    }
                }
            val vm = BudgetsViewModel(repo, FakeRepository().apply { categories.value = BudgetsFixtures.categories })
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            var saved = 0
            vm.save(null, "custom-1234", "100", false) { saved++ }
            runCurrent()
            assertEquals(0, saved)
            assertEquals(BudgetError.INVALID, vm.state.value.error)
            vm.save(null, BudgetsFixtures.GROCERIES, "100", false) { saved++ }
            runCurrent()
            assertEquals(1, saved)
            assertEquals(null, vm.state.value.error)
        }

    @Test
    fun `REQ-EXP-060 the budgets repository turns a non-UUID category id into Invalid instead of throwing`() =
        runTest {
            val remote =
                object : FakeBudgetsRemote() {
                    override suspend fun create(draft: BudgetDraft) {
                        draft.toInput()
                    }
                }
            val repo = DefaultBudgetsRepository(FakeInsightsCache(), remote, clock, StandardTestDispatcher(testScheduler))
            assertEquals(MutationOutcome.Invalid, repo.create(BudgetDraft("custom-1234", 100, "EGP", false)))
        }
}
