package eg.masroof.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eg.masroof.MemoryGate
import eg.masroof.MemoryNotifications
import eg.masroof.MemorySettings
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.CommittedSlot
import eg.masroof.data.backup.JournalRead
import eg.masroof.data.backup.PendingState
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreGate
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreKilled
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestoreOp
import eg.masroof.data.backup.RestoreOutcome
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreStage
import eg.masroof.data.backup.RestoreState
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** The named regressions R1 to R14 of the design (section 6.4) and the stage-3 security amendments, each as a test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreRegressionTest {
    private lateinit var context: Context
    private lateinit var f1: ByteArray
    private lateinit var f2: ByteArray
    private lateinit var p: Phone
    private val uri: Uri = Uri.parse("content://docs.example/document/backup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key2 = ByteArray(32) { (100 + it).toByte() }
    private var picked: ByteArray = ByteArray(0)
    private var busy = false

    @Volatile private var hold: java.util.concurrent.CountDownLatch? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        f1 = backupOf("r1", LOCAL_KEY, extra = false)
        f2 = backupOf("r2", key2, extra = true)
        p = Phone(context, "reg", seeded = true)
        picked = f1
    }

    @After
    fun tearDown() {
        eg.masroof.data.backup.RestoreActivity
            .end()
        scope.cancel()
        p.close()
    }

    private fun backupOf(
        name: String,
        key: ByteArray,
        extra: Boolean,
    ): ByteArray {
        val src = Phone(context, "reg-src-$name")
        try {
            fillSource(src)
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

    private fun stage(
        file: ByteArray,
        hooks: RestoreHooks = RestoreHooks.None,
        limits: RestoreLimits = FAST,
        phone: Phone = p,
    ): Pair<BackupRestorer, eg.masroof.data.backup.StagedRestore> =
        phone.restorer(limits = limits, hooks = hooks).let { r ->
            val h = r.checkHeader(ByteArrayInputStream(file))
            r to runBlocking { r.stage({ ByteArrayInputStream(file) }, file.size.toLong(), h, RESTORE_PASS) }
        }

    private fun applyOutcome(
        r: BackupRestorer,
        s: eg.masroof.data.backup.StagedRestore,
    ): RestoreFailure? = (runCatching { runBlocking { r.apply(s) } }.exceptionOrNull() as? RestoreException)?.failure

    private fun stageDirs(): List<File> =
        p.stage
            .listFiles()
            .orEmpty()
            .toList()

    private fun coordinator(blockers: RestoreBlockers = RestoreBlockers { busy }) =
        RestoreCoordinator(
            p.restorer(),
            EraseLock(),
            blockers,
            object : RestoreSources {
                override fun open(uri: Uri): InputStream {
                    hold?.await()
                    return ByteArrayInputStream(picked)
                }

                override fun size(uri: Uri): Long = picked.size.toLong()
            },
            scope,
            Dispatchers.IO,
        )

    private fun RestoreCoordinator.await(match: (RestoreState) -> Boolean): RestoreState =
        runBlocking { withTimeout(20_000) { state.first(match) } }

    private fun RestoreCoordinator.toPassphrase() {
        onPicked(uri)
        await { it == RestoreState.NeedsPassphrase }
    }

    private fun RestoreCoordinator.toConfirm(pass: String = RESTORE_PASS) {
        toPassphrase()
        onPassphrase(pass)
        await { it is RestoreState.Confirm }
    }

    private fun incompleteG1() {
        p.keyFailures = 1
        val (r, s) = stage(f1)
        assertEquals(RestoreFailure.INCOMPLETE, applyOutcome(r, s))
    }

    // ---- R1 to R5 --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 R1 a Keystore failure keeps staging and journal at SETTINGS_DONE and the next start installs the key`() {
        incompleteG1()
        val read = p.restorer().journalRead() as JournalRead.Ok
        assertEquals(eg.masroof.data.backup.Step.SETTINGS_DONE, (read.j.committed as CommittedSlot.Known).step)
        assertEquals(1, stageDirs().size)
        assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = true) })
        assertArrayEquals(LOCAL_KEY, p.installedKey)
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), p.receiptHashes())
        assertFalse(p.journal.exists())
    }

    @Test
    fun `REQ-OPS-179 R2 an unconfirmed second staging is swept at start and the first restore finishes from its own staging`() {
        incompleteG1()
        val g1Dir = stageDirs().single()
        stage(f2) // G2 staged, never confirmed; then the process dies
        assertEquals(2, stageDirs().size)
        assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = true) })
        assertFalse("S_G2 is swept", stageDirs().isNotEmpty())
        assertFalse(g1Dir.exists())
        assertArrayEquals("no byte of G2 was applied", LOCAL_KEY, p.installedKey)
        assertTrue(p.dump().getValue("expense").none { it["id"] == "e9" })
    }

    @Test
    fun `REQ-OPS-179 R3 settings edited after the commit are PARTIAL, nothing is applied and a re-pick completes`() {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() {
                    File(stageDirs().single(), "settings.json").writeText("{\"formatVersion\":1,\"stores\":{}}")
                }
            }
        val (r, s) = stage(f1, hooks)
        assertEquals(RestoreFailure.PARTIAL, applyOutcome(r, s))
        assertTrue(p.applied.isEmpty())
        assertNull(p.installedKey)
        val (r2, s2) = stage(f1)
        assertNull(applyOutcome(r2, s2))
        assertArrayEquals(LOCAL_KEY, p.installedKey)
        assertFalse(p.journal.exists())
    }

    @Test
    fun `REQ-SEC-068 R4 every staged file edited between Staged and Restore is refused and no validated json ever exists`() {
        for (victim in listOf("settings.json", "local-id.key", "receipts-new/r1.jpg", "table-expense.jsonl")) {
            val rows = p.dump()
            val files = p.receiptHashes()
            val (r, s) = stage(f1)
            assertTrue(p.filesDir.walkTopDown().none { it.name == "validated.json" })
            val f = File(s.dir, victim)
            f.writeBytes(f.readBytes() + byteArrayOf(32))
            assertEquals("$victim", RestoreFailure.FAILED, applyOutcome(r, s))
            p.assertUntouched(rows, files)
        }
    }

    @Test
    fun `REQ-OPS-179 R5 a key file deleted after the commit is PARTIAL on the first recovery, not INCOMPLETE`() {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() {
                    File(stageDirs().single(), "local-id.key").delete()
                }
            }
        val (r, s) = stage(f1, hooks)
        assertEquals(RestoreFailure.PARTIAL, applyOutcome(r, s))
        assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = false) })
        val (r2, s2) = stage(f1)
        assertNull(applyOutcome(r2, s2))
        assertArrayEquals(LOCAL_KEY, p.installedKey)
    }

    // ---- R6 to R8 --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 R6 a re-restore over INCOMPLETE killed after the pending write or in the transaction leaves G1 intact`() {
        for (kill in listOf("after pending write", "in beforeCommit")) {
            p.close()
            p = Phone(context, "reg6", seeded = true)
            incompleteG1()
            val before = p.journal.readText()
            val journalBefore = (p.restorer().journalRead() as JournalRead.Ok).j.committed as CommittedSlot.Known
            val hooks =
                object : RestoreHooks {
                    var writes = 0

                    override fun after(op: RestoreOp) {
                        if (kill == "after pending write" && op == RestoreOp.JOURNAL_WRITE && ++writes == 1) throw RestoreKilled()
                    }

                    override fun beforeCommit() {
                        if (kill == "in beforeCommit") throw RestoreKilled()
                    }
                }
            val (r, s) = stage(f2, hooks)
            try {
                runBlocking { r.apply(s) }
                fail("killed")
            } catch (_: RestoreKilled) {
            }
            val after = p.restorer().journalRead() as JournalRead.Ok
            assertEquals("$kill: G1's slot is unchanged", journalBefore, after.j.committed)
            assertTrue("$kill: S_G1 is intact", File(p.stage, "g%08x".format(journalBefore.gen)).isDirectory)
            assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = true) })
            assertArrayEquals("$kill: G1's key", LOCAL_KEY, p.installedKey)
            assertEquals(1, p.applied.size)
            assertTrue(stageDirs().isEmpty())
            assertTrue(before.isNotEmpty())
        }
    }

    @Test
    fun `REQ-OPS-179 R7 a corrupt journal is PARTIAL with everything kept, an unreadable one is INCOMPLETE with nothing deleted`() {
        val (r, s) = stage(f1)
        p.journal.writeText("{")
        assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = true) })
        assertTrue("journal kept", p.journal.exists())
        assertTrue("staging kept", File(p.stage, "g%08x".format(s.gen)).isDirectory)
        assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = false) })
        // a re-pick completes it and removes the corrupt journal and the older staging
        val (r2, s2) = stage(f1)
        assertNull(applyOutcome(r2, s2))
        assertFalse(p.journal.exists())
        assertTrue(stageDirs().isEmpty())
        assertTrue(r.pendingState() is PendingState.None)
        // an IOException on read (the journal is a directory)
        p.journal.mkdir()
        File(p.journal, "x").writeText("x")
        assertEquals(RestoreOutcome.INCOMPLETE, runBlocking { p.restorer().recover(atStart = true) })
        assertTrue("nothing deleted", File(p.journal, "x").exists())
    }

    @Test
    fun `REQ-OPS-179 R7b an unreadable journal at Confirm refuses INCOMPLETE and writes nothing`() {
        val (r, s) = stage(f1)
        p.journal.mkdir()
        File(p.journal, "x").writeText("x")
        val rows = p.dump()
        assertEquals(RestoreFailure.INCOMPLETE, applyOutcome(r, s))
        assertTrue(p.journal.isDirectory)
        assertTrue(File(p.journal, "x").exists())
        assertEquals(rows, p.dump())
        assertTrue(p.applied.isEmpty())
    }

    @Test
    fun `REQ-OPS-179 R8 a pick, a wrong passphrase, a failed check or a Cancel never change an unfinished restore`() {
        incompleteG1()
        val bytes = p.journal.readBytes()
        val g1Files =
            p.stage
                .walkTopDown()
                .filter { it.isFile }
                .associate { it.path to it.readBytes().toList() }
        val c = coordinator()

        fun assertUntouchedJournal(label: String) {
            assertArrayEquals("$label: journal", bytes, p.journal.readBytes())
            val now =
                p.stage
                    .walkTopDown()
                    .filter { it.isFile && it.name != "x" }
                    .associate { it.path to it.readBytes().toList() }
            assertTrue("$label: S_G1", g1Files.all { (k, v) -> now[k] == v })
        }
        // (i) wrong passphrase
        c.toPassphrase()
        c.onPassphrase("not the passphrase")
        c.await { it is RestoreState.Failed }
        assertUntouchedJournal("wrong passphrase")
        c.onDismiss()
        // (ii) Cancel at the passphrase
        c.toPassphrase()
        c.onCancel()
        c.await { it == RestoreState.Idle }
        assertUntouchedJournal("cancel at passphrase")
        // (iii) a failed check 6
        picked =
            repack(f1) { n, b -> if (n == "data/expenses.jsonl") b.replaceText("\"amountMinor\":12500", "\"amountMinor\":12.5") else b }
        c.toPassphrase()
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Failed }
        assertUntouchedJournal("failed check")
        c.onDismiss()
        // (iv) Cancel at the confirmation
        picked = f2
        c.toConfirm()
        c.onCancel()
        c.await { it == RestoreState.Idle }
        runBlocking { withTimeout(20_000) { while (stageDirs().size > 1) kotlinx.coroutines.yield() } }
        assertUntouchedJournal("cancel at confirmation")
        // the next Finish completes G1
        assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = false) })
        assertArrayEquals(LOCAL_KEY, p.installedKey)
    }

    // ---- R9 ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 R9 synchronous is FULL in the transaction and restored, and a kill after the commit recovers by the journal`() {
        var inside = -1
        val hooks =
            object : RestoreHooks {
                override fun beforeCommit() {
                    inside =
                        p.db.query("PRAGMA synchronous", null).use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                }
            }
        val before =
            p.db.query("PRAGMA synchronous", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        val (r, s) = stage(f1, hooks)
        assertNull(applyOutcome(r, s))
        assertEquals("synchronous = FULL inside the transaction", 2, inside)
        assertEquals(
            "restored afterwards",
            before,
            p.db.query("PRAGMA synchronous", null).use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        // a kill between the commit and the promotion: the journal still says pending = X and A == X (decide row 5)
        p.close()
        p = Phone(context, "reg9", seeded = true)
        val kill =
            object : RestoreHooks {
                override fun after(op: RestoreOp) {
                    if (op == RestoreOp.TX_COMMIT) throw RestoreKilled()
                }
            }
        val (r2, s2) = stage(f1, kill)
        try {
            runBlocking { r2.apply(s2) }
            fail("killed")
        } catch (_: RestoreKilled) {
        }
        val j = (p.restorer().journalRead() as JournalRead.Ok).j
        assertEquals(s2.gen, j.pending?.gen)
        assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = true) })
        assertArrayEquals(LOCAL_KEY, p.installedKey)
        // a transaction that throws is judged by A, never by the exception
        p.close()
        p = Phone(context, "reg9b", seeded = true)
        val throwing =
            object : RestoreHooks {
                override fun beforeCommit() = error("disk error at COMMIT")
            }
        val (r3, s3) = stage(f1, throwing)
        val rows = p.dump()
        assertEquals(RestoreFailure.FAILED, applyOutcome(r3, s3))
        assertEquals(rows, p.dump())
    }

    // ---- R10 to R14 and the amendments ---------------------------------------------------------------------------------------------

    private class CountingKey(
        private val current: () -> ByteArray,
    ) : LocalIdKey {
        val gets = AtomicInteger()

        override fun get(): ByteArray {
            gets.incrementAndGet()
            return current()
        }

        override fun wipe() = Unit
    }

    private val sms =
        IncomingMessage(
            CaptureSource.SMS,
            "CIB",
            "Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/03/2026 14:05",
            1_000L,
        )

    private fun pipeline(
        key: LocalIdKey,
        gate: RestoreGate,
    ) = SmsPipeline(
        p.db,
        MemorySettings(),
        MemoryNotifications(),
        Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC),
        MemoryGate(),
        key,
        restoreGate = gate,
    )

    private val journalGate =
        RestoreGate {
            eg.masroof.data.backup.RestoreActivity
                .isActive() ||
                RestoreStage.journalBlocks(p.receipts)
        }

    @Test
    fun `REQ-OPS-178 R10 an SMS in INCOMPLETE is deferred with no key read, then recorded once with the restored key`() {
        incompleteG1()
        val key = CountingKey { p.installedKey ?: ByteArray(32) { 7 } }
        val pipe = pipeline(key, journalGate)
        assertEquals(Disposition.DEFERRED, runBlocking { pipe.accept(sms) })
        assertEquals("the gate is checked before the key is read", 0, key.gets.get())
        assertEquals("only the restored row", 1, runBlocking { p.db.sources().all() }.size)
        runBlocking { p.restorer().recover(atStart = false) }
        assertEquals(Disposition.RECORDED, runBlocking { pipe.accept(sms) })
        assertEquals(Disposition.RECORDED, runBlocking { pipe.accept(sms) })
        val ids = runBlocking { p.db.sources().all() }.map { it.localSourceId }
        assertTrue(ids.contains(LocalIdHasher.id(LOCAL_KEY, sms)))
        assertEquals("recorded once", 1, ids.count { it == LocalIdHasher.id(LOCAL_KEY, sms) })
    }

    @Test
    fun `REQ-OPS-178 an SMS arriving while Checking or Staged is deferred and recorded once after Done with the restored key`() {
        val key = CountingKey { p.installedKey ?: ByteArray(32) { 7 } }
        val pipe = pipeline(key, journalGate)
        val c = coordinator()
        c.toPassphrase()
        val sms2 = sms.copy(text = sms.text.replace("99.00", "55.00"), receivedAtMillis = 2_000L)
        // Checking: the source is held open until the first SMS was offered
        hold = java.util.concurrent.CountDownLatch(1)
        c.onPassphrase(RESTORE_PASS)
        runBlocking {
            withTimeout(20_000) {
                while (!eg.masroof.data.backup.RestoreActivity
                        .isActive()
                ) {
                    kotlinx.coroutines.yield()
                }
            }
        }
        assertEquals(Disposition.DEFERRED, runBlocking { pipe.accept(sms) }) // Checking
        hold?.countDown()
        c.await { it is RestoreState.Confirm }
        assertEquals(Disposition.DEFERRED, runBlocking { pipe.accept(sms2) }) // Staged
        c.onConfirm()
        c.await { it == RestoreState.Done }
        assertEquals(Disposition.RECORDED, runBlocking { pipe.accept(sms) })
        assertEquals(Disposition.RECORDED, runBlocking { pipe.accept(sms2) })
        val ids = runBlocking { p.db.sources().all() }.map { it.localSourceId }
        assertEquals(1, ids.count { it == LocalIdHasher.id(LOCAL_KEY, sms) })
        assertEquals(1, ids.count { it == LocalIdHasher.id(LOCAL_KEY, sms2) })
    }

    @Test
    fun `REQ-OPS-178 Cancel in Staged opens the gate and the SMS is recorded with the base key`() {
        val base = ByteArray(32) { 7 }
        val key = CountingKey { p.installedKey ?: base }
        val pipe = pipeline(key, journalGate)
        val c = coordinator()
        c.toConfirm()
        assertEquals(Disposition.DEFERRED, runBlocking { pipe.accept(sms) })
        c.onCancel()
        c.await { it == RestoreState.Idle }
        assertEquals(Disposition.RECORDED, runBlocking { pipe.accept(sms) })
        assertEquals(LocalIdHasher.id(base, sms), runBlocking { p.db.sources().all() }.single().localSourceId)
    }

    @Test
    fun `REQ-OPS-178 a backup that starts after Staged makes Confirm return BUSY and changes nothing`() {
        val c = coordinator()
        c.toConfirm()
        busy = true
        val rows = p.dump()
        c.onConfirm()
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.BUSY, failed.failure)
        assertEquals(rows, p.dump())
        assertFalse(p.journal.exists())
    }

    @Test
    fun `REQ-OPS-179 R12 apply over a corrupt journal killed before the commit is still PARTIAL`() {
        p.journal.writeText("{")
        val hooks =
            object : RestoreHooks {
                override fun before(op: RestoreOp) {
                    if (op == RestoreOp.TX_COMMIT) throw RestoreKilled()
                }
            }
        val (r, s) = stage(f2, hooks)
        try {
            runBlocking { r.apply(s) }
            fail("killed")
        } catch (_: RestoreKilled) {
        }
        assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = true) })
        val j = (p.restorer().journalRead() as JournalRead.Ok).j
        assertEquals(CommittedSlot.Unknown, j.committed)
    }

    @Test
    fun `REQ-OPS-179 R13 Erase deletes the journal first, so a kill after it leaves nothing to resume and the gate open`() {
        incompleteG1()
        assertTrue(RestoreStage.deleteJournal(p.receipts)) // the first step of the wipe
        assertFalse(RestoreStage.journalBlocks(p.receipts))
        // killed here: the next start sees an absent journal and sweeps the staging
        assertEquals(RestoreOutcome.DONE, runBlocking { p.restorer().recover(atStart = true) })
        assertTrue(stageDirs().isEmpty())
        assertTrue(p.noKeyFiles())
        assertNull(p.installedKey)
        // and the full sweep works on a corrupt journal too
        p.journal.writeText("{")
        assertTrue(RestoreStage.sweep(p.receipts))
        assertFalse(p.journal.exists())
    }

    @Test
    fun `REQ-OPS-179 keeping the restored data as it is deletes the journal and staging only when the data really is the restore's`() {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() {
                    File(stageDirs().single(), "local-id.key").delete()
                }
            }
        val (r, s) = stage(f1, hooks)
        assertEquals(RestoreFailure.PARTIAL, applyOutcome(r, s))
        assertTrue(p.restorer().keepAsIs())
        assertFalse(p.journal.exists())
        assertTrue(stageDirs().isEmpty())
        assertEquals(
            "the staged receipt that verifies is kept, so the restored row has its file",
            mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)),
            p.receiptHashes(),
        )
        assertNull("the current key is kept, none was installed", p.installedKey)
        assertFalse("not applicable twice", p.restorer().keepAsIs())
        // a journal whose generation does not explain A is refused
        p.journal.writeText("{")
        assertFalse(p.restorer().keepAsIs())
    }

    @Test
    fun `REQ-SEC-068 a staged receipt directory behind a symbolic link is PARTIAL and is deleted without following the link`() {
        org.junit.Assume.assumeTrue("symbolic links need Linux (CI)", System.getProperty("os.name").orEmpty().contains("Linux"))
        incompleteG1()
        val dir = stageDirs().single()
        val outside = File(context.cacheDir, "outside-" + System.nanoTime()).also { it.mkdirs() }
        val victim = File(outside, "keep.txt").also { it.writeText("keep") }
        val link = File(dir, "receipts-new/link")
        java.nio.file.Files
            .createSymbolicLink(link.toPath(), victim.toPath())
        assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = false) })
        eg.masroof.data.backup
            .RestoreDisk(RestoreHooks.None)
            .deleteTree(dir)
        assertTrue("the link target is untouched", victim.exists())
    }

    @Test
    fun `REQ-SEC-068 a table line grown to 100 MiB after the checks is refused while streaming with nothing changed`() {
        val rows = p.dump()
        val files = p.receiptHashes()
        val (r, s) = stage(f1)
        val table = File(s.dir, "table-member.jsonl")
        java.io.FileOutputStream(table, true).use { out ->
            val chunk = ByteArray(1 shl 20) { 'x'.code.toByte() }
            repeat(100) { out.write(chunk) } // one 100 MiB line with no newline, written as a stream
        }
        assertEquals(RestoreFailure.FAILED, applyOutcome(r, s))
        p.assertUntouched(rows, files)
    }

    @Test
    fun `REQ-SEC-068 a ZIP with more entries than the cap is refused while streaming`() {
        val many = (1..30).map { "receipts/r$it.jpg" to ByteArray(4) }
        val crafted = encrypt(zipOf(many))
        expectRefused(p, crafted, RestoreFailure.INVALID_CONTENT, 4, limits = RestoreLimits(minMemoryKiB = 1, maxEntries = 10))
    }

    @Test
    fun `REQ-OPS-179 after three failed attempts at the key step the restore can be completed with the current key`() {
        p.keyFailures = 100
        val (r, s) = stage(f1)
        assertEquals(RestoreFailure.INCOMPLETE, applyOutcome(r, s))
        assertFalse("one failed attempt is not enough", p.restorer().keepCurrentKey())
        runBlocking { p.restorer().recover(atStart = false) }
        assertFalse("two failed attempts are not enough", p.restorer().keepCurrentKey())
        runBlocking { p.restorer().recover(atStart = false) }
        val state = p.restorer().pendingState() as PendingState.Incomplete
        assertTrue(state.atKeyStep)
        assertTrue(state.attempts >= 3)
        assertTrue(p.restorer().keepCurrentKey())
        assertFalse(p.journal.exists())
        assertTrue(stageDirs().isEmpty())
        assertFalse("the gate is open", RestoreStage.journalBlocks(p.receipts))
        assertTrue("the settings step was applied", p.applied.isNotEmpty())
        assertNull("the current key is kept", p.installedKey)
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), p.receiptHashes())
    }

    @Test
    fun `REQ-OPS-179 the keep-as-is exit is offered only when it can act`() {
        p.journal.writeText("{")
        val c = coordinator()
        runBlocking { c.recoverInterrupted()?.join() }
        assertEquals(PendingState.Partial(canKeep = false), c.pending.value)
        assertFalse(p.restorer().keepAsIs())
    }

    @Test
    fun `REQ-OPS-179 Erase all data resets the banner and the session of an unfinished restore`() {
        incompleteG1()
        val c = coordinator()
        assertTrue(c.pending.value is PendingState.Incomplete)
        c.onErased()
        assertEquals(PendingState.None, c.pending.value)
        assertEquals(RestoreState.Idle, c.state.value)
    }

    @Test
    fun `REQ-OPS-179 Cancel during Checking never publishes Confirm and leaves the gate open`() {
        val c = coordinator()
        c.toPassphrase()
        hold = java.util.concurrent.CountDownLatch(1)
        c.onPassphrase(RESTORE_PASS)
        runBlocking {
            withTimeout(20_000) {
                while (!eg.masroof.data.backup.RestoreActivity
                        .isActive()
                ) {
                    kotlinx.coroutines.yield()
                }
            }
        }
        c.onCancel()
        assertFalse(
            "the flag is released by the Cancel",
            eg.masroof.data.backup.RestoreActivity
                .isActive(),
        )
        hold?.countDown()
        runBlocking { withTimeout(20_000) { while (stageDirs().isNotEmpty()) kotlinx.coroutines.yield() } }
        assertEquals(RestoreState.Idle, c.state.value)
        assertFalse(
            eg.masroof.data.backup.RestoreActivity
                .isActive(),
        )
        val key = CountingKey { ByteArray(32) { 7 } }
        assertEquals(Disposition.RECORDED, runBlocking { pipeline(key, journalGate).accept(sms) })
    }

    @Test
    fun `REQ-OPS-179 a failing directory fsync after the journal was deleted still reports Done`() {
        val hooks =
            object : RestoreHooks {
                var afterDelete = false

                override fun after(op: RestoreOp) {
                    if (op == RestoreOp.JOURNAL_DELETE) afterDelete = true
                }

                override fun before(op: RestoreOp) {
                    if (afterDelete && op == RestoreOp.FSYNC_DIR) throw java.io.IOException("EIO")
                }
            }
        val (r, s) = stage(f1, hooks)
        assertNull(applyOutcome(r, s))
        assertFalse(p.journal.exists())
        assertArrayEquals(LOCAL_KEY, p.installedKey)
    }

    @Test
    fun `REQ-SEC-068 the Erase sweep never follows a directory link and zeroes the key before the folder goes`() {
        org.junit.Assume.assumeTrue("links need Linux (CI)", System.getProperty("os.name").orEmpty().contains("Linux"))
        val outside = File(context.cacheDir, "sweep-outside-" + System.nanoTime()).also { it.mkdirs() }
        val victim = File(outside, "keep.txt").also { it.writeText("keep") }
        val keyCopy = File(outside, "key-copy")
        val g = File(p.stage, "g00000001").also { it.mkdirs() }
        File(g, "local-id.key").writeBytes(ByteArray(32) { 9 })
        java.nio.file.Files
            .createLink(keyCopy.toPath(), File(g, "local-id.key").toPath())
        java.nio.file.Files
            .createSymbolicLink(File(p.stage, "link").toPath(), outside.toPath())
        assertTrue(RestoreStage.sweep(p.receipts))
        assertTrue("the link target is untouched", victim.exists())
        assertTrue("the key was zeroed before its folder was deleted", keyCopy.readBytes().all { it.toInt() == 0 })
        assertFalse(p.stage.exists())
    }
}
