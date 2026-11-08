package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.MetalRepository
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ui.wealth.MetalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetalUndoTest {
    private lateinit var db: AppDatabase
    private lateinit var metal: MetalRepository
    private lateinit var vm: MetalViewModel
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private var n = 0
    private val ids =
        object : IdFactory {
            override fun next() = "u-${++n}"
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        val wealth = WealthRepository(db, clock)
        metal = MetalRepository(db, ids, clock, EraseLock(), wealth)
        vm = MetalViewModel(metal, wealth)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private val at = CairoTime.noonOf(LocalDate.of(2026, 6, 2)).toEpochMilli()

    private fun entry(id: String) =
        ExpenseEntity(
            id,
            100_000L,
            "EGP",
            at,
            "Jeweller",
            null,
            null,
            null,
            false,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

    private suspend fun excluded(id: String) =
        db
            .expenses()
            .observeAll()
            .first()
            .first { it.id == id }
            .excludedFromTotals

    @Test
    fun `REQ-EXP-063 editing a lot onto another entry offers Undo, which gives the first link back`() =
        runTest {
            db.expenses().insert(entry("v1"))
            db.expenses().insert(entry("v2"))
            val assetId =
                (
                    metal.createHolding(
                        HoldingDraft("GOLD", "Ring", 21),
                        LotDraft("BUY", 5_000, 2_000_000, at, "v1"),
                    ) as MetalOutcome.Saved
                ).id
            val lotId =
                db
                    .assetLots()
                    .forAsset(assetId)
                    .single()
                    .id
            assertTrue(excluded("v1"))
            vm.open(assetId)
            vm.state.first { it.holding != null }

            vm.saveLot(lotId, LotDraft("BUY", 5_000, 2_000_000, at, "v2")) {}
            assertEquals(lotId, vm.state.first { it.undoLotId != null }.undoLotId)
            assertFalse(excluded("v1"))
            assertTrue(excluded("v2"))

            vm.undoEntry(lotId)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (db.assetLots().find(lotId)!!.expenseId !=
                        "v1"
                    ) {
                        kotlinx.coroutines.delay(20)
                    }
                }
            }
            assertEquals("v1", db.assetLots().find(lotId)!!.expenseId)
            assertTrue(excluded("v1"))
            assertFalse(excluded("v2"))
        }
}
