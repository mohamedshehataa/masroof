package eg.masroof

import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.categories.CategoryResult
import eg.masroof.data.categories.DefaultCategoryRepository
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.viewmodel.BudgetError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-EXP-060 was "a category made on the phone must never reach the server-backed Budgets form". Budgets are local now
 * (STORY-181), so the same three tests assert the opposite where the rule is gone: a phone category is a normal choice,
 * and an id that is not a category is an error state, never a crash. Plus: a category a budget points at is archived,
 * never hard-deleted, so a category budget can never turn into "All spending".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsLocalCategoryTest : BudgetsRoomTestBase() {
    private val custom = CategoryEntity("custom-1234", "Coffee", "قهوة", 5)
    private val seeded = CategoryEntity("cat-dining", "Dining", "مطاعم", 6, system = true)

    // was: the Budgets picker offers server categories only, never a custom or built-in local one
    @Test
    fun `REQ-EXP-060 the Budgets picker offers the phone's own categories, custom and built-in`() =
        runTest {
            seedCategories(custom, seeded)
            val vm = vm()
            watch(vm)
            val ids =
                vm.state
                    .await { s -> s.categories.size == 4 }
                    .categories
                    .map { it.id }
            assertTrue("custom-1234" in ids)
            assertTrue("cat-dining" in ids)
            assertTrue(groceries.id in ids)
            assertEquals(4, ids.size)
        }

    // was: saving a draft with a non-UUID category id is an error state, not a crash, and a UUID still saves
    @Test
    fun `REQ-EXP-060 saving with an unknown category id is an error state, not a crash, and a phone category still saves`() =
        runTest {
            seedCategories(custom)
            val vm = vm()
            watch(vm)
            val closed = CompletableDeferred<Unit>()
            vm.save(null, "no-such-category", BudgetPeriodKind.MONTHLY, "100", false) { closed.complete(Unit) }
            vm.state.await { it.error == BudgetError.CATEGORY_GONE }
            assertFalse(closed.isCompleted)
            assertTrue(db.budgets().all().isEmpty())
            vm.save(null, "custom-1234", BudgetPeriodKind.MONTHLY, "100", false) { closed.complete(Unit) }
            closed.awaitReal()
            val state = vm.state.await { it.rows.size == 1 && it.error == null }
            assertNull(state.error)
            assertEquals("Coffee", state.rows.single().nameEn)
        }

    // was: the budgets repository turns a non-UUID category id into UnknownCategory instead of throwing
    @Test
    fun `REQ-EXP-060 the budgets repository turns an unknown category id into Invalid instead of throwing`() =
        runTest {
            seedCategories()
            assertEquals(MutationOutcome.UnknownCategory, repo().create(BudgetDraft("custom-1234", 100, false)))
            assertEquals(MutationOutcome.UnknownCategory, repo().create(BudgetDraft("", 100, false)))
            assertTrue(db.budgets().all().isEmpty())
        }

    @Test
    fun `REQ-AND-137 deleting a category a budget points at is refused, archiving keeps the budget as Deleted category`() =
        runTest {
            seedCategories(custom)
            val categories = DefaultCategoryRepository(db, ids, LocalDataSignal(), EraseLock())
            repo().create(BudgetDraft(null, 1_000_000, false))
            repo().create(BudgetDraft("custom-1234", 50_000, false))
            val vm = vm()
            watch(vm)
            vm.state.await { it.rows.size == 2 && it.rows.all { r -> r.hasProgress } }

            assertEquals(CategoryResult.InUse, categories.delete("custom-1234"))
            assertEquals(
                "the category row is still there",
                "custom-1234",
                db
                    .lookups()
                    .observeEveryCategory()
                    .first()
                    .single {
                        it.id ==
                            "custom-1234"
                    }.id,
            )
            assertEquals(
                "custom-1234",
                db
                    .budgets()
                    .all()
                    .first { it.categoryId != null }
                    .categoryId,
            )

            categories.setArchived("custom-1234", true)
            val state = vm.state.await { s -> s.rows.any { it.deletedCategory } }
            val deleted = state.rows.single { it.deletedCategory }
            assertEquals("custom-1234", deleted.categoryId)
            // the total budget is unchanged and there is still exactly one of it
            val totals = state.rows.filter { it.categoryId == null && !it.deletedCategory }
            assertEquals(1, totals.size)
            assertEquals(1_000_000L, totals.single().limit.amountMinor)
            assertEquals(1, db.budgets().all().count { it.categoryId == null })
        }

    @Test
    fun `REQ-AND-137 no category delete path can null a budget's category`() =
        runTest {
            seedCategories(custom)
            val categories = DefaultCategoryRepository(db, ids, LocalDataSignal(), EraseLock())
            repo().create(BudgetDraft("custom-1234", 50_000, false, BudgetPeriodKind.WEEKLY))
            assertEquals(CategoryResult.InUse, categories.delete("custom-1234"))
            assertEquals(
                "custom-1234",
                db
                    .budgets()
                    .all()
                    .single()
                    .categoryId,
            )
        }
}
