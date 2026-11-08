package eg.masroof.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupLauncher
import eg.masroof.data.backup.BackupRun
import eg.masroof.data.backup.BackupStatusStore
import eg.masroof.data.backup.LastBackup
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PassphraseProblem
import eg.masroof.ui.settings.BackupNotice
import eg.masroof.ui.settings.BackupStep
import eg.masroof.ui.settings.BackupUiState
import eg.masroof.ui.settings.BackupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FakeLauncher : BackupLauncher {
    val started = mutableListOf<Pair<Uri, BackupInput>>()
    var cancelled = 0
    var acknowledged = 0
    val runState = MutableStateFlow<BackupRun>(BackupRun.Idle)

    var startResult = true
    var startFailure: Throwable? = null
    val discarded = mutableListOf<Uri>()

    override suspend fun start(
        destination: Uri,
        input: BackupInput,
    ): Boolean {
        startFailure?.let { throw it }
        started += destination to input
        return startResult
    }

    override fun discard(destination: Uri) {
        discarded += destination
    }

    override fun cancel() {
        cancelled++
    }

    override suspend fun acknowledge() {
        acknowledged++
    }

    override val run = runState
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelTest {
    private lateinit var launcher: FakeLauncher
    private lateinit var status: BackupStatusStore
    private lateinit var vm: BackupViewModel
    private val clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC)
    private val uri: Uri = Uri.parse("content://docs.example/document/1")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        launcher = FakeLauncher()
        status = BackupStatusStore(context.getSharedPreferences("backup-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        vm = BackupViewModel(launcher, status, clock, Dispatchers.Default)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The state is a combine of several flows: wait (real time) until it satisfies [cond] instead of reading `value` after an action. */
    private suspend fun await(cond: (BackupUiState) -> Boolean): BackupUiState =
        withContext(Dispatchers.Default) { withTimeout(20_000) { vm.state.first { cond(it) } } }

    private fun neverBackedUp() = assertNull(status.last.value)

    private fun hadBackup() = status.record(LastBackup(1_793_000_000_000L, 1_240, 37, 58L * 1024 * 1024))

    @Test
    fun `REQ-SEC-067 the first backup shows the explanation and Continue needs the checkbox`() =
        runTest {
            neverBackedUp()
            vm.onBackUpNow()
            val explain = await { it.step == BackupStep.EXPLAIN }
            assertFalse(explain.understood)
            vm.onContinueExplain()
            assertEquals("without the checkbox nothing moves on", BackupStep.EXPLAIN, await { true }.step)
            vm.onUnderstood(true)
            await { it.understood }
            vm.onContinueExplain()
            assertEquals(BackupStep.PASSPHRASE, await { it.step == BackupStep.PASSPHRASE }.step)
        }

    @Test
    fun `REQ-SEC-067 a later backup goes straight to the passphrase`() =
        runTest {
            hadBackup()
            await { it.last != null }
            vm.onBackUpNow()
            assertEquals(BackupStep.PASSPHRASE, await { it.step == BackupStep.PASSPHRASE }.step)
        }

    @Test
    fun `REQ-SEC-067 Continue is off with the reason for an empty field, 11 code points and two different entries`() =
        runTest {
            hadBackup()
            vm.onBackUpNow()
            assertEquals(PassphraseProblem.Empty, await { it.step == BackupStep.PASSPHRASE }.problem)
            vm.onPassphrase("12345678901")
            vm.onAgain("12345678901")
            val short = await { it.passphrase.length == 11 && it.again.length == 11 }
            assertEquals(PassphraseProblem.TooShort(11), short.problem)
            assertFalse(short.canContinue)
            vm.onPassphrase("123456789012")
            vm.onAgain("123456789013")
            val different = await { it.passphrase.length == 12 && it.again == "123456789013" }
            assertEquals(PassphraseProblem.Mismatch, different.problem)
            vm.onContinuePassphrase()
            assertEquals("Continue does nothing while a reason is shown", BackupStep.PASSPHRASE, await { true }.step)
            assertTrue(launcher.started.isEmpty())
        }

    @Test
    fun `REQ-SEC-067 an Arabic passphrase typed twice is accepted, also when the second entry uses another composition form`() =
        runTest {
            hadBackup()
            vm.onBackUpNow()
            vm.onPassphrase("كلمة سر طويلة جدا")
            vm.onAgain("كلمة سر طويلة جدا")
            assertNull(await { it.again.isNotEmpty() }.problem)
            vm.onPassphrase("café café café")
            vm.onAgain("café café café")
            val nfc = await { it.passphrase.startsWith("caf") && it.again.startsWith("caf") }
            assertNull(nfc.problem)
            vm.onContinuePassphrase()
            assertEquals(BackupStep.PICKING, await { it.step == BackupStep.PICKING }.step)
        }

    @Test
    fun `REQ-OPS-177 the picker opens with the default name masroof-date dot masroof in Cairo time`() =
        runTest {
            // 22:30 UTC on 27 Oct is already 28 Oct in Cairo
            assertEquals("masroof-2026-10-28.masroof", vm.defaultFileName())
            hadBackup()
            vm.onBackUpNow()
            assertEquals("masroof-2026-10-28.masroof", await { it.step == BackupStep.PASSPHRASE }.defaultFileName)
        }

    @Test
    fun `REQ-OPS-177 cancelling the picker writes nothing, clears the passphrase and leaves the last backup line alone`() =
        runTest {
            hadBackup()
            val before = status.last.value
            vm.onBackUpNow()
            vm.onPassphrase("correct horse battery")
            vm.onAgain("correct horse battery")
            vm.onContinuePassphrase()
            await { it.step == BackupStep.PICKING }
            vm.onPicked(null)
            val after = await { it.step == BackupStep.NONE }
            assertEquals("", after.passphrase)
            assertEquals("", after.again)
            assertTrue(launcher.started.isEmpty())
            assertEquals(before, status.last.value)
            assertEquals(before, after.last)
        }

    @Test
    fun `REQ-OPS-177 a picked file starts the foreground task with a key from the passphrase, then the field is cleared`() =
        runTest {
            hadBackup()
            vm.onBackUpNow()
            vm.onPassphrase("correct horse battery")
            vm.onAgain("correct horse battery")
            vm.onContinuePassphrase()
            await { it.step == BackupStep.PICKING }
            vm.onPicked(uri)
            withContext(Dispatchers.Default) { withTimeout(20_000) { while (launcher.started.isEmpty()) kotlinx.coroutines.delay(10) } }
            val (destination, input) = launcher.started.single()
            assertEquals(uri, destination)
            assertEquals(65_536L, input.header.memoryKiB)
            assertEquals(3L, input.header.iterations)
            assertEquals(16, input.header.salt.size)
            assertArrayEquals(PassphraseKdf.deriveKey("correct horse battery", input.header), input.key)
            val cleared = await { it.step == BackupStep.NONE && it.passphrase.isEmpty() && it.again.isEmpty() && !it.preparing }
            assertEquals("", cleared.passphrase)
            assertEquals(BackupStep.NONE, cleared.step)
        }

    @Test
    fun `REQ-OPS-177 while a backup runs Back up now does nothing and Cancel reaches the task`() =
        runTest {
            hadBackup()
            launcher.runState.value = BackupRun.Running(40)
            val running = await { it.run is BackupRun.Running }
            assertEquals(40, (running.run as BackupRun.Running).percent)
            vm.onBackUpNow()
            assertEquals(BackupStep.NONE, await { true }.step)
            vm.onCancelBackup()
            assertEquals(1, launcher.cancelled)
            launcher.runState.value = BackupRun.Idle
            vm.onAcknowledge()
            assertEquals(1, launcher.acknowledged)
        }

    @Test
    fun `REQ-OPS-177 the last backup line comes from the store and is what the screen shows after a success`() =
        runTest {
            neverBackedUp()
            assertNull(await { true }.last)
            hadBackup()
            val s = await { it.last != null }
            assertEquals(1_240L, s.last?.entries)
            assertEquals(37, s.last?.receipts)
        }

    @Test
    fun `REQ-SEC-066 every backup gets a fresh random salt`() {
        val a = BackupHeader.fresh()
        val b = BackupHeader.fresh()
        assertFalse(a.salt.contentEquals(b.salt))
    }

    private fun pickWith(
        passphrase: String = "correct horse battery",
        picked: Uri? = uri,
    ) {
        vm.onBackUpNow()
        vm.onPassphrase(passphrase)
        vm.onAgain(passphrase)
        vm.onContinuePassphrase()
        vm.onPicked(picked)
    }

    @Test
    fun `REQ-OPS-177 a picked file arriving after the process died says the backup was interrupted and removes the empty document`() =
        runTest {
            hadBackup()
            vm.onPicked(uri)
            val s = await { it.notice == BackupNotice.INTERRUPTED }
            assertEquals(BackupStep.NONE, s.step)
            assertEquals(listOf(uri), launcher.discarded)
            assertTrue(launcher.started.isEmpty())
        }

    @Test
    fun `REQ-OPS-177 a key derivation that fails shows a message instead of crashing and clears the field`() =
        runTest {
            hadBackup()
            val broken = BackupViewModel(launcher, status, clock, kotlinx.coroutines.Dispatchers.Default)
            launcher.startFailure = OutOfMemoryError("64 MiB")
            vm = broken
            pickWith()
            val s = await { it.notice == BackupNotice.START_FAILED && !it.preparing }
            assertEquals("", s.passphrase)
            assertEquals(listOf(uri), launcher.discarded)
        }

    @Test
    fun `REQ-OPS-177 when the launcher refuses because a backup is queued the derived key is zeroed and the user is told`() =
        runTest {
            hadBackup()
            launcher.startResult = false
            pickWith()
            val s = await { it.notice == BackupNotice.ALREADY_RUNNING && !it.preparing }
            assertEquals(BackupStep.NONE, s.step)
            val key =
                launcher.started
                    .single()
                    .second.key
            assertTrue("the key was zeroed", key.all { it == 0.toByte() })
            assertEquals(listOf(uri), launcher.discarded)
        }

    @Test
    fun `REQ-OPS-177 when starting the task throws the derived key is zeroed too`() =
        runTest {
            hadBackup()
            launcher.startFailure = IllegalStateException("work manager not ready")
            pickWith()
            await { it.notice == BackupNotice.START_FAILED && !it.preparing }
            assertTrue(launcher.started.isEmpty())
        }
}
