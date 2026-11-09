@file:Suppress(
    "ThrowsCount",
    "NestedBlockDepth",
    "ComplexCondition",
    "SwallowedException",
    "InstanceOfCheckForException",
    "LoopWithTooManyJumpStatements",
) // a streaming checker with one named failure per check

package eg.masroof.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Restore (ADR-0009 D5, REQ-SEC-068, REQ-OPS-178/179). [stage] runs checks 1 to 7 in that order in a staging folder and changes nothing
 * outside it; [apply] replaces the data in one Room transaction and finishes with a marker that a restart can complete or roll back.
 * The caller holds the erase lock around [apply] and [recover].
 */
class BackupRestorer(
    private val env: RestoreEnvironment,
) {
    private val limits get() = env.limits

    /** Checks 1 and 2: reads the header only, before any passphrase is asked for or any key derived. */
    fun checkHeader(source: InputStream): BackupHeader {
        val bytes = readHeaderBytes(source)
        if (bytes.size < BackupFormat.MAGIC.size || !bytes.copyOfRange(0, BackupFormat.MAGIC.size).contentEquals(BackupFormat.MAGIC)) {
            throw RestoreException(RestoreFailure.NOT_A_BACKUP, 1)
        }
        val header = BackupHeader.parse(bytes) ?: throw RestoreException(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
        if (header.formatVersion > BackupFormat.FORMAT_VERSION) throw RestoreException(RestoreFailure.NEWER_VERSION, 1)
        if (header.formatVersion != BackupFormat.FORMAT_VERSION) throw RestoreException(RestoreFailure.NOT_A_BACKUP, 1)
        val ok =
            header.memoryKiB in limits.minMemoryKiB..limits.maxMemoryKiB &&
                header.iterations in limits.minIterations..limits.maxIterations &&
                header.parallelism in limits.minParallelism..limits.maxParallelism
        if (!ok) throw RestoreException(RestoreFailure.CANNOT_OPEN, 2)
        return header
    }

    /** Wraps the picked file so a provider failure is told apart from a decryption failure. */
    private class Tracking(
        source: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : FilterInputStream(source) {
        var failed = false
            private set
        var count = 0L
            private set

        override fun read(): Int = guard { super.read() }.also { if (it >= 0) bump(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = guard { super.read(b, off, len) }.also { if (it > 0) bump(it.toLong()) }

        private fun bump(n: Long) {
            count += n
            onBytes(n)
        }

        private inline fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (e: IOException) {
                failed = true
                throw e
            }
    }

    /**
     * Checks 3 to 7. The file is opened twice: the first pass decrypts to the very end and discards it (a ZIP reader stops at the
     * central directory, so only this proves the last AEAD segment, a cut tail or a reordered segment), the second pass unzips into
     * staging with every limit enforced while streaming. On any failure the staging folder is gone and nothing else was touched.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun stage(
        openSource: () -> InputStream,
        sizeHint: Long,
        header: BackupHeader,
        passphrase: String,
        progress: (Int?) -> Unit = {},
    ): StagedRestore {
        val key = deriveKey(passphrase, header)
        var gen = 0
        try {
            val total = if (sizeHint > 0) sizeHint * 2 else 0L
            var done = 0L
            val tick: (Long) -> Unit = { n ->
                done += n
                progress(if (total > 0) (done * PERCENT / total).toInt().coerceIn(0, PERCENT.toInt()) else null)
            }
            drain(openSource, header, key, tick)
            // The session's own generation and staging folder: nothing a journal slot names is ever written into or deleted.
            gen = recovery.freshGen()
            val dir = recovery.stageDir(gen)
            dir.mkdirs()
            try {
                val extracted = extract(dir, openSource, header, key, tick)
                val context = currentCoroutineContext()
                val validated =
                    RestoreValidator(env.db.schema(), limits).validate(extracted, env.db.liveEntryCount()) {
                        context.ensureActive()
                    }
                arrangeReceipts(dir, extracted, validated)
                syncStaging(dir)
                progress(PERCENT.toInt())
                return StagedRestore(gen, dir, validated.info, validated, validated.hashes, env.epoch())
            } catch (e: OutOfMemoryError) {
                recovery.discardSession(gen)
                throw RestoreException(RestoreFailure.LOW_MEMORY, 6).also { it.addSuppressed(e) }
            } catch (e: Throwable) {
                recovery.discardSession(gen)
                throw e
            }
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(
        passphrase: String,
        header: BackupHeader,
    ): ByteArray =
        try {
            PassphraseKdf.deriveKey(passphrase, header)
        } catch (e: OutOfMemoryError) {
            throw RestoreException(RestoreFailure.CANNOT_OPEN, 2).also { it.addSuppressed(e) }
        }

    private fun openDecrypting(
        tracking: Tracking,
        header: BackupHeader,
        key: ByteArray,
    ): InputStream {
        val expected = header.encode()
        val head = readHeaderBytes(tracking)
        if (!head.contentEquals(expected)) throw RestoreException(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
        return BackupCipher.decrypt(tracking, key, expected)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun drain(
        openSource: () -> InputStream,
        header: BackupHeader,
        key: ByteArray,
        tick: (Long) -> Unit,
    ) {
        val context = currentCoroutineContext()
        val tracking = Tracking(openSourceOrFail(openSource), tick)
        tracking.use {
            try {
                openDecrypting(tracking, header, key).use { plain ->
                    val buf = ByteArray(CHUNK)
                    while (plain.read(buf) >= 0) context.ensureActive()
                }
            } catch (e: RestoreException) {
                throw e
            } catch (e: IOException) {
                throw RestoreException(if (tracking.failed) RestoreFailure.CANNOT_READ else RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
            } catch (e: GeneralSecurityException) {
                throw RestoreException(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
            } catch (e: RuntimeException) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                throw RestoreException(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
            }
        }
    }

    private fun openSourceOrFail(openSource: () -> InputStream): InputStream =
        try {
            openSource()
        } catch (e: IOException) {
            throw RestoreException(RestoreFailure.CANNOT_READ).also { it.addSuppressed(e) }
        } catch (e: SecurityException) {
            throw RestoreException(RestoreFailure.CANNOT_READ).also { it.addSuppressed(e) }
        }

    /** The names and sizes while the ZIP streams; nothing is trusted from the ZIP headers. */
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun extract(
        dir: File,
        openSource: () -> InputStream,
        header: BackupHeader,
        key: ByteArray,
        tick: (Long) -> Unit,
    ): ExtractedBackup {
        val context = currentCoroutineContext()
        val tracking = Tracking(openSourceOrFail(openSource), tick)
        val tableFiles = BackupTables.INCLUDED.associate { it.file to it.name }
        val tables = HashMap<String, File>()
        val receipts = LinkedHashMap<String, ExtractedFile>()
        val seen = HashSet<String>()
        var manifest: File? = null
        var settings: File? = null
        var keyFile: File? = null
        var total = 0L
        var entryCount = 0
        var neededMb = 0L
        tracking.use {
            try {
                val plain = openDecrypting(tracking, header, key)
                val counted = CountingInput(plain)
                ZipInputStream(counted).use { zip ->
                    var receiptIndex = 0
                    while (true) {
                        context.ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (++entryCount > limits.maxEntries) throw RestoreException(RestoreFailure.INVALID_CONTENT, 4)
                        val name = entry.name
                        if (name in DIRECTORIES) {
                            // A directory carries no bytes: bytes in it would bypass the size and ratio caps, so any byte refuses.
                            if (zip.read(ByteArray(1)) > 0) throw RestoreException(RestoreFailure.INVALID_CONTENT, 4)
                            continue
                        }
                        val target: File
                        val cap: Long
                        when {
                            !seen.add(name) -> throw RestoreException(RestoreFailure.INVALID_CONTENT, 4)
                            name == BackupTables.MANIFEST -> {
                                target = File(dir, "manifest.json").also { manifest = it }
                                cap = limits.maxManifestBytes
                            }
                            name == BackupTables.SETTINGS -> {
                                target = File(dir, "settings.json").also { settings = it }
                                cap = limits.maxSettingsBytes
                            }
                            name == BackupTables.LOCAL_ID_KEY -> {
                                target = File(dir, "local-id.key").also { keyFile = it }
                                cap = BackupFormat.KEY_BYTES.toLong()
                            }
                            name in tableFiles -> {
                                target = File(dir, "table-" + tableFiles.getValue(name) + ".jsonl")
                                tables[tableFiles.getValue(name)] = target
                                cap = limits.maxTotalBytes
                            }
                            RECEIPT_ENTRY.matches(name) -> {
                                target = File(dir, "receipt-in-" + receiptIndex++)
                                cap = limits.maxTotalBytes
                            }
                            else -> throw RestoreException(RestoreFailure.INVALID_CONTENT, 4)
                        }
                        if (name == BackupTables.MANIFEST && seen.size != 1) throw RestoreException(RestoreFailure.INVALID_CONTENT, 4)
                        // The manifest comes first: it says what the receipts need, so a full disk is refused before they are copied.
                        val digest = MessageDigest.getInstance("SHA-256")
                        val startPacked = counted.count
                        var written = 0L
                        val out = openStage(target, neededMb)
                        try {
                            val buf = ByteArray(CHUNK)
                            while (true) {
                                context.ensureActive()
                                val n = zip.read(buf)
                                if (n < 0) break
                                written += n
                                total += n
                                if (total > limits.maxTotalBytes) throw RestoreException(RestoreFailure.INVALID_CONTENT, 5)
                                val packed = (counted.count - startPacked).coerceAtLeast(1L)
                                if (written > maxOf(limits.ratioFreeBytes, limits.maxRatio * packed)) {
                                    throw RestoreException(RestoreFailure.INVALID_CONTENT, 5)
                                }
                                if (written > cap) throw RestoreException(RestoreFailure.INVALID_CONTENT, 5)
                                write(out, buf, n, neededMb)
                                digest.update(buf, 0, n)
                            }
                        } finally {
                            runCatching { out.close() }
                        }
                        if (name.startsWith(BackupTables.RECEIPTS_DIR)) {
                            receipts[name] = ExtractedFile(target, written, hex(digest.digest()))
                        }
                        if (name == BackupTables.MANIFEST) neededMb = checkSpace(dir, target)
                    }
                    // The decrypted stream is read to its very end in this pass as well: the ZIP reader stops at the central directory.
                    val rest = ByteArray(CHUNK)
                    while (counted.read(rest) >= 0) context.ensureActive()
                }
            } catch (e: RestoreException) {
                throw e
            } catch (e: ZipException) {
                throw RestoreException(RestoreFailure.INVALID_CONTENT, 4).also { it.addSuppressed(e) }
            } catch (e: IllegalArgumentException) {
                // ZipInputStream refuses an entry name that is not valid UTF-8 this way.
                throw RestoreException(RestoreFailure.INVALID_CONTENT, 4).also { it.addSuppressed(e) }
            } catch (e: IOException) {
                throw RestoreException(if (tracking.failed) RestoreFailure.CANNOT_READ else RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
            } catch (e: GeneralSecurityException) {
                throw RestoreException(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
            }
        }
        val missing = tableFiles.values.toSet() - tables.keys
        val m = manifest
        val s = settings
        val k = keyFile
        if (m == null || s == null || k == null || missing.isNotEmpty()) throw RestoreException(RestoreFailure.INVALID_CONTENT, 6)
        return ExtractedBackup(m, s, k, tables, receipts)
    }

    /** Reads the size the receipts need from the manifest and refuses early when the disk cannot hold them. */
    private fun checkSpace(
        dir: File,
        manifestFile: File,
    ): Long {
        val bytes =
            try {
                val text = manifestFile.readText()
                // The same nesting guard as the validator: this parse happens before the checks of check 6.
                if (!RestoreRows.depthOk(text, limits.maxJsonDepth)) throw RestoreException(RestoreFailure.INVALID_CONTENT, 6)
                val receipts = JSONObject(text).getJSONArray("receipts")
                // Saturating: sizes from a file are not trusted not to overflow.
                (0 until receipts.length()).fold(0L) { sum, i ->
                    val size = receipts.getJSONObject(i).optLong("size", 0L).coerceAtLeast(0L)
                    if (sum > Long.MAX_VALUE - size) Long.MAX_VALUE else sum + size
                }
            } catch (_: org.json.JSONException) {
                0L
            }
        val needed = if (bytes > Long.MAX_VALUE - SPACE_MARGIN) Long.MAX_VALUE else bytes + SPACE_MARGIN
        val mb = needed / MB + 1
        if (env.freeSpace(dir) < needed) throw RestoreException(RestoreFailure.STORAGE_FULL, neededMb = mb)
        return mb
    }

    private fun openStage(
        file: File,
        neededMb: Long,
    ): OutputStream =
        try {
            env.openStageFile(file)
        } catch (e: IOException) {
            throw RestoreException(RestoreFailure.STORAGE_FULL, neededMb = neededMb.coerceAtLeast(1)).also { it.addSuppressed(e) }
        }

    private fun write(
        out: OutputStream,
        buf: ByteArray,
        n: Int,
        neededMb: Long,
    ) {
        try {
            out.write(buf, 0, n)
        } catch (e: IOException) {
            // Only the staging folder is written here, so a failure is the disk.
            throw RestoreException(RestoreFailure.STORAGE_FULL, neededMb = neededMb.coerceAtLeast(1)).also { it.addSuppressed(e) }
        }
    }

    /** Check 7 passed: the receipt files get their final names, in the folder that is renamed over the receipts directory. */
    private fun arrangeReceipts(
        dir: File,
        extracted: ExtractedBackup,
        validated: ValidatedBackup,
    ) {
        val fresh = File(dir, NEW_RECEIPTS).also { it.mkdirs() }
        val manifest = JSONObject(extracted.manifest.readText()).getJSONArray("receipts")
        val entryById = (0 until manifest.length()).associate { manifest.getJSONObject(it).getString("id") to manifest.getJSONObject(it) }
        for (r in validated.receipts) {
            val entry = entryById.getValue(r.id).getString("entry")
            val target = File(fresh, r.fileName)
            // The validator already refuses "." and ".."; this is the belt to those braces.
            if (target.canonicalFile.parentFile != fresh.canonicalFile) throw RestoreException(RestoreFailure.INVALID_CONTENT, 6)
            Files.move(
                extracted.receipts
                    .getValue(entry)
                    .file
                    .toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private val recovery = RestoreRecovery(env)
    private val disk = RestoreDisk(env.hooks)

    /** Every staged file is fsynced after its last write, then the folders, all BEFORE Confirm is shown (the journal will point at them). */
    private fun syncStaging(dir: File) {
        dir.walkTopDown().filter { it.isFile }.forEach { disk.fsyncFile(it) }
        dir
            .walkTopDown()
            .filter { it.isDirectory }
            .toList()
            .asReversed()
            .forEach { disk.fsyncDir(it) }
        disk.fsyncDir(env.stageRoot)
    }

    /** Confirm: the durable half (journal, commit point, forward-only finish). Maps the outcome to a [RestoreException]; a kill passes through. */
    suspend fun apply(staged: StagedRestore) = outcome(recovery.apply(staged))

    /** App start (sweeps unreferenced staging) or the banner's Finish. */
    suspend fun recover(atStart: Boolean = true): RestoreOutcome = recovery.recover(atStart)

    fun pendingState(): PendingState = recovery.pendingOf()

    fun journalRead(): JournalRead = recovery.journalRead()

    /** PARTIAL exit 3: keeps the restored data as it is (journal and staging deleted, the current key kept). False when it does not apply. */
    fun keepAsIs(): Boolean = recovery.keepAsIs()

    /** Deletes only this session's own staging (Cancel, a failed check, picking again): a journal slot's staging is never touched. */
    fun discardSession(gen: Int) = recovery.discardSession(gen)

    private fun outcome(o: RestoreOutcome) {
        when (o) {
            RestoreOutcome.DONE -> Unit
            RestoreOutcome.FAILED -> throw RestoreException(RestoreFailure.FAILED)
            RestoreOutcome.INCOMPLETE -> throw RestoreException(RestoreFailure.INCOMPLETE)
            RestoreOutcome.PARTIAL -> throw RestoreException(RestoreFailure.PARTIAL)
        }
    }

    private class CountingInput(
        source: InputStream,
    ) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private companion object {
        const val CHUNK = 8 * 1024
        const val PERCENT = 100L
        const val NEW_RECEIPTS = "receipts-new"
        const val SETTINGS_FILE = "settings.json"
        const val KEY_FILE = "local-id.key"
        const val MB = 1024L * 1024L
        const val SPACE_MARGIN = 8L * 1024 * 1024
        val DIRECTORIES = setOf("data/", "keys/", "receipts/")
        val RECEIPT_ENTRY = Regex("receipts/[A-Za-z0-9_-]{1,100}\\.[a-z0-9]{1,5}")

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
