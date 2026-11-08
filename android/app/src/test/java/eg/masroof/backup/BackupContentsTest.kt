package eg.masroof.backup

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.R
import eg.masroof.core.DigitStyle
import eg.masroof.data.DataStoreSettings
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.DataStoreAnswerPrefs
import eg.masroof.data.answers.EntryLink
import eg.masroof.data.answers.LinkKind
import eg.masroof.data.backup.AndroidBackupPrefs
import eg.masroof.data.backup.BackupFormat
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupPrefsInventory
import eg.masroof.data.backup.BackupTables
import eg.masroof.data.backup.DirectoryReceiptFiles
import eg.masroof.data.backup.RoomBackupDatabase
import eg.masroof.data.bills.DataStoreSubscriptionPrefs
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DB_VERSION
import eg.masroof.ingest.PrefsCapturedAlertSettings
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ui.AppLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** What the real database and the real preference stores give the backup, decrypted again by the test decryptor (REQ-OPS-176). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupContentsTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var receiptsDir: File

    private val attachedBytes = ByteArray(5_000) { (it * 7).toByte() }
    private val unattachedBytes = "%PDF-1.4 unattached receipt".toByteArray()

    private val reviewText = "KNOWN-REVIEW-TEXT: your card was charged EGP 500 at Merchant Zed"
    private val otpText = "OTP-FIXTURE 482913 do not share this code"
    private val fullAccount = "4111111111111111"
    private val sessionToken = "SESSION-TOKEN-abcdef0123456789"
    private val serverUrl = "https://server.example.test/private-host/"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        receiptsDir = File(context.cacheDir, "backup-test-receipts").also { it.deleteRecursively() }
        receiptsDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        receiptsDir.deleteRecursively()
    }

    private fun insert(
        table: String,
        vararg values: Pair<String, Any?>,
    ) {
        val cv = ContentValues()
        for ((k, v) in values) {
            when (v) {
                null -> cv.putNull(k)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is String -> cv.put(k, v)
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> error("type")
            }
        }
        db.openHelper.writableDatabase.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    /** One row in every table, with the v10 columns filled, a soft-deleted entry, an attached and an unattached receipt. */
    private fun fillEverything() {
        val t = 1_793_000_000_000L
        insert(
            "category",
            "id" to "cat1",
            "nameEn" to "Groceries",
            "nameAr" to "بقالة",
            "sortOrder" to 1,
            "emoji" to "🛒",
            "parentId" to null,
            "system" to true,
        )
        insert(
            "account",
            "id" to "acc1",
            "label" to "CIB card",
            "currency" to "EGP",
            "kind" to "CARD",
            "last4" to "1898",
            "archived" to false,
            "creditLimitMinor" to 5_000_000L,
            "statementDay" to 25,
            "dueDay" to 15,
            "emoji" to "💳",
        )
        insert("member", "id" to "mem1", "displayName" to "Me", "sortOrder" to 0)
        insert(
            "expense",
            "id" to "e1",
            "amountMinor" to 125_050L,
            "currency" to "EGP",
            "occurredAtMillis" to t,
            "merchant" to "Carrefour",
            "categoryId" to "cat1",
            "accountId" to "acc1",
            "notes" to "weekly \"shop\"\nline2",
            "pending" to false,
            "failed" to false,
            "source" to "SMS",
            "type" to "EXPENSE",
            "status" to "CONFIRMED",
            "createdAtMillis" to t,
            "updatedAtMillis" to t,
            "deletedAtMillis" to null,
            "timeKnown" to false,
            "paymentMethod" to "CARD",
            "instrumentLast4" to "1898",
            "paidByMemberId" to "mem1",
            "serverId" to null,
            "excludedFromTotals" to true,
        )
        insert(
            "expense",
            "id" to "e2",
            "amountMinor" to 9_900L,
            "currency" to "EGP",
            "occurredAtMillis" to t - 1_000,
            "pending" to false,
            "failed" to false,
            "createdAtMillis" to t,
            "updatedAtMillis" to t,
            "deletedAtMillis" to t + 5,
        )
        insert(
            "budget",
            "id" to "b1",
            "categoryId" to "cat1",
            "period" to "MONTHLY",
            "amountMinor" to 300_000L,
            "currency" to "EGP",
            "createdAtMillis" to t,
            "updatedAtMillis" to t,
        )
        insert(
            "expense_source",
            "id" to "s1",
            "expenseId" to "e1",
            "channel" to "SMS",
            "localSourceId" to "abcd1234",
            "receivedAtMillis" to t,
            "senderId" to "CIB",
            "instrumentLast4" to "1898",
        )
        insert(
            "receipt",
            "id" to "r-att",
            "expenseId" to "e1",
            "fileName" to "r-att.jpg",
            "mimeType" to "image/jpeg",
            "sizeBytes" to attachedBytes.size.toLong(),
            "sha256" to sha(attachedBytes),
            "pageCount" to null,
            "createdAtMillis" to t,
        )
        insert(
            "receipt",
            "id" to "r-un",
            "expenseId" to null,
            "fileName" to "r-un.pdf",
            "mimeType" to "application/pdf",
            "sizeBytes" to unattachedBytes.size.toLong(),
            "sha256" to sha(unattachedBytes),
            "pageCount" to 1,
            "createdAtMillis" to t + 1,
        )
        File(receiptsDir, "r-att.jpg").writeBytes(attachedBytes)
        File(receiptsDir, "r-un.pdf").writeBytes(unattachedBytes)
        insert(
            "learned_template",
            "id" to "lt1",
            "sender" to "CIB",
            "shape" to "purchase of # at #",
            "kind" to "EXPENSE",
            "amountSlot" to 0,
            "last4Slot" to -1,
            "createdAtMillis" to t,
        )
        insert(
            "account_balance",
            "id" to "ab1",
            "accountId" to "acc1",
            "balanceMinor" to 1_234_500L,
            "currency" to "EGP",
            "atMillis" to t,
            "source" to "MANUAL",
            "createdAtMillis" to t,
        )
        insert(
            "installment_plan",
            "id" to "ip1",
            "title" to "Phone",
            "provider" to "valU",
            "categoryId" to null,
            "accountId" to null,
            "currency" to "EGP",
            "purchasePriceMinor" to 2_400_000L,
            "downPaymentMinor" to 0L,
            "monthlyAmountMinor" to 200_000L,
            "paymentCount" to 12,
            "firstDueAtMillis" to t,
            "reminderDaysBefore" to 2,
            "status" to "ACTIVE",
            "notes" to null,
            "createdAtMillis" to t,
            "completedAtMillis" to null,
            "payoutAtMillis" to null,
            "payoutAmountMinor" to null,
            "payoutReceivedAtMillis" to null,
        )
        insert(
            "installment_payment",
            "id" to "ipp1",
            "planId" to "ip1",
            "seq" to 1,
            "dueAtMillis" to t,
            "amountMinor" to 200_000L,
            "paidAtMillis" to null,
            "expenseId" to null,
        )
        insert(
            "subscription",
            "id" to "sub1",
            "kind" to "SUBSCRIPTION",
            "name" to "Netflix",
            "amountMinor" to 21_900L,
            "currency" to "EGP",
            "cadence" to "MONTHLY",
            "nextRenewalAtMillis" to t,
            "categoryId" to null,
            "accountId" to null,
            "merchantKey" to "netflix",
            "reminderDaysBefore" to 1,
            "notes" to null,
            "createdAtMillis" to t,
        )
        insert(
            "savings_goal",
            "id" to "g1",
            "name" to "Car",
            "emoji" to "🚗",
            "targetMinor" to 50_000_000L,
            "currency" to "EGP",
            "targetAtMillis" to null,
            "sortOrder" to 0,
            "createdAtMillis" to t,
            "completedAtMillis" to null,
            "archivedAtMillis" to null,
        )
        insert("goal_movement", "id" to "gm1", "goalId" to "g1", "amountMinor" to 100_000L, "atMillis" to t, "note" to null)
        insert(
            "asset",
            "id" to "as1",
            "kind" to "GOLD",
            "name" to "Gold bars",
            "currency" to "EGP",
            "valueMinor" to null,
            "karat" to 21,
            "principalMinor" to null,
            "annualRateBp" to null,
            "startAtMillis" to null,
            "maturityAtMillis" to null,
            "goalId" to "g1",
            "hiddenFromTotals" to false,
            "archived" to false,
            "createdAtMillis" to t,
            "updatedAtMillis" to t,
        )
        insert(
            "asset_lot",
            "id" to "al1",
            "assetId" to "as1",
            "side" to "BUY",
            "quantityMilli" to 8_000L,
            "totalMinor" to 4_000_000L,
            "currency" to "EGP",
            "atMillis" to t,
            "expenseId" to null,
            "note" to null,
        )
        insert(
            "metal_price",
            "id" to "mp1",
            "metal" to "GOLD",
            "karat" to 21,
            "pricePerGramMinor" to 380_000L,
            "currency" to "EGP",
            "atMillis" to t,
        )
        insert("merchant_rule", "id" to "mr1", "merchantKey" to "carrefour", "categoryId" to "cat1", "createdAtMillis" to t)
        insert(
            "debt",
            "id" to "d1",
            "name" to "Ahmed",
            "kind" to "PERSON",
            "direction" to "OWED_TO_ME",
            "amountMinor" to 500_000L,
            "currency" to "EGP",
            "dueAtMillis" to null,
            "settledAtMillis" to null,
            "notes" to null,
            "createdAtMillis" to t,
        )
        insert(
            "net_worth_snapshot",
            "id" to "nw1",
            "cairoDate" to "2026-11-08",
            "assetsMinor" to 9_000_000L,
            "debtsMinor" to 100_000L,
            "netMinor" to 8_900_000L,
            "currency" to "EGP",
            "breakdown" to "GOLD=4000000",
            "createdAtMillis" to t,
        )
        // The tables that must never leave the phone, with strings the grep test looks for.
        insert(
            "review_item",
            "id" to "rv1",
            "source" to "SMS",
            "sender" to "CIB",
            "text" to "$reviewText account $fullAccount",
            "reason" to "UNPARSED",
            "receivedAtMillis" to t,
            "createdAtMillis" to t,
        )
        insert(
            "review_item",
            "id" to "rv2",
            "source" to "SMS",
            "sender" to "CIB",
            "text" to otpText,
            "reason" to "SECRET",
            "receivedAtMillis" to t,
            "createdAtMillis" to t,
        )
        insert(
            "captured_tx",
            "localId" to "c1",
            "amountMinor" to 1L,
            "currency" to "EGP",
            "source" to "SMS",
            "capturedAtMillis" to t,
            "undoRequested" to false,
            "receivedAtMillis" to t,
            "paired" to false,
        )
        insert(
            "outbox",
            "clientId" to "o1",
            "amountMinor" to 1L,
            "currency" to "EGP",
            "occurredAtMillis" to t,
            "notes" to reviewText,
            "attempts" to 0,
            "failed" to false,
            "createdAtMillis" to t,
        )
        insert(
            "parsed_outbox",
            "localId" to "po1",
            "source" to "SMS",
            "kind" to "EXPENSE",
            "amountMinor" to 1L,
            "currency" to "EGP",
            "occurredAtMillis" to t,
            "senderId" to "CIB",
            "attempts" to 0,
            "failed" to false,
            "createdAtMillis" to t,
        )
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun setPreferences() =
        runBlocking {
            val settings = DataStoreSettings(context)
            settings.update {
                it.copy(
                    serverUrl = serverUrl,
                    digitStyle = DigitStyle.ARABIC_INDIC,
                    monthStartDay = 27,
                    includeCashWithdrawals = true,
                )
            }
            DataStoreAnswerPrefs(context).edit {
                it.copy(
                    answered = setOf("e1"),
                    links = setOf(EntryLink(LinkKind.REFUND, "e2", "e1", nonce = "nonce-1")),
                    ignoredCards = setOf("4321"),
                    aliases = mapOf("1898" to "acc1"),
                    atmChoice = AtmChoice.COUNT,
                )
            }
            val subs = DataStoreSubscriptionPrefs(context)
            subs.setAnchor("sub1", 30)
            subs.dismissKey("spotify")
            subs.addNotice(
                eg.masroof.data.bills
                    .PriceNotice("sub1", "Netflix", 20_000, 21_900, "EGP", "e1", 20_400),
            )
            PrefsSenderSettings(context.getSharedPreferences("masroof_senders", Context.MODE_PRIVATE)).apply {
                setSmsCaptureEnabled(false)
                addSender("MY-BANK")
                setDefaultEnabled("CIB", false)
            }
            PrefsNotificationSettings(context.getSharedPreferences("masroof_notifications", Context.MODE_PRIVATE)).apply {
                setEnabled(true)
                addPackage("com.example.bank")
            }
            PrefsCapturedAlertSettings(context.getSharedPreferences("masroof_captured_alerts", Context.MODE_PRIVATE)).setEnabled(false)
            AppLanguage.toggle(context)
            // Session and device state: never part of the backup.
            context
                .getSharedPreferences("masroof_session", Context.MODE_PRIVATE)
                .edit()
                .putString("access", sessionToken)
                .commit()
            context
                .getSharedPreferences("masroof_device", Context.MODE_PRIVATE)
                .edit()
                .putString("device_id", "device-xyz-123")
                .commit()
        }

    private val localKey = ByteArray(32) { (it + 100).toByte() }

    private fun backup(
        header: BackupHeader = fastHeader(),
        files: eg.masroof.data.backup.ReceiptFiles = DirectoryReceiptFiles(receiptsDir),
    ): Pair<Decrypted, eg.masroof.data.backup.BackupResult> {
        val env =
            eg.masroof.data.backup.BackupEnvironment(
                RoomBackupDatabase(db),
                AndroidBackupPrefs(context),
                { localKey.copyOf() },
                files,
                "0.1.7",
                1_793_100_000_000L,
            )
        val (bytes, result) = writeBackup(env, header = header)
        return BackupTestDecryptor.decrypt(bytes, "correct horse battery") to result
    }

    private fun rowsOf(table: String): List<Map<String, Any?>> =
        db.query("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        c.columnNames.indices.associate { i ->
                            c.columnNames[i] to
                                when (c.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    else -> c.getString(i)
                                }
                        },
                    )
                }
            }
        }

    private fun jsonRows(text: String): List<JSONObject> = text.lines().filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun `REQ-OPS-176 every Room table is on the included or the excluded list so a new table cannot be forgotten`() {
        val all =
            db.query("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            } - setOf("android_metadata", "room_master_table", "sqlite_sequence")
        val listed = BackupTables.INCLUDED.map { it.name }.toSet() + BackupTables.EXCLUDED
        assertEquals(all.sorted(), listed.sorted())
        assertTrue(
            BackupTables.INCLUDED
                .map { it.name }
                .toSet()
                .intersect(BackupTables.EXCLUDED)
                .isEmpty(),
        )
        assertEquals("the data schema version follows the Room version it was written for", DB_VERSION, BackupFormat.DATA_SCHEMA_VERSION)
    }

    @Test
    fun `REQ-OPS-176 manifest is the first entry with versions, app version, createdAtUtc, row counts and receipt size and sha256`() {
        fillEverything()
        val (plain, result) = backup()
        assertEquals("manifest.json", plain.names.first())
        val m = JSONObject(plain.text("manifest.json"))
        assertEquals(1, m.getInt("formatVersion"))
        assertEquals(10, m.getInt("dataSchemaVersion"))
        assertEquals("0.1.7", m.getString("appVersion"))
        assertEquals(
            java.time.Instant
                .ofEpochMilli(1_793_100_000_000L)
                .toString(),
            m.getString("createdAtUtc"),
        )
        val counts =
            m.getJSONArray("tables").let { a ->
                (0 until a.length()).associate {
                    a.getJSONObject(it).getString("name") to
                        a.getJSONObject(it).getLong("rows")
                }
            }
        for (t in BackupTables.INCLUDED) assertEquals("rows of ${t.name}", rowsOf(t.name).size.toLong(), counts.getValue(t.name))
        assertEquals(2L, counts.getValue("expense"))
        assertEquals(1L, result.entries) // the soft-deleted entry is in the file but not in the "entries" figure
        val receipts = m.getJSONArray("receipts")
        assertEquals(2, receipts.length())
        val att = (0 until 2).map { receipts.getJSONObject(it) }.first { it.getString("id") == "r-att" }
        assertEquals(attachedBytes.size.toLong(), att.getLong("size"))
        assertEquals(sha(attachedBytes), att.getString("sha256"))
        assertEquals("receipts/r-att.jpg", att.getString("entry"))
        assertFalse(att.getBoolean("missing"))
    }

    @Test
    fun `REQ-OPS-176 the entries come in the order manifest, data files, settings, local id key, receipts`() {
        fillEverything()
        val (plain, _) = backup()
        val expected =
            listOf("manifest.json") + BackupTables.INCLUDED.map { it.file } +
                listOf("data/settings.json", "keys/local-id.key", "receipts/r-att.jpg", "receipts/r-un.pdf")
        assertEquals(expected, plain.names)
        assertTrue(
            plain.names.containsAll(
                listOf("data/expenses.jsonl", "data/budgets.jsonl", "data/net_worth_snapshots.jsonl", "data/learned_templates.jsonl"),
            ),
        )
    }

    @Test
    fun `REQ-OPS-176 round trip - every row of every included table comes back column for column`() {
        fillEverything()
        val (plain, _) = backup()
        for (t in BackupTables.INCLUDED) {
            val source = rowsOf(t.name)
            val back = jsonRows(plain.text(t.file))
            assertEquals("row count of ${t.name}", source.size, back.size)
            source.zip(back).forEach { (row, json) ->
                assertEquals("columns of ${t.name}", row.keys, json.keys().asSequence().toSet())
                for ((col, v) in row) {
                    val got = if (json.isNull(col)) null else json.get(col)
                    val gotNorm = if (got is Int) got.toLong() else got
                    assertEquals("${t.name}.$col", v, gotNorm)
                }
            }
        }
    }

    @Test
    fun `REQ-OPS-176 soft-deleted entries and the v10 columns travel in the expense file`() {
        fillEverything()
        val (plain, _) = backup()
        val rows = jsonRows(plain.text("data/expenses.jsonl")).associateBy { it.getString("id") }
        assertEquals(1_793_000_000_005L, rows.getValue("e2").getLong("deletedAtMillis"))
        val e1 = rows.getValue("e1")
        assertEquals(1, e1.getInt("excludedFromTotals"))
        assertEquals(0, e1.getInt("timeKnown"))
        assertEquals("1898", e1.getString("instrumentLast4"))
        assertEquals("weekly \"shop\"\nline2", e1.getString("notes")) // escaping survives
        val cat = jsonRows(plain.text("data/categories.jsonl")).single()
        assertEquals("🛒", cat.getString("emoji"))
        assertEquals("بقالة", cat.getString("nameAr"))
        val acc = jsonRows(plain.text("data/accounts.jsonl")).single()
        assertEquals("1898", acc.getString("last4"))
        assertEquals(25, acc.getInt("statementDay"))
    }

    @Test
    fun `REQ-OPS-176 amounts are JSON integers with an ISO currency code and instants are epoch milliseconds`() {
        fillEverything()
        val (plain, _) = backup()
        val line = plain.text("data/expenses.jsonl").lines().first()
        assertTrue(line, line.contains("\"amountMinor\":125050,"))
        assertTrue(line, line.contains("\"currency\":\"EGP\""))
        assertTrue(line, line.contains("\"occurredAtMillis\":1793000000000,"))
        assertFalse("no decimal point in any amount", Regex("\"amountMinor\":\\d+\\.").containsMatchIn(plain.text("data/expenses.jsonl")))
        for (t in BackupTables.INCLUDED) {
            for (json in jsonRows(plain.text(t.file))) {
                for (k in json.keys()) {
                    val v = json.get(k)
                    assertFalse("${t.name}.$k is a floating point number", v is Double || v is Float || v is java.math.BigDecimal)
                    if (k == "currency") assertTrue(Regex("[A-Z]{3}").matches(v as String))
                }
            }
        }
    }

    @Test
    fun `REQ-SEC-066 no review text, OTP, full account number or session token appears anywhere in the plaintext`() {
        fillEverything()
        setPreferences()
        val env =
            eg.masroof.data.backup.BackupEnvironment(
                RoomBackupDatabase(db),
                AndroidBackupPrefs(context),
                { localKey.copyOf() },
                DirectoryReceiptFiles(receiptsDir),
                "0.1.7",
                1_793_100_000_000L,
            )
        val (bytes, _) = writeBackup(env)
        val plain = BackupTestDecryptor.decrypt(bytes, "correct horse battery")
        val everything = plain.everything() + plain.entries.values.joinToString("") { String(it, Charsets.UTF_8) }
        for (secret in listOf(
            reviewText,
            "KNOWN-REVIEW-TEXT",
            otpText,
            "482913",
            "OTP-FIXTURE",
            fullAccount,
            sessionToken,
            "device-xyz-123",
            serverUrl,
            "private-host",
        )) {
            assertFalse("found: $secret", everything.contains(secret))
        }
        assertFalse(plain.names.any { it.contains("review") || it.contains("captured") || it.contains("outbox") })
        // The ciphertext itself holds none of them either (sanity: it is not plaintext).
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("KNOWN-REVIEW-TEXT"))
    }

    @Test
    fun `REQ-OPS-176 attached and unattached receipts are both in the file, byte for byte`() {
        fillEverything()
        val (plain, result) = backup()
        assertArrayEquals(attachedBytes, plain.entries.getValue("receipts/r-att.jpg"))
        assertArrayEquals(unattachedBytes, plain.entries.getValue("receipts/r-un.pdf"))
        assertEquals(2, result.receiptsStored)
        assertEquals(0, result.receiptsMissing)
        assertTrue(result.fileBytes > attachedBytes.size)
    }

    @Test
    fun `REQ-OPS-176 a receipt file missing on disk is listed in the manifest and the backup still completes`() {
        fillEverything()
        File(receiptsDir, "r-att.jpg").delete()
        val (plain, result) = backup()
        assertEquals(1, result.receiptsMissing)
        assertEquals(1, result.receiptsStored)
        assertFalse(plain.names.contains("receipts/r-att.jpg"))
        val att =
            JSONObject(plain.text("manifest.json"))
                .getJSONArray("receipts")
                .let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }
                }.first {
                    it.getString("id") ==
                        "r-att"
                }
        assertTrue(att.getBoolean("missing"))
        assertTrue(att.isNull("sha256"))
        // The receipt row itself is still in receipts.jsonl: the entry has its receipt record, only the file is gone.
        assertEquals(2, jsonRows(plain.text("data/receipts.jsonl")).size)
        val message = context.resources.getQuantityString(R.plurals.backup_missing, result.receiptsMissing, "1")
        assertEquals("1 receipt file was missing and is not in the backup", message)
    }

    @Test
    fun `REQ-OPS-176 a receipt name that would leave the receipts folder counts as missing, never as a read outside it`() {
        fillEverything()
        File(context.cacheDir, "outside.bin").writeBytes(byteArrayOf(1, 2, 3))
        db.openHelper.writableDatabase.execSQL("UPDATE receipt SET fileName='../outside.bin' WHERE id='r-att'")
        val (plain, result) = backup()
        assertEquals(1, result.receiptsMissing)
        assertFalse(plain.names.any { it.contains("outside") })
    }

    @Test
    fun `REQ-OPS-176 settings json holds the allowlisted preferences and equals what the stores hold`() {
        fillEverything()
        setPreferences()
        val (plain, _) = backup()
        val live = runBlocking { AndroidBackupPrefs(context).snapshot() }
        val stores = JSONObject(plain.text("data/settings.json")).getJSONObject("stores")
        assertEquals(live.keys, stores.keys().asSequence().toSet())
        for ((store, values) in live) {
            val js = stores.getJSONObject(store)
            assertEquals("keys of $store", values.keys, js.keys().asSequence().toSet())
            for ((key, v) in values) {
                val item = js.getJSONObject(key)
                when (v) {
                    is Boolean -> assertEquals("$store.$key", v, item.getBoolean("value"))
                    is Int -> assertEquals("$store.$key", v, item.getInt("value"))
                    is Long -> assertEquals("$store.$key", v, item.getLong("value"))
                    is String -> assertEquals("$store.$key", v, item.getString("value"))
                    is Set<*> ->
                        assertEquals(
                            "$store.$key",
                            v.map { it as String }.toSortedSet(),
                            (item.getJSONArray("value") as JSONArray).let { a ->
                                (
                                    0 until
                                        a.length()
                                ).map { a.getString(it) }.toSortedSet()
                            },
                        )
                    else -> error("type")
                }
            }
        }
        // Spot checks that the settings the story names are really in there with the values that were set.
        val settings = stores.getJSONObject(BackupPrefsInventory.STORE_SETTINGS)
        assertEquals(27, settings.getJSONObject("month_start_day").getInt("value"))
        assertTrue(settings.getJSONObject("include_cash_withdrawals").getBoolean("value"))
        assertTrue(settings.getJSONObject("arabic_digits").getBoolean("value"))
        val senders = stores.getJSONObject("shared:masroof_senders")
        assertFalse(senders.getJSONObject("sms_capture_enabled").getBoolean("value"))
        assertTrue(
            senders
                .getJSONObject("added")
                .getJSONArray("value")
                .toString()
                .contains("MYBANK"),
        )
        assertTrue(stores.getJSONObject("shared:masroof_notifications").getJSONObject("notifications_enabled").getBoolean("value"))
        assertEquals("COUNT", stores.getJSONObject(BackupPrefsInventory.STORE_ANSWERS).getJSONObject("atm_choice").getString("value"))
        val links =
            stores
                .getJSONObject(BackupPrefsInventory.STORE_ANSWERS)
                .getJSONObject("entry_links")
                .getJSONArray("value")
                .getString(0)
        assertTrue("the nonce travels with the link", links.endsWith("nonce-1"))
        val subs = stores.getJSONObject(BackupPrefsInventory.STORE_SUBSCRIPTIONS)
        assertTrue(
            subs
                .getJSONObject("anchor_days")
                .getJSONArray("value")
                .toString()
                .contains("sub1:30"),
        )
        assertTrue(
            subs
                .getJSONObject("dismissed_suggestions")
                .getJSONArray("value")
                .toString()
                .contains("spotify"),
        )
        assertEquals(1, subs.getJSONObject("price_notices").getJSONArray("value").length())
        assertEquals("ar", stores.getJSONObject("shared:masroof_ui").getJSONObject("language").getString("value"))
    }

    @Test
    fun `REQ-OPS-176 server address, session, device id, erase stage and reminder log are not in the settings file`() {
        fillEverything()
        setPreferences()
        val (plain, _) = backup()
        val text = plain.text("data/settings.json")
        for (excluded in listOf(
            "server_url",
            "access",
            "device_id",
            "erase_stage",
            "masroof_session",
            "masroof_device",
            "masroof_reminders",
            "masroof_keyset",
        )) {
            assertFalse(excluded, text.contains(excluded))
        }
    }

    @Test
    fun `REQ-OPS-176 the local id key is stored as keys local-id key so a restored phone still dedups`() {
        fillEverything()
        val (plain, _) = backup()
        assertArrayEquals(localKey, plain.entries.getValue("keys/local-id.key"))
    }
}
