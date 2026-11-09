package eg.masroof.backup

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreState
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.intakeResult
import eg.masroof.ui.settings.RestorePendingBannerContent
import eg.masroof.ui.theme.MasroofTheme
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** STORY-184 fix round 4: there is always a way out of a marker, a restore never loses a message, and the fixes are mutation-tested. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RestoreFixRound4Test {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var source: Phone
    private lateinit var target: Phone
    private lateinit var file: ByteArray
    private val uri: Uri = Uri.parse("content://docs.example/document/backup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = Phone(context, "i4source")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "i4target", seeded = true)
    }

    @After
    fun tearDown() {
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

    private fun assertReRestoreCompletes() {
        val again = target.restorer()
        val staged = stage(again)
        runBlocking { again.apply(staged) }
        assertFalse(target.marker.exists())
        assertFalse(target.stage.exists())
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertTrue(target.applied.isNotEmpty())
        assertEquals(source.dump(), target.dump())
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
    }

    // ---- MAJOR: a missing staged file is a difference, not a read error ------------------------------------------------------

    private fun deletedAfterCommit(name: String) {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() {
                    check(File(target.stage, name).delete()) { "fixture has no $name" }
                }
            }
        val restorer = target.restorer(hooks = hooks)
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected PARTIAL")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertTrue(restorer.markerBroken())
        assertNull(target.installedKey)
        // every later start says PARTIAL too (never "close and reopen")
        try {
            runBlocking { target.restorer().recover() }
            fail("expected PARTIAL again")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertReRestoreCompletes()
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)

    @Test
    fun `REQ-OPS-179 a settings file deleted after the commit is PARTIAL and a re-restore completes`() = deletedAfterCommit("settings.json")

    @Test
    fun `REQ-OPS-179 a key file deleted after the commit is PARTIAL and a re-restore completes`() = deletedAfterCommit("local-id.key")

    @Test
    fun `REQ-OPS-179 a validated json deleted after the commit is PARTIAL and a re-restore completes`() =
        deletedAfterCommit(
            "validated.json",
        )

    // ---- (b) there is always a way out ---------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 an INCOMPLETE that fails on three starts in a row becomes PARTIAL, and a re-restore completes`() {
        target.keyFailures = 100
        val restorer = target.restorer()
        val staged = stage(restorer)
        try {
            runBlocking { restorer.apply(staged) }
            fail("expected INCOMPLETE")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.INCOMPLETE, e.failure)
        }
        val outcomes = mutableListOf<RestoreFailure>()
        repeat(4) {
            try {
                runBlocking { target.restorer().recover() }
                fail("expected a failure")
            } catch (e: RestoreException) {
                outcomes += e.failure
            }
        }
        assertEquals(
            listOf(RestoreFailure.INCOMPLETE, RestoreFailure.INCOMPLETE, RestoreFailure.PARTIAL, RestoreFailure.PARTIAL),
            outcomes,
        )
        target.keyFailures = 0
        assertReRestoreCompletes()
    }

    @Test
    fun `REQ-OPS-179 picking a file again is allowed from INCOMPLETE and completes the restore after all seven checks`() {
        target.keyFailures = 100
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.INCOMPLETE, failed.failure)
        c.onDismiss()
        c.onPicked(uri) // its own recovery fails again; the pick still goes on
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Confirm }
        target.keyFailures = 0
        c.onConfirm()
        c.await { it == RestoreState.Done }
        assertFalse(target.marker.exists())
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertEquals(source.dump(), target.dump())
    }

    @Test
    fun `REQ-OPS-179 a wrong passphrase on a re-pick does not break an unfinished restore`() {
        target.keyFailures = 100 // every recovery keeps failing, so the unfinished restore stays
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        c.onDismiss()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase("not the passphrase")
        c.await { it is RestoreState.Failed }
        assertFalse("the unfinished restore is still finishable", File(target.marker.path).readText().contains("\"broken\":true"))
    }

    // ---- MINOR 1: a restore never loses a message ----------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-178 fifty deferrals never fail the job, and a later database error still has its own budget`() {
        var requeued = 0
        for (n in 1..50) {
            val result = runBlocking { intakeResult(n, requeue = { requeued++ }) { Disposition.DEFERRED } }
            assertEquals("deferral $n", ListenableWorker.Result.success(), result)
        }
        assertEquals(50, requeued)
        assertEquals(ListenableWorker.Result.retry(), runBlocking { intakeResult(1) { throw SQLiteFullException("full") } })
        assertEquals(ListenableWorker.Result.success(), runBlocking { intakeResult(2) { Disposition.RECORDED } })
    }

    @Test
    fun `REQ-OPS-178 a deferral whose new job cannot be queued keeps the old job`() {
        val result = runBlocking { intakeResult(1, requeue = { error("WorkManager is not ready") }) { Disposition.DEFERRED } }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // ---- MINOR 2: the banner's action ends the restore ------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 finishing from the banner moves the state to Done`() {
        target.keyFailures = 1
        val c = coordinator()
        c.toConfirm()
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        c.finishPending()
        c.await { it == RestoreState.Done }
        assertArrayEquals(LOCAL_KEY, target.installedKey)
        assertFalse(target.marker.exists())
    }

    // ---- MINOR 3: tests that catch the removal of a fix -----------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 receipt ids and file names count in the memory budget`() {
        val src = Phone(context, "i4receipts")
        try {
            insertRow(src.db, "member", "id" to "m1", "displayName" to "Me", "sortOrder" to 0)
            repeat(20) { i ->
                insertRow(
                    src.db,
                    "receipt",
                    "id" to "r$i",
                    "expenseId" to null,
                    "fileName" to ("f$i" + "x".repeat(100)) + ".jpg",
                    "mimeType" to "image/jpeg",
                    "sizeBytes" to 1,
                    "sha256" to "%064x".format(i + 1),
                    "createdAtMillis" to T0,
                )
            }
            val bytes = sourceBackup(src)
            // the ids alone (about 70 bytes each) stay under 3,000; the receipt names push it over
            val restorer = target.restorer(limits = RestoreLimits(minMemoryKiB = 1, maxTrackedBytes = 3_000))
            val header = restorer.checkHeader(ByteArrayInputStream(bytes))
            try {
                runBlocking { restorer.stage({ ByteArrayInputStream(bytes) }, bytes.size.toLong(), header, RESTORE_PASS) }
                fail("expected LOW_MEMORY")
            } catch (e: RestoreException) {
                assertEquals(RestoreFailure.LOW_MEMORY, e.failure)
            }
            // and without the receipt rows the same budget is enough
            val ok = target.restorer(limits = RestoreLimits(minMemoryKiB = 1, maxTrackedBytes = 3_000))
            val plain = ok.checkHeader(ByteArrayInputStream(file))
            runBlocking { ok.stage({ ByteArrayInputStream(file) }, file.size.toLong(), plain, RESTORE_PASS) }
        } finally {
            src.close()
        }
    }

    @Test
    fun `REQ-OPS-178 the banner is not shown when nothing is pending`() {
        compose.setContent { MasroofTheme { RestorePendingBannerContent(pending = false, onFinish = {}) } }
        compose.onNodeWithText("Finish the restore").assertDoesNotExist()
    }

    @Test
    fun `REQ-OPS-178 the app's top bar shows the restore banner above every screen`() {
        val main = File("src/main/java/eg/masroof/MainActivity.kt").readText()
        assertTrue(
            "MainActivity's Scaffold must have the restore banner as its topBar",
            Regex("topBar\\s*=\\s*\\{[^}]*RestorePendingBanner\\(").containsMatchIn(main),
        )
    }
}
