@file:Suppress("LongParameterList") // one injected seam per thing the restore touches

package eg.masroof.data.backup

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Why a restore did not happen, as a code the UI turns into one of the messages of REQ-SEC-068. Never a stack trace or a file name. */
enum class RestoreFailure {
    /** Check 1: "This is not a Masroof backup". */
    NOT_A_BACKUP,

    /** Check 1: "Update the app to open this backup". */
    NEWER_VERSION,

    /** Check 2 (and an unreadable structure): "This backup can't be opened". */
    CANNOT_OPEN,

    /** Check 3: "Wrong passphrase or damaged file". One message for every cause, so it is no oracle. */
    WRONG_PASSPHRASE_OR_DAMAGED,

    /** A data schema older than this app understands and no upgrade exists: "This backup is too old". */
    TOO_OLD,

    /** Checks 4 to 7: a file that decrypts but is not a safe, whole backup. */
    INVALID_CONTENT,

    /** "Not enough space to restore (needs X MB)". */
    STORAGE_FULL,

    /** The picked file could not be read (provider error). */
    CANNOT_READ,

    /** A backup or an SMS scan is running. */
    BUSY,

    /** The replace itself failed and was rolled back: nothing changed. */
    FAILED,

    /** The data was replaced but a last step (receipts swap, settings, key) failed: the next start finishes it. */
    INCOMPLETE,

    /** The data was replaced but what must follow (receipts, settings, key) could not be applied: restore the same file again. */
    PARTIAL,

    /** The device ran out of memory while checking the file; nothing changed. */
    LOW_MEMORY,
}

/** [step] is the number of the check of REQ-SEC-068 that failed (1..7), 0 when it is not a check. Carries no content of the file. */
class RestoreException(
    val failure: RestoreFailure,
    val step: Int = 0,
    val neededMb: Long = 0L,
) : Exception(failure.name)

/** What the confirmation shows: the backup's date and counts, and what is on this phone now. */
data class RestoreInfo(
    val createdAtMillis: Long,
    val entries: Long,
    val receipts: Int,
    val budgets: Long,
    /** Live entries on this phone, which the restore replaces. */
    val currentEntries: Long,
)

/** The bounds of REQ-SEC-068; tests lower them to reach a check without writing gigabytes. */
data class RestoreLimits(
    val minMemoryKiB: Long = 19L * 1024,
    val maxMemoryKiB: Long = 256L * 1024,
    val minIterations: Long = 1,
    val maxIterations: Long = 10,
    val minParallelism: Int = 1,
    val maxParallelism: Int = 4,
    /** Total uncompressed bytes of all entries, counted while streaming. */
    val maxTotalBytes: Long = 4L shl 30,
    /** Uncompressed bytes allowed per compressed byte of one entry, once [ratioFreeBytes] are written. */
    val maxRatio: Long = 250,
    val ratioFreeBytes: Long = 64L shl 10,
    val maxManifestBytes: Long = 8L shl 20,
    val maxSettingsBytes: Long = 4L shl 20,
    val maxRowChars: Int = 1 shl 20,
    val maxRowsPerTable: Long = 2_000_000,
    /** ZIP entries the extraction accepts: every receipt, the 20 tables, the manifest, the settings and the key. */
    val maxEntries: Int = 100_000 + 23,
    /** Nesting of `[` and `{` in one JSON text: real rows are flat, so a deep text is a stack bomb and is refused before it is parsed. */
    val maxJsonDepth: Int = 8,
    /** Bytes the checker may keep in memory for ids and references (a bomb of ids must not exhaust the heap). */
    val maxTrackedBytes: Long = 64L shl 20,
)

/** One durable operation of a restore, named so a test can kill the process, fail the disk or damage a file right before or after it. */
enum class RestoreOp {
    JOURNAL_WRITE,
    JOURNAL_DELETE,
    STAGE_DELETE,
    TX_COMMIT,
    DELETE_O,
    RENAME_R_TO_O,
    RENAME_N_TO_R,
    FSYNC_DIR,
    READ_STAGED,
    SETTINGS_APPLY,
    KEY_INSTALL,
}

/**
 * Test seams that stand in for a process kill, a full disk or a failing Keystore. A hook throws [RestoreKilled] (a kill: nothing is cleaned
 * up) or an [java.io.IOException] (a disk fault). Every durable operation of the recovery goes through [before] and [after].
 */
interface RestoreHooks {
    /** Right before [op] runs. */
    fun before(op: RestoreOp) = Unit

    /** Right after [op] ran. */
    fun after(op: RestoreOp) = Unit

    /** Inside the Room transaction, after every row was written and before it commits. */
    fun beforeCommit() = Unit

    /** After the commit, before the receipts directory is swapped. */
    fun afterCommit() = Unit

    /** Between the two renames of the receipts swap: the old directory is moved away, the new one is not in place yet. */
    fun betweenRenames() = Unit

    object None : RestoreHooks
}

/** Thrown by a test hook to stand in for the process being killed: nothing is cleaned up, as after a real kill. */
class RestoreKilled : RuntimeException("killed")

/**
 * Seals the restored local-id key under THIS phone's Keystore (REQ-OPS-178). Contract: the key is 32 bytes and not all zero, else
 * [IllegalArgumentException]; the call is idempotent; on return the stored key reads back equal; on a throw either the old or the new key
 * is fully in place (never a torn one); it keeps no reference to [key].
 */
fun interface LocalIdKeyStore {
    fun install(key: ByteArray)
}

/** Writes the allowlisted settings of a backup into the stores of this phone; keys not on the IN list are never touched. */
fun interface RestoreSettingsApplier {
    suspend fun apply(settings: RestoredSettings)
}

class RestoreEnvironment(
    val db: RestoreDatabase,
    val settings: RestoreSettingsApplier,
    val keys: LocalIdKeyStore,
    val receiptsDir: File,
    val nowMillis: () -> Long,
    val limits: RestoreLimits = RestoreLimits(),
    /** Where checked files wait: a sibling of the receipts directory, so the final swap is a rename on one file system. */
    val stageRoot: File = RestoreStage.dir(receiptsDir),
    val freeSpace: (File) -> Long = ::freeBytes,
    val openStageFile: (File) -> OutputStream = { FileOutputStream(it) },
    val hooks: RestoreHooks = RestoreHooks.None,
    /** The erase generation (EraseLock.epoch): staging made before an Erase is never applied after it. */
    val epoch: () -> Long = { 0L },
) {
    /** `filesDir`: the journal, the staging root, the receipts directory and the old receipts all live in it (one file system). */
    val filesDir: File get() = receiptsDir.absoluteFile.parentFile!!
}

/**
 * True while a restore is replacing data. A backup (and STORY-186's SMS scan) checks it before it reads or writes and refuses: a receipts
 * swap in the middle of a backup would make it fail with "receipt changed". The restore sets it BEFORE it asks whether a backup runs, and a
 * backup checks it AFTER WorkManager marked it running, so at least one of them sees the other.
 */
object RestoreActivity {
    private val flag =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** False when another restore already runs. */
    fun begin(): Boolean = flag.compareAndSet(false, true)

    fun end() = flag.set(false)

    fun isActive(): Boolean = flag.get()
}

/**
 * True while a restore is running or has committed and not finished (its journal is on disk, or a legacy marker of an old dev build). Other
 * writers check it under the erase lock and wait: intake is deferred, backups, receipt attaches, the purge and the backfill pause.
 */
fun interface RestoreGate {
    fun blocked(): Boolean
}

object OpenRestoreGate : RestoreGate {
    override fun blocked() = false
}

/** A file check, so it holds in a WorkManager process before the start recovery has run. */
class FileRestoreGate
    @javax.inject.Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    ) : RestoreGate {
        override fun blocked() = RestoreActivity.isActive() || RestoreStage.journalBlocks(File(context.filesDir, "receipts"))
    }

/** The staging folder is checked against the plain free space on purpose: cached data is not something a restore may count on. */
@android.annotation.SuppressLint("UsableSpace")
internal fun freeBytes(dir: File): Long = dir.usableSpace

/** Up to [BackupHeader.SIZE] bytes (fewer at the end of the file); `InputStream.readNBytes` needs API 33. */
internal fun readHeaderBytes(source: java.io.InputStream): ByteArray {
    val buf = ByteArray(BackupHeader.SIZE)
    var n = 0
    while (n < buf.size) {
        val r = source.read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return buf.copyOf(n)
}

/** Where a restore stages and the journal that names an unfinished one; Erase deletes the journal FIRST. */
object RestoreStage {
    const val FOLDER = "restore-stage"
    const val JOURNAL = "restore-journal.json"
    const val LEGACY_MARKER = "restore-marker.json"
    const val OLD_RECEIPTS = "receipts.old"

    fun dir(receiptsDir: File): File = File(receiptsDir.absoluteFile.parentFile, FOLDER)

    fun journal(receiptsDir: File): File = File(receiptsDir.absoluteFile.parentFile, JOURNAL)

    fun legacyMarker(receiptsDir: File): File = File(receiptsDir.absoluteFile.parentFile, LEGACY_MARKER)

    /** True when the journal or the legacy marker exists. */
    fun journalBlocks(receiptsDir: File): Boolean = journal(receiptsDir).exists() || legacyMarker(receiptsDir).exists()

    /** Deletes the journal, its tmp file and the legacy marker (each delete is fsynced); true when none is left. */
    fun deleteJournal(receiptsDir: File): Boolean {
        val parent = receiptsDir.absoluteFile.parentFile!!
        val disk = RestoreDisk(RestoreHooks.None)
        return try {
            for (name in listOf(JOURNAL, "$JOURNAL.tmp", LEGACY_MARKER)) disk.deleteFile(File(parent, name), RestoreOp.JOURNAL_DELETE)
            true
        } catch (_: java.io.IOException) {
            false
        }
    }

    /**
     * Erase and the start sweep: the journal FIRST, then every staging folder (the raw key in it is zeroed before the folder goes; links
     * are never followed), then the old receipts, each delete followed by a directory fsync.
     */
    fun sweep(receiptsDir: File): Boolean {
        val parent = receiptsDir.absoluteFile.parentFile!!
        if (!deleteJournal(receiptsDir)) return false
        val disk = RestoreDisk(RestoreHooks.None)
        return try {
            File(parent, FOLDER).listFiles().orEmpty().forEach { dir ->
                val key = File(dir, "local-id.key")
                if (disk.isRegular(key)) runCatching { key.writeBytes(ByteArray(key.length().toInt())) }
            }
            disk.deleteTree(File(parent, FOLDER))
            disk.deleteTree(File(parent, OLD_RECEIPTS))
            disk.fsyncDir(parent)
            true
        } catch (_: java.io.IOException) {
            false
        }
    }
}
