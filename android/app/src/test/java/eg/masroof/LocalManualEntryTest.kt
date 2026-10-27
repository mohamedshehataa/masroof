package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.NewExpense
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.remote.RemoteSource
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.HomeViewModel
import eg.masroof.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalManualEntryTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:30:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = CairoTime.today(clock.instant())

    private class Ids(
        vararg val fixed: String,
    ) : IdFactory {
        private var i = 0

        override fun next(): String = if (i < fixed.size) fixed[i++] else "gen-${i++}"
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        DefaultSeed.seed(db.openHelper.writableDatabase)
    }

    @After
    fun tearDown() = db.close()

    private fun repo(ids: IdFactory = Ids("e1", "s1")) = DefaultExpenseRepository(db, clock, ids, LocalDataSignal())

    private fun entry(
        minor: Long = 20_000,
        date: LocalDate = today,
        category: String? = "cat-car.fuel",
    ) = NewExpense(Money(minor), date, category, null, "fuel")

    @Test
    fun `REQ-EXP-043 saving today's entry writes one expense and one MANUAL source at the save instant`() =
        runTest {
            val id = repo().add(entry())
            val row = db.expenses().find(id)!!
            assertEquals(20_000L, row.amountMinor)
            assertEquals("EGP", row.currency)
            assertEquals("EXPENSE", row.type)
            assertEquals("CONFIRMED", row.status)
            assertEquals(clock.millis(), row.occurredAtMillis)
            assertTrue(row.timeKnown)
            assertEquals(clock.millis(), row.createdAtMillis)
            assertEquals(row.createdAtMillis, row.updatedAtMillis)
            assertEquals("cat-car.fuel", row.categoryId)
            assertFalse(row.pending)
            val sources = db.sources().all()
            assertEquals(1, sources.size)
            assertEquals("MANUAL", sources.single().channel)
            assertEquals(id, sources.single().expenseId)
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-EXP-043 the saved row shows in the Transactions and Home lists without a refresh`() =
        runTest {
            val r = repo()
            assertTrue(r.observeAll().first().isEmpty())
            r.add(entry())
            assertEquals(1, r.observeAll().first().size)
            assertEquals(1, r.observeRecent(5).first().size)
            assertEquals(Money(20_000), r.observeMonthTotal().first())
        }

    @Test
    fun `REQ-EXP-043 a past date is saved at Cairo noon of that date and the time is not known`() =
        runTest {
            val past = LocalDate.of(2026, 10, 3)
            val id = repo().add(entry(date = past))
            val row = db.expenses().find(id)!!
            assertEquals(CairoTime.noonOf(past).toEpochMilli(), row.occurredAtMillis)
            assertFalse(row.timeKnown)
            assertEquals(clock.millis(), row.createdAtMillis)
        }

    @Test
    fun `REQ-EXP-043 a write that throws leaves no expense and no source and the error reaches the caller`() =
        runTest {
            // a source with the id the new entry would use makes the second insert of the transaction fail
            db.expenses().insert(
                ExpenseEntity("p", 1, "EGP", 1, null, null, null, null, pending = false),
            )
            db.sources().insert(ExpenseSourceEntity("s-old", "p", "MANUAL", "e1", 1, null, null))
            try {
                repo(Ids("e1", "s1")).add(entry())
                fail("the write should have thrown")
            } catch (expected: android.database.sqlite.SQLiteException) {
                // rolled back
            }
            assertNull(db.expenses().find("e1"))
            assertEquals(1, db.expenses().count())
            assertEquals(1, db.sources().all().size)
        }

    @Test
    fun `REQ-EXP-043 amount parsing reads Arabic-Indic digits and thousands separators`() {
        assertEquals(MoneyFormat.EntryAmount.Valid(20_000), MoneyFormat.parseEntry("٢٠٠"))
        assertEquals(MoneyFormat.EntryAmount.Valid(145_000), MoneyFormat.parseEntry("1,450"))
        assertEquals(MoneyFormat.EntryAmount.Valid(125_000), MoneyFormat.parseEntry("١٬٢٥٠"))
        assertEquals(MoneyFormat.EntryAmount.Valid(1_250), MoneyFormat.parseEntry("12,5"))
        assertEquals(MoneyFormat.EntryAmount.Valid(12_550), MoneyFormat.parseEntry("125.50"))
        assertEquals(MoneyFormat.EntryAmount.Valid(12_550), MoneyFormat.parseEntry("١٢٥٫٥٠"))
    }

    @Test
    fun `REQ-EXP-043 amount parsing rejects empty zero negative text and oversize input`() {
        for (text in listOf("", "  ", "0", "0.00", "-5", "abc", "1.2.3", "12.345")) {
            assertEquals(text, MoneyFormat.EntryAmount.Invalid, MoneyFormat.parseEntry(text))
        }
        assertEquals(MoneyFormat.EntryAmount.TooLarge, MoneyFormat.parseEntry("10000000000.01"))
        assertEquals(MoneyFormat.EntryAmount.TooLarge, MoneyFormat.parseEntry("99999999999999999999999"))
        assertEquals(MoneyFormat.EntryAmount.Valid(1_000_000_000_000L), MoneyFormat.parseEntry("10000000000"))
    }

    @Test
    fun `REQ-EXP-043 saving needs no network - the repository and view models have no HTTP dependency`() {
        val forbidden = listOf(RemoteSource::class.java, OkHttpClient::class.java, eg.masroof.data.auth.SessionManager::class.java)
        val types =
            listOf(
                DefaultExpenseRepository::class.java,
                AddExpenseViewModel::class.java,
                HomeViewModel::class.java,
                TransactionsViewModel::class.java,
            )
        for (type in types) {
            val params = type.constructors.flatMap { it.parameterTypes.toList() }
            for (p in params) {
                assertTrue("${type.simpleName} takes ${p.simpleName}", forbidden.none { it.isAssignableFrom(p) })
            }
        }
        assertTrue(ExpenseRepository::class.java.methods.none { it.name == "refresh" || it.name == "syncOutbox" })
    }

    @Test
    fun `REQ-EXP-043 the Add sheet offers the seeded categories once even when the server sent the same names`() =
        runTest {
            // a signed-in phone cached the server's "Fuel" and "Groceries" (same names, UUID ids)
            db.lookups().upsertCategories(
                listOf(
                    CategoryEntity("0b6d3a52-0000-4000-8000-000000000001", "Fuel", "بنزين", 1),
                    CategoryEntity("0b6d3a52-0000-4000-8000-000000000002", "groceries", "بقالة", 10),
                    CategoryEntity("0b6d3a52-0000-4000-8000-000000000003", "Coffee", "قهوة", 500),
                ),
            )
            val picker = repo().observePickerCategories().first()
            assertEquals("no duplicate names", picker.size, picker.map { it.nameEn.lowercase() }.toSet().size)
            assertEquals("cat-car.fuel", picker.single { it.nameEn == "Fuel" }.id)
            assertTrue("a server-only category is still offered", picker.any { it.nameEn == "Coffee" })
            // rows saved against a server category still get their name
            assertTrue(repo().observeLabelCategories().first().any { it.id.startsWith("0b6d3a52") })
        }
}
