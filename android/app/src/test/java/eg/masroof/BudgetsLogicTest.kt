package eg.masroof

import eg.masroof.core.Money
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetStatus
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.RoomBudgetsRepository
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.budgets.BudgetLevel
import eg.masroof.ui.budgets.levelOf
import eg.masroof.ui.budgets.plainAmount
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.HomeBudgetsViewModel
import eg.masroof.ui.viewmodel.LoadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The STORY-073/074 budget logic tests, retargeted from the server (`BudgetsRemote`, the cached snapshot) to Room in
 * STORY-181. Where a test only made sense with a server (timeouts, adopting a half-sent create, the cache) it became its
 * local equivalent, named below. Real database, repository and engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsLogicTest : BudgetsRoomTestBase() {
    private val august = Instant.parse("2026-08-01T08:00:00Z").toEpochMilli()

    private fun budget(
        id: String,
        category: String?,
        limit: Long,
        period: String = "MONTHLY",
        rollover: Boolean = false,
        createdAt: Long = august,
    ) = BudgetEntity(id, category, period, limit, "EGP", rollover, createdAtMillis = createdAt, updatedAtMillis = createdAt)

    @Test
    fun `REQ-AND-073 levels switch exactly at 50, 80 and 100 percent`() {
        assertEquals(BudgetLevel.OK, levelOf(4_999))
        assertEquals(BudgetLevel.HALF, levelOf(5_000))
        assertEquals(BudgetLevel.HALF, levelOf(7_999))
        assertEquals(BudgetLevel.WARNING, levelOf(8_000))
        assertEquals(BudgetLevel.WARNING, levelOf(9_999))
        assertEquals(BudgetLevel.OVER, levelOf(10_000))
    }

    // was: the wire format decodes and rows are sorted closest to the limit first with category names
    @Test
    fun `REQ-AND-073 stored budgets become rows sorted closest to the limit first with category names`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b-g", groceries.id, 100_000))
            db.budgets().insert(budget("b-t", null, 400_000))
            db.budgets().insert(budget("b-c", car.id, 50_000))
            db.expenses().insert(entry("g", 50_000))
            db.expenses().insert(entry("c", 60_000, category = car.id))
            db.expenses().insert(entry("m", 220_000, category = null))
            val vm = vm()
            watch(vm)
            val rows = vm.state.await { it.rows.size == 3 }.rows
            assertEquals(listOf("Car", "Groceries"), rows.filter { it.categoryId != null }.map { it.nameEn })
            assertEquals(listOf(12_000, 8_250, 5_000), rows.map { it.progressBps })
            assertEquals(BudgetLevel.OVER, rows[0].level)
            assertEquals(10_000, rows[0].barBps)
            assertEquals(Money(-10_000, "EGP"), rows[0].remaining)
            assertNull(rows[1].nameEn)
            assertTrue(rows[2].daysLeft!! in 4..5)
        }

    @Test
    fun `REQ-AND-073 spent-of uses the effective limit including rollover`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b", groceries.id, 100_000, rollover = true))
            db.expenses().insert(entry("sep", 80_000, at = "2026-09-15T08:00:00Z"))
            db.expenses().insert(entry("oct", 50_000))
            val vm = vm()
            watch(vm)
            val row =
                vm.state
                    .await { it.rows.singleOrNull()?.hasProgress == true }
                    .rows
                    .single()
            assertEquals(Money(100_000, "EGP"), row.limit)
            assertEquals(Money(120_000, "EGP"), row.effectiveLimit)
            assertEquals(Money(70_000, "EGP"), row.remaining)
            assertEquals(BudgetPeriodKind.MONTHLY, row.period)
        }

    @Test
    fun `REQ-AN-051 a limit used up by rollover reads as over budget`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b", car.id, 100_000, rollover = true))
            db.expenses().insert(entry("sep", 200_000, category = car.id, at = "2026-09-15T08:00:00Z"))
            val vm = vm()
            watch(vm)
            val row =
                vm.state
                    .await { it.rows.singleOrNull()?.hasProgress == true }
                    .rows
                    .single()
            assertEquals(Money(0, "EGP"), row.effectiveLimit)
            assertEquals(BudgetLevel.OVER, row.level)
            assertEquals(10_000, row.barBps)
        }

    // was: editing a weekly budget keeps it weekly and uses the household currency
    @Test
    fun `REQ-AND-073 editing a weekly budget keeps it weekly and in EGP`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("w", null, 100_000, period = "WEEKLY"))
            val vm = vm()
            watch(vm)
            vm.state.await { it.rows.size == 1 }
            vm.save("w", null, BudgetPeriodKind.MONTHLY, "800", false) {}
            vm.state.await { it.rows.singleOrNull()?.limit == Money(80_000) }
            val stored = db.budgets().byId("w")!!
            assertEquals("WEEKLY", stored.period)
            assertEquals("EGP", stored.currency)
            assertEquals(80_000L, stored.amountMinor)
        }

    @Test
    fun `REQ-AND-074 home shows the four budgets closest to the limit`() =
        runTest {
            val cats = (1..5).map { CategoryEntity("h$it", "Home $it", "بيت $it", it) }
            seedCategories(*cats.toTypedArray())
            cats.forEachIndexed { i, c ->
                db.budgets().insert(budget("hb$i", c.id, 100_000))
                db.expenses().insert(entry("hx$i", 10_000L * (i + 1), category = c.id))
            }
            val home = HomeBudgetsViewModel(repo(), engine())
            backgroundScope.launch { home.state.collect {} }
            assertEquals(listOf(5_000, 4_000, 3_000, 2_000), home.state.await { it.size == 4 }.map { it.progressBps })
        }

    @Test
    fun `REQ-AND-073 plain amounts keep minor units without float math`() {
        assertEquals("1500", plainAmount(Money(150_000)))
        assertEquals("1500.05", plainAmount(Money(150_005)))
        assertEquals("0.50", plainAmount(Money(50)))
    }

    // was: view model shows cached rows and ends ready
    @Test
    fun `REQ-AND-073 view model shows stored rows and ends ready`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b", groceries.id, 100_000))
            val vm = vm()
            watch(vm)
            val state = vm.state.await { it.status == LoadStatus.READY && it.rows.size == 1 }
            assertEquals(LoadStatus.READY, state.status)
            assertEquals("Groceries", state.rows.single().nameEn)
            assertFalse(state.progressFailed)
        }

    // was: offline refresh keeps the cached values and reports offline
    @Test
    fun `REQ-AND-073 with no server at all the stored values show and no offline status exists`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b", groceries.id, 100_000))
            val vm = vm()
            watch(vm)
            val state = vm.state.await { it.rows.size == 1 }
            assertEquals(Money(100_000), state.rows.single().limit)
            assertFalse(state.status == LoadStatus.OFFLINE)
            assertFalse(state.status == LoadStatus.SIGN_IN)
            assertNull(state.error)
        }

    // was: saving validates the amount locally and sends minor units
    @Test
    fun `REQ-AND-073 saving validates the amount locally and stores minor units`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "abc", false) {}
            vm.state.await { it.error == BudgetError.INVALID_AMOUNT }
            assertTrue(db.budgets().all().isEmpty())
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "1500.5", true) {}
            vm.state.await { it.rows.size == 1 }
            val stored = db.budgets().all().single()
            assertEquals(150_050L, stored.amountMinor)
            assertTrue(stored.rollover)
        }

    // was: a rejected save keeps the form open with the reason
    @Test
    fun `REQ-AND-073 a refused save keeps the form open with the reason`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 100_000, false))
            val vm = vm()
            watch(vm)
            val closed = CompletableDeferred<Unit>()
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "500", false) { closed.complete(Unit) }
            val state = vm.state.await { it.error == BudgetError.DUPLICATE }
            assertFalse(closed.isCompleted)
            assertEquals(BudgetError.DUPLICATE, state.error)
            assertEquals(1, db.budgets().all().size)
        }

    // was: a timed-out create that went through counts as saved when the retry gets a duplicate
    @Test
    fun `REQ-AND-136 a second budget for the same scope is refused and nothing changes`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 100_000, false)))
            assertEquals(MutationOutcome.Duplicate, repo.create(BudgetDraft(groceries.id, 100_000, false)))
            assertEquals(1, db.budgets().all().size)
            assertEquals(
                100_000L,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    // was: a timed-out create whose budget exists with other values is updated to what was entered
    @Test
    fun `REQ-AND-136 a duplicate with other values is never adopted or updated`() =
        runTest {
            seedCategories()
            val repo = repo()
            repo.create(BudgetDraft(groceries.id, 100_000, false))
            assertEquals(MutationOutcome.Duplicate, repo.create(BudgetDraft(groceries.id, 250_000, true)))
            val stored = db.budgets().all().single()
            assertEquals(100_000L, stored.amountMinor)
            assertFalse(stored.rollover)
        }

    // was: a duplicate for a budget the user never tried to create before is an error and changes nothing
    @Test
    fun `REQ-AND-136 a duplicate for a budget made earlier is an error and changes nothing`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, null, BudgetPeriodKind.MONTHLY, "4000", false) {}
            vm.state.await { it.rows.size == 1 }
            vm.save(null, null, BudgetPeriodKind.MONTHLY, "9000", false) {}
            val state = vm.state.await { it.error == BudgetError.DUPLICATE }
            assertEquals(1, state.rows.size)
            assertEquals(
                400_000L,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    // was: after a timed-out create a duplicate with no matching budget in the fresh list stays an error
    @Test
    fun `REQ-AND-136 a refused duplicate does not depend on the engine`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 100_000, false))
            val broken =
                object : AnalyticsEngine {
                    override fun observe(spec: String): Flow<PeriodAnalytics> = emptyFlow()

                    override suspend fun compute(spec: String): PeriodAnalytics = error("not used")

                    override fun observeBudgets(): Flow<List<BudgetStatus>> = flow { error("boom") }
                }
            val vm = vm(broken)
            watch(vm)
            vm.state.await { it.progressFailed }
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "700", false) {}
            val state = vm.state.await { it.error == BudgetError.DUPLICATE }
            assertEquals(1, state.rows.size)
            assertEquals(
                100_000L,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    // was: a timed-out create never adopts a budget of a different category
    @Test
    fun `REQ-AND-136 a budget of another category does not block this one`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(car.id, 100_000, false)))
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 100_000, false)))
            assertEquals(
                setOf(car.id, groceries.id),
                db
                    .budgets()
                    .all()
                    .map { it.categoryId }
                    .toSet(),
            )
        }

    // was: a timed-out monthly create never adopts a weekly budget of the same category
    @Test
    fun `REQ-AND-136 a weekly budget does not block the monthly one of the same category`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 100_000, false, BudgetPeriodKind.WEEKLY)))
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 400_000, false)))
            assertEquals(MutationOutcome.Duplicate, repo.create(BudgetDraft(groceries.id, 1, false, BudgetPeriodKind.WEEKLY)))
            assertEquals(2, db.budgets().all().size)
        }

    // was: a timed-out create is not adopted from a stale cache when the refresh fails
    @Test
    fun `REQ-AND-136 nothing is taken from a stale copy, only the table decides`() =
        runTest {
            seedCategories()
            val repo = repo()
            repo.create(BudgetDraft(groceries.id, 100_000, false))
            db.budgets().all().forEach { db.budgets().delete(it) }
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 300_000, false)))
            assertEquals(
                300_000L,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-AND-073 delete closes on success and when the budget is already gone`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("b", groceries.id, 100_000))
            val vm = vm()
            watch(vm)
            vm.state.await { it.rows.size == 1 }
            val first = CompletableDeferred<Unit>()
            vm.delete("b") { first.complete(Unit) }
            first.awaitReal()
            assertTrue(db.budgets().all().isEmpty())
            val second = CompletableDeferred<Unit>()
            vm.delete("b") { second.complete(Unit) }
            second.awaitReal()
            assertTrue(second.isCompleted)
        }

    @Test
    fun `REQ-AND-074 home shows the three budgets closest to the limit`() =
        runTest {
            seedCategories()
            db.budgets().insert(budget("a", groceries.id, 100_000))
            db.budgets().insert(budget("b", car.id, 100_000))
            db.budgets().insert(budget("c", null, 1_000_000))
            db.expenses().insert(entry("x", 30_000))
            db.expenses().insert(entry("y", 20_000, category = car.id))
            val home = HomeBudgetsViewModel(repo(), engine())
            backgroundScope.launch { home.state.collect {} }
            val top = home.state.await { it.size == 3 && it.all { r -> r.hasProgress } }
            assertEquals(listOf(3_000, 2_000, 500), top.map { it.progressBps })
        }

    // was: repository caches the last good answer and serves it offline
    @Test
    fun `REQ-AND-073 repository serves the stored budgets with no network, also from a new instance`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 100_000, false))
            val reopened = RoomBudgetsRepository(db, clock, ids, Dispatchers.Unconfined)
            val rows = reopened.observe().first()
            assertEquals(1, rows.size)
            assertEquals(100_000L, rows.single().amountMinor)
            assertEquals(2, reopened.observeCategories().first().size)
        }

    // was: repository maps http failures to outcomes
    @Test
    fun `REQ-AND-073 repository maps every stored-data case to an outcome`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(groceries.id, 100, false)))
            assertEquals(MutationOutcome.Duplicate, repo.create(BudgetDraft(groceries.id, 100, false)))
            assertEquals(MutationOutcome.Invalid, repo.create(BudgetDraft(car.id, 0, false)))
            assertEquals(MutationOutcome.NotFound, repo.update("nope", 100, false))
            assertEquals(MutationOutcome.NotFound, repo.delete("nope"))
            assertEquals(
                MutationOutcome.Ok,
                repo.delete(
                    db
                        .budgets()
                        .all()
                        .single()
                        .id,
                ),
            )
        }
}
