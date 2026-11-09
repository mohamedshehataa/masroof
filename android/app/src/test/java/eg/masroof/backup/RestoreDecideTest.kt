package eg.masroof.backup

import eg.masroof.data.backup.CommittedSlot
import eg.masroof.data.backup.Decision
import eg.masroof.data.backup.Journal
import eg.masroof.data.backup.JournalRead
import eg.masroof.data.backup.PendingSlot
import eg.masroof.data.backup.RestoreJournal
import eg.masroof.data.backup.StageHashes
import eg.masroof.data.backup.Step
import eg.masroof.data.backup.decide
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The oracle for design 4.2 (REQ-OPS-179): every journal read x committed x pending x application_id against an independent `when`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreDecideTest {
    private val h = StageHashes("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
    private val g1 = 1001
    private val g2 = 2002
    private val other = 3003

    private fun known(step: Step) = CommittedSlot.Known(g1, h, step, 0)

    /** The independent statement of the table: written out row by row, not derived from the production code. */
    private fun expected(
        read: JournalRead,
        a: Int,
    ): String =
        when (read) {
            JournalRead.Absent -> "Clean"
            JournalRead.Unreadable -> "Unreadable"
            JournalRead.Corrupt -> "Partial(drop=null,unknown=true)"
            is JournalRead.Ok -> {
                val c = read.j.committed
                val p = read.j.pending
                when {
                    c == null && p == null -> "Clean"
                    p != null && a == p.gen -> "Promote(${p.gen})"
                    p != null && a != p.from -> "Partial(drop=null,unknown=true)"
                    c == null -> "Clean"
                    c is CommittedSlot.Unknown -> "Partial(drop=${p?.gen},unknown=true)"
                    c is CommittedSlot.Known && a == c.gen -> "Finish(${c.gen},drop=${p?.gen})"
                    else -> "Partial(drop=${p?.gen},unknown=false)"
                }
            }
        }

    private fun show(d: Decision): String =
        when (d) {
            Decision.Clean -> "Clean"
            Decision.Unreadable -> "Unreadable"
            is Decision.Promote -> "Promote(${d.p.gen})"
            is Decision.Finish -> "Finish(${d.c.gen},drop=${d.dropPending?.gen})"
            is Decision.Partial -> "Partial(drop=${d.dropPending?.gen},unknown=${d.keepAsUnknown})"
        }

    @Test
    fun `REQ-OPS-179 decide gives the table's answer for every journal state and application id`() {
        val committeds: List<CommittedSlot?> = listOf(null, CommittedSlot.Unknown) + Step.entries.map(::known)
        val pendings: List<PendingSlot?> = listOf(null, PendingSlot(g2, g1, h), PendingSlot(g2, other, h))
        val reads =
            listOf(JournalRead.Absent, JournalRead.Unreadable, JournalRead.Corrupt) +
                committeds.flatMap { c -> pendings.map { p -> JournalRead.Ok(Journal(c, p)) } }
        var n = 0
        for (read in reads) {
            for (a in listOf(0, g1, g2, other)) {
                assertEquals("read=$read a=$a", expected(read, a), show(decide(read, a)))
                n++
            }
        }
        assertEquals("every row was exercised", (3 + 7 * 3) * 4, n)
    }

    @Test
    fun `REQ-OPS-179 the loop-5 case decides Finish for the earlier generation, never Partial`() {
        // G1 committed and unfinished, a pending G2 that did not commit (A is still G1): the pending slot is dropped, G1 is finished
        val read = JournalRead.Ok(Journal(known(Step.SETTINGS_DONE), PendingSlot(g2, g1, h)))
        val d = decide(read, g1)
        assertEquals("Finish(1001,drop=2002)", show(d))
    }

    // ---- strict parse -------------------------------------------------------------------------------------------------------------

    private val good = RestoreJournal.encode(Journal(known(Step.SWAPPING), PendingSlot(g2, g1, h)))

    @Test
    fun `REQ-OPS-179 a valid journal round-trips and the strict cases are Corrupt`() {
        assertEquals(JournalRead.Ok(Journal(known(Step.SWAPPING), PendingSlot(g2, g1, h))), RestoreJournal.parse(good))
        val bad =
            listOf(
                "",
                "{",
                good.take(good.length / 2),
                good.replace("\"v\":2", "\"v\":3"),
                good.replace("\"v\":2", "\"v\":\"2\""),
                good.replace(h.tables, "a".repeat(63)),
                good.replace(h.tables, "A".repeat(64)),
                good.replace("\"gen\":1001", "\"gen\":0"),
                good.replace("\"gen\":2002", "\"gen\":1001"),
                good.replace("SWAPPING", "FLYING"),
                good.replace("\"attempts\":0", "\"attempts\":-1"),
                good.replace("\"attempts\":0", "\"attempts\":\"0\""),
                good.replace("\"attempts\":0", "\"attempts\":0.0"),
                good.replace("\"gen\":1001", "\"gen\":\"1001\""),
                good.replace("}}", "},\"extra\":1}"),
                good.replace("\"v\":2,", "\"v\":2,\"v\":2,"),
                " $good",
                "$good\n",
                "{\"v\":2}",
                "{\"v\":2,\"committed\":null,\"pending\":null,\"x\":1}",
            )
        for (text in bad) assertEquals("text=$text", JournalRead.Corrupt, RestoreJournal.parse(text))
    }

    @Test
    fun `REQ-OPS-179 the legacy marker and a journal larger than 4 KiB are Corrupt without being parsed`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir").orEmpty(), "journal-parse-" + System.nanoTime()).also { it.mkdirs() }
        try {
            val disk =
                eg.masroof.data.backup
                    .RestoreDisk(eg.masroof.data.backup.RestoreHooks.None)
            val journal = RestoreJournal(disk, java.io.File(dir, "restore-journal.json"), java.io.File(dir, "restore-marker.json"))
            assertEquals(JournalRead.Absent, journal.read())
            java.io.File(dir, "restore-marker.json").writeText("{\"nonce\":1}")
            assertEquals("a legacy marker alone", JournalRead.Corrupt, journal.read())
            java.io.File(dir, "restore-journal.json").writeText(good + " ".repeat(5000))
            assertEquals("larger than 4 KiB", JournalRead.Corrupt, journal.read())
            java.io.File(dir, "restore-journal.json").writeText(good)
            assertEquals(JournalRead.Ok(Journal(known(Step.SWAPPING), PendingSlot(g2, g1, h))), journal.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
