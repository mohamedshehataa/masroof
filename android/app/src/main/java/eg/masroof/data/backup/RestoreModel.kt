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
)

/** Test seams that stand in for a process kill. A hook throws [RestoreKilled]; the restore then leaves everything as a kill would. */
interface RestoreHooks {
    /** Inside the Room transaction, after every row was written and before it commits. */
    fun beforeCommit() = Unit

    /** After the commit, before the receipts directory is swapped. */
    fun afterCommit() = Unit

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
    val freeSpace: (File) -> Long = { it.usableSpace },
    val openStageFile: (File) -> OutputStream = { FileOutputStream(it) },
    val hooks: RestoreHooks = RestoreHooks.None,
)

/** Where a restore stages and which file marks it as committed. All of it is removed by a cancel, a failure, a finished restore and by Erase. */
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
