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
import eg.masroof.data.backup.RestoreKilled
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestoreSettingKeys
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreState
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.KeystoreLocalIdKey
import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** STORY-184 fix round 1: the findings of the loop-1 verification and the L3 security review, each as a test that failed first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreFixRoundTest {
    private lateinit var context: Context
    private lateinit var source: Phone
    private lateinit var target: Phone
    private lateinit var file: ByteArray
    private val uri: Uri = Uri.parse("content://docs.example/document/backup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = Phone(context, "fsource")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "ftarget", seeded = true)
        File(target.receipts, "old.bin").writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        RestoreActivity.end()
        scope.cancel()
        source.close()
        target.close()
    }

    private fun coordinator(
        restorer: BackupRestorer = target.restorer(),
        bytes: ByteArray = file,
    ) = RestoreCoordinator(
        restorer,
        EraseLock(),
        RestoreBlockers { false },
        object : RestoreSources {
            override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)

            override fun size(uri: Uri): Long = bytes.size.toLong()
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

    private fun stage(
        restorer: BackupRestorer,
        bytes: ByteArray = file,
    ) = restorer.checkHeader(ByteArrayInputStream(bytes)).let { h ->
        runBlocking { restorer.stage({ ByteArrayInputStream(bytes) }, bytes.size.toLong(), h, RESTORE_PASS) }
    }

    private fun mutated(
        vararg edits: Triple<String, String, String>,
    ) = repack(file) { name, bytes -> edits.filter { it.first == name }.fold(bytes) { b, e -> b.replaceText(e.second, e.third) } }

    // ---- MAJOR 1: an interrupted finish ----------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 an interrupted finish keeps the staging and the next start installs the key, the settings and every receipt`() {
        target.keyFailures = 1
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.INCOMPLETE, failed.failure)
        assertTrue("staging is kept", target.stage.exists())
        assertTrue("marker is kept", target.marker.exists())
        runBlocking { target.restorer().recover() }
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertTrue("the settings were applied (again: it is idempotent)", target.applied.isNotEmpty())
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
        assertFalse(target.marker.exists())
        assertFalse(target.stage.exists())
    }

    @Test
    fun `REQ-OPS-179 while a restore marker exists a new pick first finishes the old restore`() {
        target.keyFailures = 1
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        c.onDismiss()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        assertArrayEquals("the old restore was finished first", LOCAL_KEY, target.installedKey)
        assertFalse(target.marker.exists())
        assertFalse(target.stage.exists())
    }

    @Test
    fun `REQ-SEC-068 staging that is not what was validated is never applied by a restart`() {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() = throw RestoreKilled()
            }
        val restorer = target.restorer(hooks = hooks)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("killed")
        } catch (_: RestoreKilled) {
        }
        File(target.stage, "local-id.key").writeBytes(ByteArray(32) { 7 }) // another file's key, swapped in after the checks
        try {
            runBlocking { target.restorer().recover() }
            fail("changed staging must end in PARTIAL")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertNull("nothing from changed staging is applied", target.installedKey)
        assertTrue(target.applied.isEmpty())
        assertTrue("the marker is kept: only restoring the same file again repairs it", target.marker.exists())
        assertTrue(target.stage.exists())
    }

    @Test
    fun `REQ-OPS-178 the key install needs exactly 32 bytes and refuses an all-zero key`() {
        val prefs = context.getSharedPreferences("masroof_capture_key", Context.MODE_PRIVATE)
        prefs.edit().putString("k", "old").commit()
        val aead =
            object : com.google.crypto.tink.Aead {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()

                override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
            }
        for (bad in listOf(ByteArray(31) { 1 }, ByteArray(33) { 1 }, ByteArray(32))) {
            try {
                KeystoreLocalIdKey(context).installWith(aead, bad)
                fail("expected a refusal")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals("old", prefs.getString("k", null))
        }
    }

    @Test
    fun `REQ-OPS-179 a kill between the two renames of the receipts swap loses no receipt`() {
        val hooks =
            object : RestoreHooks {
                override fun betweenRenames() = throw RestoreKilled()
            }
        val restorer = target.restorer(hooks = hooks)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("killed between the renames")
        } catch (_: RestoreKilled) {
        }
        assertFalse("old directory is moved away", target.receipts.exists())
        assertTrue(File(target.root, "files/receipts.old").exists())
        runBlocking { target.restorer().recover() }
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
        assertFalse(File(target.root, "files/receipts.old").exists())
    }

    // ---- MAJOR 2: files that passed the checks must not crash the app ----------------------------------------------------------

    @Test
    fun `REQ-SEC-068 two manifest receipts on one ZIP entry are refused`() {
        val r1sha = sha256Of(RECEIPT_BYTES)
        val bad =
            mutated(
                Triple(
                    "manifest.json",
                    "\"entry\":\"receipts/r2.pdf\",\"fileName\":\"gone.pdf\",\"missing\":true,\"size\":0,\"sha256\":null",
                    "\"entry\":\"receipts/r1.jpg\",\"fileName\":\"gone.pdf\",\"missing\":false,\"size\":3000,\"sha256\":\"$r1sha\"",
                ),
            )
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    @Test
    fun `REQ-SEC-068 a receipt file name of dot or dot-dot is refused`() {
        for (name in listOf("..", ".")) {
            val bad =
                mutated(
                    Triple("data/receipts.jsonl", "\"fileName\":\"r1.jpg\"", "\"fileName\":\"$name\""),
                    Triple("manifest.json", "\"fileName\":\"r1.jpg\"", "\"fileName\":\"$name\""),
                )
            expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
        }
    }

    @Test
    fun `REQ-SEC-068 a manifest receipt whose file name differs from its row is refused`() {
        val bad = mutated(Triple("manifest.json", "\"fileName\":\"r1.jpg\"", "\"fileName\":\"other.jpg\""))
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    @Test
    fun `REQ-SEC-068 a malformed UTF-8 entry name is refused`() {
        val zip = zipOf(listOf("receipts/aaaa.jpg" to ByteArray(10)))
        val text = zip.copyOf()
        val needle = "aaaa.jpg".toByteArray()
        var i = 0
        while (i <= text.size - needle.size) {
            if ((needle.indices).all { text[i + it] == needle[it] }) for (k in 0 until 4) text[i + k] = 0xFF.toByte()
            i++
        }
        expectRefused(target, encrypt(text), RestoreFailure.INVALID_CONTENT, 4)
    }

    @Test
    fun `REQ-SEC-068 deeply nested JSON is refused before it is parsed`() {
        val plain = BackupTestDecryptor.decrypt(file, RESTORE_PASS)
        val deep = ("{\"id\":" + "[".repeat(30_000) + "\n").toByteArray()
        val bad = encrypt(zipOf(plain.names.map { it to if (it == "data/members.jsonl") deep else plain.entries.getValue(it) }))
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    @Test
    fun `REQ-SEC-068 a table with more rows than the cap is refused`() {
        expectRefused(target, file, RestoreFailure.INVALID_CONTENT, 6, limits = RestoreLimits(minMemoryKiB = 1, maxRowsPerTable = 2))
    }

    @Test
    fun `REQ-SEC-068 an unexpected failure while checking ends in Failed and never crashes the app`() {
        val restorer = target.restorer(openStage = { error("boom") })
        val c = coordinator(restorer)
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.INVALID_CONTENT, failed.failure)
        assertFalse(target.stage.exists())
        assertTrue(target.dump().getValue("expense").isEmpty())
    }

    // ---- MAJOR 3: every date the app can write must restore --------------------------------------------------------------------

    @Test
    fun `REQ-OPS-178 entries dated 30 days ahead or before 2000 restore exactly`() {
        val src = Phone(context, "fdates")
        try {
            insertRow(src.db, "member", "id" to "m1", "displayName" to "Me", "sortOrder" to 0)
            for ((id, at) in listOf("future" to T0 + 30L * 86_400_000, "old" to 946_684_799_999L, "ancient" to -2_000_000_000_000L)) {
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
            val bytes = sourceBackup(src)
            val restorer = target.restorer()
            val staged = stage(restorer, bytes)
            runBlocking { restorer.apply(staged) }
            assertEquals(src.dump(), target.dump())
        } finally {
            src.close()
        }
    }

    // ---- MINORs ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 a directory entry that carries bytes is refused`() {
        val plain = BackupTestDecryptor.decrypt(file, RESTORE_PASS)
        val bad = encrypt(zipOf(plain.names.map { it to plain.entries.getValue(it) } + ("data/" to ByteArray(100))))
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 4)
    }

    @Test
    fun `REQ-SEC-068 an all-zero local-id key is refused`() {
        val bad = repack(file) { n, b -> if (n == "keys/local-id.key") ByteArray(32) else b }
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    @Test
    fun `REQ-SEC-068 a non-currency code is refused`() {
        expectRefused(
            target,
            mutated(Triple("data/expenses.jsonl", "\"currency\":\"EGP\"", "\"currency\":\"XXX\"")),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 settings values are bounded, language is ar or en and the month starts on day 1 to 28`() {
        fun text(
            key: String,
            type: String,
            value: String,
        ) = "{\"formatVersion\":1,\"stores\":{\"shared:masroof_ui\":{\"$key\":{\"type\":\"$type\",\"value\":$value}}," +
            "\"datastore:settings\":{\"month_start_day\":{\"type\":\"int\",\"value\":${if (key == "month") value else "1"}}}}}"
        assertEquals(mapOf("language" to "ar"), RestoreSettingKeys.parse(text("language", "string", "\"ar\"")).stores["shared:masroof_ui"])
        for (bad in listOf("\"fr\"", "\"\"")) {
            try {
                RestoreSettingKeys.parse(text("language", "string", bad))
                fail("language $bad accepted")
            } catch (e: RestoreException) {
                assertEquals(6, e.step)
            }
        }
        try {
            RestoreSettingKeys.parse(text("month", "int", "29"))
            fail("day 29 accepted")
        } catch (e: RestoreException) {
            assertEquals(6, e.step)
        }
    }

    @Test
    fun `REQ-OPS-177 a backup refuses to run while a restore is replacing data`() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val status =
                BackupStatusStore(
                    context.getSharedPreferences("restore-fix-backup", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                )
            val pending = PendingBackupKey()
            val id = java.util.UUID.randomUUID()
            val factory =
                BackupEnvironmentFactory(
                    db,
                    context,
                    File(context.cacheDir, "fix-receipts").also { it.mkdirs() },
                    object : LocalIdKey {
                        override fun get() = ByteArray(32) { 3 }

                        override fun wipe() = Unit
                    },
                )
            val clock = Clock.fixed(Instant.parse("2026-10-27T11:05:00Z"), ZoneOffset.UTC)
            val worker =
                TestListenableWorkerBuilder<BackupWorker>(context)
                    .setId(id)
                    .setInputData(workDataOf(BackupWorker.KEY_URI to "content://docs.example/document/b"))
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker =
                                BackupWorker(appContext, workerParameters, factory, pending, status, clock)
                        },
                    ).build()
            shadowOf(context.contentResolver).registerOutputStream(Uri.parse("content://docs.example/document/b"), ByteArrayOutputStream())
            val header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 4 }, 1, 0)
            pending.put(id, BackupInput(header, PassphraseKdf.deriveKey("correct horse battery", header)))
            assertTrue(RestoreActivity.begin())
            val result = runBlocking { worker.doWork() }
            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(
                BackupFailure.RESTORE_RUNNING.name,
                (result as ListenableWorker.Result.Failure).outputData.getString(BackupWorker.KEY_REASON),
            )
            assertNull(status.last.value)
        } finally {
            RestoreActivity.end()
            db.close()
        }
    }

    @Test
    fun `REQ-OPS-178 the local id is hashed with the key that is current once the erase lock is held`() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = ByteArray(32) { 1 }
            val second = ByteArray(32) { 2 }
            val gets = AtomicInteger()
            val key =
                object : LocalIdKey {
                    @Volatile var current = first

                    override fun get(): ByteArray {
                        gets.incrementAndGet()
                        return current.copyOf()
                    }

                    override fun wipe() = Unit
                }
            val lock = EraseLock()
            val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
            val pipeline = SmsPipeline(db, MemorySettings(), MemoryNotifications(), clock, MemoryGate(), key, eraseLock = lock)
            val message =
                IncomingMessage(
                    CaptureSource.SMS,
                    "CIB",
                    "Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/03/2026 14:05",
                    1_000L,
                )
            runBlocking {
                lock.mutex.lock() // a restore holds the lock
                val done = async(Dispatchers.Default) { pipeline.accept(message) }
                withTimeout(20_000) { while (gets.get() < 1) yield() }
                key.current = second // the restore installs the backup's key before it lets go
                lock.mutex.unlock()
                done.await()
            }
            assertEquals(
                LocalIdHasher.id(second, message),
                runBlocking { db.sources().all() }.single().localSourceId,
            )
        } finally {
            db.close()
        }
    }
}
