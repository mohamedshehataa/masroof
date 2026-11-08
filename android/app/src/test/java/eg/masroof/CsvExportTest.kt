package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.export.CsvExporter
import eg.masroof.data.export.CsvFormat
import eg.masroof.data.export.ExportRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate

private val BOM = 0xFEFF.toChar().toString()

private const val HEADER =
    "date_cairo,time_cairo,occurred_at_utc,type,status,amount,currency,category,parent_category,merchant,account,paid_by,notes," +
        "sources,receipts"

/** Splits CSV text into records of cells following RFC 4180, so the tests prove quoted commas and new lines stay in one cell. */
internal fun parseCsv(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                cell.append('"')
                i++
            }
            quoted && c == '"' -> quoted = false
            quoted -> cell.append(c)
            c == '"' -> quoted = true
            c == ',' -> {
                cells.add(cell.toString())
                cell.clear()
            }
            c == '\r' && text.getOrNull(i + 1) == '\n' -> {
                cells.add(cell.toString())
                cell.clear()
                records.add(cells)
                cells = mutableListOf()
                i++
            }
            else -> cell.append(c)
        }
        i++
    }
    return records
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CsvExportTest {
    private lateinit var db: AppDatabase
    private lateinit var exporter: CsvExporter

    private val october = ExportRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        exporter = CsvExporter(db, Dispatchers.Unconfined)
        // Cairo is UTC+3 in early October 2026 (DST ends on the 29th).
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun at(utc: String) = Instant.parse(utc).toEpochMilli()

    private fun entry(
        id: String,
        minor: Long,
        utc: String,
        currency: String = "EGP",
        type: String = "EXPENSE",
        merchant: String? = null,
        notes: String? = null,
    ) = ExpenseEntity(
        id = id,
        amountMinor = minor,
        currency = currency,
        occurredAtMillis = at(utc),
        merchant = merchant,
        categoryId = null,
        accountId = null,
        notes = notes,
        pending = false,
        type = type,
    )

    /** An entry that points at a category, account and member, or is soft-deleted, or has a date only. */
    private fun entryWith(
        base: ExpenseEntity,
        categoryId: String? = null,
        accountId: String? = null,
        memberId: String? = null,
        timeKnown: Boolean = true,
        deleted: Long? = null,
    ) = base.copy(
        categoryId = categoryId,
        accountId = accountId,
        paidByMemberId = memberId,
        timeKnown = timeKnown,
        deletedAtMillis = deleted,
    )

    private fun member(
        id: String,
        name: String,
    ) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO member (id, displayName, sortOrder) VALUES (?, ?, 0)", arrayOf(id, name))
    }

    private suspend fun run(range: ExportRange = october): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val n = exporter.export(range, out)
        return n to out.toByteArray()
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun `REQ-EXP-048 the file starts with a UTF-8 BOM then the exact header and uses CRLF`() =
        runTest {
            db.expenses().insert(entry("a", 145_000, "2026-10-05T07:15:00Z", merchant = "Carrefour"))
            val (_, bytes) = run()
            assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
            val body = text(bytes).removePrefix(BOM)
            assertTrue(body.startsWith("$HEADER\r\n"))
            assertEquals(2, parseCsv(body).size)
            assertFalse("a bare LF line end", body.replace("\r\n", "").contains('\n'))
            assertTrue(body.endsWith("\r\n"))
        }

    @Test
    fun `REQ-EXP-048 a Carrefour grocery of 1450 EGP is one exact row with Cairo date and time and a UTC instant`() =
        runTest {
            db.lookups().upsertCategories(
                listOf(
                    CategoryEntity("food", "Food", "أكل", 0),
                    CategoryEntity("groc", "Groceries", "بقالة", 1, parentId = "food"),
                ),
            )
            db.lookups().upsertAccounts(listOf(AccountEntity("acc", "CIB Visa", "EGP", last4 = "4242")))
            member("m1", "Mohamed")
            db.expenses().insert(
                entryWith(
                    entry("a", 145_000, "2026-10-05T07:15:00Z", merchant = "Carrefour", notes = "paid 1,450 Carrefour groceries"),
                    categoryId = "groc",
                    accountId = "acc",
                    memberId = "m1",
                ),
            )
            val rows = parseCsv(text(run().second).removePrefix(BOM))
            assertEquals(
                listOf(
                    "2026-10-05",
                    "10:15",
                    "2026-10-05T07:15:00Z",
                    "EXPENSE",
                    "CONFIRMED",
                    "1450.00",
                    "EGP",
                    "Groceries",
                    "Food",
                    "Carrefour",
                    "CIB Visa",
                    "Mohamed",
                    "paid 1,450 Carrefour groceries",
                    "",
                    "0",
                ),
                rows[1],
            )
        }

    @Test
    fun `REQ-EXP-048 USD 12_99 refund income transfer and TRANSFER_IN keep exact positive amounts and their stored type`() =
        runTest {
            db.expenses().insert(entry("1", 1_299, "2026-10-01T07:00:00Z", currency = "USD", merchant = "Netflix"))
            db.expenses().insert(entry("2", 45_000, "2026-10-02T07:00:00Z", type = "REFUND", merchant = "Amazon"))
            db.expenses().insert(entry("3", 2_500_000, "2026-10-03T07:00:00Z", type = "INCOME", merchant = "Salary"))
            db.expenses().insert(entry("4", 100_000, "2026-10-04T07:00:00Z", type = "TRANSFER"))
            db.expenses().insert(entry("5", 100_000, "2026-10-04T07:05:00Z", type = "TRANSFER_IN"))
            db.expenses().insert(entry("6", 5, "2026-10-06T07:00:00Z"))
            db.expenses().insert(entry("7", -7_050, "2026-10-07T07:00:00Z"))
            val rows = parseCsv(text(run().second).removePrefix(BOM)).drop(1)
            assertEquals(listOf("12.99", "USD"), rows[0].slice(5..6))
            assertEquals(listOf("REFUND", "450.00"), listOf(rows[1][3], rows[1][5]))
            assertEquals(listOf("INCOME", "25000.00"), listOf(rows[2][3], rows[2][5]))
            assertEquals("TRANSFER", rows[3][3])
            assertEquals("TRANSFER_IN", rows[4][3])
            assertEquals("0.05", rows[5][5])
            assertEquals("a stored negative amount is exported positive", "70.50", rows[6][5])
            assertTrue(rows.all { !it[5].startsWith("-") })
        }

    @Test
    fun `REQ-EXP-048 a soft-deleted entry is not exported`() =
        runTest {
            db.expenses().insert(entry("live", 100, "2026-10-05T07:00:00Z", merchant = "Kept"))
            val removed = entry("gone", 200, "2026-10-05T08:00:00Z", merchant = "Removed")
            db.expenses().insert(entryWith(removed, deleted = at("2026-10-06T07:00:00Z")))
            val (n, bytes) = run()
            assertEquals(1, n)
            assertTrue(text(bytes).contains("Kept"))
            assertFalse(text(bytes).contains("Removed"))
        }

    @Test
    fun `REQ-EXP-048 time_cairo is empty when the time is not known`() =
        runTest {
            db.expenses().insert(entryWith(entry("d", 100, "2026-10-05T09:00:00Z"), timeKnown = false))
            val row = parseCsv(text(run().second).removePrefix(BOM))[1]
            assertEquals("2026-10-05", row[0])
            assertEquals("", row[1])
            assertEquals("2026-10-05T09:00:00Z", row[2])
        }

    @Test
    fun `REQ-EXP-048 an Arabic name appears as typed`() =
        runTest {
            db.lookups().upsertCategories(listOf(CategoryEntity("fuel", "", "بنزين", 0)))
            db.lookups().upsertAccounts(listOf(AccountEntity("acc", "حساب الراتب", "EGP")))
            member("m", "محمد")
            val base = entry("a", 30_000, "2026-10-05T07:00:00Z", merchant = "وطنية")
            db.expenses().insert(entryWith(base, categoryId = "fuel", accountId = "acc", memberId = "m"))
            val bytes = run().second
            val row = parseCsv(String(bytes, Charsets.UTF_8).removePrefix(BOM))[1]
            assertEquals(listOf("بنزين", "", "وطنية", "حساب الراتب", "محمد"), row.slice(7..11))
        }

    @Test
    fun `REQ-EXP-048 sources are joined with a semicolon in the order received and receipts are counted`() =
        runTest {
            db.expenses().insert(entry("a", 100, "2026-10-05T07:00:00Z"))
            db.sources().insert(ExpenseSourceEntity("s1", "a", "PUSH", "L1", 2_000, null, null))
            db.sources().insert(ExpenseSourceEntity("s2", "a", "SMS", "L2", 1_000, null, null))
            db.sources().insert(ExpenseSourceEntity("s3", "a", "SMS", "L3", 3_000, null, null))
            db.receipts().insert(ReceiptEntity("r1", "a", "one.pdf", "application/pdf", 10, "sha-1", 1, 1))
            db.receipts().insert(ReceiptEntity("r2", "a", "two.pdf", "application/pdf", 10, "sha-2", 1, 1))
            val row = parseCsv(text(run().second).removePrefix(BOM))[1]
            assertEquals("SMS;PUSH", row[13])
            assertEquals("2", row[14])
        }

    @Test
    fun `REQ-EXP-048 a range with no entries writes only the header and reports 0`() =
        runTest {
            db.expenses().insert(entry("a", 100, "2026-09-05T07:00:00Z"))
            val (n, bytes) = run()
            assertEquals(0, n)
            assertEquals(BOM + "$HEADER\r\n", text(bytes))
        }

    @Test
    fun `REQ-EXP-048 the range is whole Cairo days so late night entries land on the right day`() =
        runTest {
            // 23:30 Cairo on 31 Oct (UTC+2 after DST) is in October; 00:00 Cairo on 1 Nov is not. 21:00Z on 30 Sep is 00:00 Cairo 1 Oct.
            db.expenses().insert(entry("in-last", 100, "2026-10-31T21:30:00Z", merchant = "Last"))
            db.expenses().insert(entry("out-next", 100, "2026-10-31T22:00:00Z", merchant = "Next"))
            db.expenses().insert(entry("in-first", 100, "2026-09-30T21:00:00Z", merchant = "First"))
            db.expenses().insert(entry("out-prev", 100, "2026-09-30T20:59:00Z", merchant = "Prev"))
            val rows = parseCsv(text(run().second).removePrefix(BOM)).drop(1)
            assertEquals(listOf("First", "Last"), rows.map { it[9] })
            assertEquals("2026-10-31", rows.last()[0])
        }

    @Test
    fun `REQ-SEC-069 formula starters are prefixed with a quote and quoted in every text cell`() =
        runTest {
            db.lookups().upsertCategories(listOf(CategoryEntity("c", "=SUM(A1)", "-x", 0)))
            db.lookups().upsertAccounts(listOf(AccountEntity("acc", "+20 100", "EGP")))
            member("m", "@me")
            db.expenses().insert(
                entryWith(
                    entry("a", 100, "2026-10-05T07:00:00Z", merchant = "=HYPERLINK(\"x\")", notes = "@SUM(A1)"),
                    categoryId = "c",
                    accountId = "acc",
                    memberId = "m",
                ),
            )
            db.expenses().insert(entry("b", 100, "2026-10-06T07:00:00Z", merchant = "+20 100", notes = "\tTabbed"))
            db.expenses().insert(entry("c", 100, "2026-10-07T07:00:00Z", merchant = "\rCarriage", notes = "-5 off"))
            val raw = text(run().second)
            assertTrue(raw.contains("\"'=HYPERLINK(\"\"x\"\")\""))
            assertTrue(raw.contains("\"'+20 100\""))
            assertTrue(raw.contains("\"'@SUM(A1)\""))
            val rows = parseCsv(raw.removePrefix(BOM)).drop(1)
            assertEquals(listOf("'=SUM(A1)", ""), listOf(rows[0][7], rows[0][8]))
            assertEquals(listOf("'=HYPERLINK(\"x\")", "'+20 100", "'@me", "'@SUM(A1)"), rows[0].slice(9..12))
            assertEquals(listOf("'+20 100", "'\tTabbed"), listOf(rows[1][9], rows[1][12]))
            assertEquals(listOf("'\rCarriage", "'-5 off"), listOf(rows[2][9], rows[2][12]))
            for (cell in rows.flatten()) {
                assertFalse("a cell starts a formula: $cell", cell.isNotEmpty() && cell[0] in "=+-@\t\r")
            }
        }

    @Test
    fun `REQ-SEC-069 a note with a comma a quote and a new line stays one cell in one record`() =
        runTest {
            val note = "milk, eggs\nand \"bread\"\r\nthanks"
            db.expenses().insert(entry("a", 100, "2026-10-05T07:00:00Z", merchant = "Shop, Inc", notes = note))
            db.expenses().insert(entry("b", 200, "2026-10-06T07:00:00Z", merchant = "Plain"))
            val rows = parseCsv(text(run().second).removePrefix(BOM))
            assertEquals(3, rows.size)
            assertEquals(15, rows[1].size)
            assertEquals(note, rows[1][12])
            assertEquals("Shop, Inc", rows[1][9])
            assertEquals("Plain", rows[2][9])
        }

    @Test
    fun `REQ-SEC-069 no message text sender full account number or receipt content reaches the file`() =
        runTest {
            db.expenses().insert(entryWith(entry("a", 100, "2026-10-05T07:00:00Z", merchant = "Shop"), accountId = "acc"))
            db.lookups().upsertAccounts(listOf(AccountEntity("acc", "Main", "EGP", last4 = "1234")))
            db.sources().insert(ExpenseSourceEntity("s", "a", "SMS", "LOCAL-SECRET-ID", 1, "SENDER-SECRET", "1234"))
            db.reviews().insert(
                ReviewItemEntity("rv", "SMS", "REVIEW-SENDER-SECRET", "BODY-SECRET debit", "UNPARSED", at("2026-10-05T07:00:00Z"), 1),
            )
            db.receipts().insert(ReceiptEntity("r", "a", "RECEIPT-NAME-SECRET.pdf", "application/pdf", 5, "SHA-SECRET-HASH", 1, 1))
            val raw = text(run().second)
            val secrets =
                listOf("BODY-SECRET", "REVIEW-SENDER-SECRET", "LOCAL-SECRET-ID", "RECEIPT-NAME-SECRET", "SHA-SECRET", "1234")
            for (secret in secrets) {
                assertFalse("leaked $secret", raw.contains(secret))
            }
            assertEquals(1, parseCsv(raw.removePrefix(BOM)).size - 1)
        }

    @Test
    fun `REQ-EXP-048 more than one page of entries with equal timestamps is written once each in order`() =
        runTest {
            val same = "2026-10-05T07:00:00Z"
            for (i in 0 until 450) db.expenses().insert(entry("id-%04d".format(i), 100L + i, same, merchant = "m$i"))
            val (n, bytes) = run()
            assertEquals(450, n)
            val rows = parseCsv(text(bytes).removePrefix(BOM)).drop(1)
            assertEquals(450, rows.size)
            assertEquals((0 until 450).map { "m$it" }, rows.map { it[9] })
        }

    @Test
    fun `REQ-EXP-048 a provider failure mid-write surfaces as an exception and leaves app data unchanged`() =
        runTest {
            for (i in 0 until 50) db.expenses().insert(entry("id-$i", 100, "2026-10-05T07:00:00Z", merchant = "m$i"))
            val failing =
                object : OutputStream() {
                    private var written = 0

                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        written += len
                        if (written > 100) throw IOException("provider went away")
                    }
                }
            val result = runCatching { exporter.export(october, failing) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(
                50,
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-048 cancelling the coroutine stops the export`() =
        runTest {
            for (i in 0 until 450) db.expenses().insert(entry("id-%04d".format(i), 100, "2026-10-05T07:00:00Z"))
            val slow = CsvExporter(db, StandardTestDispatcher(testScheduler))
            val out = ByteArrayOutputStream()
            val job = launch { slow.export(october, out) }
            job.cancel()
            testScheduler.advanceUntilIdle()
            assertTrue(job.isCancelled)
        }

    @Test
    fun `REQ-EXP-048 amounts come from minor units with exact decimals and no sign`() {
        assertEquals("1450.00", CsvFormat.amount(145_000))
        assertEquals("12.99", CsvFormat.amount(1_299))
        assertEquals("0.00", CsvFormat.amount(0))
        assertEquals("0.07", CsvFormat.amount(7))
        assertEquals("92233720368547758.07", CsvFormat.amount(Long.MAX_VALUE))
        assertEquals("92233720368547758.08", CsvFormat.amount(Long.MIN_VALUE))
        assertEquals("1.00", CsvFormat.amount(-100))
    }

    @Test
    fun `REQ-SEC-069 RFC 4180 cell rules double quotes and quote commas and line breaks`() {
        assertEquals("plain", CsvFormat.text("plain"))
        assertEquals("", CsvFormat.text(null))
        assertEquals("\"a,b\"", CsvFormat.text("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.text("say \"hi\""))
        assertEquals("\"a\nb\"", CsvFormat.text("a\nb"))
        assertEquals("\"'=1+1\"", CsvFormat.text("=1+1"))
        assertEquals("\"'-1\"", CsvFormat.text("-1"))
        assertEquals("a=b", CsvFormat.text("a=b"))
    }
}
