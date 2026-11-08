package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.withChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExcludedFromTotalsTest {
    private lateinit var db: AppDatabase
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun entry(
        id: String,
        amount: Long,
        type: String = "EXPENSE",
        excluded: Boolean = false,
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = "EGP",
        occurredAtMillis = Instant.parse("2026-11-05T09:00:00Z").toEpochMilli(),
        merchant = null,
        categoryId = null,
        accountId = "acc",
        notes = null,
        pending = false,
        type = type,
        excludedFromTotals = excluded,
    )

    private val from = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()

    @Test
    fun `REQ-AN-175 an excluded entry is left out of spending income Insights and Ask but still moves its account`() =
        runTest {
            db.expenses().insert(entry("a", 10_000))
            db.expenses().insert(entry("b", 5_000, excluded = true))
            db.expenses().insert(entry("c", 9_000, type = "INCOME", excluded = true))

            assertEquals(10_000L, db.expenses().observeTotal(from, to).first())
            assertEquals(1, db.expenses().analyticsRows(from, to).size)
            assertEquals(listOf("a"), db.expenses().askRows(from, to).map { it.id })
            assertEquals(
                listOf("a"),
                db
                    .expenses()
                    .observeAskRows(from, to)
                    .first()
                    .map { it.id },
            )

            val engine = RoomAnalyticsEngine(db, FakeSettings(), clock, Dispatchers.Unconfined)
            val analytics = engine.compute("current")
            assertEquals(10_000L, analytics.summary.total)
            assertEquals(0L, analytics.summary.income)

            // The balance math reads every live entry of the account, flagged or not.
            val flows = db.accountBalances().observeFlowRows().first()
            assertEquals(setOf(10_000L, 5_000L, 9_000L), flows.map { it.amountMinor }.toSet())
        }

    @Test
    fun `REQ-AN-175 switching the flag off restores the entry`() =
        runTest {
            db.expenses().insert(entry("a", 10_000, excluded = true))
            assertEquals(0L, db.expenses().observeTotal(from, to).first())

            val stored = checkNotNull(db.expenses().find("a"))
            db.expenses().update(stored.withChanges(ExpenseChanges(excludedFromTotals = false), now.toEpochMilli()))

            assertEquals(10_000L, db.expenses().observeTotal(from, to).first())
        }

    @Test
    fun `REQ-AN-175 editing only the flag changes only the flag`() {
        val before = entry("a", 10_000)
        val after = before.withChanges(ExpenseChanges(excludedFromTotals = true), 5L)
        assertTrue(after.excludedFromTotals)
        assertEquals(before.copy(excludedFromTotals = true, updatedAtMillis = 5L), after)
        assertFalse(ExpenseChanges(excludedFromTotals = true).isEmpty)
        assertTrue(ExpenseChanges().isEmpty)
        assertEquals(before, before.withChanges(ExpenseChanges(), 0L).copy(updatedAtMillis = before.updatedAtMillis))
    }
}
