package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.IdFactory
import eg.masroof.data.budgets.RoomBudgetsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ui.viewmodel.BudgetsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

internal const val AWAIT_MS = 10_000L

/** Waits (in real time, off the test scheduler) until a combined StateFlow holds a value that satisfies [done]. */
internal suspend fun <T> StateFlow<T>.await(done: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { first(done) } }

internal suspend fun CompletableDeferred<Unit>.awaitReal() = withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { await() } }

/** Real in-memory Room, repository and engine for the budget tests; only the clock and settings are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class BudgetsRoomTestBase {
    protected lateinit var db: AppDatabase
    protected val settings = FakeSettings()
    protected val now: Instant = Instant.parse("2026-10-27T10:00:00Z")
    protected val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    protected val counter = AtomicInteger()
    protected val ids =
        object : IdFactory {
            override fun next() = "budget-${counter.incrementAndGet()}"
        }
    protected val expenses = FakeRepository()
    protected val eraseLock =
        eg.masroof.data.auth
            .EraseLock()

    protected val groceries = CategoryEntity("cat-groceries", "Groceries", "بقالة", 1)
    protected val car = CategoryEntity("cat-car", "Car", "سيارة", 2)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    protected suspend fun seedCategories(vararg extra: CategoryEntity) {
        val all = listOf(groceries, car) + extra
        db.lookups().upsertCategories(all)
        expenses.categories.value = all.filterNot { it.archived }
    }

    protected fun repo() = RoomBudgetsRepository(db, clock, ids, eraseLock, Dispatchers.Unconfined)

    protected fun engine() = RoomAnalyticsEngine(db, settings, clock, Dispatchers.Unconfined)

    protected fun vm(engine: AnalyticsEngine = engine()) = BudgetsViewModel(repo(), engine, expenses)

    protected fun kotlinx.coroutines.test.TestScope.watch(vm: BudgetsViewModel) {
        backgroundScope.launch { vm.state.collect {} }
    }

    @Suppress("LongParameterList")
    protected fun entry(
        id: String,
        amount: Long,
        category: String? = groceries.id,
        type: String = "EXPENSE",
        status: String = "CONFIRMED",
        currency: String = "EGP",
        at: String = "2026-10-26T08:00:00Z",
        excluded: Boolean = false,
        deleted: Boolean = false,
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = currency,
        occurredAtMillis = Instant.parse(at).toEpochMilli(),
        merchant = null,
        categoryId = category,
        accountId = null,
        notes = null,
        pending = false,
        type = type,
        status = status,
        deletedAtMillis = if (deleted) 1L else null,
        excludedFromTotals = excluded,
    )
}
