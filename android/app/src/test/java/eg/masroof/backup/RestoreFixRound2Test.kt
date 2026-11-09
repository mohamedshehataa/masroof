package eg.masroof.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import eg.masroof.MemoryGate
import eg.masroof.MemoryNotifications
import eg.masroof.MemorySettings
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.BackupEnvironmentFactory
import eg.masroof.data.backup.BackupFailure
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.BackupStatusStore
import eg.masroof.data.backup.BackupWorker
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PendingBackupKey
import eg.masroof.data.backup.RestoreActivity
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestorePending
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreStage
import eg.masroof.data.backup.RestoreState
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.Disposition
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
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** STORY-184 fix round 2: the loop-2 findings, closed as classes. Each test failed before its fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreFixRound2Test {
    private lateinit var context: Context
    private lateinit var source: Phone
    private lateinit var target: Phone
    private lateinit var file: ByteArray
    private val uri: Uri = Uri.parse("content://docs.example/document/backup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cairo = ZoneId.of("Africa/Cairo")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = Phone(context, "g2source")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "g2target", seeded = true)
        File(target.receipts, "old.bin").writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        RestoreActivity.end()
        RestoreStage.marker(File(context.filesDir, "receipts")).delete()
        scope.cancel()
        source.close()
        target.close()
    }

    private fun coordinator(restorer: BackupRestorer = target.restorer()) =
        RestoreCoordinator(
            restorer,
            EraseLock(),
            RestoreBlockers { false },
            object : RestoreSources {
                override fun open(uri: Uri): InputStream = ByteArrayInputStream(file)

                override fun size(uri: Uri): Long = file.size.toLong()
            },
            scope,
            Dispatchers.IO,
        )

    private fun RestoreCoordinator.await(match: (RestoreState) -> Boolean): RestoreState =
        runBlocking { withTimeout(20_000) { state.first(match) } }

    private fun RestoreCoordinator.toConfirm() {
        onPicked(uri)
        await { it == RestoreState.NeedsPassphrase }
        onPassphrase(RESTORE_PASS)
        await { it is RestoreState.Confirm }
    }

    private fun stage(restorer: BackupRestorer) =
        restorer.checkHeader(ByteArrayInputStream(file)).let { h ->
            runBlocking { restorer.stage({ ByteArrayInputStream(file) }, file.size.toLong(), h, RESTORE_PASS) }
        }

    private fun mutated(
        entry: String,
        from: String,
        to: String,
    ) = repack(file) { name, bytes -> if (name == entry) bytes.replaceText(from, to) else bytes }

    // ---- MAJOR 1: every value the app itself can store -------------------------------------------------------------------------

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atStartOfDay(cairo)
            .plusHours(hour.toLong())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `REQ-OPS-178 every typed date the app's forms accept restores exactly, from year 0 to 9999`() {
        val src = Phone(context, "g2dates")
        try {
            insertRow(src.db, "member", "id" to "m1", "displayName" to "Me", "sortOrder" to 0)
            val first = millis(0, 1, 1)
            val last = millis(9999, 12, 31, 23)
            for ((id, at) in listOf("e0" to first, "e1" to millis(1, 1, 1), "e9" to last, "e2205" to millis(2205, 3, 1))) {
                insertRow(
                    src.db,
                    "expense",
                    "id" to id,
                    "amountMinor" to 100L,
                    "currency" to "EGP",
                    "occurredAtMillis" to at,
                    "pending" to false,
                    "failed" to false,
                )
            }
            insertRow(
                src.db,
                "debt",
                "id" to "d1",
                "name" to "Typo",
                "kind" to "PERSON",
                "direction" to "I_OWE",
                "amountMinor" to 5L,
                "currency" to "EGP",
                "dueAtMillis" to millis(2205, 3, 1),
                "createdAtMillis" to T0,
            )
            insertRow(
                src.db,
                "asset",
                "id" to "as1",
                "kind" to "CERTIFICATE",
                "name" to "Cert",
                "currency" to "EGP",
                "startAtMillis" to first,
                "maturityAtMillis" to last,
                "createdAtMillis" to T0,
                "updatedAtMillis" to T0,
            )
            insertRow(
                src.db,
                "savings_goal",
                "id" to "g1",
                "name" to "Far",
                "targetMinor" to 10L,
                "currency" to "EGP",
                "sortOrder" to 0,
                "targetAtMillis" to first,
                "createdAtMillis" to T0,
            )
            insertRow(
                src.db,
                "installment_plan",
                "id" to "ip1",
                "title" to "Plan",
                "provider" to "valU",
                "currency" to "EGP",
                "downPaymentMinor" to 0L,
                "monthlyAmountMinor" to 100L,
                "paymentCount" to 36,
                "firstDueAtMillis" to millis(2095, 1, 1),
                "reminderDaysBefore" to 2,
                "status" to "ACTIVE",
                "createdAtMillis" to T0,
            )
            insertRow(
                src.db,
                "installment_payment",
                "id" to "ipp1",
                "planId" to "ip1",
                "seq" to 36,
                "dueAtMillis" to millis(2101, 12, 1),
                "amountMinor" to 100L,
            )
            val bytes = sourceBackup(src)
            val restorer = target.restorer()
            val header = restorer.checkHeader(ByteArrayInputStream(bytes))
            val staged = runBlocking { restorer.stage({ ByteArrayInputStream(bytes) }, bytes.size.toLong(), header, RESTORE_PASS) }
            runBlocking { restorer.apply(staged) }
            assertEquals(src.dump(), target.dump())
        } finally {
            src.close()
        }
    }

    @Test
    fun `REQ-SEC-068 an instant beyond what any date form can produce is still refused`() {
        for (v in listOf("-70000000000000", "300000000000000", "9000000000000000")) {
            expectRefused(
                target,
                mutated("data/expenses.jsonl", "\"occurredAtMillis\":1793000000000", "\"occurredAtMillis\":$v"),
                RestoreFailure.INVALID_CONTENT,
                6,
            )
        }
    }

    // ---- MAJOR 2: recovery never crashes ---------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 a Keystore failure during the recovery of a new pick shows a message, never crashes, and the next pick finishes it`() {
        target.keyFailures = 2
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        c.onDismiss()
        c.onPicked(uri) // its recovery fails again (no crash); the pick itself goes on: a file can always be picked again
        c.await { it == RestoreState.NeedsPassphrase }
        assertTrue(target.marker.exists())
        c.onCancel()
        c.await { it == RestoreState.Idle }
        c.onPicked(uri) // the Keystore works again: the recovery finishes the restore
        c.await { it == RestoreState.NeedsPassphrase }
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertFalse(target.marker.exists())
    }

    @Test
    fun `REQ-OPS-179 a recovery sets the restore flag and refuses to run while another restore is active`() {
        target.keyFailures = 1
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        c.onDismiss()
        var active = false
        target.onInstall = { active = RestoreActivity.isActive() }
        assertTrue(RestoreActivity.begin())
        c.onPicked(uri)
        val busy = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.BUSY, busy.failure)
        assertNull("nothing was finished while another restore ran", target.installedKey)
        RestoreActivity.end()
        c.onDismiss()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        assertTrue("the recovery ran with the restore flag set", active)
        assertFalse(RestoreActivity.isActive())
    }

    // ---- MAJOR 3: after the commit there is no going back ----------------------------------------------------------------------

    private fun settingsEditedAfterCommit(edit: () -> Unit) =
        object : RestoreHooks {
            override fun afterCommit() = edit()
        }

    private fun assertPartialThenReRestoreCompletes(hooks: RestoreHooks) {
        val restorer = target.restorer(hooks = hooks)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected PARTIAL")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertTrue("the marker is kept", target.marker.exists())
        assertTrue(target.applied.isEmpty())
        assertNull(target.installedKey)
        // the same file again completes it
        val again = target.restorer()
        val s2 = stage(again)
        runBlocking { again.apply(s2) }
        assertFalse(target.marker.exists())
        assertFalse(target.stage.exists())
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertTrue(target.applied.isNotEmpty())
        assertEquals(source.dump(), target.dump())
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
    }

    @Test
    fun `REQ-OPS-179 settings edited after the commit end in PARTIAL with the marker kept and a re-restore completes`() {
        assertPartialThenReRestoreCompletes(
            settingsEditedAfterCommit { File(target.stage, "settings.json").writeText("{\"formatVersion\":1,\"stores\":{}}") },
        )
    }

    @Test
    fun `REQ-OPS-179 a transient read error after the commit is INCOMPLETE, not broken, and the next start finishes it`() {
        val settings = File(target.stage, "settings.json")
        var saved = ByteArray(0)
        val hooks =
            settingsEditedAfterCommit {
                saved = settings.readBytes()
                settings.delete()
                settings.mkdir() // reading a directory is an IOException
            }
        val restorer = target.restorer(hooks = hooks)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected INCOMPLETE")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.INCOMPLETE, e.failure)
        }
        assertTrue(target.marker.exists())
        assertFalse("a read error is not proof of change: the marker is not broken", restorer.markerBroken())
        settings.delete() // the disk is readable again
        settings.writeBytes(saved)
        runBlocking { target.restorer().recover() }
        assertFalse(target.marker.exists())
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
    }

    @Test
    fun `REQ-OPS-179 a recovery that finds changed staging reports PARTIAL and keeps the marker`() {
        val killed =
            object : RestoreHooks {
                override fun afterCommit() = throw eg.masroof.data.backup
                    .RestoreKilled()
            }
        val restorer = target.restorer(hooks = killed)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
        } catch (_: eg.masroof.data.backup.RestoreKilled) {
        }
        File(target.stage, "local-id.key").writeBytes(ByteArray(32) { 5 })
        try {
            runBlocking { target.restorer().recover() }
            fail("expected PARTIAL")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertTrue(target.marker.exists())
        assertNull(target.installedKey)
    }

    // ---- MAJOR 4: what was checked is what is applied --------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 editing the settings and validated json together before Restore is refused and nothing changes`() {
        val rows = target.dump()
        val files = target.receiptHashes()
        val restorer = target.restorer()
        val staged = stage(restorer)
        val settings = File(target.stage, "settings.json")
        settings.writeText("{\"formatVersion\":1,\"stores\":{\"datastore:settings\":{\"month_start_day\":{\"type\":\"int\",\"value\":9}}}}")
        val validated = File(target.stage, "validated.json")
        validated.writeText(JSONObject(validated.readText()).put("settings", sha256Of(settings.readBytes())).toString())
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected a refusal")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.FAILED, e.failure)
        }
        target.assertUntouched(rows, files)
    }

    @Test
    fun `REQ-SEC-068 a table file edited after the checks is refused before the commit`() {
        val rows = target.dump()
        val files = target.receiptHashes()
        val restorer = target.restorer()
        val staged = stage(restorer)
        val table = File(target.stage, "table-expense.jsonl")
        table.writeText(table.readText().replace("Shop e1", "Shop XX"))
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected a refusal")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.FAILED, e.failure)
        }
        target.assertUntouched(rows, files)
    }

    // ---- MINORs ----------------------------------------------------------------------------------------------------------------

    private fun runBackupWorker(): ListenableWorker.Result {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val status =
                BackupStatusStore(
                    context.getSharedPreferences("restore-fix2-backup", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                )
            val pending = PendingBackupKey()
            val id = java.util.UUID.randomUUID()
            val factory =
                BackupEnvironmentFactory(
                    db,
                    context,
                    File(context.cacheDir, "fix2-receipts").also { it.mkdirs() },
                    object : LocalIdKey {
                        override fun get() = ByteArray(32) { 3 }

                        override fun wipe() = Unit
                    },
                )
            val clock = Clock.fixed(Instant.parse("2026-10-27T11:05:00Z"), ZoneOffset.UTC)
            val dest = Uri.parse("content://docs.example/document/b2")
            val worker =
                TestListenableWorkerBuilder<BackupWorker>(context)
                    .setId(id)
                    .setInputData(workDataOf(BackupWorker.KEY_URI to dest.toString()))
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = BackupWorker(appContext, workerParameters, factory, pending, status, clock)
                        },
                    ).build()
            shadowOf(context.contentResolver).registerOutputStream(dest, ByteArrayOutputStream())
            val header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 4 }, 1, 0)
            pending.put(id, BackupInput(header, PassphraseKdf.deriveKey("correct horse battery", header)))
            return runBlocking { worker.doWork() }
        } finally {
            db.close()
        }
    }

    @Test
    fun `REQ-OPS-177 a backup refuses while a restore marker is on disk`() {
        RestoreStage.marker(File(context.filesDir, "receipts")).apply { parentFile?.mkdirs() }.writeText("{}")
        val result = runBackupWorker()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            BackupFailure.RESTORE_RUNNING.name,
            (result as ListenableWorker.Result.Failure).outputData.getString(BackupWorker.KEY_REASON),
        )
    }

    @Test
    fun `REQ-OPS-178 an SMS that arrives while a restore is unfinished is deferred, then recorded once`() {
        var pendingNow = true
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
            val key =
                object : LocalIdKey {
                    override fun get() = ByteArray(32) { 1 }

                    override fun wipe() = Unit
                }
            val pipeline =
                SmsPipeline(
                    db,
                    MemorySettings(),
                    MemoryNotifications(),
                    clock,
                    MemoryGate(),
                    key,
                    restorePending = RestorePending { pendingNow },
                )
            val message =
                IncomingMessage(
                    CaptureSource.SMS,
                    "CIB",
                    "Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/03/2026 14:05",
                    1_000L,
                )
            assertEquals(Disposition.DEFERRED, runBlocking { pipeline.accept(message) })
            assertTrue(runBlocking { db.sources().all() }.isEmpty())
            assertTrue(runBlocking { db.expenses().all() }.isEmpty())
            pendingNow = false // the restore is finished: the same job records it, exactly once
            assertEquals(Disposition.RECORDED, runBlocking { pipeline.accept(message) })
            assertEquals(Disposition.RECORDED, runBlocking { pipeline.accept(message) })
            assertEquals(1, runBlocking { db.sources().all() }.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `REQ-SEC-068 a single-quoted string cannot hide nesting from the depth cap`() {
        val plain = BackupTestDecryptor.decrypt(file, RESTORE_PASS)
        val payload = ("{'a':'\"','b':" + "[".repeat(30_000) + "\n").toByteArray()
        val bad = encrypt(zipOf(plain.names.map { it to if (it == "data/members.jsonl") payload else plain.entries.getValue(it) }))
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    @Test
    fun `REQ-SEC-068 ids and references beyond the memory budget are refused as low memory, not as invalid`() {
        expectRefused(target, file, RestoreFailure.LOW_MEMORY, 6, limits = RestoreLimits(minMemoryKiB = 1, maxTrackedBytes = 100))
    }

    @Test
    fun `REQ-SEC-068 non-currency and metal codes are refused like XXX`() {
        for (code in listOf("XTS", "XAU", "XAG", "XPT", "XPD")) {
            expectRefused(
                target,
                mutated("data/expenses.jsonl", "\"currency\":\"EGP\"", "\"currency\":\"$code\""),
                RestoreFailure.INVALID_CONTENT,
                6,
            )
        }
    }

    @Test
    fun `REQ-OPS-178 picking another file at the confirmation discards the staged key first`() {
        val c = coordinator()
        c.toConfirm()
        assertTrue(File(target.stage, "local-id.key").exists())
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        assertFalse("the raw key does not linger in staging", File(target.stage, "local-id.key").exists())
    }
}
