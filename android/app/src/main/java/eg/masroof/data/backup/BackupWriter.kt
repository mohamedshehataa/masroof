package eg.masroof.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
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

/**
 * The non-secret phone state outside Room, by store name then key (see [BackupPrefsInventory]).
 * Values: Boolean, Int, Long, String, Set<String>.
 */
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
    /** Where the rows are staged (encrypted, with a key that only lives in memory) while the database snapshot is open. */
    val tempDir: File = File(System.getProperty("java.io.tmpdir").orEmpty()),
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

    @Suppress("TooGenericExceptionCaught") // any failure at all closes the provider stream and is rethrown
    suspend fun write(
        sink: OutputStream,
        input: BackupInput,
        report: (Int) -> Unit = {},
    ): BackupResult {
        val context = currentCoroutineContext()
        val cancelled = { context.ensureActive() }
        var localIdKey: ByteArray? = null
        try {
            // inside the try: a key that cannot be read still closes the provider's stream
            localIdKey = env.localIdKey()
            return writeAll(sink, input, report, cancelled, localIdKey)
        } catch (e: Throwable) {
            // Whatever went wrong (provider failure, Cancel, a changed receipt): the provider's stream is closed and nothing is reported.
            runCatching { sink.close() }
            throw e
        } finally {
            localIdKey?.fill(0)
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

        // The rows are staged first: the snapshot (Room's write lock) is held only for local disk work, never while a provider is written.
        val stage = StageArea(env.tempDir)
        try {
            var counts: List<Pair<String, Long>> = emptyList()
            var entries = 0L
            var receiptList: List<BackupReceiptRef> = emptyList()
            env.db.snapshot {
                counts = BackupTables.INCLUDED.map { it.name to env.db.count(it.name) }
                receiptList = env.db.receipts()
                entries = env.db.liveEntryCount()
                for (table in BackupTables.INCLUDED) {
                    stage.write(table.name, counts.first { it.first == table.name }.second, progress, cancelled)
                }
            }
            for (ref in receiptList) {
                if (!fingerprints.containsKey(ref.id)) fingerprints[ref.id] = measure(ref, progress, cancelled)
            }
            val names = entryNames(receiptList)
            stage.verify(cancelled)

            counting.write(headerBytes)
            val zip = ZipOutputStream(BackupCipher.encrypt(counting, input.key, headerBytes))
            writeEntry(zip, BackupTables.MANIFEST) { it.write(manifest(counts, receiptList, fingerprints, names).toByteArray(UTF8)) }
            for (table in BackupTables.INCLUDED) {
                writeEntry(zip, table.file) { out -> stage.copyTo(table.name, out, cancelled) }
            }
            writeEntry(zip, BackupTables.SETTINGS) { it.write(settingsJson.toByteArray(UTF8)) }
            writeEntry(zip, BackupTables.LOCAL_ID_KEY) { it.write(localIdKey) }
            zip.setLevel(Deflater.NO_COMPRESSION) // JPEG and PDF do not shrink; do not spend the battery on it
            var stored = 0
            var missing = 0
            for (ref in receiptList) {
                val fp = fingerprints[ref.id]
                if (fp == null) {
                    missing++
                    continue
                }
                writeEntry(zip, names.getValue(ref.id)) { copyReceipt(ref, fp, it, progress, cancelled) }
                stored++
            }
            zip.close() // writes the final AEAD segment and closes the sink: a failure here is a failed backup
            report(PERCENT_MAX)
            return BackupResult(entries, stored, missing, counting.count)
        } finally {
            stage.delete()
        }
    }

    /** Table rows as encrypted temp files (random in-memory key, removed in [delete]): the snapshot fills them. */
    private inner class StageArea(
        private val dir: File,
    ) {
        private val key = ByteArray(BackupFormat.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        private val files = HashMap<String, File>()

        fun write(
            table: String,
            expected: Long,
            progress: Progress,
            cancelled: () -> Unit,
        ) {
            dir.mkdirs()
            val file = File.createTempFile("masroof-stage", ".bin", dir)
            files[table] = file
            var rows = 0L
            FileOutputStream(file).use { raw ->
                BackupCipher.encrypt(raw, key, aad(table)).use { out ->
                    env.db.forEachRow(table) { columns, values ->
                        if (rows % CANCEL_EVERY == 0L) cancelled()
                        out.write(BackupJson.obj(columns.indices.map { columns[it] to BackupJson.value(values[it]) }).toByteArray(UTF8))
                        out.write(NEWLINE)
                        rows++
                        progress.add(ROW_UNITS)
                    }
                }
            }
            if (rows != expected) throw IOException("table changed during snapshot")
        }

        @Suppress("NestedBlockDepth") // two closing streams around the copy loop
        fun copyTo(
            table: String,
            out: OutputStream,
            cancelled: () -> Unit,
        ) {
            FileInputStream(files.getValue(table)).use { raw ->
                BackupCipher.decrypt(raw, key, aad(table)).use { stream ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        cancelled()
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }

        /** Reads every temp file to its end: a damaged or swapped one fails here, before anything is written to the provider. */
        fun verify(cancelled: () -> Unit) {
            val discard =
                object : OutputStream() {
                    override fun write(b: Int) = Unit

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = Unit
                }
            for (table in files.keys) copyTo(table, discard, cancelled)
        }

        fun delete() {
            key.fill(0)
            files.values.forEach { runCatching { it.delete() } }
        }
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

    private fun manifest(
        counts: List<Pair<String, Long>>,
        receipts: List<BackupReceiptRef>,
        fingerprints: Map<String, Fingerprint?>,
        names: Map<String, String>,
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
                            "entry" to BackupJson.string(names.getValue(ref.id)),
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

        /** The table name is the associated data of its temp stream, so a temp file cannot be swapped for another table's. */
        private fun aad(table: String) = table.toByteArray(UTF8)

        /**
         * `receipts/<id>.<ext>` per receipt: id and extension are reduced to safe characters so a restored name can never escape the
         * folder, and two receipts that clean to the same name get a numbered suffix (the manifest records the entry name used).
         */
        fun entryNames(refs: List<BackupReceiptRef>): Map<String, String> {
            val used = HashSet<String>()
            val result = LinkedHashMap<String, String>()
            for (ref in refs) {
                val safeId = ref.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "receipt" }
                val ext =
                    ref.fileName
                        .substringAfterLast('.', "")
                        .lowercase()
                        .filter { it in 'a'..'z' || it in '0'..'9' }
                        .take(MAX_EXT)
                val suffix = ext.ifEmpty { "bin" }
                var name = BackupTables.RECEIPTS_DIR + safeId + "." + suffix
                var n = 2
                while (!used.add(name)) name = BackupTables.RECEIPTS_DIR + safeId + "-" + n++ + "." + suffix
                result[ref.id] = name
            }
            return result
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
