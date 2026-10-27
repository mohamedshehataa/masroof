package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.toPatch
import eg.masroof.data.remote.serverUuidOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpenseWritesV9Test {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(context)
    }

    @After
    fun tearDown() = db.close()

    private fun synced(
        id: String,
        amount: Long = 1_000,
        merchant: String? = "Shop",
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = "EGP",
        occurredAtMillis = 5_000,
        merchant = merchant,
        categoryId = null,
        accountId = null,
        notes = null,
        pending = false,
        serverId = id,
        createdAtMillis = 5_000,
        updatedAtMillis = 5_000,
    )

    @Test
    fun `REQ-AND-129 a refresh keeps serverId the timestamps and the local-only columns of rows it lists again`() =
        runTest {
            db.expenses().upsert(
                synced("e1").copy(paidByMemberId = "m1", timeKnown = false, deletedAtMillis = 9_000, createdAtMillis = 111),
            )
            // the server sends the row without any local-only column
            db.expenses().replaceSynced(listOf(synced("e1", amount = 2_000).copy(createdAtMillis = 5_000, updatedAtMillis = 5_000)))
            val row = db.expenses().find("e1")!!
            assertEquals(2_000L, row.amountMinor)
            assertEquals("e1", row.serverId)
            assertEquals(111L, row.createdAtMillis)
            assertEquals(5_000L, row.updatedAtMillis)
            assertEquals("m1", row.paidByMemberId)
            assertEquals(false, row.timeKnown)
            assertEquals(9_000L, row.deletedAtMillis)
        }

    @Test
    fun `REQ-AND-129 a refresh removes rows the server no longer lists and never touches pending rows`() =
        runTest {
            db.expenses().upsert(listOf(synced("e1"), synced("e2")))
            db.expenses().upsert(synced("p1").copy(pending = true, serverId = null))
            db.expenses().replaceSynced(listOf(synced("e1")))
            assertNotNull(db.expenses().find("e1"))
            assertNull(db.expenses().find("e2"))
            assertNotNull(db.expenses().find("p1"))
        }

    @Test
    fun `REQ-DEDUP-062 an expense with a source row keeps that row and its receipt link through a refresh`() =
        runTest {
            db.expenses().upsert(synced("e1"))
            val raw = db.openHelper.writableDatabase
            raw.execSQL(
                "INSERT INTO expense_source (id, expenseId, channel, localSourceId, receivedAtMillis) VALUES ('s1', 'e1', 'SMS', 'L1', 1)",
            )
            raw.execSQL(
                "INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis) " +
                    "VALUES ('r1', 'e1', 'a.jpg', 'image/jpeg', 1, 'h', NULL, 1)",
            )
            db.expenses().replaceSynced(listOf(synced("e1", amount = 3_000)))
            db.expenses().upsert(synced("e1", amount = 4_000))
            raw.query("SELECT COUNT(*) FROM expense_source WHERE expenseId = 'e1'").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            raw.query("SELECT expenseId FROM receipt WHERE id = 'r1'").use {
                it.moveToFirst()
                assertEquals("e1", it.getString(0))
            }
        }

    @Test
    fun `REQ-AND-129 an edit keeps serverId and timestamps`() =
        runTest {
            db.expenses().upsert(synced("e1").copy(createdAtMillis = 100, updatedAtMillis = 100))
            // the edited row comes back from the server mapped like any server row (no local-only columns)
            db.expenses().upsert(synced("e1", amount = 7_000, merchant = "Edited").copy(createdAtMillis = 0, updatedAtMillis = 0))
            val row = db.expenses().find("e1")!!
            assertEquals(7_000L, row.amountMinor)
            assertEquals("e1", row.serverId)
            assertEquals(100L, row.createdAtMillis)
            assertEquals(100L, row.updatedAtMillis)
        }

    @Test
    fun `REQ-AND-129 completing an upload keeps the created time and re-points sources and receipts to the server row`() =
        runTest {
            val pending = synced("client-1").copy(pending = true, serverId = null, createdAtMillis = 42, updatedAtMillis = 42)
            db.enqueue(pending, OutboxEntity("client-1", 1_000, "EGP", 5_000, null, null, null, createdAtMillis = 42))
            val raw = db.openHelper.writableDatabase
            raw.execSQL(
                "INSERT INTO expense_source (id, expenseId, channel, localSourceId, receivedAtMillis) " +
                    "VALUES ('s1', 'client-1', 'MANUAL', 'L1', 1)",
            )
            raw.execSQL(
                "INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis) " +
                    "VALUES ('r1', 'client-1', 'a.jpg', 'image/jpeg', 1, 'h', NULL, 1)",
            )
            db.completeUpload("client-1", synced("srv-9").copy(createdAtMillis = 5_000, updatedAtMillis = 5_000))
            assertNull(db.expenses().find("client-1"))
            val row = db.expenses().find("srv-9")!!
            assertEquals("srv-9", row.serverId)
            assertEquals(42L, row.createdAtMillis)
            assertEquals(5_000L, row.updatedAtMillis)
            assertTrue(db.outbox().all().isEmpty())
            raw.query("SELECT expenseId FROM expense_source WHERE id = 's1'").use {
                it.moveToFirst()
                assertEquals("srv-9", it.getString(0))
            }
            raw.query("SELECT expenseId FROM receipt WHERE id = 'r1'").use {
                it.moveToFirst()
                assertEquals("srv-9", it.getString(0))
            }
        }

    @Test
    fun `REQ-EXP-041 the seeded categories are not offered to the server pickers and the server categories are not duplicated`() =
        runTest {
            DefaultSeed.seed(db.openHelper.writableDatabase)
            db.lookups().upsertCategories(listOf(CategoryEntity("0b6d3a52-0000-4000-8000-000000000001", "Groceries", "بقالة", 10)))
            val pickers = db.lookups().observeCategories().first()
            assertEquals(listOf("Groceries"), pickers.map { it.nameEn })
            assertTrue(pickers.none { it.id.startsWith("cat-") })
            // the local-only screens still see the defaults (REQ-EXP-041 holds)
            assertEquals(
                30,
                db
                    .lookups()
                    .observeAllCategories()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-041 a seeded category id never reaches an upload`() {
        assertNull(serverUuidOrNull("cat-groceries"))
        assertNull(serverUuidOrNull(null))
        val server = "0b6d3a52-0000-4000-8000-000000000001"
        assertEquals(server, serverUuidOrNull(server).toString())
        assertNull(ExpenseChanges(categoryId = "cat-car.fuel").toPatch().categoryId)
    }

    @Test
    fun `REQ-EXP-041 the defaults are seeded again after the data wipe on logout`() =
        runTest {
            DefaultSeed.seed(db.openHelper.writableDatabase)
            val wiper =
                eg.masroof.data.auth.RoomDataWiper(
                    db,
                    object : eg.masroof.data.insights.InsightsCache {
                        override fun read(key: String): String? = null

                        override fun write(
                            key: String,
                            json: String,
                        ) = Unit

                        override fun clear() = Unit
                    },
                    androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                    kotlinx.coroutines.Dispatchers.Unconfined,
                )
            wiper.wipe()
            assertEquals(
                29,
                db
                    .lookups()
                    .observeAllCategories()
                    .first()
                    .size,
            )
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM member").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }
}
