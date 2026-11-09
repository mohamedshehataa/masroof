package eg.masroof.backup

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.UserDate
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreState
import eg.masroof.data.goals.GoalInput
import eg.masroof.data.wealth.WealthValidation
import eg.masroof.ui.bills.parseDate
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
import org.junit.Assert.assertNull
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
import java.time.LocalDate

/** STORY-184 fix round 3: SMS is never dropped during a restore, the date forms share one rule, and a read error is not "changed". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RestoreFixRound3Test {
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
        source = Phone(context, "h3source")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "h3target", seeded = true)
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

    private fun stage(restorer: BackupRestorer) =
        restorer.checkHeader(ByteArrayInputStream(file)).let { h ->
            runBlocking { restorer.stage({ ByteArrayInputStream(file) }, file.size.toLong(), h, RESTORE_PASS) }
        }

    // ---- MAJOR 1: never drop ---------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-178 while a restore marker exists the banner is up, and finishing it takes it down`() {
        target.keyFailures = 1
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Confirm }
        c.onConfirm()
        c.await { it is RestoreState.Failed }
        runBlocking { withTimeout(20_000) { c.pending.first { it } } }
        c.finishPending()
        runBlocking { withTimeout(20_000) { c.pending.first { !it } } }
        assertArrayEquals(LOCAL_KEY, target.installedKey)
    }

    @Test
    fun `REQ-OPS-178 the pending banner says capture and backups are paused and offers to finish`() {
        compose.setContent { MasroofTheme { RestorePendingBannerContent(pending = true, onFinish = {}) } }
        compose.onNodeWithText("SMS capture and backups are paused until the restore finishes.").assertIsDisplayed()
        compose.onNodeWithText("Finish the restore").assertIsDisplayed()
    }

    // ---- MAJOR 2: one date rule everywhere -------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-178 every date form refuses signed and out-of-layout years and accepts 0000 to 9999`() {
        val parsers: Map<String, (String) -> LocalDate?> =
            mapOf(
                "UserDate" to UserDate::parse,
                "GoalInput" to GoalInput::parseDate,
                "WealthValidation" to WealthValidation::parseDate,
                "plan and subscription forms" to ::parseDate,
            )
        for ((name, parse) in parsers) {
            for (bad in listOf("+10000-03-01", "-0001-03-01", "10000-03-01", "99-03-01", "2026-3-1", "2026/03/01", "")) {
                assertNull("$name accepted $bad", parse(bad))
            }
            assertEquals(name, LocalDate.of(0, 1, 1), parse("0000-01-01"))
            assertEquals(name, LocalDate.of(9999, 12, 31), parse("9999-12-31"))
            assertEquals(name, LocalDate.of(2205, 3, 1), parse("٢٢٠٥-٠٣-٠١"))
        }
    }

    @Test
    fun `REQ-OPS-178 no form parses a typed date on its own`() {
        val allowed = setOf("core/UserDate.kt", "ingest/quick/QuickDates.kt", "ui/wealth/NetWorthChart.kt")
        val offenders =
            File("src/main/java/eg/masroof")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains("LocalDate.parse(") }
                .map { it.invariantSeparatorsPath.substringAfter("eg/masroof/") }
                .filter { it !in allowed }
                .toList()
        assertTrue("typed dates must go through UserDate: $offenders", offenders.isEmpty())
    }

    // ---- MINORs and NITs -------------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 a refused re-restore under a broken marker says PARTIAL and Cancel wipes the new staging`() {
        val hooks =
            object : RestoreHooks {
                override fun afterCommit() = File(target.stage, "settings.json").writeText("{\"formatVersion\":1,\"stores\":{}}")
            }
        val first = target.restorer(hooks = hooks)
        val s1 = stage(first)
        try {
            runBlocking { first.apply(s1) }
            fail("expected PARTIAL")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        assertTrue(first.markerBroken())
        // a re-restore whose staging is refused before the commit: the phone is still partly restored, so PARTIAL, not "nothing changed"
        val again = target.restorer()
        val s2 = stage(again)
        File(target.stage, "settings.json").writeText("{\"formatVersion\":1,\"stores\":{\"shared:masroof_ui\":{}}}")
        try {
            runBlocking { again.apply(s2) }
            fail("expected a refusal")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.PARTIAL, e.failure)
        }
        // Cancel after staging again: the raw key does not stay, the marker does
        val third = target.restorer()
        val s3 = stage(third)
        assertTrue(File(target.stage, "local-id.key").exists())
        third.discard(s3)
        assertFalse(File(target.stage, "local-id.key").exists())
        assertTrue(target.marker.exists())
    }
}
