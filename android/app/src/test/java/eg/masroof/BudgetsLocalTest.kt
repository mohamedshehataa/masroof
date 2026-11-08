package eg.masroof

import eg.masroof.core.Money
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetStatus
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.MAX_BUDGET_MINOR
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.toSpec
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.budgets.BudgetLevel
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.buildBudgetRows
import eg.masroof.ui.budgets.firstFreeScope
import eg.masroof.ui.budgets.freeScopes
import eg.masroof.ui.budgets.levelOf
import eg.masroof.ui.budgets.plainAmount
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsState
import eg.masroof.ui.viewmodel.BudgetsViewModel
import eg.masroof.ui.viewmodel.HomeBudgetsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * STORY-181: budgets stored in Room, progress from the on-device engine, no server. State is awaited, never read right
 * after an action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsLocalTest : BudgetsRoomTestBase() {
    // ---------------------------------------------------------------- REQ-AND-136

    @Test
    fun `REQ-AND-136 saving Groceries monthly 4000 stores one EGP row with 50-80-100 thresholds and shows it at once`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.state.await { it.status == eg.masroof.ui.viewmodel.LoadStatus.READY }
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "4000", false) {}
            val state = vm.state.await { it.rows.size == 1 }
            val stored = db.budgets().all().single()
            assertEquals(400_000L, stored.amountMinor)
            assertEquals("EGP", stored.currency)
            assertEquals("MONTHLY", stored.period)
            assertEquals(groceries.id, stored.categoryId)
            assertEquals("50,80,100", stored.alertThresholds)
            assertFalse(stored.rollover)
            assertEquals(now.toEpochMilli(), stored.createdAtMillis)
            val row = state.rows.single()
            assertEquals("Groceries", row.nameEn)
            assertEquals(Money(400_000), row.limit)
            assertEquals(0, row.progressBps)
            assertTrue(row.hasProgress)
            assertNull(state.error)
        }

    @Test
    fun `REQ-AND-136 Arabic-Indic digits give the same amount`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "٤٠٠٠", false) {}
            vm.state.await { it.rows.size == 1 }
            assertEquals(
                400_000L,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-AND-136 a second Groceries monthly budget is refused with the duplicate message and nothing changes`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "4000", false) {}
            vm.state.await { it.rows.size == 1 }
            var saved = false
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "9999", true) { saved = true }
            val state = vm.state.await { it.error == BudgetError.DUPLICATE }
            assertFalse("no silent overwrite and no close", saved)
            val stored = db.budgets().all().single()
            assertEquals(400_000L, stored.amountMinor)
            assertFalse(stored.rollover)
            assertEquals(1, state.rows.size)
        }

    @Test
    fun `REQ-AND-136 a weekly Groceries budget next to the monthly one is allowed`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "4000", false) {}
            vm.state.await { it.rows.size == 1 }
            vm.save(null, groceries.id, BudgetPeriodKind.WEEKLY, "1000", false) {}
            val state = vm.state.await { it.rows.size == 2 }
            assertEquals(
                setOf("MONTHLY", "WEEKLY"),
                db
                    .budgets()
                    .all()
                    .map { it.period }
                    .toSet(),
            )
            assertEquals(1, state.rows.count { it.weekly })
            assertNull(state.error)
        }

    @Test
    fun `REQ-AND-136 a second All spending budget in the same period is refused too`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(null, 1_000_000, false)))
            assertEquals(MutationOutcome.Duplicate, repo.create(BudgetDraft(null, 2_000_000, false)))
            assertEquals(MutationOutcome.Ok, repo.create(BudgetDraft(null, 500_000, false, BudgetPeriodKind.WEEKLY)))
            assertEquals(2, db.budgets().all().size)
        }

    @Test
    fun `REQ-AND-136 empty zero negative and unreadable amounts show the field error and store nothing`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            for (text in listOf("", "   ", "0", "0.00", "-5", "abc", "١٢ab")) {
                var saved = false
                vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, text, false) { saved = true }
                val state = vm.state.await { it.error == BudgetError.INVALID_AMOUNT }
                assertFalse("'$text' must not save", saved)
                assertTrue(state.rows.isEmpty())
                vm.clearError()
                vm.state.await { it.error == null }
            }
            assertTrue(db.budgets().all().isEmpty())
        }

    @Test
    fun `REQ-AND-136 an amount above 1,000,000,000,000 minor units is refused and exactly that amount is accepted`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            var saved = false
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "10000000000.01", false) { saved = true }
            vm.state.await { it.error == BudgetError.AMOUNT_TOO_LARGE }
            assertFalse(saved)
            assertTrue(db.budgets().all().isEmpty())
            val done = CompletableDeferred<Unit>()
            vm.save(null, groceries.id, BudgetPeriodKind.MONTHLY, "10000000000", false) { done.complete(Unit) }
            done.awaitReal()
            vm.state.await { it.rows.size == 1 }
            assertEquals(
                MAX_BUDGET_MINOR,
                db
                    .budgets()
                    .all()
                    .single()
                    .amountMinor,
            )
        }

    @Test
    fun `REQ-AND-136 the repository enforces the amount range and a live category even when called directly`() =
        runTest {
            seedCategories(CategoryEntity("cat-old", "Old", "قديم", 9, archived = true))
            val repo = repo()
            assertEquals(MutationOutcome.Invalid, repo.create(BudgetDraft(groceries.id, 0, false)))
            assertEquals(MutationOutcome.Invalid, repo.create(BudgetDraft(groceries.id, -1, false)))
            assertEquals(MutationOutcome.Invalid, repo.create(BudgetDraft(groceries.id, MAX_BUDGET_MINOR + 1, false)))
            assertEquals(MutationOutcome.UnknownCategory, repo.create(BudgetDraft("no-such-category", 100, false)))
            assertEquals(MutationOutcome.UnknownCategory, repo.create(BudgetDraft("cat-old", 100, false)))
            assertTrue(db.budgets().all().isEmpty())
        }

    @Test
    fun `REQ-AND-136 a category made on the phone can be budgeted and is offered in the picker`() =
        runTest {
            seedCategories(CategoryEntity("custom-1234", "Coffee", "قهوة", 5))
            val vm = vm()
            watch(vm)
            val state = vm.state.await { s -> s.categories.any { it.id == "custom-1234" } }
            assertTrue(state.categories.any { it.id == "custom-1234" })
            vm.save(null, "custom-1234", BudgetPeriodKind.MONTHLY, "300", false) {}
            val after = vm.state.await { it.rows.size == 1 }
            assertEquals("Coffee", after.rows.single().nameEn)
        }

    @Test
    fun `REQ-AND-136 Add starts on the first free category and is disabled when every scope is taken`() {
        val cats = BudgetsFixtures.categories
        assertEquals(null, firstFreeScope(emptyList(), cats)?.categoryId)
        val withTotal = listOf(BudgetsFixtures.row("t", null, null, null, 100, 0, 0))
        assertEquals(BudgetsFixtures.GROCERIES, firstFreeScope(withTotal, cats)?.categoryId)
        assertEquals(BudgetPeriodKind.MONTHLY, firstFreeScope(withTotal, cats)?.period)
        // everything monthly taken: the weekly scopes are still free
        val monthlyOnly = BudgetsFixtures.everythingTaken().filterNot { it.weekly }
        assertEquals(BudgetPeriodKind.WEEKLY, firstFreeScope(monthlyOnly, cats)?.period)
        assertTrue(freeScopes(monthlyOnly, cats, BudgetPeriodKind.MONTHLY).isEmpty())
        assertNull(firstFreeScope(BudgetsFixtures.everythingTaken(), cats))
    }

    // ---------------------------------------------------------------- REQ-AND-137

    @Test
    fun `REQ-AND-137 editing changes only the amount and rollover and progress follows at once`() =
        runTest {
            seedCategories()
            val vm = vm()
            watch(vm)
            vm.save(null, groceries.id, BudgetPeriodKind.WEEKLY, "1000", false) {}
            val id =
                vm.state
                    .await { it.rows.size == 1 }
                    .rows
                    .single()
                    .id
            val created = db.budgets().byId(id)!!
            vm.save(id, null, BudgetPeriodKind.MONTHLY, "2500.50", true) {}
            val state = vm.state.await { it.rows.singleOrNull()?.limit == Money(250_050) }
            val stored = db.budgets().byId(id)!!
            assertEquals(250_050L, stored.amountMinor)
            assertTrue(stored.rollover)
            assertEquals("category is fixed", groceries.id, stored.categoryId)
            assertEquals("period is fixed", "WEEKLY", stored.period)
            assertEquals(created.createdAtMillis, stored.createdAtMillis)
            assertTrue(state.rows.single().weekly)
            assertEquals("2500.50", plainAmount(state.rows.single().limit))
        }

    @Test
    fun `REQ-AND-137 deleting removes the budget and Home's remaining-budget figure goes away`() =
        runTest {
            seedCategories()
            val repo = repo()
            repo.create(BudgetDraft(null, 1_000_000, false))
            val engine = engine()
            assertEquals(1_000_000L, engine.compute("current").summary.remainingBudget)
            val vm = vm(engine)
            watch(vm)
            val id =
                vm.state
                    .await { it.rows.size == 1 }
                    .rows
                    .single()
                    .id
            val done = CompletableDeferred<Unit>()
            vm.delete(id) { done.complete(Unit) }
            done.awaitReal()
            vm.state.await { it.rows.isEmpty() }
            assertTrue(db.budgets().all().isEmpty())
            assertNull(engine.compute("current").summary.remainingBudget)
        }

    @Test
    fun `REQ-AND-137 a budget that is already gone counts as deleted and a failed edit is not a crash`() =
        runTest {
            seedCategories()
            val repo = repo()
            assertEquals(MutationOutcome.NotFound, repo.delete("missing"))
            assertEquals(MutationOutcome.NotFound, repo.update("missing", 100, false))
            val vm = vm()
            watch(vm)
            val done = CompletableDeferred<Unit>()
            vm.delete("missing") { done.complete(Unit) }
            done.awaitReal()
            var saved = false
            vm.save("missing", null, BudgetPeriodKind.MONTHLY, "100", false) { saved = true }
            vm.state.await { it.error == BudgetError.FAILED }
            assertFalse(saved)
        }

    @Test
    fun `REQ-AND-137 a budget whose category is archived shows as Deleted category and can still be deleted`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            db.lookups().upsertCategories(listOf(groceries.copy(archived = true)))
            expenses.categories.value = listOf(car)
            val vm = vm()
            watch(vm)
            val state = vm.state.await { s -> s.rows.singleOrNull()?.deletedCategory == true }
            val row = state.rows.single()
            assertNull(row.nameEn)
            assertEquals(groceries.id, row.categoryId)
            val done = CompletableDeferred<Unit>()
            vm.delete(row.id) { done.complete(Unit) }
            done.awaitReal()
            vm.state.await { it.rows.isEmpty() }
        }

    @Test
    fun `REQ-AND-137 a category that no longer exists makes the row a Deleted category`() {
        val budget =
            eg.masroof.data.db
                .BudgetEntity("b1", "gone", "MONTHLY", 100_000, "EGP", createdAtMillis = 1, updatedAtMillis = 1)
        val row = buildBudgetRows(listOf(budget), BudgetsFixtures.categories, emptyMap()).single()
        assertTrue(row.deletedCategory)
        assertNull(row.nameEn)
        assertFalse(row.hasProgress)
        val total = budget.copy(id = "b2", categoryId = null)
        assertFalse(buildBudgetRows(listOf(total), BudgetsFixtures.categories, emptyMap()).single().deletedCategory)
    }

    // ---------------------------------------------------------------- REQ-AND-138

    @Test
    fun `REQ-AND-138 adding a 500 EGP Groceries entry moves the Groceries bar and the Home figure without a refresh`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            repo().create(BudgetDraft(null, 1_000_000, false))
            val vm = vm()
            val home = HomeBudgetsViewModel(repo(), engine())
            watch(vm)
            backgroundScope.launch { home.state.collect {} }
            val before = vm.state.await { s -> s.rows.size == 2 && s.rows.all { it.hasProgress } }
            assertEquals(0, before.rows.first { it.categoryId == groceries.id }.progressBps)
            assertEquals(BudgetLevel.OK, before.rows.first { it.categoryId == groceries.id }.level)
            assertEquals(1_000_000L, engine().compute("current").summary.remainingBudget)

            db.expenses().insert(entry("e1", 50_000))

            val after = vm.state.await { s -> s.rows.firstOrNull { it.categoryId == groceries.id }?.spent == Money(50_000) }
            val row = after.rows.first { it.categoryId == groceries.id }
            assertEquals(1_250, row.progressBps)
            assertEquals(Money(350_000), row.remaining)
            assertEquals(levelOf(1_250), row.level)
            assertEquals(Money(50_000), after.rows.first { it.categoryId == null }.spent)
            home.state.await { list -> list.any { it.categoryId == groceries.id && it.progressBps == 1_250 } }
            assertEquals(950_000L, engine().compute("current").summary.remainingBudget)
        }

    @Test
    fun `REQ-AND-138 excluded, soft-deleted, transfer, foreign-currency and ATM entries never move the bar`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            val vm = vm()
            watch(vm)
            vm.state.await { it.rows.size == 1 && it.rows.single().hasProgress }
            // counted: 100 confirmed + 50 pending review - 30 refund = 120 EGP
            db.expenses().insert(entry("ok1", 10_000))
            db.expenses().insert(entry("ok2", 5_000, status = "PENDING_REVIEW"))
            db.expenses().insert(entry("ref", 3_000, type = "REFUND"))
            // never counted
            db.expenses().insert(entry("excluded", 99_900, excluded = true))
            db.expenses().insert(entry("deleted", 77_700, deleted = true))
            db.expenses().insert(entry("transfer", 50_000, type = "TRANSFER"))
            db.expenses().insert(entry("transfer-in", 40_000, type = "TRANSFER_IN"))
            db.expenses().insert(entry("usd", 100_000, currency = "USD"))
            db.expenses().insert(entry("atm", 20_000, type = "CASH_WITHDRAWAL"))
            db.expenses().insert(entry("other-category", 60_000, category = car.id))
            val state = vm.state.await { it.rows.singleOrNull()?.spent == Money(12_000) }
            val row = state.rows.single()
            assertEquals(Money(12_000), row.spent)
            assertEquals(300, row.progressBps)
            assertEquals(Money(388_000), row.remaining)
        }

    @Test
    fun `REQ-AND-138 turning an entry's exclusion on or deleting it moves the bar back`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            db.expenses().insert(entry("e1", 100_000))
            val vm = vm()
            watch(vm)
            vm.state.await { it.rows.singleOrNull()?.spent == Money(100_000) }
            db.expenses().insert(entry("e2", 100_000, excluded = true))
            db.expenses().insert(entry("e3", 100_000, deleted = true))
            assertEquals(
                Money(100_000),
                vm.state
                    .await { it.rows.isNotEmpty() }
                    .rows
                    .single()
                    .spent,
            )
        }

    @Test
    fun `REQ-AND-138 a weekly budget counts this week only`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 100_000, false, BudgetPeriodKind.WEEKLY))
            // 2026-10-27 is a Tuesday: the week starts Monday 26th
            db.expenses().insert(entry("this-week", 20_000, at = "2026-10-26T08:00:00Z"))
            db.expenses().insert(entry("last-week", 90_000, at = "2026-10-25T08:00:00Z"))
            val vm = vm()
            watch(vm)
            val row =
                vm.state
                    .await { it.rows.singleOrNull()?.spent == Money(20_000) }
                    .rows
                    .single()
            assertTrue(row.weekly)
            assertEquals(2_000, row.progressBps)
        }

    @Test
    fun `REQ-AND-138 a budget with no eligible spend shows 0 percent and On track`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            db.expenses().insert(entry("old", 500_000, at = "2026-08-01T08:00:00Z"))
            val vm = vm()
            watch(vm)
            val row =
                vm.state
                    .await { s -> s.rows.singleOrNull()?.hasProgress == true }
                    .rows
                    .single()
            assertEquals(0, row.progressBps)
            assertEquals(BudgetLevel.OK, row.level)
            assertEquals(Money(0), row.spent)
            assertEquals(Money(400_000), row.remaining)
        }

    @Test
    fun `REQ-AND-138 a failing engine lists the budgets without progress and Retry brings it back`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            val failing = AtomicInteger(1)
            val flaky =
                object : AnalyticsEngine {
                    override fun observe(spec: String): Flow<PeriodAnalytics> = emptyFlow()

                    override suspend fun compute(spec: String): PeriodAnalytics = error("not used")

                    override fun observeBudgets(): Flow<List<BudgetStatus>> =
                        flow {
                            check(failing.get() == 0) { "boom" }
                            emitAll(engine().observeBudgets())
                        }
                }
            val vm = vm(flaky)
            watch(vm)
            val broken = vm.state.await { it.progressFailed }
            val row = broken.rows.single()
            assertEquals("Groceries", row.nameEn)
            assertFalse(row.hasProgress)
            assertEquals(Money(400_000), row.limit)
            failing.set(0)
            vm.retryProgress()
            val fixed = vm.state.await { !it.progressFailed && it.rows.singleOrNull()?.hasProgress == true }
            assertEquals(0, fixed.rows.single().progressBps)
        }

    @Test
    fun `REQ-AND-138 Home's card lists the four budgets closest to the limit and hides when the engine failed`() =
        runTest {
            val cats = (1..5).map { CategoryEntity("c$it", "Cat $it", "فئة $it", it) }
            seedCategories(*cats.toTypedArray())
            cats.forEachIndexed { i, c ->
                repo().create(BudgetDraft(c.id, 100_000, false))
                db.expenses().insert(entry("x$i", 10_000L * (i + 1), category = c.id))
            }
            val home = HomeBudgetsViewModel(repo(), engine())
            backgroundScope.launch { home.state.collect {} }
            val top = home.state.await { it.size == 4 }
            assertEquals(listOf(5_000, 4_000, 3_000, 2_000), top.map { it.progressBps })
            val broken =
                object : AnalyticsEngine {
                    override fun observe(spec: String): Flow<PeriodAnalytics> = emptyFlow()

                    override suspend fun compute(spec: String): PeriodAnalytics = error("not used")

                    override fun observeBudgets(): Flow<List<BudgetStatus>> = flow { error("boom") }
                }
            val homeBroken = HomeBudgetsViewModel(repo(), broken)
            backgroundScope.launch { homeBroken.state.collect {} }
            assertTrue(homeBroken.state.value.isEmpty())
        }

    @Test
    fun `REQ-AND-138 Insights' remaining budget is the All spending budget minus this month's spend and follows edits`() =
        runTest {
            seedCategories()
            val engine = engine()
            assertNull(engine.compute("current").summary.remainingBudget)
            repo().create(BudgetDraft(null, 1_000_000, false))
            db.expenses().insert(entry("e1", 250_000))
            val live = engine.observe("current")
            assertEquals(750_000L, live.first().summary.remainingBudget)
            val id =
                db
                    .budgets()
                    .all()
                    .single()
                    .id
            repo().update(id, 2_000_000, false)
            assertEquals(1_750_000L, engine.compute("current").summary.remainingBudget)
            // a category budget does not feed the household figure
            repo().create(BudgetDraft(groceries.id, 5_000, false))
            assertEquals(1_750_000L, engine.compute("current").summary.remainingBudget)
        }

    @Test
    fun `REQ-AN-155 a stored total budget reaches the Insights and Wallet snapshot with its remaining amount`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(null, 1_000_000, false))
            db.expenses().insert(entry("e1", 250_000))
            val repository =
                eg.masroof.data.insights
                    .LocalInsightsRepository(engine(), clock)
            val snapshot = repository.observe(eg.masroof.data.insights.InsightsPeriod.CURRENT).first()!!
            assertEquals(750_000L, snapshot.summary.remainingBudget?.amountMinor)
            assertEquals("EGP", snapshot.summary.remainingBudget?.currency)
            db.budgets().all().forEach { db.budgets().delete(it) }
            val without = repository.observe(eg.masroof.data.insights.InsightsPeriod.CURRENT).first()!!
            assertNull(without.summary.remainingBudget)
        }

    @Test
    fun `REQ-AN-153 a weekly budget on 29 Oct counts the whole ISO week including 1 Nov, outside the month`() =
        runTest {
            seedCategories()
            val lateClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
            repo().create(BudgetDraft(groceries.id, 100_000, false, BudgetPeriodKind.WEEKLY))
            db.expenses().insert(entry("mon", 20_000, at = "2026-10-26T08:00:00Z"))
            db.expenses().insert(entry("sun", 30_000, at = "2026-11-01T08:00:00Z"))
            db.expenses().insert(entry("before", 90_000, at = "2026-10-25T08:00:00Z"))
            db.expenses().insert(entry("after", 90_000, at = "2026-11-02T08:00:00Z"))
            val vm = BudgetsViewModel(repo(), RoomAnalyticsEngine(db, settings, lateClock, Dispatchers.Unconfined), expenses)
            watch(vm)
            val row =
                vm.state
                    .await { s -> s.rows.singleOrNull()?.hasProgress == true }
                    .rows
                    .single()
            assertEquals(Money(50_000), row.spent)
            assertEquals(5_000, row.progressBps)
        }

    @Test
    fun `REQ-AN-154 stored thresholds are made unique and limited to 1 to 200 before they fire`() {
        val stored =
            eg.masroof.data.db.BudgetEntity(
                "b",
                null,
                "MONTHLY",
                100,
                "EGP",
                alertThresholds = "80,80,50,0,300,100,-5,200",
                createdAtMillis = 1,
                updatedAtMillis = 1,
            )
        assertEquals(listOf(50, 80, 100, 200), stored.toSpec()!!.alertThresholds)
        assertEquals(listOf(50, 80, 100), stored.copy(alertThresholds = "x,0").toSpec()!!.alertThresholds)
    }

    @Test
    fun `REQ-AN-155 a restored duplicate budget is shown marked, the oldest feeds the remaining budget and the duplicate can be deleted`() =
        runTest {
            seedCategories()
            val older = BudgetEntity("old", null, "MONTHLY", 1_000_000, "EGP", createdAtMillis = 10, updatedAtMillis = 10)
            db.budgets().insert(older)
            db.budgets().insert(older.copy(id = "new", amountMinor = 2_000_000, createdAtMillis = 20, updatedAtMillis = 20))
            db.expenses().insert(entry("e", 100_000))
            val engine = engine()
            assertEquals(900_000L, engine.compute("current").summary.remainingBudget)
            val vm = vm(engine)
            watch(vm)
            val state = vm.state.await { s -> s.rows.size == 2 && s.rows.all { it.hasProgress } }
            assertEquals(listOf("new"), state.rows.filter { it.duplicate }.map { it.id })
            assertFalse(state.rows.first { it.id == "old" }.duplicate)
            val done = CompletableDeferred<Unit>()
            vm.delete("new") { done.complete(Unit) }
            done.awaitReal()
            val after = vm.state.await { it.rows.size == 1 }
            assertFalse(after.rows.single().duplicate)
            assertEquals(900_000L, engine.compute("current").summary.remainingBudget)
        }

    @Test
    fun `REQ-AND-138 the screen state shows nothing about a server, last update or offline`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(groceries.id, 400_000, false))
            val vm = vm()
            watch(vm)
            val state: BudgetsState = vm.state.await { it.rows.size == 1 }
            assertEquals(eg.masroof.ui.viewmodel.LoadStatus.READY, state.status)
            val row: BudgetRowUi = state.rows.single()
            assertTrue(row.hasProgress)
        }

    @Test
    fun `REQ-AND-137 Home's total-budget figure follows a new 500 EGP entry and goes away when the total budget is deleted`() =
        runTest {
            seedCategories()
            repo().create(BudgetDraft(null, 400_000, false))
            repo().create(BudgetDraft(groceries.id, 100_000, false))
            val home = HomeBudgetsViewModel(repo(), engine())
            backgroundScope.launch { home.total.collect {} }
            val before = home.total.await { it != null && it.hasProgress }!!
            assertEquals(Money(400_000), before.remaining)
            db.expenses().insert(entry("e1", 50_000))
            val moved = home.total.await { it?.spent == Money(50_000) }!!
            assertEquals(Money(350_000), moved.remaining)
            assertEquals(Money(400_000), moved.effectiveLimit)
            db
                .budgets()
                .all()
                .first { it.categoryId == null }
                .let { db.budgets().delete(it) }
            assertNull(home.total.await { it == null })
        }
}
