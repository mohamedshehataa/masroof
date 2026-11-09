@file:Suppress(
    "TooGenericExceptionCaught",
    "ThrowsCount",
    "TooManyFunctions",
    "SwallowedException",
    "CyclomaticComplexMethod",
    "ComplexCondition",
) // one named outcome per failure; a kill must pass through

package eg.masroof.data.backup

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What one run of apply / finish / recover ended in. [PARTIAL] is decided only from evidence that cannot change by retrying. */
enum class RestoreOutcome { DONE, FAILED, INCOMPLETE, PARTIAL }

/** What the banner and Settings show about an unfinished restore (derived from the journal and the last outcome). */
sealed interface PendingState {
    data object None : PendingState

    /** [atKeyStep]: the finish is stuck at the key step (everything before it is applied); from three attempts a way out is offered. */
    data class Incomplete(
        val attempts: Int,
        val atKeyStep: Boolean = false,
    ) : PendingState

    /** [canKeep]: "Keep the restored data as it is" can act (a readable journal names a committed attempt that is the phone's data). */
    data class Partial(
        val canKeep: Boolean = false,
    ) : PendingState
}

/** A staged restore waits here for Confirm: its generation, its own staging folder and the hashes of what checks 6 and 7 read. */
class StagedRestore(
    val gen: Int,
    val dir: File,
    val info: RestoreInfo,
    internal val validated: ValidatedBackup,
    internal val hashes: StageHashes,
    internal val epoch: Long,
)

/**
 * The durable half of a restore (design note STORY-184-restore-recovery.md): the commit point is `PRAGMA application_id == gen` inside the
 * replace transaction, the journal names every attempt, and after the commit everything moves forward only. Callers hold the erase lock.
 */
class RestoreRecovery(
    private val env: RestoreEnvironment,
) {
    private val disk = RestoreDisk(env.hooks)
    private val journal = RestoreJournal(disk, RestoreStage.journal(env.receiptsDir), RestoreStage.legacyMarker(env.receiptsDir))
    private val receipts get() = env.receiptsDir
    private val oldReceipts get() = File(env.filesDir, RestoreStage.OLD_RECEIPTS)

    /** Evidence that cannot change by retrying (a missing or changed staged file, a bad key, unparseable settings). */
    private class PartialEvidence : Exception()

    fun journalRead(): JournalRead = journal.read()

    fun stageDir(gen: Int): File = File(env.stageRoot, "g" + "%08x".format(gen))

    private fun deleteStage(gen: Int) {
        val dir = stageDir(gen)
        zeroKey(dir)
        disk.deleteRecursively(dir, RestoreOp.STAGE_DELETE)
    }

    /** Best effort on flash (file-based encryption is the control): zero the raw key before its file goes; never through a link. */
    private fun zeroKey(dir: File) {
        val key = File(dir, KEY_FILE)
        if (disk.isRegular(key)) runCatching { key.writeBytes(ByteArray(key.length().toInt())) }
    }

    private fun deleteStagesExcept(keep: Int?) {
        for (d in env.stageRoot.listFiles().orEmpty()) {
            if (keep == null || d.name != "g" + "%08x".format(keep)) {
                zeroKey(d)
                disk.deleteRecursively(d, RestoreOp.STAGE_DELETE)
            }
        }
    }

    /** Applies the bookkeeping of [d] to the journal and the staging; returns the journal now on disk (null = clean). */
    fun settle(d: Decision): Journal? =
        when (d) {
            Decision.Clean -> {
                val old = (journal.read() as? JournalRead.Ok)?.j
                journal.delete()
                File(env.filesDir, RestoreStage.LEGACY_MARKER).delete()
                old?.pending?.let { deleteStage(it.gen) }
                null
            }
            is Decision.Promote -> {
                val old = (journal.read() as? JournalRead.Ok)?.j
                val c = CommittedSlot.Known(d.p.gen, d.p.hashes, Step.COMMITTED, 0)
                journal.write(Journal(c, null)) // durable before anything is deleted
                when (val oc = old?.committed) {
                    is CommittedSlot.Known -> deleteStage(oc.gen)
                    CommittedSlot.Unknown -> deleteStagesExcept(d.p.gen)
                    null -> Unit
                }
                File(env.filesDir, RestoreStage.LEGACY_MARKER).delete()
                Journal(c, null)
            }
            is Decision.Finish -> {
                if (d.dropPending != null) {
                    journal.write(Journal(d.c, null))
                    deleteStage(d.dropPending.gen)
                }
                Journal(d.c, null)
            }
            is Decision.Partial -> {
                val cur = (journal.read() as? JournalRead.Ok)?.j
                if (d.dropPending != null && cur != null) {
                    journal.write(cur.copy(pending = null))
                    deleteStage(d.dropPending.gen)
                }
                cur
            }
            Decision.Unreadable -> throw IOException("journal unreadable")
        }

    // ---- Confirm -----------------------------------------------------------------------------------------------------------------

    /** Confirm (§4.4). Never throws past the caller except [RestoreKilled]; every other problem is an outcome. */
    suspend fun apply(s: StagedRestore): RestoreOutcome {
        if (env.epoch() != s.epoch) {
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        }
        val a0 =
            try {
                env.db.applicationId()
            } catch (e: Exception) {
                deleteStage(s.gen)
                return RestoreOutcome.FAILED
            }
        // An unreadable journal is read once more; if it still cannot be read nothing is written (only a CORRUPT one may become Unknown).
        if (journal.read() is JournalRead.Unreadable && journal.read() is JournalRead.Unreadable) {
            deleteStage(s.gen)
            return RestoreOutcome.INCOMPLETE
        }
        val base: CommittedSlot? =
            try {
                settleBase(a0)
            } catch (k: RestoreKilled) {
                throw k
            } catch (e: Exception) {
                deleteStage(s.gen)
                return RestoreOutcome.FAILED
            }
        try {
            verifyStaged(s)
        } catch (k: RestoreKilled) {
            throw k
        } catch (e: PartialEvidence) {
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        } catch (e: Exception) {
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        }
        if (s.gen == a0 || s.gen == (base as? CommittedSlot.Known)?.gen) {
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        }
        try {
            journal.write(Journal(base, PendingSlot(s.gen, a0, s.hashes)))
        } catch (k: RestoreKilled) {
            throw k
        } catch (e: Exception) {
            journal.removeTmp()
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        }
        try {
            env.hooks.before(RestoreOp.TX_COMMIT)
            env.db.replaceAll(s.gen, s.validated.tables, s.hashes.tables) { env.hooks.beforeCommit() }
            env.hooks.after(RestoreOp.TX_COMMIT)
        } catch (k: RestoreKilled) {
            throw k
        } catch (e: Throwable) {
            // decided below from the application_id, never from the exception
        }
        val a1 =
            try {
                env.db.applicationId()
            } catch (e: Exception) {
                return RestoreOutcome.INCOMPLETE // the journal stays as it is; the next decide judges it
            }
        if (a1 != s.gen) {
            runCatching { journal.write(Journal(base, null)) }
            deleteStage(s.gen)
            return RestoreOutcome.FAILED
        }
        env.hooks.afterCommit()
        val promoted =
            try {
                settle(Decision.Promote(PendingSlot(s.gen, a0, s.hashes)))
            } catch (k: RestoreKilled) {
                throw k
            } catch (e: Exception) {
                return RestoreOutcome.INCOMPLETE // pending = X stays: the next decide promotes it (row 5)
            }
        return finish(promoted!!.committed as CommittedSlot.Known)
    }

    /** The base journal before an attempt: a committed slot of an earlier attempt is carried over verbatim, never touched. */
    private fun settleBase(a0: Int): CommittedSlot? =
        when (val read = journal.read()) {
            JournalRead.Absent -> null
            JournalRead.Unreadable -> throw IOException("journal unreadable")
            JournalRead.Corrupt -> CommittedSlot.Unknown
            is JournalRead.Ok ->
                when (val d = decide(read, a0)) {
                    Decision.Clean -> {
                        settle(d)
                        null
                    }
                    is Decision.Promote -> settle(d)?.committed
                    is Decision.Finish -> settle(d)?.committed
                    is Decision.Partial -> {
                        settle(d)
                        if (d.keepAsUnknown) CommittedSlot.Unknown else read.j.committed
                    }
                    Decision.Unreadable -> CommittedSlot.Unknown
                }
        }

    /** The staging must still be exactly what was checked: hashes from memory, never from disk. Table bytes are hashed when read. */
    private fun verifyStaged(s: StagedRestore) {
        readVerified(File(s.dir, SETTINGS_FILE), s.hashes.settings).fill(0)
        readVerified(File(s.dir, KEY_FILE), s.hashes.key).fill(0)
        verifyDir(File(s.dir, NEW_RECEIPTS), s.hashes.receipts)
    }

    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun readVerified(
        file: File,
        sha: String,
    ): ByteArray {
        if (!disk.isRegular(file)) throw PartialEvidence()
        val bytes = disk.readStaged(file)
        if (sha(bytes) != sha) throw PartialEvidence()
        return bytes
    }

    private fun verifyDir(
        dir: File,
        sha: String,
    ) {
        if (!disk.isRealDir(dir)) throw PartialEvidence()
        val digest = MessageDigest.getInstance("SHA-256")
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            // Only regular files, never a symbolic link or a subdirectory; every file is re-hashed and compared with the listing hash.
            if (!disk.isRegular(f)) throw PartialEvidence()
            digest.update((f.name + ":" + sha(disk.readStaged(f)) + "\n").toByteArray())
        }
        if (hex(digest.digest()) != sha) throw PartialEvidence()
    }

    // ---- finish ------------------------------------------------------------------------------------------------------------------

    /** Forward-only and idempotent: resumes at [c].step, verifies every byte it uses against the journal's hashes first. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun finish(c: CommittedSlot.Known): RestoreOutcome {
        val dir = stageDir(c.gen)
        val n = File(dir, NEW_RECEIPTS)
        var keyBytes: ByteArray? = null
        try {
            if (c.step == Step.COMMITTED) verifyDir(n, c.hashes.receipts)
            if (c.step == Step.SWAPPING && n.exists()) verifyDir(n, c.hashes.receipts)
            if (c.step == Step.SWAPPING && !n.exists() && !receipts.exists()) {
                receipts.mkdirs() // the app keeps working; the user is told to restore again
                return RestoreOutcome.PARTIAL
            }
            val settingsBytes =
                if (c.step.ordinal <=
                    Step.SWAPPED.ordinal
                ) {
                    readVerified(File(dir, SETTINGS_FILE), c.hashes.settings)
                } else {
                    null
                }
            keyBytes = if (c.step.ordinal <= Step.SETTINGS_DONE.ordinal) readVerified(File(dir, KEY_FILE), c.hashes.key) else null
            var s = c.step
            if (s == Step.COMMITTED) s = journal.step(c, Step.SWAPPING)
            if (s == Step.SWAPPING) {
                swap(n)
                s = journal.step(c, Step.SWAPPED)
            }
            if (s == Step.SWAPPED) {
                val parsed =
                    try {
                        RestoreSettingKeys.parse(settingsBytes!!.decodeToString())
                    } catch (e: RestoreException) {
                        throw PartialEvidence()
                    }
                env.hooks.before(RestoreOp.SETTINGS_APPLY)
                env.settings.apply(parsed)
                env.hooks.after(RestoreOp.SETTINGS_APPLY)
                s = journal.step(c, Step.SETTINGS_DONE)
            }
            if (s == Step.SETTINGS_DONE) {
                val key = keyBytes!!
                if (!(key.size == KEY_BYTES && key.any { it.toInt() != 0 })) throw PartialEvidence()
                env.hooks.before(RestoreOp.KEY_INSTALL)
                env.keys.install(key)
                env.hooks.after(RestoreOp.KEY_INSTALL)
                s = journal.step(c, Step.KEY_DONE)
            }
            keyBytes?.fill(0)
            zeroKey(dir)
            deleteStage(c.gen)
            disk.deleteRecursively(oldReceipts, RestoreOp.DELETE_O)
            disk.fsyncDir(env.filesDir)
            journal.delete()
            File(env.filesDir, RestoreStage.LEGACY_MARKER).delete()
            return RestoreOutcome.DONE
        } catch (k: RestoreKilled) {
            throw k
        } catch (e: PartialEvidence) {
            return RestoreOutcome.PARTIAL
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { journal.bumpAttempts(c.gen) }
            return RestoreOutcome.INCOMPLETE
        } finally {
            keyBytes?.fill(0) // on every exit: Done, PARTIAL, INCOMPLETE or a kill
        }
    }

    /** Idempotent from every directory layout: after the commit the old receipts are always garbage. */
    private fun swap(n: File) {
        if (!n.exists()) return // N -> R already happened
        if (receipts.exists()) {
            disk.deleteRecursively(oldReceipts, RestoreOp.DELETE_O)
            disk.move(receipts, oldReceipts, RestoreOp.RENAME_R_TO_O)
            disk.fsyncDir(env.filesDir)
        }
        env.hooks.betweenRenames()
        disk.move(n, receipts, RestoreOp.RENAME_N_TO_R)
        disk.fsyncDir(env.filesDir)
    }

    // ---- recovery ----------------------------------------------------------------------------------------------------------------

    /** App start ([atStart], which also sweeps unreferenced staging) or the banner's Finish. Never throws past the caller except a kill. */
    suspend fun recover(atStart: Boolean): RestoreOutcome {
        val read = journal.read()
        val a =
            try {
                env.db.applicationId()
            } catch (e: Exception) {
                return RestoreOutcome.INCOMPLETE
            }
        try {
            if (atStart) sweep(read)
            return run(decide(read, a))
        } catch (k: RestoreKilled) {
            throw k
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return RestoreOutcome.INCOMPLETE
        }
    }

    private suspend fun run(d: Decision): RestoreOutcome =
        when (d) {
            Decision.Clean -> {
                settle(d)
                RestoreOutcome.DONE
            }
            is Decision.Promote -> finish(settle(d)!!.committed as CommittedSlot.Known)
            is Decision.Finish -> {
                settle(d)
                finish(d.c)
            }
            is Decision.Partial -> {
                settle(d)
                RestoreOutcome.PARTIAL
            }
            Decision.Unreadable -> RestoreOutcome.INCOMPLETE
        }

    /** Start sweep: staging that no journal slot names is garbage. An unreadable or corrupt journal sweeps nothing (R7). */
    private fun sweep(read: JournalRead) {
        journal.removeTmp()
        when (read) {
            JournalRead.Absent -> {
                disk.deleteRecursively(env.stageRoot, RestoreOp.STAGE_DELETE)
                disk.deleteRecursively(oldReceipts, RestoreOp.DELETE_O)
            }
            is JournalRead.Ok -> {
                val keep =
                    listOfNotNull(
                        (read.j.committed as? CommittedSlot.Known)?.gen,
                        read.j.pending?.gen,
                    ).map { "g" + "%08x".format(it) }
                for (d in env.stageRoot.listFiles().orEmpty()) {
                    if (d.name !in keep && read.j.committed !is CommittedSlot.Unknown) {
                        zeroKey(d)
                        disk.deleteRecursively(d, RestoreOp.STAGE_DELETE)
                    }
                }
            }
            JournalRead.Unreadable, JournalRead.Corrupt -> Unit
        }
    }

    /** The banner/Settings state for a journal: None when absent; Partial when corrupt or the last run found evidence; else Incomplete. */
    fun pendingOf(read: JournalRead = journal.read()): PendingState =
        when (read) {
            JournalRead.Absent -> PendingState.None
            JournalRead.Corrupt -> PendingState.Partial(canKeep = false)
            JournalRead.Unreadable -> PendingState.Incomplete(0)
            is JournalRead.Ok -> {
                val c = read.j.committed as? CommittedSlot.Known
                PendingState.Incomplete(c?.attempts ?: 0, atKeyStep = c?.step == Step.SETTINGS_DONE)
            }
        }

    /** A fresh generation: non-zero, not the phone's own, not named by the journal, with no staging folder yet. */
    fun freshGen(random: java.security.SecureRandom = java.security.SecureRandom()): Int {
        val used = mutableSetOf(env.db.applicationId())
        (journal.read() as? JournalRead.Ok)?.j?.let {
            (it.committed as? CommittedSlot.Known)?.gen?.let(used::add)
            it.pending?.gen?.let(used::add)
        }
        while (true) {
            val g = random.nextInt()
            if (g != 0 && g !in used && !stageDir(g).exists()) return g
        }
    }

    /** True when "Keep the restored data as it is" can act: a readable journal names a committed attempt and A is that attempt. */
    fun canKeepAsIs(): Boolean {
        val j = (journal.read() as? JournalRead.Ok)?.j ?: return false
        val c = j.committed as? CommittedSlot.Known ?: return false
        return runCatching { env.db.applicationId() == c.gen }.getOrDefault(false)
    }

    /**
     * The third way out of PARTIAL: "keep the restored data as it is". Allowed only when [canKeepAsIs]. Every staged receipt that is a regular
     * file and belongs to a restored receipt row is moved into place (so the restored rows do not point at missing files); the rest is
     * dropped. Deletes the journal and the staging; the settings and key stay as they are now (the current key is kept).
     */
    fun keepAsIs(): Boolean {
        if (!canKeepAsIs()) return false
        val j = (journal.read() as JournalRead.Ok).j
        val c = j.committed as CommittedSlot.Known
        moveVerifiedReceipts(File(stageDir(c.gen), NEW_RECEIPTS))
        return finishKeep(j, c.gen)
    }

    /**
     * The way out of a finish that is stuck at the KEY step: after three failed attempts the restore is completed with the CURRENT key
     * (the receipts and settings are already applied). Journal and staging are deleted and the gate reopens.
     */
    fun keepCurrentKey(): Boolean {
        val j = (journal.read() as? JournalRead.Ok)?.j ?: return false
        val c = j.committed as? CommittedSlot.Known ?: return false
        if (c.step != Step.SETTINGS_DONE || c.attempts < KEY_STEP_ATTEMPTS) return false
        if (runCatching { env.db.applicationId() != c.gen }.getOrDefault(true)) return false
        return finishKeep(j, c.gen)
    }

    private fun finishKeep(
        j: Journal,
        gen: Int,
    ): Boolean {
        journal.delete()
        File(env.filesDir, RestoreStage.LEGACY_MARKER).delete()
        deleteStage(gen)
        j.pending?.let { deleteStage(it.gen) }
        disk.deleteRecursively(oldReceipts, RestoreOp.DELETE_O)
        return true
    }

    private fun moveVerifiedReceipts(fresh: File) {
        if (!disk.isRealDir(fresh)) return
        val rowNames = runCatching { env.db.receiptFileNames() }.getOrDefault(emptySet())
        receipts.mkdirs()
        for (f in fresh.listFiles().orEmpty()) {
            val target = File(receipts, f.name)
            if (disk.isRegular(f) && f.name in rowNames && !target.exists()) runCatching { disk.move(f, target, RestoreOp.RENAME_N_TO_R) }
        }
    }

    /** Deletes only the session's own staging (Cancel, a failed check, picking again): a journal slot's staging is never touched. */
    fun discardSession(gen: Int) {
        val named =
            (journal.read() as? JournalRead.Ok)
                ?.j
                ?.let {
                    listOfNotNull((it.committed as? CommittedSlot.Known)?.gen, it.pending?.gen)
                }.orEmpty()
        if (gen !in named) deleteStage(gen)
    }

    private companion object {
        const val SETTINGS_FILE = "settings.json"
        const val KEY_FILE = "local-id.key"
        const val NEW_RECEIPTS = "receipts-new"
        const val KEY_BYTES = 32
        const val KEY_STEP_ATTEMPTS = 3

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
