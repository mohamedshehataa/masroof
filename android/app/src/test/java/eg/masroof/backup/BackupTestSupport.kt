package eg.masroof.backup

import eg.masroof.data.backup.BackupCipher
import eg.masroof.data.backup.BackupDatabase
import eg.masroof.data.backup.BackupEnvironment
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupPrefs
import eg.masroof.data.backup.BackupReceiptRef
import eg.masroof.data.backup.BackupResult
import eg.masroof.data.backup.BackupTables
import eg.masroof.data.backup.BackupWriter
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.ReceiptFiles
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The plaintext of a backup after decryption: ZIP entry names in file order and their bytes. */
class Decrypted(
    val header: BackupHeader,
    val names: List<String>,
    val entries: Map<String, ByteArray>,
) {
    fun text(name: String): String = String(entries.getValue(name), Charsets.UTF_8)

    /** Every entry joined: what a grep over the plaintext sees. */
    fun everything(): String = entries.entries.joinToString("\n") { String(it.value, Charsets.ISO_8859_1) }
}

class BackupRejected(
    message: String,
) : Exception(message)

/**
 * The test decryptor (STORY-183): the reverse of the writer, following the restore validation order of ADR-0009 D5 as far as this story
 * needs it. It drains the decrypted stream to its end BEFORE reading the ZIP: a ZIP reader stops at the central directory, so
 * reading only the entries would leave the last AEAD segment (and a cut tail) unverified.
 */
object BackupTestDecryptor {
    @Suppress("ThrowsCount", "ComplexCondition", "SwallowedException")
    fun decrypt(
        file: ByteArray,
        passphrase: String,
        enforceBounds: Boolean = false,
    ): Decrypted {
        val header = BackupHeader.parse(file) ?: throw BackupRejected("magic")
        if (header.formatVersion != 1 || header.kdfId != 1 || header.aeadId != 1 || header.reserved != 0) throw BackupRejected("version")
        if (enforceBounds) {
            val ok = header.memoryKiB in 19L * 1024..256L * 1024 && header.iterations in 1..10 && header.parallelism in 1..4
            if (!ok) throw BackupRejected("kdf bounds")
        }
        val key = PassphraseKdf.deriveKey(passphrase, header)
        val headerBytes = file.copyOfRange(0, BackupHeader.SIZE)
        val plain =
            try {
                BackupCipher
                    .decrypt(ByteArrayInputStream(file, BackupHeader.SIZE, file.size - BackupHeader.SIZE), key, headerBytes)
                    .readBytes()
            } catch (e: java.io.IOException) {
                throw BackupRejected("decryption failed")
            } catch (e: java.security.GeneralSecurityException) {
                throw BackupRejected("decryption failed")
            }
        val names = mutableListOf<String>()
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.contains("..") || e.name.startsWith("/") || e.name in entries) throw BackupRejected("entry name")
                names += e.name
                entries[e.name] = zip.readBytes()
            }
        }
        return Decrypted(header, names, entries)
    }
}

/** A header with cheap KDF settings so tests that are not about the KDF stay fast; the default header is checked on its own. */
fun fastHeader(seed: Int = 7): BackupHeader =
    BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { (it + seed).toByte() }, 1, 0)

/** In-memory tables: name to rows, each row a column-to-value map. */
class FakeBackupDb(
    private val tables: Map<String, List<Map<String, Any?>>> = emptyMap(),
    private val receiptRefs: List<BackupReceiptRef> = emptyList(),
    private val live: Long = 0,
) : BackupDatabase {
    var inSnapshot = false
    var snapshots = 0

    override fun <T> snapshot(block: () -> T): T {
        inSnapshot = true
        snapshots++
        try {
            return block()
        } finally {
            inSnapshot = false
        }
    }

    override fun count(table: String): Long = (tables[table] ?: emptyList()).size.toLong()

    override fun liveEntryCount(): Long = live

    override fun forEachRow(
        table: String,
        action: (List<String>, List<Any?>) -> Unit,
    ) {
        for (row in tables[table].orEmpty()) action(row.keys.toList(), row.values.toList())
    }

    override fun receipts(): List<BackupReceiptRef> = receiptRefs
}

fun mapPrefs(stores: Map<String, Map<String, Any>> = emptyMap()) = BackupPrefs { stores }

fun environment(
    db: BackupDatabase = FakeBackupDb(),
    prefs: BackupPrefs = mapPrefs(),
    files: ReceiptFiles = ReceiptFiles { null },
    localKey: ByteArray = ByteArray(32) { 9 },
    now: Long = 1_793_000_000_000L,
) = BackupEnvironment(db, prefs, { localKey.copyOf() }, files, "0.1.7", now)

/** Writes a backup into memory with a throwaway key; returns the file bytes and the writer's result. */
fun writeBackup(
    env: BackupEnvironment,
    passphrase: String = "correct horse battery",
    header: BackupHeader = fastHeader(),
): Pair<ByteArray, BackupResult> {
    val key = PassphraseKdf.deriveKey(passphrase, header)
    val out = ByteArrayOutputStream()
    val result = runBlocking { BackupWriter(env).write(out, BackupInput(header, key)) }
    return out.toByteArray() to result
}

fun filesOf(vararg files: Pair<String, ByteArray>): ReceiptFiles {
    val map = files.toMap()
    return ReceiptFiles { ref -> map[ref.fileName]?.let { ByteArrayInputStream(it) as InputStream } }
}

val ALL_TABLE_NAMES: List<String> = BackupTables.INCLUDED.map { it.name }
