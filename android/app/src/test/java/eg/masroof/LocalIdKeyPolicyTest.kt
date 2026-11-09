package eg.masroof

import eg.masroof.ingest.Action
import eg.masroof.ingest.BuildObs
import eg.masroof.ingest.Episode
import eg.masroof.ingest.FailKind
import eg.masroof.ingest.Health
import eg.masroof.ingest.Keyset
import eg.masroof.ingest.Obs
import eg.masroof.ingest.OpenObs
import eg.masroof.ingest.classify
import eg.masroof.ingest.decide
import eg.masroof.ingest.decodeEpisode
import eg.masroof.ingest.encodeEpisode
import eg.masroof.ingest.lost
import eg.masroof.ingest.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.CharConversionException
import java.io.IOException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

private const val H = 60L * 60 * 1000
private const val MIN = 60_000L
private const val T0 = 1_000_000_000_000L

/** The pure policy: the oracle of the driver (design §3, §7.1). */
class LocalIdKeyPolicyTest {
    private val none = Health(null, null)

    private fun ep(
        fails: Int = 0,
        corrupt: Int = 0,
        starts: Int = 1,
        age: Long = 0,
        c: Boolean = false,
    ) = Episode(T0 - age, fails, corrupt, starts, T0 - age, 1, c)

    private val failed2 = BuildObs.Failed(FailKind.WEAK, 2)
    private val failed1 = BuildObs.Failed(FailKind.WEAK, 1)
    private val gone = Obs(localEntry = true, legacyEntry = true, localBuild = BuildObs.Built, localOpen = OpenObs.BAD_TAG)

    @Test
    fun `REQ-SEC-065 row 1 builds the local keyset first`() = assertEquals(Action.BuildLocal, decide(Obs(true, true), none, T0))

    @Test
    fun `REQ-SEC-065 row 2 retries a failed first build once at once`() =
        assertEquals(Action.BuildLocal, decide(Obs(true, true, localBuild = failed1), none, T0))

    @Test
    fun `REQ-SEC-065 row 3 recreates a local keyset whose evidence says lost, once per call`() {
        val h = Health(ep(corrupt = 2), null)
        assertEquals(Action.RecreateLocal, decide(Obs(true, true, localBuild = failed2), h, T0))
        assertEquals(Action.Hold(Keyset.LOCAL), decide(Obs(true, true, localBuild = failed2, recreatedThisCall = true), h, T0))
    }

    @Test
    fun `REQ-SEC-065 row 4 holds a local keyset that fails to build before the evidence is in`() =
        assertEquals(Action.Hold(Keyset.LOCAL), decide(Obs(true, true, localBuild = failed2), Health(ep(fails = 2), null), T0))

    @Test
    fun `REQ-SEC-065 row 5 opens the local entry`() =
        assertEquals(Action.OpenLocal, decide(Obs(true, false, localBuild = BuildObs.Built), none, T0))

    @Test
    fun `REQ-SEC-065 row 6 uses a local entry that opened`() =
        assertEquals(Action.UseLocal, decide(Obs(true, true, BuildObs.Built, OpenObs.OPENED), none, T0))

    @Test
    fun `REQ-SEC-065 row 7 builds the session keyset when local is gone and a legacy key exists`() {
        assertEquals(Action.BuildSession, decide(gone, none, T0))
        assertEquals(Action.BuildSession, decide(Obs(false, true, localBuild = BuildObs.Built), none, T0))
    }

    @Test
    fun `REQ-SEC-065 row 8 retries a failed session build once at once`() =
        assertEquals(Action.BuildSession, decide(gone.copy(sessionBuild = failed1), none, T0))

    @Test
    fun `REQ-SEC-065 row 9 drops the legacy key only when the session evidence says lost`() =
        assertEquals(Action.DropLegacy, decide(gone.copy(sessionBuild = failed2), Health(null, ep(fails = 3, age = H)), T0))

    @Test
    fun `REQ-SEC-065 row 10 holds the session keyset before the evidence is in`() =
        assertEquals(Action.Hold(Keyset.SESSION), decide(gone.copy(sessionBuild = failed2), Health(null, ep(fails = 3, age = H - 1)), T0))

    @Test
    fun `REQ-SEC-065 rows 11 12 13 open then move or drop the legacy key`() {
        val built = gone.copy(sessionBuild = BuildObs.Built)
        assertEquals(Action.OpenLegacy, decide(built, none, T0))
        assertEquals(Action.MoveLegacy, decide(built.copy(legacyOpen = OpenObs.OPENED), none, T0))
        assertEquals(Action.DropLegacy, decide(built.copy(legacyOpen = OpenObs.BAD_TAG), none, T0))
    }

    @Test
    fun `REQ-SEC-065 row 14 mints, and says it replaces only after a bad tag`() {
        assertEquals(Action.Mint(false), decide(Obs(false, false, localBuild = BuildObs.Built), none, T0))
        assertEquals(Action.Mint(true), decide(Obs(true, false, BuildObs.Built, OpenObs.BAD_TAG), none, T0))
    }

    @Test
    fun `REQ-SEC-065 decide is total and never escalates unless its row condition holds`() {
        val builds =
            listOf(BuildObs.NotTried, BuildObs.Built) +
                FailKind.entries.flatMap { k -> listOf(1, 2).map { BuildObs.Failed(k, it) } }
        var n = 0
        for (le in listOf(false, true)) {
            for (ge in listOf(false, true)) {
                for (lb in builds) {
                    for (lo in OpenObs.entries) {
                        for (sb in builds) {
                            for (go in OpenObs.entries) {
                                for (rc in listOf(false, true)) {
                                    val o = Obs(le, ge, lb, lo, sb, go, rc)
                                    for (h in listOf(none, Health(ep(corrupt = 2), ep(corrupt = 2)))) {
                                        val a = decide(o, h, T0)
                                        n++
                                        if (a == Action.RecreateLocal) assertTrue(lb is BuildObs.Failed && lb.attempts == 2 && !rc)
                                        if (a == Action.DropLegacy) assertTrue(ge && (sb is BuildObs.Failed || go == OpenObs.BAD_TAG))
                                        if (a is Action.Mint) assertTrue(!ge || false)
                                        if (a is Action.Mint) assertTrue(lb == BuildObs.Built)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(n > 2_592)
    }

    @Test
    fun `REQ-SEC-065 lost - corrupt needs two parses`() {
        assertFalse(lost(Keyset.LOCAL, ep(corrupt = 1), T0))
        assertTrue(lost(Keyset.LOCAL, ep(corrupt = 2), T0))
        assertFalse(lost(Keyset.LOCAL, null, T0))
    }

    @Test
    fun `REQ-SEC-065 lost - corroborated needs 3 counted failures over an hour`() {
        assertFalse(lost(Keyset.LOCAL, ep(fails = 2, age = H, c = true), T0))
        assertFalse(lost(Keyset.LOCAL, ep(fails = 3, age = H - 1000, c = true), T0))
        assertTrue(lost(Keyset.LOCAL, ep(fails = 3, age = H, c = true), T0))
    }

    @Test
    fun `REQ-SEC-065 lost - uncorroborated needs 5 failures over 6 hours and 2 starts, or 24 hours`() {
        assertFalse(lost(Keyset.LOCAL, ep(fails = 4, age = 7 * H, starts = 3), T0))
        assertFalse(lost(Keyset.LOCAL, ep(fails = 5, age = 6 * H - 1000, starts = 2), T0))
        assertFalse(lost(Keyset.LOCAL, ep(fails = 5, age = 6 * H, starts = 1), T0))
        assertTrue(lost(Keyset.LOCAL, ep(fails = 5, age = 6 * H, starts = 2), T0))
        assertFalse(lost(Keyset.LOCAL, ep(fails = 5, age = 24 * H - 1000, starts = 1), T0))
        assertTrue(lost(Keyset.LOCAL, ep(fails = 5, age = 24 * H, starts = 1), T0))
    }

    @Test
    fun `REQ-SEC-065 lost - the session keyset always uses the 1 hour rule`() {
        assertTrue(lost(Keyset.SESSION, ep(fails = 3, age = H, c = false), T0))
        assertFalse(lost(Keyset.SESSION, ep(fails = 2, age = 9 * H, c = false), T0))
    }

    @Test
    fun `REQ-SEC-065 record - failures less than a minute apart count once, a minute apart count twice`() {
        val first = record(null, FailKind.WEAK, false, T0, 1)
        val burst = record(first, FailKind.WEAK, false, T0 + 59_000, 1)
        assertEquals(1, burst.fails)
        assertEquals(2, record(burst, FailKind.WEAK, false, T0 + MIN, 1).fails)
    }

    @Test
    fun `REQ-SEC-065 record - corrupt counts every time, starts once per token, corroboration is sticky`() {
        var e = record(null, FailKind.CORRUPT, false, T0, 7)
        e = record(e, FailKind.CORRUPT, true, T0 + 1, 7)
        assertEquals(2, e.corrupt)
        assertEquals(1, e.starts)
        assertTrue(e.corroborated)
        e = record(e, FailKind.WEAK, false, T0 + 2, 8)
        assertEquals(2, e.starts)
        assertTrue(e.corroborated)
    }

    @Test
    fun `REQ-SEC-065 record - a clock moved back keeps the counts and lowers since`() {
        val e = record(null, FailKind.WEAK, false, T0, 1)
        val back = record(e, FailKind.WEAK, false, T0 - 10 * H, 1)
        assertEquals(2, back.fails)
        assertEquals(T0 - 10 * H, back.since)
    }

    @Test
    fun `REQ-SEC-065 episode codec round-trips and anything else parses as no episode`() {
        val e = Episode(5, 2, 1, 3, 4, 9, true)
        assertEquals(e, decodeEpisode(encodeEpisode(e)))
        for (bad in listOf(
            null,
            "",
            "v1|1|2|3|4|5|6",
            "v1|1|2|3|4|5|6|1|9",
            "v2|1|2|3|4|5|6|1",
            "v1|1|-2|3|4|5|6|1",
            "v1|x|2|3|4|5|6|1",
            "v1|1|2|3|4|5|6|2",
        )) {
            assertNull(bad, decodeEpisode(bad))
        }
    }

    @Test
    fun `REQ-SEC-065 classify - only a keyset that does not parse is corrupt`() {
        assertEquals(FailKind.CORRUPT, classify(RuntimeException(IOException(CharConversionException("x")))))

        class InvalidProtocolBufferException : IOException()
        assertEquals(FailKind.CORRUPT, classify(InvalidProtocolBufferException()))
        for (e in listOf(
            InvalidKeyException("Keystore cannot load the key"),
            UnrecoverableKeyException(),
            ProviderException(),
            IOException("disk"),
            IllegalStateException(),
        )) {
            assertEquals(FailKind.WEAK, classify(e))
        }
    }
}
