package eg.masroof

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.ALL_MIGRATIONS
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.DB_VERSION
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.DbStartup
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.GoalMovementEntity
import eg.masroof.data.db.InstallmentPaymentEntity
import eg.masroof.data.db.InstallmentPlanEntity
import eg.masroof.data.db.MerchantRuleEntity
import eg.masroof.data.db.MetalPriceEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.data.db.SavingsGoalEntity
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.data.db.seededCategoryId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private val NEW_TABLES =
    listOf(
        "account_balance",
        "budget",
        "installment_plan",
        "installment_payment",
        "subscription",
        "savings_goal",
        "goal_movement",
        "asset",
        "asset_lot",
        "metal_price",
        "merchant_rule",
        "debt",
        "net_worth_snapshot",
    )

/** STORY-201: the v9 -> v10 additive migration (REQ-AND-145), its tests from every version (REQ-AND-146) and erase (REQ-AND-147). */
@Suppress("LargeClass") // one story, one file of REQ-AND-145/146/147 tests
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomV10MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "v10-test.db"
    private val dbPath: String get() = context.getDatabasePath(name).path

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

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
        val tail = if (version >= 8) ", 'EXPENSE', 'CONFIRMED'" else ""
        val cols = if (version >= 8) ", type, status" else ""
        val src = if (version >= 3) ", source" else ""

        fun expense(
            id: String,
            amount: Long,
            currency: String,
            at: Long,
            merchant: String?,
            pending: Int,
            failed: Int,
            source: String,
        ) = execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, failed" +
                "$src$cols) VALUES ('$id', $amount, '$currency', $at, ${merchant?.let { "'$it'" }}, 'c1', 'a1', 'n', $pending, $failed" +
                "${if (version >= 3) ", '$source'" else ""}$tail)",
        )
        expense("e-123", 12_500, "EGP", 5_000, "Shop", 0, 0, "SMS")
        expense("q-1", 700, "EGP", 6_000, null, 1, 0, "MANUAL")
        expense("f-1", 900, "USD", 7_000, null, 1, 1, "MANUAL")
        execSQL("INSERT INTO category (id, nameEn, nameAr, sortOrder) VALUES ('c1', 'Food', 'أكل', 3)")
        execSQL("INSERT INTO account (id, label, currency) VALUES ('a1', 'Card', 'EGP')")
        execSQL(
            "INSERT INTO outbox (clientId, amountMinor, currency, occurredAtMillis, categoryId, accountId, notes, attempts, failed, " +
                "createdAtMillis) VALUES ('q-1', 700, 'EGP', 6000, NULL, NULL, NULL, 1, 0, 6000)",
        )
    }

    /** What exists from v8 on: captured, learned and review rows (plus v9's member, receipt and source). */
    private fun SupportSQLiteDatabase.seedPhoneRows(version: Int) {
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
        if (version >= 9) {
            execSQL("INSERT INTO member (id, displayName, sortOrder) VALUES ('m1', 'Sara', 1)")
            execSQL(
                "INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis) " +
                    "VALUES ('rc1', 'e-123', 'f.jpg', 'image/jpeg', 10, 'abc', NULL, 2)",
            )
            execSQL(
                "INSERT INTO expense_source (id, expenseId, channel, localSourceId, receivedAtMillis, senderId, instrumentLast4) " +
                    "VALUES ('s1', 'e-123', 'SMS', 'local-1', 3, 'CIB', '1234')",
            )
        }
    }

    private fun createAt(from: Int) {
        context.deleteDatabase(name)
        helper.createDatabase(dbPath, from).apply {
            seedBase(from)
            if (from >= 8) seedPhoneRows(from)
            close()
        }
    }

    private fun SupportSQLiteDatabase.assertOldRowsSurvived(from: Int) {
        assertEquals("v$from expenses", 3L, count("expense"))
        assertEquals("v$from categories", 1L, count("category"))
        assertEquals("v$from accounts", 1L, count("account"))
        assertEquals("v$from outbox", 1L, count("outbox"))
        query(
            "SELECT amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, pending, failed, " +
                "excludedFromTotals FROM expense WHERE id = 'e-123'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(12_500L, it.getLong(0))
            assertEquals("EGP", it.getString(1))
            assertEquals(5_000L, it.getLong(2))
            assertEquals("Shop", it.getString(3))
            assertEquals("c1", it.getString(4))
            assertEquals("a1", it.getString(5))
            assertEquals("n", it.getString(6))
            assertEquals(0, it.getInt(7))
            assertEquals(0, it.getInt(8))
            assertEquals("existing entries still count in totals", 0, it.getInt(9))
        }
        assertEquals("every entry counts in totals", 3L, count("expense WHERE excludedFromTotals = 0"))
        query("SELECT nameEn, nameAr, sortOrder, emoji FROM category WHERE id = 'c1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Food", it.getString(0))
            assertEquals("أكل", it.getString(1))
            assertEquals(3, it.getInt(2))
            assertTrue("existing categories have no emoji", it.isNull(3))
        }
        query("SELECT label, currency, creditLimitMinor, statementDay, dueDay, emoji FROM account WHERE id = 'a1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Card", it.getString(0))
            assertEquals("EGP", it.getString(1))
            for (i in 2..5) assertTrue("new account column $i is NULL", it.isNull(i))
        }
        query("SELECT amountMinor, attempts FROM outbox WHERE clientId = 'q-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(700L, it.getLong(0))
            assertEquals(1, it.getInt(1))
        }
        if (from >= 8) {
            assertEquals(1L, count("parsed_outbox"))
            assertEquals(1L, count("review_item"))
            assertEquals(1L, count("learned_template"))
            assertEquals(1L, count("captured_tx"))
            query("SELECT text FROM review_item WHERE id = 'r1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("body", it.getString(0))
            }
            query("SELECT localId, amountMinor, label FROM captured_tx").use {
                assertTrue(it.moveToFirst())
                assertEquals("l1", it.getString(0))
                assertEquals(100L, it.getLong(1))
                assertEquals("Shop", it.getString(2))
            }
        }
        if (from >= 9) {
            assertEquals(1L, count("receipt"))
            assertEquals(1L, count("expense_source"))
            assertEquals(1L, count("member WHERE id = 'm1' AND displayName = 'Sara'"))
            query("SELECT sha256, expenseId FROM receipt WHERE id = 'rc1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("abc", it.getString(0))
                assertEquals("e-123", it.getString(1))
            }
            query("SELECT localSourceId, senderId FROM expense_source WHERE id = 's1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("local-1", it.getString(0))
                assertEquals("CIB", it.getString(1))
            }
        }
        for (table in NEW_TABLES) assertEquals("$table starts empty", 0L, count(table))
    }

    @Test
    fun `REQ-AND-146 migrating from every shipped version 2 to 9 validates against the exported v10 schema and keeps every seeded row`() {
        for (from in 2..9) {
            createAt(from)
            val db = helper.runMigrationsAndValidate(dbPath, 10, true, *ALL_MIGRATIONS)
            db.assertOldRowsSurvived(from)
            db.close()
        }
    }

    @Test
    fun `REQ-AND-145 the app start migrates a database from every shipped version after the safety copy and keeps its rows`() {
        for (from in 2..9) {
            createAt(from)
            val ready = DatabaseStartup.open(context, name) as DbStartup.Ready
            val db = ready.db.openHelper.writableDatabase
            assertEquals(10, db.version)
            assertTrue(
                "the safety copy of v$from exists",
                File(context.noBackupFilesDir, "pre-migration-v$from.db").exists(),
            )
            db.assertOldRowsSurvived(from)
            ready.db.close()
            context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun `REQ-AND-145 a v9 database with sources, receipts, review items, learned templates and captured rows keeps them all`() {
        createAt(9)
        val ready = DatabaseStartup.open(context, name) as DbStartup.Ready
        val db = ready.db.openHelper.writableDatabase
        assertEquals(1L, db.count("learned_template WHERE id = 't1' AND shape = 'shape' AND last4Slot = 1"))
        assertEquals(1L, db.count("captured_tx WHERE localId = 'l1' AND expenseId = 'e-123'"))
        assertEquals(1L, db.count("review_item WHERE id = 'r1' AND text = 'body'"))
        assertEquals(1L, db.count("expense_source WHERE id = 's1' AND expenseId = 'e-123'"))
        assertEquals(1L, db.count("receipt WHERE id = 'rc1' AND expenseId = 'e-123'"))
        ready.db.close()
    }

    @Test
    fun `REQ-AND-145 a fresh install is created at v10 with every new table empty and no safety copy`() {
        context.deleteDatabase(name)
        val ready = DatabaseStartup.open(context, name) as DbStartup.Ready
        val db = ready.db.openHelper.writableDatabase
        assertEquals(10, db.version)
        assertEquals(DB_VERSION, db.version)
        for (table in NEW_TABLES) assertEquals(table, 0L, db.count(table))
        assertEquals(0, context.noBackupFilesDir.listFiles { f -> f.name.startsWith("pre-migration-") }!!.size)
        ready.db.close()
    }

    @Test
    fun `REQ-AND-145 a migration 9 to 10 that throws shows could not finish and keeps the original and the safety copy`() {
        createAt(9)
        val failing =
            arrayOf(
                *ALL_MIGRATIONS.filter { it.startVersion != 9 }.toTypedArray(),
                object : androidx.room.migration.Migration(9, 10) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE category ADD COLUMN emoji TEXT")
                        error("boom")
                    }
                },
            )
        val result = DatabaseStartup.open(context, name, failing)
        assertEquals(DbStartup.CouldNotFinish, result)
        assertTrue(File(context.noBackupFilesDir, "pre-migration-v9.db").exists())
        for (file in listOf(context.getDatabasePath(name), File(context.noBackupFilesDir, "pre-migration-v9.db"))) {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals("still version 9: ${file.name}", 9, it.version)
                it.rawQuery("SELECT COUNT(*) FROM expense", null).use { c ->
                    c.moveToFirst()
                    assertEquals(3, c.getInt(0))
                }
                it.rawQuery("SELECT COUNT(*) FROM review_item", null).use { c ->
                    c.moveToFirst()
                    assertEquals(1, c.getInt(0))
                }
            }
        }
    }

    @Test
    fun `REQ-AND-145 no destructive fallback is added and the shipped migrations are all present`() {
        assertEquals((2..9).toList(), ALL_MIGRATIONS.map { it.startVersion })
        assertEquals((3..10).toList(), ALL_MIGRATIONS.map { it.endVersion })
        val source = File("src/main/java/eg/masroof/data/db/DatabaseStartup.kt").readText()
        val fallbacks = Regex("fallbackToDestructiveMigration\\w*\\([^)]*\\)").findAll(source).map { it.value.replace("\\s".toRegex(), "") }
        assertEquals(listOf("fallbackToDestructiveMigrationFrom(true,1)"), fallbacks.toList())
    }

    @Test
    fun `REQ-AND-146 the exported v10 schema is at version 10, lists every new table, has no REAL column and 1 to 9 still exist`() {
        val dir = File("schemas/eg.masroof.data.db.AppDatabase")
        for (v in 1..9) assertTrue("$v.json is kept", File(dir, "$v.json").exists())
        val schema = Json.parseToJsonElement(File(dir, "10.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(10, schema["version"]!!.jsonPrimitive.content.toInt())
        val entities = schema["entities"]!!.jsonArray
        val names = entities.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(NEW_TABLES))
        for (entity in entities) {
            for (field in entity.jsonObject["fields"]!!.jsonArray) {
                assertFalse("no REAL column", field.jsonObject["affinity"]!!.jsonPrimitive.content == "REAL")
            }
        }
    }

    // ---- inserting and reading one row per new table (the real start path, foreign keys on)

    private fun freshDb(): AppDatabase {
        context.deleteDatabase(name)
        return (DatabaseStartup.open(context, name) as DbStartup.Ready).db
    }

    private val cat get() = seededCategoryId("groceries")

    private fun AppDatabase.seedParents() {
        openHelper.writableDatabase.execSQL("INSERT INTO account (id, label, currency, archived) VALUES ('a1', 'CIB', 'EGP', 0)")
        openHelper.writableDatabase.execSQL("INSERT INTO category (id, nameEn, nameAr, sortOrder) VALUES ('c-x', 'X', 'س', 99)")
    }

    private suspend fun AppDatabase.insertOneOfEach() {
        seedParents()
        expenses().insert(ExpenseEntity("e1", 5_000, "EGP", 1, "Shop", cat, "a1", null, pending = false))
        accountBalances().insert(AccountBalanceEntity("ab1", "a1", 1_250_000, "EGP", 10, "MANUAL", 11))
        budgets().insert(BudgetEntity("b1", cat, "MONTHLY", 300_000, "EGP", createdAtMillis = 1, updatedAtMillis = 2))
        installmentPlans().insert(plan("p1"))
        installmentPayments().insert(InstallmentPaymentEntity("ip1", "p1", 1, 100, 250_000, null, "e1"))
        subscriptions().insert(
            SubscriptionEntity(
                "s1",
                "INCOME",
                "Salary",
                2_000_000,
                "EGP",
                "MONTHLY",
                50,
                cat,
                "a1",
                "acme",
                2,
                notes = null,
                createdAtMillis = 1,
            ),
        )
        savingsGoals().insert(SavingsGoalEntity("g1", "Car", "x", 90_000_000, "EGP", null, 0, 1, null, null))
        goalMovements().insert(GoalMovementEntity("gm1", "g1", -5_000, 20, "took out"))
        assets().insert(asset("as1", "g1"))
        assetLots().insert(AssetLotEntity("al1", "as1", "BUY", 8_000, 1_600_000, "EGP", 30, "e1", null))
        metalPrices().insert(MetalPriceEntity("mp1", "GOLD", 21, 340_000, "EGP", 40))
        merchantRules().insert(MerchantRuleEntity("mr1", "carrefour", cat, 5))
        debts().insert(DebtEntity("d1", "Loan", "LOAN", "I_OWE", 5_000_000, "EGP", 60, null, null, 1))
        netWorthSnapshots().insert(NetWorthSnapshotEntity("n1", "2026-11-08", 10, 4, 6, "EGP", "GOLD=10;CASH=0", 7))
    }

    private fun plan(id: String) =
        InstallmentPlanEntity(
            id,
            "Phone",
            "valU",
            cat,
            "a1",
            "EGP",
            3_000_000,
            500_000,
            250_000,
            12,
            100,
            3,
            "ACTIVE",
            null,
            1,
            null,
            900,
            4_000_000,
            null,
        )

    private fun asset(
        id: String,
        goalId: String?,
    ) = AssetEntity(
        id,
        "GOLD",
        "Gold 21",
        "EGP",
        null,
        21,
        null,
        null,
        null,
        null,
        goalId,
        createdAtMillis = 1,
        updatedAtMillis = 2,
    )

    @Test
    fun `REQ-AND-146 one row inserted into every new table reads back with its values`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            assertEquals(
                1_250_000L,
                db
                    .accountBalances()
                    .observeById("ab1")
                    .first()!!
                    .balanceMinor,
            )
            assertEquals(
                "50,80,100",
                db
                    .budgets()
                    .observeById("b1")
                    .first()!!
                    .alertThresholds,
            )
            assertFalse(
                db
                    .budgets()
                    .observeById("b1")
                    .first()!!
                    .rollover,
            )
            val plan =
                db
                    .installmentPlans()
                    .observeAll()
                    .first()
                    .single()
            assertEquals(4_000_000L, plan.payoutAmountMinor)
            assertEquals(
                "e1",
                db
                    .installmentPayments()
                    .observeById("ip1")
                    .first()!!
                    .expenseId,
            )
            assertTrue(
                db
                    .subscriptions()
                    .observeById("s1")
                    .first()!!
                    .active,
            )
            assertEquals(
                "Car",
                db
                    .savingsGoals()
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
            assertEquals(
                -5_000L,
                db
                    .goalMovements()
                    .observeById("gm1")
                    .first()!!
                    .amountMinor,
            )
            assertEquals(
                21,
                db
                    .assets()
                    .observeById("as1")
                    .first()!!
                    .karat,
            )
            assertEquals(
                8_000L,
                db
                    .assetLots()
                    .observeById("al1")
                    .first()!!
                    .quantityMilli,
            )
            assertEquals(
                340_000L,
                db
                    .metalPrices()
                    .observeAll()
                    .first()
                    .single()
                    .pricePerGramMinor,
            )
            assertEquals(
                "carrefour",
                db
                    .merchantRules()
                    .observeById("mr1")
                    .first()!!
                    .merchantKey,
            )
            assertEquals(
                "I_OWE",
                db
                    .debts()
                    .observeById("d1")
                    .first()!!
                    .direction,
            )
            assertEquals(
                "2026-11-08",
                db
                    .netWorthSnapshots()
                    .observeAll()
                    .first()
                    .single()
                    .cairoDate,
            )
            // update and delete work, and a missing id observes null
            db.debts().update(
                db
                    .debts()
                    .observeById("d1")
                    .first()!!
                    .copy(settledAtMillis = 99),
            )
            assertEquals(
                99L,
                db
                    .debts()
                    .observeById("d1")
                    .first()!!
                    .settledAtMillis,
            )
            db.debts().delete(db.debts().observeById("d1").first()!!)
            assertNull(db.debts().observeById("d1").first())
            db.close()
        }

    @Test
    fun `REQ-AND-146 the new account and category columns and the excluded flag round trip`() =
        runTest {
            val db = freshDb()
            val sql = db.openHelper.writableDatabase
            sql.execSQL(
                "INSERT INTO account (id, label, currency, archived, creditLimitMinor, statementDay, dueDay, emoji) " +
                    "VALUES ('a9', 'Visa', 'EGP', 0, 5000000, 25, 12, 'x')",
            )
            sql.execSQL("UPDATE category SET emoji = 'y' WHERE id = '$cat'")
            db.expenses().insert(ExpenseEntity("e9", 1, "EGP", 1, null, null, null, null, pending = false, excludedFromTotals = true))
            assertEquals(1L, sql.count("account WHERE creditLimitMinor = 5000000 AND statementDay = 25 AND dueDay = 12 AND emoji = 'x'"))
            assertEquals(1L, sql.count("category WHERE emoji = 'y'"))
            assertEquals(1L, sql.count("expense WHERE excludedFromTotals = 1"))
            db.close()
        }

    @Test
    fun `REQ-AND-146 deleting a plan deletes its payments and deleting an expense unlinks payments and lots`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM expense WHERE id = 'e1'")
            assertNull(
                db
                    .installmentPayments()
                    .observeById("ip1")
                    .first()!!
                    .expenseId,
            )
            assertNull(
                db
                    .assetLots()
                    .observeById("al1")
                    .first()!!
                    .expenseId,
            )
            assertEquals("payment and lot themselves stay", 2L, sql.count("installment_payment") + sql.count("asset_lot"))
            db.installmentPlans().delete(plan("p1"))
            assertEquals(0L, sql.count("installment_payment"))
            db.close()
        }

    @Test
    fun `REQ-AND-146 deleting an asset deletes its lots, a goal its movements, an account its balances`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            val sql = db.openHelper.writableDatabase
            db.assets().delete(asset("as1", "g1"))
            assertEquals(0L, sql.count("asset_lot"))
            db.savingsGoals().delete(db.savingsGoals().observeById("g1").first()!!)
            assertEquals(0L, sql.count("goal_movement"))
            sql.execSQL("DELETE FROM account WHERE id = 'a1'")
            assertEquals(0L, sql.count("account_balance"))
            db.close()
        }

    @Test
    fun `REQ-AND-146 deleting a goal keeps its asset with the goal link cleared and deleting a category nulls the budget`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM savings_goal WHERE id = 'g1'")
            assertNull(
                db
                    .assets()
                    .observeById("as1")
                    .first()!!
                    .goalId,
            )
            assertNotNull(db.assets().observeById("as1").first())
            sql.execSQL("DELETE FROM category WHERE id = '$cat'")
            assertNull(
                db
                    .budgets()
                    .observeById("b1")
                    .first()!!
                    .categoryId,
            )
            assertEquals("a merchant rule goes with its category", 0L, sql.count("merchant_rule"))
            db.close()
        }

    @Test
    fun `REQ-AND-146 a second installment payment with the same plan and seq is rejected and the first is unchanged`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            try {
                db.installmentPayments().insert(InstallmentPaymentEntity("ip2", "p1", 1, 200, 1, null, null))
                fail("duplicate (planId, seq) must be rejected")
            } catch (expected: SQLiteConstraintException) {
                assertEquals(
                    250_000L,
                    db
                        .installmentPayments()
                        .observeById("ip1")
                        .first()!!
                        .amountMinor,
                )
            }
            db.installmentPayments().insert(InstallmentPaymentEntity("ip3", "p1", 2, 200, 1, null, null))
            assertEquals(
                2,
                db
                    .installmentPayments()
                    .observeAll()
                    .first()
                    .size,
            )
            db.close()
        }

    @Test
    fun `REQ-AND-146 a second merchant rule or net worth snapshot with the same unique key is rejected`() =
        runTest {
            val db = freshDb()
            db.insertOneOfEach()
            try {
                db.merchantRules().insert(MerchantRuleEntity("mr2", "carrefour", cat, 6))
                fail("duplicate merchantKey must be rejected")
            } catch (expected: SQLiteConstraintException) {
                assertEquals(
                    1,
                    db
                        .merchantRules()
                        .observeAll()
                        .first()
                        .size,
                )
            }
            try {
                db.netWorthSnapshots().insert(NetWorthSnapshotEntity("n2", "2026-11-08", 1, 1, 0, "EGP", "", 8))
                fail("duplicate cairoDate must be rejected")
            } catch (expected: SQLiteConstraintException) {
                assertEquals(
                    1,
                    db
                        .netWorthSnapshots()
                        .observeAll()
                        .first()
                        .size,
                )
            }
            db.close()
        }

    @Test
    fun `REQ-AND-146 a row pointing at a missing parent is rejected`() =
        runTest {
            val db = freshDb()
            try {
                db.installmentPayments().insert(InstallmentPaymentEntity("ip1", "no-plan", 1, 1, 1, null, null))
                fail("payment without a plan must be rejected")
            } catch (expected: SQLiteConstraintException) {
                assertTrue(
                    db
                        .installmentPayments()
                        .observeAll()
                        .first()
                        .isEmpty(),
                )
            }
            db.close()
        }

    // ---- REQ-AND-147: Erase all data empties every new table

    @Test
    fun `REQ-AND-147 erase all data empties every new table`() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
            try {
                eg.masroof.data.db.DefaultSeed
                    .seed(db.openHelper.writableDatabase)
                db.insertOneOfEach()
                val sql = db.openHelper.writableDatabase
                for (table in NEW_TABLES) assertTrue("$table has a row before the erase", sql.count(table) > 0)
                val wiper =
                    RoomDataWiper(
                        db,
                        NoInsightsCache,
                        androidx.work.WorkManager.getInstance(context),
                        Dispatchers.Unconfined,
                        gate = MemoryGate(open = true),
                        receiptsDir = tmp.newFolder("receipts"),
                        safetyCopyDir = tmp.newFolder("safety"),
                        cameraDir = tmp.newFolder("camera"),
                    )
                wiper.wipe()
                for (table in NEW_TABLES) assertEquals("$table is empty after the erase", 0L, sql.count(table))
                assertEquals("the expense is gone too", 0L, sql.count("expense"))
                assertEquals("the default categories are back", 29L, sql.count("category"))
            } finally {
                db.close()
            }
        }
}
