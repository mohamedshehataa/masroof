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
    /** Nesting of `[` and `{` in one JSON text: real rows are flat, so a deep text is a stack bomb and is refused before it is parsed. */
    val maxJsonDepth: Int = 8,
    /** Bytes the checker may keep in memory for ids and references (a bomb of ids must not exhaust the heap). */
    val maxTrackedBytes: Long = 64L shl 20,
)

/** Test seams that stand in for a process kill. A hook throws [RestoreKilled]; the restore then leaves everything as a kill would. */
interface RestoreHooks {
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
 * Installs the local-id HMAC key under THIS phone's Keystore (REQ-OPS-178). Crash-safe: the new sealed copy is verified before it
 * replaces the old one in a single atomic commit, so there is never a moment without a decryptable key. It may be called again
 * (recovery repeats the finish), and it must throw rather than leave a different key behind.
 */
fun interface LocalIdKeyInstaller {
    fun install(key: ByteArray)
}

/** Writes the allowlisted settings of a backup into the stores of this phone; keys not on the IN list are never touched. */
fun interface RestoreSettingsApplier {
    suspend fun apply(settings: RestoredSettings)
}

class RestoreEnvironment(
    val db: RestoreDatabase,
    val settings: RestoreSettingsApplier,
    val keys: LocalIdKeyInstaller,
    val receiptsDir: File,
    val nowMillis: () -> Long,
    val limits: RestoreLimits = RestoreLimits(),
    /** Where checked files wait: a sibling of the receipts directory, so the final swap is a rename on one file system. */
    val stageRoot: File = RestoreStage.dir(receiptsDir),
    val freeSpace: (File) -> Long = ::freeBytes,
    val openStageFile: (File) -> OutputStream = { FileOutputStream(it) },
    val hooks: RestoreHooks = RestoreHooks.None,
)

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

/** True while a restore is running or has committed and not finished (its marker is on disk): other writers must not run across it. */
fun interface RestorePending {
    fun active(): Boolean
}

object NoRestorePending : RestorePending {
    override fun active() = false
}

/** The marker file lives beside the receipts directory, which is `filesDir/receipts`. */
class FileRestorePending
    @javax.inject.Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    ) : RestorePending {
        override fun active() = RestoreActivity.isActive() || RestoreStage.marker(File(context.filesDir, "receipts")).exists()
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

/** Where a restore stages and which file marks it as committed; a cancel, a failure, a finished restore and Erase remove all of it. */
object RestoreStage {
    const val FOLDER = "restore-stage"
    const val MARKER = "restore-marker.json"
    const val OLD_RECEIPTS = "receipts.old"

    fun dir(receiptsDir: File): File = File(receiptsDir.absoluteFile.parentFile, FOLDER)

    fun marker(receiptsDir: File): File = File(receiptsDir.absoluteFile.parentFile, MARKER)

    fun sweep(receiptsDir: File): Boolean {
        val parent = receiptsDir.absoluteFile.parentFile
        return listOf(File(parent, FOLDER), File(parent, MARKER), File(parent, OLD_RECEIPTS)).all { !it.exists() || it.deleteRecursively() }
    }
}
