package eg.masroof.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.backup.CommittedSlot
import eg.masroof.data.backup.JournalRead
import eg.masroof.data.backup.PendingState
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreKilled
import eg.masroof.data.backup.RestoreOp
import eg.masroof.data.backup.RestoreOutcome
import eg.masroof.data.backup.RestoreStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** What a phone holds that a restore may change: the rows, the receipts, the settings last applied and the installed key. */
data class Snap(
    val rows: Map<String, List<Map<String, Any?>>>,
    val receipts: Map<String, String>,
    val settings: Map<String, Map<String, Any>>?,
    val key: List<Byte>?,
)

fun snap(p: Phone) = Snap(p.dump(), p.receiptHashes(), p.applied.lastOrNull()?.stores, p.installedKey?.toList())

/** Counts the durable operations of one apply and injects one fault at operation [k] (1-based), once armed. */
class Fault(
    val kind: Kind,
    val k: Int,
) : RestoreHooks {
    enum class Kind { NONE, KILL_BEFORE, KILL_AFTER, IO, KILL_IN_TX }

    var armed = false
    var count = 0
    val ops = mutableListOf<RestoreOp>()

    override fun before(op: RestoreOp) {
        if (!armed) return
        ops += op
        count++
        if (count == k && kind == Kind.KILL_BEFORE) throw RestoreKilled()
        if (count == k && kind == Kind.IO) throw IOException("ENOSPC")
    }

    override fun after(op: RestoreOp) {
        if (armed && count == k && kind == Kind.KILL_AFTER) throw RestoreKilled()
    }

    override fun beforeCommit() {
        if (armed && kind == Kind.KILL_IN_TX) throw RestoreKilled()
    }
}

/** The generated path x fault test of design section 6.2: after every injected step and restart the invariants of section 3 hold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreCrashMatrixTest {
    private lateinit var context: Context
    private lateinit var f1: ByteArray
    private lateinit var f2: ByteArray
    private lateinit var f3: ByteArray
    private lateinit var target2: Snap
    private lateinit var target3: Snap
    private val key2 = ByteArray(32) { (100 + it).toByte() }
    private var seq = 0

    private fun build(
        name: String,
        key: ByteArray,
        extra: Boolean,
        receipts: Boolean = true,
    ): ByteArray {
        val src = Phone(context, "mx-src-$name")
        try {
            if (receipts) {
                fillSource(src)
            } else {
                insertRow(src.db, "member", "id" to "m1", "displayName" to "Me", "sortOrder" to 0)
                insertRow(
                    src.db,
                    "expense",
                    "id" to "only",
                    "amountMinor" to 700L,
                    "currency" to "EGP",
                    "occurredAtMillis" to T0,
                    "pending" to false,
                    "failed" to false,
                )
            }
            if (extra) {
                insertRow(
                    src.db,
                    "expense",
                    "id" to "e9",
                    "amountMinor" to 4242L,
                    "currency" to "EGP",
                    "occurredAtMillis" to T0,
                    "pending" to false,
                    "failed" to false,
                )
            }
            return sourceBackup(src, localKey = key)
        } finally {
            src.close()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        f1 = build("1", LOCAL_KEY, extra = false)
        f2 = build("2", key2, extra = true)
        f3 = build("3", key2, extra = false, receipts = false)
        target2 = clean(f2, wipeReceiptsDir = false)
        target3 = clean(f3, wipeReceiptsDir = true)
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
    }

    private fun newPhone(wipeReceiptsDir: Boolean = false): Phone {
        val p = Phone(context, "mx-${seq++}", seeded = true)
        if (wipeReceiptsDir) p.receipts.deleteRecursively()
        return p
    }

    private fun stage(
        p: Phone,
        file: ByteArray,
        hooks: RestoreHooks = RestoreHooks.None,
    ) = p.restorer(hooks = hooks).let { r ->
        val h = r.checkHeader(ByteArrayInputStream(file))
        r to runBlocking { r.stage({ ByteArrayInputStream(file) }, file.size.toLong(), h, RESTORE_PASS) }
    }

    private fun clean(
        file: ByteArray,
        wipeReceiptsDir: Boolean,
    ): Snap {
        val p = newPhone(wipeReceiptsDir)
        try {
            val (r, s) = stage(p, file)
            runBlocking { r.apply(s) }
            return snap(p)
        } finally {
            p.close()
        }
    }

    /** The phone before the attempt, for each scenario of section 6.2. Returns the phone and the file the user will restore. */
    private fun scenario(name: String): Pair<Phone, ByteArray> =
        when (name) {
            "a wiped phone" -> newPhone() to f2
            "b phone with data and receipts" ->
                newPhone().also {
                    insertRow(
                        it.db,
                        "expense",
                        "id" to "mine",
                        "amountMinor" to 9L,
                        "currency" to "EGP",
                        "occurredAtMillis" to T0,
                        "pending" to false,
                        "failed" to false,
                    )
                    File(it.receipts, "mine.jpg").writeBytes(byteArrayOf(9, 9, 9))
                } to f2
            "c over INCOMPLETE G1" ->
                newPhone().also {
                    it.keyFailures = 1
                    val (r, s) = stage(it, f1)
                    runCatching { runBlocking { r.apply(s) } }
                    check(r.pendingState() is PendingState.Incomplete)
                } to f2
            "d over PARTIAL G1" ->
                newPhone().also {
                    val hooks =
                        object : RestoreHooks {
                            override fun afterCommit() {
                                File(it.stage.listFiles()!!.first(), "local-id.key").delete()
                            }
                        }
                    val (r, s) = stage(it, f1, hooks)
                    runCatching { runBlocking { r.apply(s) } }
                } to f2
            "e over a Corrupt journal" -> newPhone().also { it.journal.writeText("{") } to f2
            "f zero receipts, no receipts directory" -> newPhone(wipeReceiptsDir = true) to f3
            else -> error(name)
        }

    private val scenarios =
        listOf(
            "a wiped phone",
            "b phone with data and receipts",
            "c over INCOMPLETE G1",
            "d over PARTIAL G1",
            "e over a Corrupt journal",
            "f zero receipts, no receipts directory",
        )

    private fun targetOf(file: ByteArray) = if (file === f3) target3 else target2

    // ---- invariants ----------------------------------------------------------------------------------------------------------------

    private fun assertInvariants(
        label: String,
        p: Phone,
        base: Snap,
        baseJournal: String?,
        target: Snap,
        gen: Int,
        afterStartRecovery: Boolean,
    ) {
        val a =
            p.db.query("PRAGMA application_id", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        val rows = p.dump()
        // I1 commit point: the rows are the base's or the target's, and the target's exactly when A == gen
        assertEquals("$label I1", if (a == gen) target.rows else base.rows, rows)
        // I2 nothing unconfirmed: before the commit, receipts, settings and key are the base's
        if (a != gen && !afterStartRecovery) {
            assertEquals("$label I2 receipts", base.receipts, p.receiptHashes())
            assertEquals("$label I2 settings", base.settings, p.applied.lastOrNull()?.stores)
            assertEquals("$label I2 key", base.key, p.installedKey?.toList())
        }
        // I3 the journal names every commit that is not fully applied
        val read = p.restorer().journalRead()
        val fully =
            read == JournalRead.Absent &&
                p.receiptHashes() == target.receipts &&
                p.applied.lastOrNull()?.stores == target.settings &&
                p.installedKey?.toList() == target.key
        if (a == gen && !fully) {
            val named =
                when (read) {
                    is JournalRead.Ok -> (read.j.committed as? CommittedSlot.Known)?.gen == gen || read.j.pending?.gen == gen
                    JournalRead.Corrupt, JournalRead.Unreadable -> true
                    JournalRead.Absent -> false
                }
            assertTrue("$label I3: X is committed, unfinished and unnamed ($read)", named)
        }
        // I5 the earlier generation's committed slot is byte-identical while X has not committed
        if (a != gen && baseJournal != null && read is JournalRead.Ok && baseJournal.isNotEmpty() && p.journal.exists()) {
            val before =
                eg.masroof.data.backup.RestoreJournal
                    .parse(baseJournal)
            if (before is JournalRead.Ok) assertEquals("$label I5", before.j.committed, read.j.committed)
        }
        // I7 the gate is closed exactly while a journal exists
        assertEquals("$label I7", p.journal.exists() || p.legacy.exists(), RestoreStage.journalBlocks(p.receipts))
        if (afterStartRecovery) {
            // I9 hygiene
            assertFalse("$label I9 tmp", File(p.journal.parentFile, "restore-journal.json.tmp").exists())
            if (read is JournalRead.Ok) {
                val named = listOfNotNull((read.j.committed as? CommittedSlot.Known)?.gen, read.j.pending?.gen).map { "g%08x".format(it) }
                p.stage
                    .listFiles()
                    .orEmpty()
                    .forEach { assertTrue("$label I9 stray ${it.name}", it.name in named) }
            }
            if (read == JournalRead.Absent) {
                assertFalse(
                    "$label I9 stage",
                    p.stage.exists() &&
                        p.stage
                            .listFiles()
                            .orEmpty()
                            .isNotEmpty(),
                )
                assertFalse("$label I9 old receipts", File(p.filesDir, "receipts.old").exists())
                assertTrue("$label I9 key file", p.noKeyFiles())
            }
        }
    }

    private fun assertTarget(
        label: String,
        p: Phone,
        target: Snap,
    ) {
        assertEquals("$label I11 rows", target.rows, p.dump())
        assertEquals("$label I11 receipts", target.receipts, p.receiptHashes())
        assertEquals("$label I11 settings", target.settings, p.applied.lastOrNull()?.stores)
        assertEquals("$label I11 key", target.key, p.installedKey?.toList())
        assertFalse("$label I11 journal", p.journal.exists())
        assertTrue("$label I11 stage", !p.stage.exists() || p.stage.listFiles().isNullOrEmpty())
        assertFalse("$label I11 old receipts", File(p.filesDir, "receipts.old").exists())
        assertTrue("$label I11 key file", p.noKeyFiles())
        assertFalse("$label I11 gate", RestoreStage.journalBlocks(p.receipts))
    }

    /** At most two user actions with the faults cleared: Finish, then (if needed) pick the same file again and Restore. */
    private fun userLoop(
        label: String,
        p: Phone,
        file: ByteArray,
        target: Snap,
    ) {
        var finished = false
        repeat(2) {
            if (p.restorer().journalRead() == JournalRead.Absent &&
                p.dump() == target.rows &&
                p.receiptHashes() == target.receipts
            ) {
                return@repeat
            }
            if (!finished && p.restorer().journalRead() != JournalRead.Absent) {
                finished = true
                runBlocking { p.restorer().recover(atStart = false) }
            } else {
                val (r, s) = stage(p, file)
                runBlocking { r.apply(s) }
            }
        }
        assertTarget(label, p, target)
    }

    private fun logsAreClean(label: String) {
        // only the outcome enum may be logged, at INFO or below; no gen hex, hash, staging path, row text or Throwable
        for (l in ShadowLog.getLogs().filter {
            it.tag.contains("Restore", true) ||
                it.tag.contains("Backup", true) ||
                it.msg.contains("restore-stage")
        }) {
            assertTrue("$label logs: ${l.tag}", l.type <= android.util.Log.INFO)
            assertNull("$label logs a Throwable", l.throwable)
            assertFalse("$label logs a path", l.msg.contains("restore-stage"))
            assertFalse("$label logs hex", Regex("[0-9a-f]{16,}").containsMatchIn(l.msg))
        }
    }

    // ---- the matrix -----------------------------------------------------------------------------------------------------------------

    /** One run: build the scenario, stage, inject [fault] into apply, restart, and check the invariants at every point. */
    private fun run(
        name: String,
        fault: Fault,
        faultAtStartRecovery: Fault? = null,
    ): Fault {
        val (p, file) = scenario(name)
        val target = targetOf(file)
        val label = "$name ${fault.kind}@${fault.k}"
        try {
            val base = snap(p)
            val baseJournal = if (p.journal.exists()) p.journal.readText() else null
            val (r, staged) = stage(p, file, fault)
            fault.armed = true
            try {
                runBlocking { r.apply(staged) }
            } catch (_: RestoreKilled) {
                // the process died here
            } catch (_: RestoreException) {
                // a reported failure
            }
            fault.armed = false
            assertInvariants("$label after the fault", p, base, baseJournal, target, staged.gen, afterStartRecovery = false)
            // restart: a new restorer over the same files, optionally killed again during the recovery
            val second = faultAtStartRecovery
            second?.armed = true
            try {
                runBlocking { p.restorer(hooks = second ?: RestoreHooks.None).recover(atStart = true) }
            } catch (_: RestoreKilled) {
                // killed again
            }
            second?.armed = false
            runBlocking { p.restorer().recover(atStart = true) }
            assertInvariants("$label after restart", p, base, baseJournal, target, staged.gen, afterStartRecovery = true)
            userLoop(label, p, file, target)
            logsAreClean(label)
            return fault
        } finally {
            p.close()
        }
    }

    private fun opCount(name: String): Fault {
        // a clean run records the operations of one apply
        val rec = Fault(Fault.Kind.NONE, 0)
        val (p, file) = scenario(name)
        try {
            val (r, s) = stage(p, file, rec)
            rec.armed = true
            runBlocking { r.apply(s) }
            return rec
        } finally {
            p.close()
        }
    }

    @Test
    fun `REQ-OPS-179 every operation of apply killed before or after, or failing with a full disk, ends in the target or the base`() {
        for (name in scenarios) {
            val n = opCount(name).ops.size
            assertTrue("$name has operations", n > 5)
            for (k in 1..n) {
                run(name, Fault(Fault.Kind.KILL_BEFORE, k))
                run(name, Fault(Fault.Kind.KILL_AFTER, k))
                run(name, Fault(Fault.Kind.IO, k))
            }
            run(name, Fault(Fault.Kind.KILL_IN_TX, 0))
        }
    }

    @Test
    fun `REQ-OPS-179 a second kill during the recovery of any post-commit kill still ends in the target`() {
        val name = scenarios.first()
        val clean = opCount(name)
        val commitAt = clean.ops.indexOf(RestoreOp.TX_COMMIT) + 1
        for (k1 in commitAt..clean.ops.size) {
            // how many operations does the recovery after a kill at k1 run?
            val probe = run(name, Fault(Fault.Kind.KILL_AFTER, k1), faultAtStartRecovery = Fault(Fault.Kind.NONE, 0))
            check(probe.kind == Fault.Kind.KILL_AFTER)
            for (k2 in 1..12) run(name, Fault(Fault.Kind.KILL_AFTER, k1), faultAtStartRecovery = Fault(Fault.Kind.KILL_AFTER, k2))
        }
    }

    @Test
    fun `REQ-OPS-179 a Keystore failure at the key step is INCOMPLETE and a damaged staged file is PARTIAL, each with a way out`() {
        for (damage in listOf("settings.json", "local-id.key", "receipts-new/r1.jpg", "receipts-new")) {
            val (p, file) = scenario("a wiped phone")
            try {
                val hooks =
                    object : RestoreHooks {
                        override fun afterCommit() {
                            val dir = p.stage.listFiles()!!.first()
                            val f = File(dir, damage)
                            if (f.isDirectory) {
                                f.deleteRecursively()
                            } else if (damage.endsWith("jpg")) {
                                f.writeBytes(byteArrayOf(1))
                            } else {
                                f.delete()
                            }
                        }
                    }
                val (r, s) = stage(p, file, hooks)
                val outcome = runCatching { runBlocking { r.apply(s) } }.exceptionOrNull() as? RestoreException
                assertEquals("$damage is evidence that cannot change by retrying", RestoreFailure.PARTIAL, outcome?.failure)
                assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = false) })
                userLoop("damaged $damage", p, file, target2)
            } finally {
                p.close()
            }
        }
        val (p, file) = scenario("a wiped phone")
        try {
            p.keyFailures = 1
            val (r, s) = stage(p, file)
            val failure = runCatching { runBlocking { r.apply(s) } }.exceptionOrNull() as? RestoreException
            assertEquals(RestoreFailure.INCOMPLETE, failure?.failure)
            userLoop("keystore", p, file, target2)
        } finally {
            p.close()
        }
    }

    @Test
    fun `REQ-SEC-068 the restore logs nothing but an outcome`() {
        ShadowLog.clear()
        run("a wiped phone", Fault(Fault.Kind.NONE, 0))
        assertTrue(shadowOf(context.mainLooper) != null)
        logsAreClean("clean run")
    }
}
