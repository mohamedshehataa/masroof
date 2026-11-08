package eg.masroof

import eg.masroof.data.answers.DuplicateMatcher
import eg.masroof.data.answers.EntrySources
import eg.masroof.data.db.ExpenseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STORY-173 REQ-DEDUP-063: the pair rule, as a pure function. */
class DuplicateMatcherTest {
    private val manualSources = EntrySources(setOf("MANUAL"))
    private val smsSources = EntrySources(setOf("SMS"), "CIB")

    private fun pairs(
        entries: List<ExpenseEntity>,
        sources: Map<String, EntrySources> = entries.associate { it.id to if (it.source == "MANUAL") manualSources else smsSources },
        blocked: Set<String> = emptySet(),
        kept: Set<String> = emptySet(),
    ) = DuplicateMatcher.pairs(entries, sources, blocked, kept)

    @Test
    fun `REQ-DEDUP-063 an untimed manual entry and an SMS the same Cairo day are one pair`() {
        val found = pairs(listOf(manualEntry("m"), captureEntry("c")))
        assertEquals(listOf("m|c"), found.map { it.key })
    }

    @Test
    fun `REQ-DEDUP-063 a push notification capture pairs the same way`() {
        assertEquals(1, pairs(listOf(manualEntry("m"), captureEntry("c", source = "PUSH"))).size)
    }

    @Test
    fun `REQ-DEDUP-063 two timed entries are a pair up to two hours apart and not a millisecond more`() {
        val at = cairo(DUP_DAY, "12:00")
        val manual = manualEntry("m", at = at, timeKnown = true)
        assertEquals(1, pairs(listOf(manual, captureEntry("c", at = at + DuplicateMatcher.MAX_GAP_MS))).size)
        assertEquals(1, pairs(listOf(manual, captureEntry("c", at = at - DuplicateMatcher.MAX_GAP_MS))).size)
        assertTrue(pairs(listOf(manual, captureEntry("c", at = at + DuplicateMatcher.MAX_GAP_MS + 1))).isEmpty())
        assertTrue(pairs(listOf(manual, captureEntry("c", at = at - DuplicateMatcher.MAX_GAP_MS - 1))).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 a timed manual entry never pairs with a capture whose time is unknown`() {
        val at = cairo(DUP_DAY, "12:00")
        val untimedCapture = captureEntry("c", at = at).copy(timeKnown = false)
        assertTrue(pairs(listOf(manualEntry("m", at = at, timeKnown = true), untimedCapture)).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 other amounts, other currencies and other types make no pair`() {
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c", amount = 145_050))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m").copy(currency = "USD"), captureEntry("c"))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c", type = "INCOME"))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m", type = "INCOME"), captureEntry("c", type = "TRANSFER"))).isEmpty())
        // received money pairs with received money
        assertEquals(1, pairs(listOf(manualEntry("m", type = "INCOME"), captureEntry("c", type = "INCOME"))).size)
    }

    @Test
    fun `REQ-DEDUP-063 a capture on the previous Cairo day makes no pair`() {
        val lateYesterday = captureEntry("c", at = cairo("2026-11-05", "23:55"))
        assertTrue(pairs(listOf(manualEntry("m"), lateYesterday)).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 the Cairo midnight edge uses Cairo days, not UTC days`() {
        // 00:01 Cairo on the 7th is 22:01 UTC on the 6th
        val justAfterMidnight = captureEntry("c", at = cairo("2026-11-07", "00:01"))
        assertEquals(
            "2026-11-06T22:01:00Z",
            java.time.Instant
                .ofEpochMilli(justAfterMidnight.occurredAtMillis)
                .toString(),
        )
        assertTrue(pairs(listOf(manualEntry("m", at = cairo("2026-11-06", "12:00")), justAfterMidnight)).isEmpty())
        // and the other way: the same instant IS the 7th, so a manual entry of the 7th pairs with it
        assertEquals(1, pairs(listOf(manualEntry("m", at = cairo("2026-11-07", "12:00")), justAfterMidnight)).size)
        val lateOnThe6th = captureEntry("c2", at = cairo("2026-11-06", "23:59"))
        assertEquals(1, pairs(listOf(manualEntry("m", at = cairo("2026-11-06", "12:00")), lateOnThe6th)).size)
    }

    @Test
    fun `REQ-DEDUP-063 two manual entries or two captures make no pair`() {
        assertTrue(pairs(listOf(manualEntry("m1"), manualEntry("m2"))).isEmpty())
        assertTrue(pairs(listOf(captureEntry("c1"), captureEntry("c2", source = "PUSH"))).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 a manual entry that matches two captures gets one pair per capture`() {
        val found = pairs(listOf(manualEntry("m"), captureEntry("c1"), captureEntry("c2", source = "PUSH")))
        assertEquals(listOf("m|c1", "m|c2"), found.map { it.key }.sorted())
    }

    @Test
    fun `REQ-DEDUP-064 an entry a merge already made is neither manual nor capture-only, so it is never offered again`() {
        val survivor = captureEntry("c")
        val both = EntrySources(setOf("SMS", "MANUAL"), "CIB")
        val sources = mapOf("c" to both, "m2" to manualSources)
        assertTrue(pairs(listOf(survivor, manualEntry("m2")), sources).isEmpty())
        // a typed entry that gained a capture source is not "manual" any more either
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c2")), mapOf("m" to both, "c2" to smsSources)).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 deleted, failed, excluded and source-less entries make no pair`() {
        assertTrue(pairs(listOf(manualEntry("m").copy(deletedAtMillis = 5L), captureEntry("c"))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c").copy(deletedAtMillis = 5L))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c").copy(failed = true))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m").copy(excludedFromTotals = true), captureEntry("c"))).isEmpty())
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c")), sources = emptyMap()).isEmpty())
        // server-imported rows are not captures
        val imported = mapOf("m" to manualSources, "c" to EntrySources(setOf("SERVER_IMPORT")))
        assertTrue(pairs(listOf(manualEntry("m"), captureEntry("c")), imported).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-063 a linked or referenced entry is never paired`() {
        val entries = listOf(manualEntry("m"), captureEntry("c"))
        assertTrue(pairs(entries, blocked = setOf("c")).isEmpty())
        assertTrue(pairs(entries, blocked = setOf("m")).isEmpty())
    }

    @Test
    fun `REQ-DEDUP-065 a pair that was kept apart never comes back, other pairs do`() {
        val entries = listOf(manualEntry("m"), captureEntry("c1"), captureEntry("c2"))
        assertEquals(listOf("m|c2"), pairs(entries, kept = setOf("m|c1")).map { it.key })
        assertFalse(pairs(entries, kept = setOf("m|c1", "m|c2")).any())
    }

    @Test
    fun `REQ-DEDUP-063 cards are newest capture first`() {
        val older = captureEntry("c1", at = cairo(DUP_DAY, "09:00"))
        val newer = captureEntry("c2", at = cairo(DUP_DAY, "20:00"))
        assertEquals(listOf("m|c2", "m|c1"), pairs(listOf(manualEntry("m"), older, newer)).map { it.key })
    }

    @Test
    fun `REQ-DEDUP-063 zero amounts never make a pair`() {
        assertTrue(pairs(listOf(manualEntry("m", amount = 0), captureEntry("c", amount = 0))).isEmpty())
    }

    private fun startOfCairoDay(date: String) =
        java.time.LocalDate
            .parse(date)
            .atStartOfDay(eg.masroof.core.CairoTime.zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `REQ-DEDUP-063 around the end of Egypt summer time days are still Cairo calendar days`() {
        // 30 Oct 2026: the clocks go back, so the days around it are not 24 hours long; the rule must not care
        val noon30 = manualEntry("m", at = cairo("2026-10-30", "12:00"))
        val first = startOfCairoDay("2026-10-30")
        val next = startOfCairoDay("2026-10-31")
        assertEquals(1, pairs(listOf(noon30, captureEntry("c", at = first))).size)
        assertEquals(1, pairs(listOf(noon30, captureEntry("c", at = next - 1))).size)
        assertTrue(pairs(listOf(noon30, captureEntry("c", at = first - 1))).isEmpty())
        assertTrue(pairs(listOf(noon30, captureEntry("c", at = next))).isEmpty())
        // and the same on the days before and after
        val noon29 = manualEntry("m29", at = cairo("2026-10-29", "12:00"))
        assertEquals(1, pairs(listOf(noon29, captureEntry("c29", at = first - 1))).size)
    }

    @Test
    fun `REQ-DEDUP-063 two timed entries are two hours apart in elapsed time across the clock change`() {
        val at = startOfCairoDay("2026-10-30") - 30 * 60 * 1000
        val manual = manualEntry("m", at = at, timeKnown = true)
        assertEquals(1, pairs(listOf(manual, captureEntry("c", at = at + DuplicateMatcher.MAX_GAP_MS))).size)
        assertTrue(pairs(listOf(manual, captureEntry("c", at = at + DuplicateMatcher.MAX_GAP_MS + 1))).isEmpty())
    }
}
