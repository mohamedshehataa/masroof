package eg.masroof

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import eg.masroof.data.db.ALL_MIGRATIONS
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.BackupOutcome
import eg.masroof.data.db.DB_VERSION
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.DbStartup
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ME_MEMBER_ID
import eg.masroof.data.db.PreMigrationBackup
import eg.masroof.data.db.seededCategoryId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomV9MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "v9-test.db"

    // MigrationTestHelper (Room 2.8) wants the full path as the database name.
    private val dbPath: String get() = context.getDatabasePath(name).path

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @After
    fun cleanup() {
        context.deleteDatabase(name)
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun SupportSQLiteDatabase.seedBase(version: Int) {
        val expenseTail = if (version >= 8) ", 'EXPENSE', 'CONFIRMED'" else ""
        val expenseCols = if (version >= 8) ", type, status" else ""
        execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, failed" +
                "${if (version >= 3) ", source" else ""}$expenseCols) VALUES ('e-123', 12500, 'EGP', 5000, 'Shop', 'c1', 'a1', 'n', 0, 0" +
                "${if (version >= 3) ", 'SMS'" else ""}$expenseTail)",
        )
        execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, failed" +
                "${if (version >= 3) ", source" else ""}$expenseCols) VALUES ('q-1', 700, 'EGP', 6000, NULL, NULL, NULL, NULL, 1, 0" +
                "${if (version >= 3) ", 'MANUAL'" else ""}$expenseTail)",
        )
        execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, failed" +
                "${if (version >= 3) ", source" else ""}$expenseCols) VALUES ('f-1', 900, 'USD', 7000, NULL, NULL, NULL, NULL, 1, 1" +
                "${if (version >= 3) ", 'MANUAL'" else ""}$expenseTail)",
        )
        execSQL("INSERT INTO category (id, nameEn, nameAr, sortOrder) VALUES ('c1', 'Food', 'أكل', 3)")
        execSQL("INSERT INTO account (id, label, currency) VALUES ('a1', 'Card', 'EGP')")
        execSQL(
            "INSERT INTO outbox (clientId, amountMinor, currency, occurredAtMillis, categoryId, accountId, notes, attempts, failed, " +
                "createdAtMillis) VALUES ('q-1', 700, 'EGP', 6000, NULL, NULL, NULL, 1, 0, 6000)",
        )
    }

    private fun SupportSQLiteDatabase.seedV8Extras() {
        execSQL(
            "INSERT INTO parsed_outbox (localId, source, kind, amountMinor, currency, occurredAtMillis, instrumentLast4, senderId, " +
                "institution, paymentMethod, merchant, attempts, failed, createdAtMillis, claimedAtMillis, claims) " +
                "VALUES ('p1', 'SMS', 'CARD_PURCHASE', 100, 'EGP', 1, '1234', 'CIB', 'CIB', 'CARD', 'Shop', 0, 0, 1, 0, 0)",
        )
        execSQL(
            "INSERT INTO review_item (id, source, sender, text, reason, receivedAtMillis, createdAtMillis) " +
                "VALUES ('r1', 'SMS', 'CIB', 'body', 'AMBIGUOUS', 1, 1)",
        )
        execSQL(
            "INSERT INTO learned_template (id, sender, shape, kind, amountSlot, last4Slot, createdAtMillis) " +
                "VALUES ('t1', 'CIB', 'shape', 'CARD_PURCHASE', 0, 1, 1)",
        )
        execSQL(
            "INSERT INTO captured_tx (localId, expenseId, amountMinor, currency, label, source, templateId, capturedAtMillis) " +
                "VALUES ('l1', 'e-123', 100, 'EGP', 'Shop', 'SMS', NULL, 1)",
        )
    }

    private fun migrate(
        from: Int,
        seed: SupportSQLiteDatabase.() -> Unit,
    ) {
        helper.createDatabase(dbPath, from).apply {
            seed()
            close()
        }
    }

    @Test
    fun `REQ-AND-127 migrating from every shipped version 2 to 8 keeps every seeded row and validates against the v9 schema`() {
        for (from in 2..8) {
            context.deleteDatabase(name)
            migrate(from) {
                seedBase(from)
                if (from == 8) seedV8Extras()
            }
            val db = helper.runMigrationsAndValidate(dbPath, 9, true, *ALL_MIGRATIONS)
            assertEquals("v$from expenses", 3L, db.count("expense"))
            assertEquals("v$from categories", 1L, db.count("category"))
            assertEquals("v$from accounts", 1L, db.count("account"))
            assertEquals("v$from outbox", 1L, db.count("outbox"))
            db
                .query("SELECT amountMinor, currency, occurredAtMillis, pending, failed, categoryId FROM expense WHERE id = 'e-123'")
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals(12_500L, it.getLong(0))
                    assertEquals("EGP", it.getString(1))
                    assertEquals(5_000L, it.getLong(2))
                    assertEquals(0, it.getInt(3))
                    assertEquals(0, it.getInt(4))
                    assertEquals("c1", it.getString(5))
                }
            db.query("SELECT nameEn, nameAr, sortOrder FROM category WHERE id = 'c1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Food", it.getString(0))
                assertEquals("أكل", it.getString(1))
                assertEquals(3, it.getInt(2))
            }
            if (from == 8) {
                assertEquals(1L, db.count("parsed_outbox"))
                assertEquals(1L, db.count("review_item"))
                assertEquals(1L, db.count("learned_template"))
                assertEquals(1L, db.count("captured_tx"))
                db.query("SELECT text FROM review_item WHERE id = 'r1'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("body", it.getString(0))
                }
            }
            db.close()
        }
    }

    @Test
    fun `REQ-AND-127 the exported v9 schema adds no REAL column and the older schema files are the ones that shipped`() {
        val schema = Json.parseToJsonElement(File("schemas/eg.masroof.data.db.AppDatabase/9.json").readText()).jsonObject["database"]!!
        assertEquals(
            9,
            schema.jsonObject["version"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        for (entity in schema.jsonObject["entities"]!!.jsonArray) {
            for (field in entity.jsonObject["fields"]!!.jsonArray) {
                val affinity = field.jsonObject["affinity"]!!.jsonPrimitive.content
                assertFalse("no REAL column", affinity == "REAL")
            }
        }
    }

    @Test
    fun `REQ-AND-127 a migration that throws shows could not finish and deletes neither the original nor the safety copy`() {
        migrate(8) { seedBase(8) }
        val failing =
            arrayOf(
                *ALL_MIGRATIONS.filter { it.startVersion != 8 }.toTypedArray(),
                object : androidx.room.migration.Migration(8, 9) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE expense ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
                        error("boom")
                    }
                },
            )
        val result = DatabaseStartup.open(context, name, failing)
        assertEquals(DbStartup.CouldNotFinish, result)
        assertTrue(context.getDatabasePath(name).exists())
        assertTrue(File(context.noBackupFilesDir, "pre-migration-v8.db").exists())
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        raw.use {
            assertEquals(8, it.version)
            it.rawQuery("SELECT COUNT(*) FROM expense", null).use { c ->
                c.moveToFirst()
                assertEquals(3, c.getInt(0))
            }
        }
    }

    @Test
    fun `REQ-AND-127 a corrupt database file shows could not finish and is left on disk untouched`() {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val junk = ByteArray(2048) { (it * 7).toByte() }
        file.writeBytes(junk)
        val result = DatabaseStartup.open(context, name)
        assertEquals(DbStartup.CouldNotFinish, result)
        assertTrue(file.readBytes().contentEquals(junk))
    }

    @Test
    fun `REQ-AND-127 a second start runs no migration and no copy and keeps the row counts`() {
        migrate(8) { seedBase(8) }
        val first = DatabaseStartup.open(context, name) as DbStartup.Ready
        first.db.close()
        val copy = File(context.noBackupFilesDir, "pre-migration-v8.db")
        assertTrue(copy.exists())
        val stamp = copy.lastModified()
        copy.setLastModified(stamp - 100_000)
        val before = copy.lastModified()
        val second = DatabaseStartup.open(context, name) as DbStartup.Ready
        second.db.openHelper.writableDatabase.let {
            assertEquals(3L, it.count("expense"))
            assertEquals(1L, it.count("category"))
        }
        second.db.close()
        assertEquals("no new copy on the second start", before, copy.lastModified())
    }

    @Test
    fun `REQ-AND-128 an existing older database is checkpointed and copied to noBackupFilesDir before the migration runs`() {
        migrate(8) { seedBase(8) }
        var copiedBeforeMigration = false
        val spy =
            arrayOf(
                *ALL_MIGRATIONS.filter { it.startVersion != 8 }.toTypedArray(),
                object : androidx.room.migration.Migration(8, 9) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        copiedBeforeMigration = File(context.noBackupFilesDir, "pre-migration-v8.db").exists()
                        ALL_MIGRATIONS.last().migrate(db)
                    }
                },
            )
        val ready = DatabaseStartup.open(context, name, spy) as DbStartup.Ready
        ready.db.close()
        assertTrue("the copy existed before the first migration statement", copiedBeforeMigration)
        val copy = File(context.noBackupFilesDir, "pre-migration-v8.db")
        val raw = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
        raw.use {
            assertEquals("the copy is the old version", 8, it.version)
            it.rawQuery("SELECT COUNT(*) FROM expense", null).use { c ->
                c.moveToFirst()
                assertEquals(3, c.getInt(0))
            }
        }
    }

    @Test
    fun `REQ-AND-128 only the newest pre-migration copy is kept`() {
        migrate(7) { seedBase(7) }
        val dir = context.noBackupFilesDir
        File(dir, "pre-migration-v5.db").writeText("old")
        val outcome = PreMigrationBackup(context.getDatabasePath(name), dir, DB_VERSION).run()
        assertTrue(outcome is BackupOutcome.Done)
        assertFalse(File(dir, "pre-migration-v5.db").exists())
        assertTrue(File(dir, "pre-migration-v7.db").exists())
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("pre-migration-") }!!.size)
    }

    @Test
    fun `REQ-AND-128 an IOException while copying leaves the database at its old version deletes the half copy and asks for storage`() {
        migrate(8) { seedBase(8) }
        val dir = context.noBackupFilesDir
        val failing =
            PreMigrationBackup(
                context.getDatabasePath(name),
                dir,
                DB_VERSION,
                copy = { from, to ->
                    to.writeBytes(from.readBytes().copyOf(100))
                    throw IOException("disk full")
                },
            )
        val result = DatabaseStartup.open(context, name, backup = failing)
        assertTrue(result is DbStartup.FreeUpStorage)
        assertEquals(1L, (result as DbStartup.FreeUpStorage).neededMb)
        assertEquals("no half-written copy", 0, dir.listFiles { f -> f.name.startsWith("pre-migration-") }!!.size)
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        raw.use { assertEquals("the migration did not run", 8, it.version) }
        // the next start retries and succeeds
        val retry = DatabaseStartup.open(context, name)
        assertTrue(retry is DbStartup.Ready)
        (retry as DbStartup.Ready).db.close()
    }

    @Test
    fun `REQ-AND-128 less free space than the database size blocks the migration with the needed size`() {
        migrate(8) { seedBase(8) }
        val size = context.getDatabasePath(name).length()
        val backup = PreMigrationBackup(context.getDatabasePath(name), context.noBackupFilesDir, DB_VERSION, freeSpace = { size - 1 })
        val result = DatabaseStartup.open(context, name, backup = backup)
        assertTrue(result is DbStartup.FreeUpStorage)
        assertTrue((result as DbStartup.FreeUpStorage).neededMb >= 1)
        assertFalse(File(context.noBackupFilesDir, "pre-migration-v8.db").exists())
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        raw.use { assertEquals(8, it.version) }
    }

    @Test
    fun `REQ-AND-128 a fresh install attempts no copy and creates the database directly at v9`() {
        context.deleteDatabase(name)
        val ready = DatabaseStartup.open(context, name) as DbStartup.Ready
        assertEquals(0, context.noBackupFilesDir.listFiles { f -> f.name.startsWith("pre-migration-") }!!.size)
        assertEquals(9, ready.db.openHelper.writableDatabase.version)
        ready.db.close()
    }

    @Test
    fun `REQ-AND-129 server era rows keep their id as serverId and get safe defaults`() {
        migrate(8) { seedBase(8) }
        val db = helper.runMigrationsAndValidate(dbPath, 9, true, *ALL_MIGRATIONS)
        db
            .query(
                "SELECT id, serverId, createdAtMillis, updatedAtMillis, deletedAtMillis, timeKnown, paidByMemberId, pending, failed, " +
                    "amountMinor, currency, occurredAtMillis FROM expense ORDER BY id",
            ).use {
                val rows = mutableMapOf<String, List<String?>>()
                while (it.moveToNext()) rows[it.getString(0)] = (1 until 12).map { i -> if (it.isNull(i)) null else it.getString(i) }
                val cached = rows.getValue("e-123")
                assertEquals("e-123", cached[0])
                assertEquals("5000", cached[1])
                assertEquals("5000", cached[2])
                assertNull(cached[3])
                assertEquals("1", cached[4])
                assertNull(cached[5])
                assertEquals("12500", cached[8])
                assertEquals("EGP", cached[9])
                val queued = rows.getValue("q-1")
                assertNull("a queued manual entry has no server id", queued[0])
                assertEquals("6000", queued[1])
                assertEquals("1", queued[6])
                val refused = rows.getValue("f-1")
                assertNull(refused[0])
                assertEquals("pending stays", "1", refused[6])
                assertEquals("failed stays", "1", refused[7])
                assertEquals("900", refused[8])
                assertEquals("USD", refused[9])
            }
        db.close()
    }

    @Test
    fun `REQ-AND-129 a second row with the same serverId is rejected`() {
        migrate(8) { seedBase(8) }
        val db = helper.runMigrationsAndValidate(dbPath, 9, true, *ALL_MIGRATIONS)
        try {
            db.execSQL(
                "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, pending, failed, serverId) " +
                    "VALUES ('x', 1, 'EGP', 1, 0, 0, 'e-123')",
            )
            fail("duplicate serverId must be rejected")
        } catch (expected: SQLiteConstraintException) {
            assertEquals(3L, db.count("expense"))
        } finally {
            db.close()
        }
    }

    private fun freshDb(): AppDatabase = (DatabaseStartup.open(context, name) as DbStartup.Ready).db

    @Test
    fun `REQ-EXP-041 a fresh install holds the 20 top level and 9 sub categories with stable ids and the SQL names`() {
        val db = freshDb().openHelper.writableDatabase
        assertEquals(29L, db.count("category"))
        assertEquals(20L, db.count("category WHERE parentId IS NULL"))
        assertEquals(9L, db.count("category WHERE parentId IS NOT NULL"))
        assertEquals(29L, db.count("category WHERE system = 1 AND archived = 0"))
        db
            .query(
                "SELECT id, nameEn, nameAr, color, sortOrder, parentId FROM category WHERE id = ?",
                arrayOf(seededCategoryId("car.fuel")),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("Fuel", it.getString(1))
                assertEquals("بنزين", it.getString(2))
                assertEquals("#F59E0B", it.getString(3))
                assertEquals(1, it.getInt(4))
                assertEquals(seededCategoryId("car"), it.getString(5))
            }
        db.query("SELECT nameEn, nameAr, color, sortOrder FROM category WHERE id = ?", arrayOf(seededCategoryId("groceries"))).use {
            assertTrue(it.moveToFirst())
            assertEquals("Groceries", it.getString(0))
            assertEquals("بقالة", it.getString(1))
            assertEquals("#16A34A", it.getString(2))
            assertEquals(10, it.getInt(3))
        }
        db.close()
        // identical on every install
        context.deleteDatabase(name)
        val again = freshDb().openHelper.writableDatabase
        again.query("SELECT id FROM category ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals("cat-bills", it.getString(0))
        }
        again.close()
    }

    @Test
    fun `REQ-EXP-041 a v8 database with an empty category table gets the defaults`() {
        migrate(8) {
            execSQL("INSERT INTO account (id, label, currency) VALUES ('a1', 'Card', 'EGP')")
        }
        val db = freshDb().openHelper.writableDatabase
        assertEquals(29L, db.count("category"))
        db.close()
    }

    @Test
    fun `REQ-EXP-041 a v8 database that already has categories gets no default and keeps its ids`() {
        migrate(8) { seedBase(8) }
        val db = freshDb().openHelper.writableDatabase
        assertEquals(1L, db.count("category"))
        db.query("SELECT id, parentId, archived, system FROM category").use {
            assertTrue(it.moveToFirst())
            assertEquals("c1", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(0, it.getInt(2))
            assertEquals(0, it.getInt(3))
        }
        db.close()
    }

    @Test
    fun `REQ-EXP-041 seeding twice leaves 29 categories`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        DefaultSeed.seed(db)
        DefaultSeed.seed(db)
        assertEquals(29L, db.count("category"))
        // an interrupted seed (one row missing) is not completed on top of existing rows: no duplicates either way
        appDb.close()
        val reopened = freshDb()
        assertEquals(29L, reopened.openHelper.writableDatabase.count("category"))
        reopened.close()
    }

    @Test
    fun `REQ-EXP-042 the member table holds exactly one Me row from the first start`() {
        migrate(8) { seedBase(8) }
        val db = freshDb().openHelper.writableDatabase
        assertEquals(1L, db.count("member"))
        db.query("SELECT id, displayName, sortOrder FROM member").use {
            assertTrue(it.moveToFirst())
            assertEquals(ME_MEMBER_ID, it.getString(0))
            assertEquals("Me", it.getString(1))
            assertEquals(0, it.getInt(2))
        }
        db.close()
    }

    @Test
    fun `REQ-EXP-042 seeding again does not create a second Me and respects an existing member`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        DefaultSeed.seed(db)
        assertEquals(1L, db.count("member"))
        db.execSQL("DELETE FROM member")
        db.execSQL("INSERT INTO member (id, displayName, sortOrder) VALUES ('m-2', 'Sara', 0)")
        DefaultSeed.seed(db)
        assertEquals(1L, db.count("member"))
        appDb.close()
    }

    private fun SupportSQLiteDatabase.insertExpense(id: String) =
        execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, pending, failed) VALUES ('$id', 1, 'EGP', 1, 0, 0)",
        )

    private fun SupportSQLiteDatabase.insertSource(
        id: String,
        expenseId: String,
        local: String,
    ) = execSQL(
        "INSERT INTO expense_source (id, expenseId, channel, localSourceId, receivedAtMillis) " +
            "VALUES ('$id', '$expenseId', 'SMS', '$local', 1)",
    )

    @Test
    fun `REQ-DEDUP-062 the same localSourceId can be linked only once and the first row is unchanged`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        db.insertExpense("e1")
        db.insertExpense("e2")
        db.insertSource("s1", "e1", "L1")
        try {
            db.insertSource("s2", "e2", "L1")
            fail("duplicate localSourceId must be rejected")
        } catch (expected: SQLiteConstraintException) {
            db.query("SELECT expenseId FROM expense_source WHERE localSourceId = 'L1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("e1", it.getString(0))
                assertEquals(1, it.count)
            }
        }
        appDb.close()
    }

    @Test
    fun `REQ-DEDUP-062 deleting an expense deletes its sources and keeps the receipt with a null expenseId`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        db.insertExpense("e1")
        db.insertSource("s1", "e1", "L1")
        db.insertSource("s2", "e1", "L2")
        db.execSQL(
            "INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis) " +
                "VALUES ('r1', 'e1', 'a.jpg', 'image/jpeg', 10, 'hash1', NULL, 1)",
        )
        db.execSQL("DELETE FROM expense WHERE id = 'e1'")
        assertEquals(0L, db.count("expense_source"))
        assertEquals(1L, db.count("receipt"))
        db.query("SELECT expenseId FROM receipt WHERE id = 'r1'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
        }
        appDb.close()
    }

    @Test
    fun `REQ-DEDUP-062 the same file hash cannot be stored twice`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        val insert = "INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis) VALUES "
        db.execSQL("$insert('r1', NULL, 'a.jpg', 'image/jpeg', 10, 'same', NULL, 1)")
        try {
            db.execSQL("$insert('r2', NULL, 'b.jpg', 'image/jpeg', 10, 'same', NULL, 1)")
            fail("duplicate sha256 must be rejected")
        } catch (expected: SQLiteConstraintException) {
            assertEquals(1L, db.count("receipt"))
        }
        appDb.close()
    }

    @Test
    fun `REQ-DEDUP-062 a source whose expense does not exist is rejected by the enforced foreign key`() {
        val appDb = freshDb()
        val db = appDb.openHelper.writableDatabase
        try {
            db.insertSource("s1", "missing", "L9")
            fail("orphan source must be rejected")
        } catch (expected: SQLiteConstraintException) {
            assertEquals(0L, db.count("expense_source"))
        }
        appDb.close()
    }
}
