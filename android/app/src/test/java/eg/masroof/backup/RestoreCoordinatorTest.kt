package eg.masroof.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreCoordinator
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RestoreState
import eg.masroof.ingest.KeystoreLocalIdKey
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreCoordinatorTest {
    private lateinit var context: Context
    private lateinit var source: Phone
    private lateinit var target: Phone
    private lateinit var file: ByteArray
    private val uri: Uri = Uri.parse("content://docs.example/document/backup")
    private var opens = 0
    private var bytesRead = 0L
    private var busy = false
    private val lock = EraseLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = Phone(context, "csource")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "ctarget")
    }

    @After
    fun tearDown() {
        scope.cancel()
        source.close()
        target.close()
    }

    private fun coordinator() =
        RestoreCoordinator(
            target.restorer(),
            lock,
            RestoreBlockers { busy },
            object : RestoreSources {
                override fun open(uri: Uri): InputStream {
                    opens++
                    return object : FilterInputStream(ByteArrayInputStream(file)) {
                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }

                        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
                    }
                }

                override fun size(uri: Uri): Long = file.size.toLong()
            },
            scope,
            Dispatchers.IO,
        )

    /** Waits for a StateFlow value (the coordinator runs in its own scope), never sleeps. */
    private fun RestoreCoordinator.await(match: (RestoreState) -> Boolean): RestoreState =
        runBlocking { withTimeout(60_000) { state.first(match) } }

    @Test
    fun `REQ-SEC-068 cancel at the picker reads nothing and stays idle`() {
        val c = coordinator()
        c.onPicked(null)
        assertEquals(RestoreState.Idle, c.state.value)
        assertEquals(0, opens)
    }

    @Test
    fun `REQ-SEC-068 a picked file is read up to its header only until the passphrase is typed`() {
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        assertTrue("only the header: $bytesRead bytes", bytesRead <= 39)
        c.onCancel()
        c.await { it == RestoreState.Idle }
        assertEquals(1, opens)
    }

    @Test
    fun `REQ-SEC-068 a wrong passphrase ends in the one message and a later try with the right one reaches the confirmation`() {
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase("not the passphrase")
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, failed.failure)
        c.onDismiss()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        val confirm = c.await { it is RestoreState.Confirm } as RestoreState.Confirm
        assertEquals(2L, confirm.info.entries)
        assertEquals(0L, confirm.info.currentEntries)
    }

    @Test
    fun `REQ-OPS-178 cancel at the confirmation deletes the staging directory`() {
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Confirm }
        assertTrue(target.stage.exists())
        c.onCancel()
        c.await { it == RestoreState.Idle }
        runBlocking { withTimeout(30_000) { while (target.stage.exists()) kotlinx.coroutines.yield() } }
        assertFalse(target.stage.exists())
        assertTrue(target.dump().getValue("expense").isEmpty())
    }

    @Test
    fun `REQ-OPS-178 Restore replaces everything and ends in Done`() {
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Confirm }
        c.onConfirm()
        c.await { it == RestoreState.Done }
        assertEquals(source.dump(), target.dump())
    }

    @Test
    fun `REQ-OPS-178 the replace waits for the erase lock like every other writer`() {
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        c.await { it is RestoreState.Confirm }
        runBlocking { lock.mutex.lock() }
        c.onConfirm()
        c.await { it == RestoreState.Restoring }
        runBlocking { kotlinx.coroutines.delay(300) } // a real wait: the lock is held, so nothing may have changed
        assertEquals(RestoreState.Restoring, c.state.value)
        assertTrue(target.dump().getValue("expense").isEmpty())
        lock.mutex.unlock()
        c.await { it == RestoreState.Done }
        assertEquals(source.dump(), target.dump())
    }

    @Test
    fun `REQ-OPS-178 a running backup or SMS scan refuses the restore and nothing changes`() {
        busy = true
        val c = coordinator()
        c.onPicked(uri)
        c.await { it == RestoreState.NeedsPassphrase }
        c.onPassphrase(RESTORE_PASS)
        val failed = c.await { it is RestoreState.Failed } as RestoreState.Failed
        assertEquals(RestoreFailure.BUSY, failed.failure)
        assertFalse(target.stage.exists())
        assertTrue(target.dump().getValue("expense").isEmpty())
    }

    // ---- the local-id key re-seal is crash safe (STORY-184 with the STORY-182 review note) ------------------------------------

    private class XorAead(
        private val failEncrypt: Boolean = false,
        private val failDecrypt: Boolean = false,
        private val wrongDecrypt: Boolean = false,
    ) : Aead {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            if (failEncrypt) throw java.security.GeneralSecurityException("Keystore unavailable")
            return ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }
        }

        override fun decrypt(
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            if (failDecrypt) throw java.security.GeneralSecurityException("Keystore unavailable")
            val plain = ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
            if (wrongDecrypt) plain[0] = (plain[0] + 1).toByte()
            return plain
        }
    }

    private fun prefs() = context.getSharedPreferences("masroof_capture_key", Context.MODE_PRIVATE)

    private fun seedOld(): String {
        val old = Base64.encodeToString(XorAead().encrypt(ByteArray(32) { 9 }, ByteArray(0)), Base64.NO_WRAP)
        prefs().edit().putString("k", old).commit()
        return old
    }

    @Test
    fun `REQ-OPS-178 a Keystore failure while sealing leaves the old key in place`() {
        val old = seedOld()
        for (aead in listOf(XorAead(failEncrypt = true), XorAead(failDecrypt = true), XorAead(wrongDecrypt = true))) {
            try {
                KeystoreLocalIdKey(context).installWith(aead, LOCAL_KEY)
                fail("expected a failure")
            } catch (_: Exception) {
                // the old sealed copy is still the stored one, so get() still returns the old key
            }
            assertEquals(old, prefs().getString("k", null))
        }
    }

    @Test
    fun `REQ-OPS-178 the restored key is stored only after it decrypts to the same bytes`() {
        seedOld()
        val key = LOCAL_KEY.copyOf()
        KeystoreLocalIdKey(context).installWith(XorAead(), key)
        val stored = Base64.decode(prefs().getString("k", null), Base64.NO_WRAP)
        assertArrayEquals(LOCAL_KEY, XorAead().decrypt(stored, ByteArray(0)))
    }
}
