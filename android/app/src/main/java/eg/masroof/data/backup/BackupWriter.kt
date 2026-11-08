package eg.masroof.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read access to the tables, one consistent snapshot at a time. Implemented over Room; faked in the streaming test. */
interface BackupDatabase {
    /** Everything read inside [block] comes from one moment (a read transaction); writers elsewhere are not blocked for long. */
    fun <T> snapshot(block: () -> T): T

    fun count(table: String): Long

    /** Entries that are not soft-deleted: the "1,240 entries" in "Last backup". */
    fun liveEntryCount(): Long

    fun forEachRow(
        table: String,
        action: (columns: List<String>, values: List<Any?>) -> Unit,
    )

    fun receipts(): List<BackupReceiptRef>
}

/** The non-secret phone state outside Room, by store name then key (see [BackupPrefsInventory]). Values: Boolean, Int, Long, String, Set<String>. */
fun interface BackupPrefs {
    suspend fun snapshot(): Map<String, Map<String, Any>>
}

/** Opens a stored receipt file; null (or FileNotFoundException) when the file is missing on disk. */
fun interface ReceiptFiles {
    fun open(ref: BackupReceiptRef): InputStream?
}

class BackupResult(
    /** Entries that are not soft-deleted. */
    val entries: Long,
    val receiptsStored: Int,
    val receiptsMissing: Int,
    /** Size of the whole `.masroof` file. */
    val fileBytes: Long,
)

/** A receipt file changed or vanished after it was measured: the file would not match its manifest line, so nothing is reported as done. */
class BackupSourceChangedException : IOException("receipt changed during backup")

/** The user pressed Cancel. */
class BackupCancelledException : IOException("backup cancelled")

class BackupEnvironment(
    val db: BackupDatabase,
    val prefs: BackupPrefs,
    val localIdKey: () -> ByteArray,
    val receipts: ReceiptFiles,
    val appVersion: String,
    val nowMillis: Long,
)

/**
 * Writes the plaintext ZIP through Tink's streaming AEAD (ADR-0009 D5, REQ-SEC-066, REQ-OPS-176). Nothing is held whole: rows are
 * read from a cursor one at a time, receipts are copied in 8 KiB chunks and the cipher works in 1 MiB segments, so memory does not
 * grow with the number of receipts. Returns only after the final segment is written and [sink] is closed; any failure throws, so a
 * partial file is never reported as a backup.
 */
class BackupWriter(
    private val env: BackupEnvironment,
) {
    private class Fingerprint(
        val size: Long,
        val sha256: String,
    )

    private class Progress(
        private val total: Long,
        private val report: (Int) -> Unit,
    ) {
        private var done = 0L
        private var last = -1

        fun add(units: Long) {
            done += units
            val pct = (done * PERCENT_MAX / total).toInt().coerceIn(0, PERCENT_MAX - 1)
            if (pct != last) {
                last = pct
                report(pct)
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun write(
        sink: OutputStream,
        input: BackupInput,
        report: (Int) -> Unit = {},
    ): BackupResult {
        val context = currentCoroutineContext()
        val cancelled = { context.ensureActive() }
        val localIdKey = env.localIdKey()
        try {
            return writeAll(sink, input, report, cancelled, localIdKey)
        } catch (e: Throwable) {
            // Whatever went wrong (provider failure, Cancel, a changed receipt): the provider's stream is closed and nothing is reported.
            runCatching { sink.close() }
            throw e
        } finally {
            localIdKey.fill(0)
        }
    }

    @Suppress("LongMethod")
    private suspend fun writeAll(
        sink: OutputStream,
        input: BackupInput,
        report: (Int) -> Unit,
        cancelled: () -> Unit,
        localIdKey: ByteArray,
    ): BackupResult {
        val settingsJson = settingsJson(env.prefs.snapshot())
        val counting = CountingOutputStream(sink)
        val headerBytes = input.header.encode()

        // Receipts are measured before the manifest is written (it comes first and lists size and SHA-256 per receipt).
        val firstList = env.db.receipts()
        val progress = Progress(progressTotal(firstList), report)
        val fingerprints = HashMap<String, Fingerprint?>()
        for (ref in firstList) fingerprints[ref.id] = measure(ref, progress, cancelled)

        counting.write(headerBytes)
        val zip = ZipOutputStream(BackupCipher.encrypt(counting, input.key, headerBytes))
        var entries = 0L
        var stored = 0
        var missing = 0
        var receiptList: List<BackupReceiptRef> = emptyList()
        env.db.snapshot {
            val counts = BackupTables.INCLUDED.map { it.name to env.db.count(it.name) }
            receiptList = env.db.receipts()
            for (ref in receiptList) {
                if (!fingerprints.containsKey(ref.id)) fingerprints[ref.id] = measure(ref, progress, cancelled)
            }
            entries = env.db.liveEntryCount()
            writeEntry(zip, BackupTables.MANIFEST) { it.write(manifest(counts, receiptList, fingerprints).toByteArray(UTF8)) }
            for (table in BackupTables.INCLUDED) {
                val expected = counts.first { it.first == table.name }.second
                writeEntry(zip, table.file) { out -> writeRows(out, table, expected, progress, cancelled) }
            }
        }
        writeEntry(zip, BackupTables.SETTINGS) { it.write(settingsJson.toByteArray(UTF8)) }
        writeEntry(zip, BackupTables.LOCAL_ID_KEY) { it.write(localIdKey) }
        zip.setLevel(Deflater.NO_COMPRESSION) // JPEG and PDF do not shrink; do not spend the battery on it
        for (ref in receiptList) {
            val fp = fingerprints[ref.id]
            if (fp == null) {
                missing++
                continue
            }
            writeEntry(zip, entryName(ref)) { copyReceipt(ref, fp, it, progress, cancelled) }
            stored++
        }
        zip.close() // writes the final AEAD segment and closes the sink: a failure here is a failed backup
        report(PERCENT_MAX)
        return BackupResult(entries, stored, missing, counting.count)
    }

    private fun progressTotal(refs: List<BackupReceiptRef>): Long {
        val receiptBytes = refs.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return (2 * receiptBytes + ROW_UNITS * ESTIMATED_ROWS).coerceAtLeast(1L)
    }

    private fun measure(
        ref: BackupReceiptRef,
        progress: Progress,
        cancelled: () -> Unit,
    ): Fingerprint? {
        val stream = openOrNull(ref) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        stream.use { s ->
            val buf = ByteArray(CHUNK)
            while (true) {
                cancelled()
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                size += n
                progress.add(n.toLong())
            }
        }
        return Fingerprint(size, hex(digest.digest()))
    }

    private fun openOrNull(ref: BackupReceiptRef): InputStream? =
        try {
            env.receipts.open(ref)
        } catch (_: FileNotFoundException) {
            null
        }

    private fun copyReceipt(
        ref: BackupReceiptRef,
        fp: Fingerprint,
        out: OutputStream,
        progress: Progress,
        cancelled: () -> Unit,
    ) {
        val stream = openOrNull(ref) ?: throw BackupSourceChangedException()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        stream.use { s ->
            val buf = ByteArray(CHUNK)
            while (true) {
                cancelled()
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                size += n
                progress.add(n.toLong())
            }
        }
        if (size != fp.size || hex(digest.digest()) != fp.sha256) throw BackupSourceChangedException()
    }

    private fun writeRows(
        out: OutputStream,
        table: BackupTable,
        expected: Long,
        progress: Progress,
        cancelled: () -> Unit,
    ) {
        var rows = 0L
        env.db.forEachRow(table.name) { columns, values ->
            if (rows % CANCEL_EVERY == 0L) cancelled()
            val line =
                BackupJson.obj(columns.indices.map { columns[it] to BackupJson.value(values[it]) })
            out.write(line.toByteArray(UTF8))
            out.write(NEWLINE)
            rows++
            progress.add(ROW_UNITS)
        }
        if (rows != expected) throw IOException("table changed during snapshot")
    }

    private fun manifest(
        counts: List<Pair<String, Long>>,
        receipts: List<BackupReceiptRef>,
        fingerprints: Map<String, Fingerprint?>,
    ): String {
        val created = Instant.ofEpochMilli(env.nowMillis)
        val tables =
            BackupJson.array(
                counts.map { (name, n) ->
                    val file = BackupTables.INCLUDED.first { it.name == name }.file
                    BackupJson.obj(listOf("name" to BackupJson.string(name), "file" to BackupJson.string(file), "rows" to n.toString()))
                },
            )
        val receiptItems =
            BackupJson.array(
                receipts.map { ref ->
                    val fp = fingerprints[ref.id]
                    BackupJson.obj(
                        listOf(
                            "id" to BackupJson.string(ref.id),
                            "entry" to BackupJson.string(entryName(ref)),
                            "fileName" to BackupJson.string(ref.fileName),
                            "missing" to (fp == null).toString(),
                            "size" to (fp?.size ?: 0L).toString(),
                            "sha256" to (fp?.let { BackupJson.string(it.sha256) } ?: "null"),
                        ),
                    )
                },
            )
        return BackupJson.obj(
            listOf(
                "formatVersion" to BackupFormat.FORMAT_VERSION.toString(),
                "dataSchemaVersion" to BackupFormat.DATA_SCHEMA_VERSION.toString(),
                "appVersion" to BackupJson.string(env.appVersion),
                "createdAtUtc" to BackupJson.string(created.toString()),
                "createdAtMillis" to env.nowMillis.toString(),
                "tables" to tables,
                "settings" to BackupJson.string(BackupTables.SETTINGS),
                "localIdKey" to BackupJson.string(BackupTables.LOCAL_ID_KEY),
                "receipts" to receiptItems,
            ),
        )
    }

    private fun settingsJson(stores: Map<String, Map<String, Any>>): String {
        val storeItems =
            stores.toSortedMap().map { (store, values) ->
                store to
                    BackupJson.obj(
                        values.toSortedMap().map { (key, v) -> key to prefValue(v) },
                    )
            }
        return BackupJson.obj(listOf("formatVersion" to "1", "stores" to BackupJson.obj(storeItems)))
    }

    private fun prefValue(v: Any): String {
        val (type, json) =
            when (v) {
                is Boolean -> "bool" to v.toString()
                is Int -> "int" to v.toString()
                is Long -> "long" to v.toString()
                is String -> "string" to BackupJson.string(v)
                is Set<*> -> "stringSet" to BackupJson.array(v.map { BackupJson.string(it as String) }.sorted())
                else -> error("unsupported preference type")
            }
        return BackupJson.obj(listOf("type" to BackupJson.string(type), "value" to json))
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        name: String,
        body: (OutputStream) -> Unit,
    ) {
        zip.putNextEntry(ZipEntry(name))
        body(zip)
        zip.closeEntry()
    }

    companion object {
        private val UTF8 = StandardCharsets.UTF_8
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
        private const val CHUNK = 8 * 1024
        private const val PERCENT_MAX = 100
        private const val ROW_UNITS = 64L
        private const val ESTIMATED_ROWS = 2_000L
        private const val CANCEL_EVERY = 256L

        /** `receipts/<id>.<ext>`: the id and extension are reduced to safe characters, so a restored name can never escape the folder. */
        fun entryName(ref: BackupReceiptRef): String {
            val safeId = ref.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "receipt" }
            val ext =
                ref.fileName
                    .substringAfterLast('.', "")
                    .lowercase()
                    .filter { it in 'a'..'z' || it in '0'..'9' }
                    .take(MAX_EXT)
            return BackupTables.RECEIPTS_DIR + safeId + "." + ext.ifEmpty { "bin" }
        }

        private const val MAX_EXT = 5

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** The key and header of one backup. The key is zeroed by whoever created it once the write ended. */
class BackupInput(
    val header: BackupHeader,
    val key: ByteArray,
)

private class CountingOutputStream(
    sink: OutputStream,
) : FilterOutputStream(sink) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        count += len
    }
}
