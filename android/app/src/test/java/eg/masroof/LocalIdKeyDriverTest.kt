package eg.masroof

import androidx.work.ListenableWorker
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.KeystoreLocalIdKey
import eg.masroof.ingest.LocalIdKeyUnavailableException
import eg.masroof.ingest.intakeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

internal const val QUARTER = 15L * 60 * 1000
internal const val HOUR = 60L * 60 * 1000

/** One simulated phone: prefs, two keysets, a clock and a notice, with a fresh process on demand. */
internal class KeyWorld {
    val clock = KeyFakeClock()
    val writes = Writes()
    val prefs = FakePrefs(writes)
    val local = FakeKeyset("local", writes, clock)
    val session = FakeKeyset("session", writes, clock) { true }
    val notice = RecordingNotice()
    var token = 1L
    val k0 = ByteArray(32) { (it + 1).toByte() }

    fun process() = KeystoreLocalIdKey(prefs, local, session, { clock.now }, notice, token++)

    fun legacy() {
        session.generation = 1
        prefs.map["k"] = sealUnder(session.aeadFor(1), k0)
    }

    fun localEntry() {
        local.generation = 1
        prefs.map["k_local"] = sealUnder(local.aeadFor(1), k0)
    }

    /** A `k_local` that a fresh LOCAL keyset rejects (it was sealed under a gone generation). */
    fun badLocalEntry() {
        local.generation = 2
        prefs.map["k_local"] = sealUnder(local.aeadFor(1), k0)
    }

    fun tryGet(p: KeystoreLocalIdKey = process()): ByteArray? =
        try {
            p.get()
        } catch (_: LocalIdKeyUnavailableException) {
            null
        }

    /** Calls get() every 15 minutes (a new process every hour) until a key comes back or [limit] has passed; returns it with the time. */
    fun untilKey(limit: Long): Pair<ByteArray, Long>? {
        val start = clock.now
        var p = process()
        while (clock.now - start <= limit) {
            tryGet(p)?.let { return it to (clock.now - start) }
            clock.advance(QUARTER)
            if ((clock.now - start) % HOUR == 0L) p = process()
        }
        return null
    }
}

/** The driver over fakes: one test per state and event of the design (§2) and the regression repros (§7.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalIdKeyDriverTest {
    private val w = KeyWorld()

    @Test
    fun `REQ-SEC-065 N a fresh install mints one key, seals it, and posts no note`() {
        val k = w.process().get()
        assertEquals(32, k.size)
        assertArrayEquals(k, w.process().get())
        assertNotNull(w.prefs.map["k_local"])
        assertEquals(0, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 G the legacy key moves to the local keyset and k goes after the read-back`() {
        w.legacy()
        assertArrayEquals(w.k0, w.process().get())
        assertNull(w.prefs.map["k"])
        assertArrayEquals(w.k0, w.process().get())
        assertEquals(0, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 L2a a local keyset that fails to build twice holds and returns the same key on the next call`() {
        w.localEntry()
        w.local.fault = Fault { throw weak() }
        assertNull(w.tryGet())
        assertEquals("k_local is never rewritten", w.prefs.map["k_local"], w.prefs.map["k_local"])
        w.local.fault = null
        assertArrayEquals(w.k0, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 G plus a busy local keyset holds with k and the session keyset untouched`() {
        w.legacy()
        val sealed = w.prefs.map["k"]
        w.local.fault = Fault { throw weak() }
        assertNull(w.tryGet())
        assertEquals(sealed, w.prefs.map["k"])
        assertEquals(0, w.session.recreates)
        w.local.fault = null
        assertArrayEquals(w.k0, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 I10 the hot path of a healthy key writes nothing and builds nothing fresh`() {
        w.localEntry()
        val p = w.process()
        p.get()
        val commits = w.prefs.commits
        val builds = w.local.builds
        repeat(5) { p.get() }
        assertEquals(commits, w.prefs.commits)
        assertEquals("one cached build per call, no fresh rebuilds", builds + 5, w.local.builds)
    }

    @Test
    fun `REQ-SEC-065 LG a local entry that opens makes k redundant and it is removed`() {
        w.localEntry()
        w.legacy()
        assertArrayEquals(w.k0, w.process().get())
        assertNull(w.prefs.map["k"])
    }

    @Test
    fun `REQ-SEC-065 X a bad tag under a built keyset is certain loss, one new key and one note`() {
        w.badLocalEntry()
        val k = w.process().get()
        assertTrue(!k.contentEquals(w.k0))
        assertArrayEquals(k, w.process().get())
        assertEquals(1, w.notice.posts)
        assertNull(w.prefs.map["renewed"])
    }

    @Test
    fun `REQ-SEC-065 XG a bad local tag with a legacy key moves the legacy key`() {
        w.badLocalEntry()
        w.legacy()
        assertArrayEquals(w.k0, w.process().get())
        assertEquals(0, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 L plus a corrupt local keyset recreates at once and settles on one new key`() {
        w.localEntry()
        w.local.fault = Fault(clearedByDestroy = true) { throw corrupt() }
        val k = w.process().get()
        assertEquals(1, w.local.recreates)
        assertArrayEquals(k, w.process().get())
        assertEquals(1, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 L plus a corroborated permanent loss recreates within the 1 h window and holds before it`() {
        w.localEntry()
        w.local.fault = Fault(clearedByDestroy = true) { throw keyGone() }
        val (key, after) = w.untilKey(2 * HOUR)!!
        assertTrue("held for the window: $after", after >= HOUR)
        assertTrue(after <= HOUR + QUARTER)
        assertTrue(!key.contentEquals(w.k0))
        assertEquals(1, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 L4 k_local lost, legacy present and the session master gone for good held then one new key in 75 min, none dropped`() =
        runTest {
            w.badLocalEntry()
            w.legacy()
            w.session.fault = Fault { throw java.security.InvalidKeyException("Keystore cannot load the key") }
            var dropped = 0
            var held = 0
            var returned: ByteArray? = null
            var p = w.process()
            val start = w.clock.now
            while (returned == null && w.clock.now - start <= HOUR + QUARTER) {
                val result =
                    intakeResult(attempt = 50, hold = { held++ }) {
                        returned = p.get()
                        Disposition.RECORDED
                    }
                if (result == ListenableWorker.Result.failure()) dropped++
                w.clock.advance(QUARTER)
                if ((w.clock.now - start) % HOUR == 0L) p = w.process()
            }
            assertEquals(0, dropped)
            assertTrue("the message was held while the key was unavailable", held >= 1)
            assertNotNull("a key within 1 h 15 min", returned)
            assertEquals(1, w.notice.posts)
            assertNull(w.prefs.map["k"])
        }

    @Test
    fun `REQ-SEC-065 M1 a missing alias with a building keyset destroys nothing`() {
        w.localEntry()
        val gone = FakeKeyset("local", w.writes, w.clock) { true } // corroborates everything
        gone.generation = 1
        val k = KeystoreLocalIdKey(w.prefs, gone, w.session, { w.clock.now }, w.notice, 9)
        // the local entry was sealed under the other fake's generation 1 key; reuse that primitive so it opens
        val same = object : eg.masroof.ingest.KeysetPort by w.local {}
        val key = KeystoreLocalIdKey(w.prefs, same, w.session, { w.clock.now }, w.notice, 10)
        assertArrayEquals(w.k0, key.get())
        assertEquals(0, gone.recreates)
        assertEquals(0, w.local.recreates)
        assertNotNull(k)
    }

    @Test
    fun `REQ-SEC-065 L3c nothing remembered from an earlier recreation can overwrite k_local later`() {
        w.badLocalEntry()
        val k2 = w.process().get()
        val sealed = w.prefs.map["k_local"]
        w.local.fault = Fault { throw weak() }
        repeat(3) {
            assertNull(w.tryGet())
            w.clock.advance(QUARTER)
        }
        assertEquals(sealed, w.prefs.map["k_local"])
        w.local.fault = null
        assertArrayEquals(k2, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 L3b a keyset recreated by a process that died before sealing is settled by the next process`() {
        w.badLocalEntry() // process A recreated the keyset and died before get() sealed anything
        val k = w.process().get()
        assertArrayEquals(k, w.process().get())
        assertArrayEquals(k, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 a failed prefs write is a hold and is not counted as a Keystore failure`() {
        w.localEntry()
        w.legacy()
        w.prefs.map.remove("k_local")
        w.local.generation = 0
        w.prefs.failNextCommit = true
        assertNull(w.tryGet())
        assertNull("no episode: storage is not the Keystore", w.prefs.map["h_local"])
        assertArrayEquals(w.k0, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 N4 a note is posted once, after the flag is gone, and a kill before the removal posts nothing`() {
        w.badLocalEntry()
        // the writes of the replacing mint: 1 (k_local + renewed); the flag removal is write 2
        w.writes.killAt = 2
        try {
            w.process().get()
            fail("killed")
        } catch (_: KeyKilled) {
            // process died before the flag was removed
        }
        assertEquals(0, w.notice.posts)
        w.writes.killAt = -1
        val k = w.process().get()
        assertEquals(1, w.notice.posts)
        assertArrayEquals(k, w.process().get())
        assertEquals(1, w.notice.posts)
    }

    @Test
    fun `REQ-SEC-065 N5 install replaces the key atomically, removes k and the flag, and is idempotent`() {
        w.legacy()
        w.prefs.map["renewed"] = "1"
        val k = ByteArray(32) { 9 }
        val p = w.process()
        p.install(k)
        p.install(k)
        assertArrayEquals(k, w.process().get())
        assertNull(w.prefs.map["k"])
        assertNull(w.prefs.map["renewed"])
        for (bad in listOf(ByteArray(31) { 1 }, ByteArray(32))) {
            try {
                p.install(bad)
                fail("a key is 32 non-zero bytes")
            } catch (_: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun `REQ-SEC-065 N5 a transient local failure during install leaves the old key intact`() {
        w.localEntry()
        w.local.fault = Fault { throw weak() }
        try {
            w.process().install(ByteArray(32) { 5 })
            fail("held")
        } catch (_: LocalIdKeyUnavailableException) {
            // old key kept
        }
        w.local.fault = null
        assertArrayEquals(w.k0, w.process().get())
    }

    @Test
    fun `REQ-SEC-065 wipe leaves an empty file`() {
        w.localEntry()
        w.legacy()
        w.process().wipe()
        assertTrue(w.prefs.map.isEmpty())
    }

    @Test
    fun `REQ-SEC-065 detach destroys the session keyset only after k_local opens, then removes k`() {
        w.legacy()
        var destroyed = 0
        w.process().detachFromSessionKeyset { destroyed++ }
        assertEquals(1, destroyed)
        assertNull(w.prefs.map["k"])
        // a held key never reaches the destroy
        val w2 = KeyWorld()
        w2.legacy()
        w2.local.fault = Fault { throw weak() }
        var destroyed2 = 0
        try {
            w2.process().detachFromSessionKeyset { destroyed2++ }
            fail("held")
        } catch (_: LocalIdKeyUnavailableException) {
            // the forget step stays pending
        }
        assertEquals(0, destroyed2)
        assertTrue(w2.prefs.map["k"] != null)
    }

    @Test
    fun `REQ-SEC-065 I1 nothing but Unavailable leaves get for every kind of failure`() {
        for (make in ALL_WEAK + listOf({ corrupt() }, { keyGone() })) {
            val world = KeyWorld()
            world.localEntry()
            world.local.fault = Fault { throw make() }
            try {
                world.process().get()
            } catch (_: LocalIdKeyUnavailableException) {
                continue
            } catch (e: IOException) {
                fail("raw $e")
            }
        }
    }

    @Test
    fun `REQ-SEC-065 a bad tag through a cached primitive is confirmed with a fresh build before it counts`() {
        w.localEntry()
        val stale = w.local.aeadFor(7) // a cached primitive of another generation
        var calls = 0
        val port =
            object : eg.masroof.ingest.KeysetPort {
                override fun build(fresh: Boolean) = if (!fresh && calls++ == 0) stale else w.local.aeadFor(1)

                override fun corroborates(e: Throwable) = false

                override fun recreate() = fail("must not recreate")
            }
        val key = KeystoreLocalIdKey(w.prefs, port, w.session, { w.clock.now }, w.notice, 3)
        assertArrayEquals("the fresh build opens it: no loss", w.k0, key.get())
        assertEquals(0, w.notice.posts)
        assertFalse(w.prefs.map.containsKey("renewed"))
    }
}
