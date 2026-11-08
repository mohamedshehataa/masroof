package eg.masroof

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.Money
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.NewExpense
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DefaultSeed
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** STORY-200 through a real Room database: what the Add form saves from a spoken line. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickEntrySaveTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:30:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        DefaultSeed.seed(db.openHelper.writableDatabase)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.fillAndSave(line: String) {
        val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), LocalDataSignal())
        val vm = AddExpenseViewModel(repo, clock)
        backgroundScope.launch { vm.state.collect {} }
        vm.onQuickText(line)
        vm.applyQuickText()
        // The form is filled once the seeded categories have been read (the quick line is cleared when it is applied).
        until {
            vm.state.value.form.amountText
                .isNotEmpty()
        }
        vm.save()
        until { vm.state.value.form.saved }
    }

    @Test
    fun `REQ-EXP-052 a spoken income in dollars is stored as INCOME in USD`() =
        runTest {
            fillAndSave("received 20 dollars salary")
            val row =
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .single()
            assertEquals("INCOME", row.type)
            assertEquals("USD", row.currency)
            assertEquals(2000L, row.amountMinor)
            assertNull(row.categoryId)
        }

    @Test
    fun `REQ-AND-144 REQ-PARSE-098 a spoken line is stored with the seeded category and merchant after Save`() =
        runTest {
            fillAndSave("150 pounds at carrefour")
            val row =
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .single()
            assertEquals("EXPENSE", row.type)
            assertEquals("EGP", row.currency)
            assertEquals(15000L, row.amountMinor)
            assertEquals("carrefour", row.merchant)
            assertEquals("cat-groceries", row.categoryId)
        }

    @Test
    fun `REQ-PARSE-099 an Arabic fuel line is stored in the Fuel subcategory`() =
        runTest {
            fillAndSave("١٥٠ جنيه بنزين")
            val row =
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .single()
            assertEquals(15000L, row.amountMinor)
            assertEquals("cat-car.fuel", row.categoryId)
        }

    @Test
    fun `REQ-EXP-052 the repository refuses an unknown type or a currency that is not 3 capital letters`() =
        runTest {
            val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), LocalDataSignal())
            val today = java.time.LocalDate.of(2026, 10, 15)
            for (bad in listOf(
                NewExpense(Money(100, "EGP"), today, null, null, null, type = "TRANSFER"),
                NewExpense(Money(100, "usd"), today, null, null, null),
                NewExpense(Money(100, "EG"), today, null, null, null),
            )) {
                try {
                    repo.add(bad)
                    org.junit.Assert.fail("should have been refused")
                } catch (expected: IllegalArgumentException) {
                    // refused before anything is written
                }
            }
            assertEquals(0, db.expenses().count())
        }
}

private const val WAIT_MS = 5_000L
private const val POLL_MS = 10L

// Room works on its own threads and posts back to the main looper; runTest runs on virtual time, so poll in real time.
private suspend fun until(condition: () -> Boolean) {
    val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MS
    while (!condition()) {
        shadowOf(Looper.getMainLooper()).idle()
        check(System.nanoTime() < deadline) { "timed out waiting for the form" }
        withContext(Dispatchers.Default) { delay(POLL_MS) }
    }
}

private const val NANOS_PER_MS = 1_000_000L
