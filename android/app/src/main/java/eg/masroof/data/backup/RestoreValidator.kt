@file:Suppress("ThrowsCount", "NestedBlockDepth", "ComplexCondition") // one named failure per check of REQ-SEC-068

package eg.masroof.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency

internal object RestoreRows {
    /**
     * True when the text is strict-looking JSON that nests no deeper than [max]: a stack bomb never reaches the parser. Outside a
     * double-quoted string only letters, digits, whitespace and `{}[]:,+-.` may appear, so everything Android's lenient `org.json`
     * would read differently (single-quoted strings, comments, `;` and `=` separators) is refused here; the depth never goes negative.
     */
    fun depthOk(
        text: String,
        max: Int,
    ): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '"' -> inString = true
                c == '[' || c == '{' -> if (++depth > max) return false
                c == ']' || c == '}' -> if (--depth < 0) return false
                c.isLetterOrDigit() || c in " \t\r\n:,+-." -> Unit
                else -> return false
            }
        }
        return !inString
    }

    /** A local-id key is exactly 32 bytes and not all zero (a zeroed key would make every message id guessable). */
    fun keyOk(file: File): Boolean = file.length() == BackupFormat.KEY_BYTES.toLong() && file.readBytes().any { it.toInt() != 0 }

    /** Reads UTF-8 lines; a line longer than [maxChars] is refused (a file with no line breaks must not fill the memory). */
    fun forEachLine(
        file: File,
        maxChars: Int,
        action: (String) -> Unit,
    ) {
        file.inputStream().buffered().use { raw ->
            InputStreamReader(raw, StandardCharsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        if (sb.isNotEmpty()) action(sb.toString())
                        sb.setLength(0)
                    } else {
                        if (sb.length >= maxChars) throw RestoreException(RestoreFailure.INVALID_CONTENT, step = 6)
                        sb.append(c.toChar())
                    }
                }
                if (sb.isNotEmpty()) action(sb.toString())
            }
        }
    }
}

/** A receipt file that passed check 7, in the staging folder, named by its final file name. */
class StagedReceipt(
    val id: String,
    val fileName: String,
)

/** The result of checks 6 and 7. */
class ValidatedBackup(
    val info: RestoreInfo,
    val tables: List<Pair<String, File>>,
    val receipts: List<StagedReceipt>,
    val settings: File,
    val key: File,
)

/** A ZIP entry that was written to the staging folder, with what was measured while it streamed. */
class ExtractedFile(
    val file: File,
    val size: Long,
    val sha256: String,
)

class ExtractedBackup(
    val manifest: File,
    val settings: File,
    val key: File,
    val tables: Map<String, File>,
    /** ZIP entry name to the staged file. */
    val receipts: Map<String, ExtractedFile>,
)

/**
 * Checks 6 and 7 of REQ-SEC-068, on the files already staged: manifest, JSON rows (integer amounts, ISO-4217 currencies, instants in
 * range, known enums, every foreign key resolvable) and the receipt sizes and SHA-256. It reads the schema of the live database, so a
 * backup is judged against exactly what this app version stores.
 */
class RestoreValidator(
    private val schema: RestoreSchema,
    private val limits: RestoreLimits,
) {
    private class Receipt(
        val id: String,
        val entry: String,
        val fileName: String,
        val missing: Boolean,
        val size: Long,
        val sha256: String?,
    )

    fun validate(
        files: ExtractedBackup,
        currentEntries: Long,
        cancelled: () -> Unit,
    ): ValidatedBackup {
        val manifest = parseManifest(files.manifest)
        val counts = checkTables(files, manifest, cancelled)
        val settings = files.settings.readText()
        RestoreSettingKeys.parse(settings)
        if (!RestoreRows.keyOk(files.key)) throw fail(6)
        val receipts = checkReceipts(files, manifest)
        val info = RestoreInfo(manifest.createdAtMillis, counts.entries, receipts.size, counts.budgets, currentEntries)
        return ValidatedBackup(info, files.tables.map { it.key to it.value }, receipts, files.settings, files.key)
    }

    private class Manifest(
        val createdAtMillis: Long,
        val rows: Map<String, Long>,
        val receipts: List<Receipt>,
    )

    private class Counts(
        val entries: Long,
        val budgets: Long,
    )

    private fun fail(step: Int) = RestoreException(RestoreFailure.INVALID_CONTENT, step)

    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    private fun parseManifest(file: File): Manifest {
        try {
            val text = file.readText()
            if (!RestoreRows.depthOk(text, limits.maxJsonDepth)) throw fail(6)
            val root = JSONObject(text)
            if (root.getInt("formatVersion") != BackupFormat.FORMAT_VERSION) throw fail(6)
            val data = root.getInt("dataSchemaVersion")
            if (data > BackupFormat.DATA_SCHEMA_VERSION) throw RestoreException(RestoreFailure.NEWER_VERSION, 6)
            // No upgrade step exists yet (data schema 10 is the first): an older one is refused, never half-mapped.
            if (data < BackupFormat.DATA_SCHEMA_VERSION) throw RestoreException(RestoreFailure.TOO_OLD, 6)
            val created = root.get("createdAtMillis") as? Long ?: (root.get("createdAtMillis") as? Int)?.toLong() ?: throw fail(6)
            if (created !in MIN_INSTANT..MAX_INSTANT) throw fail(6)
            val rows = LinkedHashMap<String, Long>()
            val tables = root.getJSONArray("tables")
            for (i in 0 until tables.length()) {
                val t = tables.getJSONObject(i)
                val name = t.getString("name")
                val known = BackupTables.INCLUDED.firstOrNull { it.name == name } ?: throw fail(6)
                if (t.getString("file") != known.file || rows.put(name, t.getLong("rows")) != null) throw fail(6)
            }
            if (rows.keys != BackupTables.INCLUDED.map { it.name }.toSet()) throw fail(6)
            return Manifest(created, rows, receipts(root.getJSONArray("receipts")))
        } catch (e: JSONException) {
            throw fail(6).also { it.addSuppressed(e) }
        }
    }

    private fun receipts(array: JSONArray): List<Receipt> {
        if (array.length() > MAX_RECEIPTS) throw fail(6)
        val list =
            (0 until array.length()).map { i ->
                val r = array.getJSONObject(i)
                val missing = r.getBoolean("missing")
                val sha = if (r.isNull("sha256")) null else r.getString("sha256")
                val fileName = r.getString("fileName")
                val entry = r.getString("entry")
                if (!SAFE_FILE.matches(fileName) || !ENTRY.matches(entry) || (!missing && (sha == null || !SHA.matches(sha)))) throw fail(6)
                Receipt(r.getString("id"), entry, fileName, missing, r.getLong("size"), sha)
            }
        // One ZIP entry per receipt and one file name per receipt: two manifest lines on one entry would move one file twice.
        if (list.map { it.entry }.toSet().size != list.size || list.map { it.id }.toSet().size != list.size) throw fail(6)
        return list
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun checkTables(
        files: ExtractedBackup,
        manifest: Manifest,
        cancelled: () -> Unit,
    ): Counts {
        val parents =
            schema.tables.values
                .flatMap { t -> t.foreignKeys.map { it.parentTable } }
                .toSet()
        val ids = HashMap<String, HashSet<String>>()
        val refs = HashMap<String, HashSet<String>>()
        var entries = 0L
        var budgets = 0L
        var tracked = 0L
        val receiptRows = HashMap<String, String>()
        val receiptNames = HashSet<String>()
        for (table in BackupTables.INCLUDED) {
            val t = schema.tables.getValue(table.name)
            val file = files.tables[table.name] ?: throw fail(6)
            val pk = HashSet<String>()
            var rows = 0L
            RestoreRows.forEachLine(file, limits.maxRowChars) { line ->
                if (rows % CANCEL_EVERY == 0L) cancelled()
                if (++rows > limits.maxRowsPerTable) throw fail(6)
                val row = row(t, line)
                val id = row["id"] as? String ?: throw fail(6)
                if (id.isEmpty() || id.length > MAX_ID || !pk.add(id)) throw fail(6)
                tracked += id.length * 2L + ENTRY_OVERHEAD
                for (fk in t.foreignKeys) {
                    (row[fk.column] as? String)?.let {
                        if (refs.getOrPut(fk.parentTable) { HashSet() }.add(it)) tracked += it.length * 2L + ENTRY_OVERHEAD
                    }
                }
                // The ids and references the checker keeps in memory have a budget: a file of many long ids must not exhaust the heap.
                if (tracked > limits.maxTrackedBytes) throw RestoreException(RestoreFailure.LOW_MEMORY, 6)
                if (table.name == "expense" && row["deletedAtMillis"] == null) entries++
                if (table.name == "budget") budgets++
                if (table.name == "receipt") {
                    val name = row["fileName"] as? String ?: throw fail(6)
                    receiptRows[id] = name
                    if (!receiptNames.add(name)) throw fail(6)
                }
            }
            if (rows != manifest.rows.getValue(table.name)) throw fail(6)
            if (table.name in parents) ids[table.name] = pk
        }
        // Every foreign key must resolve: a budget whose category is missing is refused, never repaired by dropping the category.
        for ((parent, wanted) in refs) if (!ids.getValue(parent).containsAll(wanted)) throw fail(6)
        // Exactly the same (id, file name) pairs in the manifest and in the receipt rows.
        if (manifest.receipts.associate { it.id to it.fileName } != receiptRows) throw fail(6)
        return Counts(entries, budgets)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun row(
        t: TableSchema,
        line: String,
    ): Map<String, Any?> {
        if (!RestoreRows.depthOk(line, limits.maxJsonDepth)) throw fail(6)
        val obj =
            try {
                JSONObject(line)
            } catch (e: JSONException) {
                throw fail(6).also { it.addSuppressed(e) }
            }
        if (obj.length() != t.columns.size) throw fail(6)
        val out = HashMap<String, Any?>()
        for (c in t.columns) {
            if (!obj.has(c.name)) throw fail(6)
            val v = obj.get(c.name)
            if (v == JSONObject.NULL) {
                if (c.notNull) throw fail(6)
                out[c.name] = null
                continue
            }
            if (c.integer) {
                // Integers only: 12.5, 1e3 and "12" (a string) are all refused.
                val n = (v as? Int)?.toLong() ?: v as? Long ?: throw fail(6)
                checkNumber(t.name, c.name, n)
                out[c.name] = n
            } else {
                val s = v as? String ?: throw fail(6)
                checkText(t.name, c.name, s)
                out[c.name] = s
            }
        }
        return out
    }

    private fun checkNumber(
        table: String,
        column: String,
        n: Long,
    ) {
        val ok =
            when {
                column in BOOLEAN_COLUMNS -> n == 0L || n == 1L
                column.endsWith("Minor") -> n in -MAX_MINOR..MAX_MINOR
                column.endsWith("Millis") -> instantOk(column, n)
                else -> true
            }
        if (!ok) throw fail(6).also { it.addSuppressed(IllegalStateException(table)) }
    }

    /**
     * Wide sanity bounds, 1900-01-01..2100-12-31, for EVERY instant: the app lets the user save any date (the date picker, a future bill,
     * an old entry), so plausibility is not a reason to refuse a genuine backup. The checks are for type and format.
     */
    private fun instantOk(
        column: String,
        n: Long,
    ): Boolean = (n == 0L && column in UNSET_OK) || n in MIN_INSTANT..MAX_INSTANT

    private fun checkText(
        table: String,
        column: String,
        s: String,
    ) {
        val allowed = ENUMS["$table.$column"]
        val ok =
            when {
                s.length > MAX_TEXT -> false
                column == "currency" -> s in CURRENCIES
                allowed != null -> s in allowed
                else -> true
            }
        if (!ok) throw fail(6)
    }

    private fun checkReceipts(
        files: ExtractedBackup,
        manifest: Manifest,
    ): List<StagedReceipt> {
        val listed = manifest.receipts.filterNot { it.missing }
        if (files.receipts.keys != listed.map { it.entry }.toSet()) throw fail(7)
        for (r in listed) {
            val got = files.receipts.getValue(r.entry)
            if (got.size != r.size || got.sha256 != r.sha256) throw fail(7)
        }
        return listed.map { StagedReceipt(it.id, it.fileName) }
    }

    companion object {
        /**
         * The widest values the app's own date parsers can produce: `LocalDate` years 0000..9999 (GoalInput, the plan, subscription, debt
         * and asset forms), with days of margin so Cairo-local times on the first and last day fit. Whatever the app can store restores.
         */
        val MIN_INSTANT: Long =
            LocalDate
                .of(0, 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() - 2 * DAY_MS
        private val MAX_INSTANT: Long =
            LocalDate
                .of(9999, 12, 31)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() + 3 * DAY_MS
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_MINOR = 1_000_000_000_000_000L
        private const val MAX_ID = 200
        private const val ENTRY_OVERHEAD = 64L
        private const val MAX_TEXT = 100_000
        private const val CANCEL_EVERY = 256L
        val SAFE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
        private val ENTRY = Regex("receipts/[A-Za-z0-9_-]{1,100}\\.[a-z0-9]{1,5}")
        private const val MAX_RECEIPTS = 100_000
        private val SHA = Regex("[0-9a-f]{64}")
        private val UNSET_OK = setOf("createdAtMillis", "updatedAtMillis")

        // XXX (no currency), XTS (testing) and the metals are never a real amount here (gold is grams priced in EGP, not XAU).
        private val CURRENCIES: Set<String> by lazy {
            Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() - setOf("XXX", "XTS", "XAU", "XAG", "XPT", "XPD")
        }

        /** Known enum columns; a value outside refuses the file. Budget periods stay open to the unsupported ones STORY-181 shows. */
        val ENUMS: Map<String, Set<String>> =
            mapOf(
                "expense.type" to setOf("EXPENSE", "INCOME", "REFUND", "TRANSFER", "TRANSFER_IN", "CASH_WITHDRAWAL"),
                "expense.status" to setOf("CONFIRMED", "PENDING_REVIEW"),
                "expense_source.channel" to setOf("SMS", "PUSH", "MANUAL", "BACKFILL", "SERVER_IMPORT"),
                "account_balance.source" to setOf("MANUAL", "SMS"),
                "budget.period" to setOf("MONTHLY", "WEEKLY", "DAILY", "QUARTERLY", "YEARLY"),
                "installment_plan.status" to setOf("ACTIVE", "COMPLETED", "ARCHIVED"),
                "subscription.kind" to setOf("SUBSCRIPTION", "BILL", "INCOME"),
                "subscription.cadence" to setOf("WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"),
                "asset.kind" to
                    setOf("GOLD", "SILVER", "CERTIFICATE", "FUND", "INVESTMENT", "PROPERTY", "VEHICLE", "BUSINESS", "OTHER"),
                "asset_lot.side" to setOf("BUY", "SELL"),
                "metal_price.metal" to setOf("GOLD", "SILVER"),
                "debt.kind" to setOf("LOAN", "CREDIT_CARD", "PERSON", "OTHER"),
                "debt.direction" to setOf("I_OWE", "OWED_TO_ME"),
            )
    }
}
