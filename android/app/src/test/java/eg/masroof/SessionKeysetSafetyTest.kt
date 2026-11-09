package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.data.auth.sessionAeadAfterMove
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.LocalIdKeyUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

/** The session keyset is never reset while the legacy local-id key may still be sealed under it (REQ-SEC-065). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionKeysetSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keysetPrefs get() = context.getSharedPreferences("masroof_keyset", Context.MODE_PRIVATE)

    private fun corrupt() {
        keysetPrefs.edit().putString("masroof_session_keyset", "not a keyset").commit()
    }

    @Test
    fun `REQ-SEC-065 aeadOrThrow keeps a keyset it cannot read while aead resets it`() {
        corrupt()
        try {
            TinkKeystore.aeadOrThrow(context)
            fail("a keyset that does not parse must be reported")
        } catch (_: Exception) {
            // thrown, never swallowed
        }
        // nothing was deleted: the bytes are still there for a later, successful read
        assertEquals("not a keyset", keysetPrefs.getString("masroof_session_keyset", null))
        // the resetting builder, by contrast, replaces what it cannot read (and so would delete what k was sealed under)
        assertNotNull(TinkKeystore.aead(context))
        assertNotEquals("not a keyset", keysetPrefs.getString("masroof_session_keyset", null))
    }

    private class FakeKey(
        private val fail: Boolean,
    ) : LocalIdKey {
        var gets = 0

        override fun get(): ByteArray {
            gets++
            if (fail) throw LocalIdKeyUnavailableException(GeneralSecurityException("busy"))
            return ByteArray(32) { 1 }
        }

        override fun wipe() = Unit
    }

    private val resetting = { inMemoryAead() }

    @Test
    fun `REQ-SEC-065 the session AEAD is built after the move, with the resetting builder`() {
        val key = FakeKey(fail = false)
        var safeUsed = false
        sessionAeadAfterMove(key, resetting) {
            safeUsed = true
            inMemoryAead()
        }
        assertEquals(1, key.gets)
        assertTrue(!safeUsed)
    }

    @Test
    fun `REQ-SEC-065 while the move cannot be done the session AEAD is built without any reset, and k is untouched`() {
        val key = FakeKey(fail = true)
        var resetUsed = false
        val built: Aead =
            sessionAeadAfterMove(
                key,
                {
                    resetUsed = true
                    inMemoryAead()
                },
            ) { inMemoryAead() }
        assertNotNull(built)
        assertTrue("the resetting builder must not run before the move", !resetUsed)
        assertEquals(1, key.gets)
    }
}
