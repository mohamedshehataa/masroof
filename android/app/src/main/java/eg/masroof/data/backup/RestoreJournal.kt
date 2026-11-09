@file:Suppress("ComplexCondition") // the strict parse checks several fields in one condition

package eg.masroof.data.backup

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** SHA-256 (lowercase hex) of what checks 6 and 7 read: the table files, the settings, the key and the receipts listing. */
data class StageHashes(
    val tables: String,
    val settings: String,
    val key: String,
    val receipts: String,
)

/** Where the finish of a committed restore stands; it only ever moves forward. */
enum class Step { COMMITTED, SWAPPING, SWAPPED, SETTINGS_DONE, KEY_DONE }

sealed interface CommittedSlot {
    /** Something committed that the journal cannot describe (a corrupt journal was superseded by a new attempt). */
    data object Unknown : CommittedSlot

    data class Known(
        val gen: Int,
        val hashes: StageHashes,
        val step: Step,
        val attempts: Int,
    ) : CommittedSlot
}

/** The attempt about to commit. [from] is the `application_id` read under the lock just before this slot was written. */
data class PendingSlot(
    val gen: Int,
    val from: Int,
    val hashes: StageHashes,
)

data class Journal(
    val committed: CommittedSlot?,
    val pending: PendingSlot?,
)

sealed interface JournalRead {
    data object Absent : JournalRead

    /** Reading threw an IOException: nothing is known, nothing is touched. */
    data object Unreadable : JournalRead

    /** Present but not a valid version-2 journal (or only a legacy marker): counts as "something committed" (R7). */
    data object Corrupt : JournalRead

    data class Ok(
        val j: Journal,
    ) : JournalRead
}

sealed interface Decision {
    data object Clean : Decision

    data class Promote(
        val p: PendingSlot,
    ) : Decision

    data class Finish(
        val c: CommittedSlot.Known,
        val dropPending: PendingSlot?,
    ) : Decision

    data class Partial(
        val dropPending: PendingSlot?,
        val keepAsUnknown: Boolean,
    ) : Decision

    data object Unreadable : Decision
}

/** What to do with a journal given A = `PRAGMA application_id`: the pure function the tests use as an oracle (design 4.2). */
fun decide(
    read: JournalRead,
    a: Int,
): Decision =
    when (read) {
        JournalRead.Absent -> Decision.Clean
        JournalRead.Unreadable -> Decision.Unreadable
        JournalRead.Corrupt -> Decision.Partial(null, keepAsUnknown = true)
        is JournalRead.Ok -> decideOk(read.j, a)
    }

private fun decideOk(
    j: Journal,
    a: Int,
): Decision {
    val c = j.committed
    val p = j.pending
    return when {
        c == null && p == null -> Decision.Clean
        p != null && a == p.gen -> Decision.Promote(p)
        p != null && a != p.from -> Decision.Partial(null, keepAsUnknown = true)
        c == null -> Decision.Clean
        c is CommittedSlot.Unknown -> Decision.Partial(p, keepAsUnknown = true)
        c is CommittedSlot.Known && a == c.gen -> Decision.Finish(c, p)
        else -> Decision.Partial(p, keepAsUnknown = false)
    }
}

/**
 * Every durable operation of a restore goes through here so each can be killed, failed or damaged by a test, and so the order of the
 * fsyncs can be asserted. A directory fsync that the platform refuses is swallowed only when the OS is not Linux (tests on Windows).
 */
class RestoreDisk(
    private val hooks: RestoreHooks,
) {
    private fun <T> op(
        op: RestoreOp,
        block: () -> T,
    ): T {
        hooks.before(op)
        val r = block()
        hooks.after(op)
        return r
    }

    /** fsync of a directory so a rename or a delete inside it is durable. */
    fun fsyncDir(dir: File) =
        op(RestoreOp.FSYNC_DIR) {
            if (dir.isDirectory) {
                try {
                    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
                } catch (e: IOException) {
                    if (System.getProperty("os.name").orEmpty().contains("Linux")) throw e
                }
            }
        }

    /** fsync of a file after its last write. */
    fun fsyncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    fun move(
        from: File,
        to: File,
        op: RestoreOp,
    ) = op(op) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }

    fun deleteRecursively(
        dir: File,
        op: RestoreOp,
    ) = op(op) { deleteTree(dir) }

    /** A regular file, never a symbolic link or a directory (NOFOLLOW_LINKS): what staging may contain. */
    fun isRegular(file: File): Boolean =
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile
        } catch (_: IOException) {
            false
        }

    /** A real directory, not a link to one. */
    fun isRealDir(file: File): Boolean =
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
        } catch (_: IOException) {
            false
        }

    /** Deletes a tree without ever following a link: a symbolic link is removed itself, its target is never touched. */
    fun deleteTree(root: File) {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Reads a staged file; an IOException is a transient error (INCOMPLETE), a missing file is decided by the caller with `isFile`. */
    fun readStaged(file: File): ByteArray = op(RestoreOp.READ_STAGED) { file.readBytes() }

    fun <T> kind(
        op: RestoreOp,
        block: () -> T,
    ): T = op(op, block)

    /** Write-then-rename of a whole small file, fsynced, with the directory fsynced after the rename. */
    fun writeAtomic(
        target: File,
        bytes: ByteArray,
    ) = op(RestoreOp.JOURNAL_WRITE) {
        val tmp = File(target.absoluteFile.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fsyncDir(target.absoluteFile.parentFile!!)
    }

    fun deleteFile(
        file: File,
        op: RestoreOp,
    ) = op(op) {
        Files.deleteIfExists(file.toPath())
        fsyncDir(file.absoluteFile.parentFile!!)
    }
}

/** The journal file (version 2, strict parse): the only record of which attempt has committed and how far its finish got. */
class RestoreJournal(
    private val disk: RestoreDisk,
    val file: File,
    private val legacy: File,
) {
    fun read(): JournalRead {
        if (!file.exists()) return if (legacy.exists()) JournalRead.Corrupt else JournalRead.Absent
        val text =
            try {
                // At most 4 KiB: a real journal is a few hundred bytes, so anything larger is Corrupt without being read.
                if (file.length() > MAX_BYTES) return JournalRead.Corrupt
                file.readText()
            } catch (_: IOException) {
                return JournalRead.Unreadable
            }
        return parse(text)
    }

    fun write(j: Journal) {
        if (j.committed == null && j.pending == null) {
            delete()
            return
        }
        disk.writeAtomic(file, encode(j).toByteArray())
    }

    fun delete() = disk.deleteFile(file, RestoreOp.JOURNAL_DELETE)

    /** The tmp file of an interrupted write is never read; it is removed. */
    fun removeTmp() {
        File(file.absoluteFile.parentFile, file.name + ".tmp").delete()
    }

    /** Records that [c]'s finish reached [step]: re-reads the journal, keeps its pending slot, and never goes back a step. */
    fun step(
        c: CommittedSlot.Known,
        step: Step,
    ): Step {
        val cur = (read() as? JournalRead.Ok)?.j ?: error("journal is not readable")
        val known = cur.committed as? CommittedSlot.Known ?: error("no committed slot")
        check(known.gen == c.gen) { "committed slot changed" }
        if (step.ordinal > known.step.ordinal) write(cur.copy(committed = known.copy(step = step)))
        return step
    }

    /** One more failed finish for [gen]; the step stays. */
    fun bumpAttempts(gen: Int) {
        val cur = (read() as? JournalRead.Ok)?.j ?: return
        val known = cur.committed as? CommittedSlot.Known ?: return
        if (known.gen == gen) write(cur.copy(committed = known.copy(attempts = known.attempts + 1)))
    }

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")

        /** A JSON integer only: a string "5" or 5.0 is a wrong type, not a number. */
        private fun intOf(
            o: JSONObject,
            key: String,
        ): Int = o.get(key) as? Int ?: throw JSONException("not an int")

        fun encode(j: Journal): String {
            val root = JSONObject().put("v", 2)
            root.put(
                "committed",
                when (val c = j.committed) {
                    null -> JSONObject.NULL
                    CommittedSlot.Unknown -> JSONObject().put("unknown", true)
                    is CommittedSlot.Known ->
                        hashes(JSONObject().put("gen", c.gen).put("step", c.step.name).put("attempts", c.attempts), c.hashes)
                },
            )
            root.put(
                "pending",
                j.pending?.let { hashes(JSONObject().put("gen", it.gen).put("from", it.from), it.hashes) } ?: JSONObject.NULL,
            )
            return root.toString()
        }

        private fun hashes(
            o: JSONObject,
            h: StageHashes,
        ): JSONObject =
            o
                .put("tables", h.tables)
                .put("settings", h.settings)
                .put("key", h.key)
                .put("receipts", h.receipts)

        const val MAX_BYTES = 4096L

        /**
         * Anything that is not exactly a version-2 journal is [JournalRead.Corrupt]: exact JSON types (no coercion), and the parsed value
         * re-encode to the very same text, which rejects unknown keys, duplicate keys, other key order and extra whitespace in one check.
         */
        fun parse(text: String): JournalRead {
            val read = parseLoose(text)
            return if (read is JournalRead.Ok && encode(read.j) != text) JournalRead.Corrupt else read
        }

        private fun parseLoose(text: String): JournalRead =
            try {
                val root = JSONObject(text)
                if (intOf(root, "v") != 2) {
                    JournalRead.Corrupt
                } else {
                    val committed = committed(root.get("committed"))
                    val pending = pending(root.get("pending"))
                    val clash = committed is CommittedSlot.Known && pending != null && committed.gen == pending.gen
                    if (committed == BAD ||
                        pending == BAD_PENDING ||
                        clash
                    ) {
                        JournalRead.Corrupt
                    } else {
                        JournalRead.Ok(Journal(committed, pending))
                    }
                }
            } catch (_: JSONException) {
                JournalRead.Corrupt
            }

        private val BAD = CommittedSlot.Known(0, StageHashes("", "", "", ""), Step.COMMITTED, -1)
        private val BAD_PENDING = PendingSlot(0, 0, StageHashes("", "", "", ""))

        private fun strOf(
            o: JSONObject,
            key: String,
        ): String = o.get(key) as? String ?: throw JSONException("not a string")

        private fun hashesOf(o: JSONObject): StageHashes? {
            val h = StageHashes(strOf(o, "tables"), strOf(o, "settings"), strOf(o, "key"), strOf(o, "receipts"))
            return h.takeIf { listOf(it.tables, it.settings, it.key, it.receipts).all(HEX::matches) }
        }

        private fun committed(v: Any): CommittedSlot? {
            if (v == JSONObject.NULL) return null
            val o = v as? JSONObject ?: return BAD
            if (o.has("unknown")) return if (o.optBoolean("unknown") && o.length() == 1) CommittedSlot.Unknown else BAD
            val gen = intOf(o, "gen")
            val step = Step.entries.firstOrNull { it.name == strOf(o, "step") }
            val attempts = intOf(o, "attempts")
            val hashes = hashesOf(o)
            return if (gen == 0 || step == null || attempts < 0 || hashes == null) BAD else CommittedSlot.Known(gen, hashes, step, attempts)
        }

        private fun pending(v: Any): PendingSlot? {
            if (v == JSONObject.NULL) return null
            val o = v as? JSONObject ?: return BAD_PENDING
            val gen = intOf(o, "gen")
            val hashes = hashesOf(o)
            return if (gen == 0 || hashes == null) BAD_PENDING else PendingSlot(gen, intOf(o, "from"), hashes)
        }
    }
}
