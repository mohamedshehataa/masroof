package eg.masroof

import android.util.Base64
import com.google.crypto.tink.Aead
import eg.masroof.ingest.KeyPrefs
import eg.masroof.ingest.KeystoreLocalIdKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.GeneralSecurityException

private class MemPrefs : KeyPrefs {
    val map = mutableMapOf<String, String>()

    /** Fails the [failAt]th write or remove (0-based) with an I/O error, like a kill at that step. */
    var failAt = -1
    private var ops = 0

    private fun step() {
        if (ops++ == failAt) throw IOException("killed")
    }

    override fun get(name: String) = map[name]

    override fun put(
        name: String,
        value: String,
    ) {
        step()
        map[name] = value
    }

    override fun remove(name: String) {
        step()
        map.remove(name)
    }

    override fun clear() = map.clear()
}

/** The local-id key and its move out of the session keyset (REQ-SEC-065), with injected AEADs and a fault in every step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalIdKeyMigrationTest {
    private val aad = "masroof-local-id".toByteArray()
    private val local = inMemoryAead()
    private var session = inMemoryAead()
    private val prefs = MemPrefs()
    private val legacyPrefs = MemPrefs()

    /** What a key of the older build looks like: 32 bytes sealed under the SESSION keyset in the legacy entry. */
    private fun legacyKey(): ByteArray {
        val k = ByteArray(32) { (it * 7 + 1).toByte() }
        legacyPrefs.map["k"] = Base64.encodeToString(session.encrypt(k, aad), Base64.NO_WRAP)
        return k
    }

    private fun key(
        localAead: () -> Aead = { local },
        current: KeyPrefs = prefs,
        legacy: KeyPrefs = legacyPrefs,
        lost: () -> Boolean = { false },
    ) = KeystoreLocalIdKey(current, legacy, localAead, { session }, lost)

    /** The real deletion: afterwards the session keyset is a different, empty one (what Tink makes at the next use). */
    private fun destroySession() {
        session = inMemoryAead()
    }

    @Test
    fun `REQ-SEC-065 the local-id key keeps its exact bytes after the session keyset is destroyed`() {
        val original = legacyKey()
        val k = key()
        assertArrayEquals(original, k.get())
        k.detachFromSessionKeyset { destroySession() }
        assertArrayEquals(original, key().get())
        assertNull(legacyPrefs.map["k"])
        assertNotNull(prefs.map["k_local"])
    }

    @Test
    fun `REQ-SEC-065 a new install makes one key and keeps it across restarts and the keyset deletion`() {
        val first = key().get()
        assertArrayEquals(first, key().get())
        key().detachFromSessionKeyset { destroySession() }
        assertArrayEquals(first, key().get())
    }

    @Test
    fun `REQ-SEC-065 a keyset deletion that throws still leaves the key readable`() {
        val original = legacyKey()
        try {
            key().detachFromSessionKeyset { throw GeneralSecurityException("reset failed") }
            fail("the failure must reach the caller")
        } catch (_: GeneralSecurityException) {
            // the forgetter swallows it; the key must not be affected
        }
        assertArrayEquals(original, key().get())
    }

    @Test
    fun `REQ-SEC-065 a local keyset that throws while sealing destroys nothing and the key survives the retry`() {
        val original = legacyKey()
        var failures = 1
        val flaky = {
            if (failures-- > 0) throw GeneralSecurityException("keystore busy")
            local
        }
        var destroyed = 0
        try {
            key(flaky).detachFromSessionKeyset { destroyed++ }
            fail("the sealing failure must reach the caller")
        } catch (_: eg.masroof.ingest.LocalIdKeyUnavailableException) {
            // nothing was written and nothing was destroyed
        }
        assertEquals(0, destroyed)
        assertNull(prefs.map["k_local"])
        assertArrayEquals(original, key(flaky).get())
    }

    @Test
    fun `REQ-SEC-065 an AEAD that throws after the reset leaves the verified key readable on the next start`() {
        val original = legacyKey()
        var afterReset = false
        val flaky = {
            if (afterReset && failuresLeft-- > 0) throw GeneralSecurityException("keystore busy")
            local
        }
        key(flaky).detachFromSessionKeyset {
            destroySession()
            afterReset = true
        }
        try {
            key(flaky).get()
            fail("the transient failure must not be taken for a missing key")
        } catch (_: eg.masroof.ingest.LocalIdKeyUnavailableException) {
            // not swallowed: no fresh key is made over the real one
        }
        assertArrayEquals(original, key(flaky).get())
    }

    private var failuresLeft = 1

    @Test
    fun `REQ-SEC-065 a kill at every step of the move loses nothing`() {
        val original = legacyKey()
        // steps: write the local entry (0), remove the legacy entry (1); the deletion itself is the step in between
        for (failAt in 0..1) {
            prefs.map.clear()
            legacyPrefs.map.clear()
            session = inMemoryAead()
            legacyKey()
            val faultyCurrent = MemPrefs().also { it.failAt = if (failAt == 0) 0 else -1 }
            val faultyLegacy =
                MemPrefs().also {
                    it.map.putAll(legacyPrefs.map)
                    it.failAt = if (failAt == 1) 0 else -1
                }
            try {
                key(current = faultyCurrent, legacy = faultyLegacy).detachFromSessionKeyset { destroySession() }
            } catch (_: IOException) {
                // killed here
            }
            // "restart": new objects over the same stores
            assertArrayEquals(
                "kill at step $failAt",
                original,
                key(
                    current = faultyCurrent.also { it.failAt = -1 },
                    legacy =
                        faultyLegacy.also {
                            it.failAt =
                                -1
                        },
                ).get(),
            )
        }
    }

    @Test
    fun `REQ-SEC-065 a local entry under a PROVABLY lost keyset falls back to the legacy one before making a new key`() {
        val original = legacyKey()
        prefs.map["k_local"] = Base64.encodeToString(inMemoryAead().encrypt(ByteArray(32), aad), Base64.NO_WRAP)
        assertArrayEquals(original, key(lost = { true }).get())
        assertArrayEquals(original, key().get())
    }

    @Test
    fun `REQ-SEC-065 a local entry that does not open while the keyset is not provably lost is never replaced`() {
        legacyKey()
        val stale = Base64.encodeToString(inMemoryAead().encrypt(ByteArray(32), aad), Base64.NO_WRAP)
        prefs.map["k_local"] = stale
        try {
            key().get()
            fail("must throw")
        } catch (_: eg.masroof.ingest.LocalIdKeyUnavailableException) {
            // try later
        }
        assertEquals(stale, prefs.map["k_local"])
    }

    @Test
    fun `REQ-SEC-065 a lost keyset with no legacy key mints one new key and keeps it`() {
        prefs.map["k_local"] = Base64.encodeToString(inMemoryAead().encrypt(ByteArray(32), aad), Base64.NO_WRAP)
        val fresh = key(lost = { true }).get()
        assertArrayEquals(fresh, key().get())
    }

    @Test
    fun `REQ-SEC-065 intake retries with the same key when the key is unavailable and does not drop the message`() =
        kotlinx.coroutines.test.runTest {
            val retry =
                eg.masroof.ingest.intakeResult(
                    1,
                ) { throw eg.masroof.ingest.LocalIdKeyUnavailableException(GeneralSecurityException("busy")) }
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                retry,
            )
        }

    /** The REAL RecoveringAead (it builds lazily inside decrypt, so a failed build looks like a failed open) over [local]. */
    private fun realLocalAead(buildFailures: Int): eg.masroof.data.auth.RecoveringAead {
        var left = buildFailures
        return eg.masroof.data.auth.RecoveringAead(
            build = { if (left-- > 0) throw GeneralSecurityException("keystore busy") else local },
            destroy = {},
        )
    }

    @Test
    fun `REQ-SEC-065 a sealed key whose AEAD fails to build twice throws, is not overwritten, and reads back the same later`() {
        val original = legacyKey()
        key().get()
        // restart: the legacy entry is gone (forgotten), the local entry is the only copy
        legacyPrefs.map.clear()
        val sealed = prefs.map["k_local"]
        val real = realLocalAead(buildFailures = 2)
        try {
            key({ real }).get()
            fail("a key that exists but does not open must be reported, never replaced")
        } catch (_: java.io.IOException) {
            // "try later"
        } catch (_: GeneralSecurityException) {
            // also "try later"
        }
        assertEquals(sealed, prefs.map["k_local"])
        assertArrayEquals(original, key({ real }).get())
    }

    @Test
    fun `REQ-SEC-065 a session keyset that fails to build during the move keeps k and the keyset, and the next start completes it`() {
        val original = legacyKey()
        var failures = 1
        var destroyed = 0
        val flakySession = {
            if (failures-- > 0) throw GeneralSecurityException("keystore busy")
            session
        }
        val k = KeystoreLocalIdKey(prefs, legacyPrefs, { local }, flakySession)
        try {
            k.detachFromSessionKeyset { destroyed++ }
            fail("the failure must reach the caller")
        } catch (_: java.io.IOException) {
            // try later
        } catch (_: GeneralSecurityException) {
            // try later
        }
        assertEquals(0, destroyed)
        assertTrue(legacyPrefs.map["k"] != null)
        assertNull(prefs.map["k_local"])
        assertArrayEquals(original, KeystoreLocalIdKey(prefs, legacyPrefs, { local }, flakySession).get())
    }

    @Test
    fun `REQ-SEC-065 Erase all data wipes both entries`() {
        legacyKey()
        val k = key()
        k.get()
        k.wipe()
        assertNull(prefs.map["k_local"])
        assertNull(legacyPrefs.map["k"])
    }
}
