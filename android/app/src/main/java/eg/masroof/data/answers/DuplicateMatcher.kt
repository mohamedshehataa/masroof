package eg.masroof.data.answers

import eg.masroof.core.CairoTime
import eg.masroof.data.db.ExpenseEntity

/** Where an entry came from, read from its `expense_source` rows: the channels and one sender id (a label, never message text). */
data class EntrySources(
    val channels: Set<String>,
    val senderId: String? = null,
)

/** One look-alike pair (STORY-173): a manual entry and a captured one. */
data class DuplicatePair(
    val manual: ExpenseEntity,
    val capture: ExpenseEntity,
    val captureSources: EntrySources,
) {
    val key: String get() = DuplicateMatcher.pairKey(manual.id, capture.id)

    // Never prints the entries (merchants, amounts).
    override fun toString() = "DuplicatePair(manual=${manual.id}, capture=${capture.id})"
}

/**
 * REQ-DEDUP-063: the pair rule, pure (no clock, no database). Same currency and amount, one MANUAL entry and one capture-only
 * entry, and either the manual entry has no time and the capture falls on the same Cairo calendar day, or both are timed and at
 * most two hours apart. Days are Cairo days, never UTC days.
 */
object DuplicateMatcher {
    const val MAX_GAP_MS = 2L * 60 * 60 * 1000
    private const val MANUAL = "MANUAL"
    private val captureChannels = setOf("SMS", "PUSH", "BACKFILL")
    private val captureRowSources = setOf("SMS", "PUSH")
    private val mergeableTypes = setOf(ExpenseTypes.EXPENSE, ExpenseTypes.INCOME)

    fun pairKey(
        manualId: String,
        captureId: String,
    ) = "$manualId|$captureId"

    /** A typed entry that nothing was merged into: every source of it is a MANUAL one (a survivor of a merge has more). */
    fun isManual(
        e: ExpenseEntity,
        s: EntrySources,
    ) = e.source == MANUAL && s.channels.isNotEmpty() && s.channels.all { it == MANUAL }

    /** An entry only phone captures made: no MANUAL source (a merge survivor carries the manual one and is never offered again). */
    fun isCaptureOnly(
        e: ExpenseEntity,
        s: EntrySources,
    ) = e.source in captureRowSources && s.channels.isNotEmpty() && captureChannels.containsAll(s.channels)

    private fun usable(e: ExpenseEntity) = e.deletedAtMillis == null && !e.failed && !e.excludedFromTotals

    /**
     * Whether [manual] and [capture] are a look-alike pair right now. [blocked] holds the ids that must never be merged: entries
     * of a refund or transfer link, or that a gold lot or an installment payment points at.
     */
    fun matches(
        manual: ExpenseEntity,
        manualSources: EntrySources,
        capture: ExpenseEntity,
        captureSources: EntrySources,
        blocked: Set<String>,
    ): Boolean =
        manual.id != capture.id &&
            usable(manual) &&
            usable(capture) &&
            manual.id !in blocked &&
            capture.id !in blocked &&
            manual.currency == capture.currency &&
            manual.amountMinor == capture.amountMinor &&
            manual.type == capture.type &&
            manual.type in mergeableTypes &&
            isManual(manual, manualSources) &&
            isCaptureOnly(capture, captureSources) &&
            closeInTime(manual, capture)

    private fun closeInTime(
        manual: ExpenseEntity,
        capture: ExpenseEntity,
    ): Boolean =
        if (!manual.timeKnown) {
            CairoTime.dateOf(manual.occurredAtMillis) == CairoTime.dateOf(capture.occurredAtMillis)
        } else {
            capture.timeKnown && kotlin.math.abs(manual.occurredAtMillis - capture.occurredAtMillis) <= MAX_GAP_MS
        }

    /** Every pair among [entries] that has no card yet answered ("Keep both"), newest capture first. One card per pair. */
    fun pairs(
        entries: List<ExpenseEntity>,
        sources: Map<String, EntrySources>,
        blocked: Set<String>,
        keptApart: Set<String>,
    ): List<DuplicatePair> {
        val none = EntrySources(emptySet())
        val manuals = entries.filter { isManual(it, sources[it.id] ?: none) }
        val captures = entries.filter { isCaptureOnly(it, sources[it.id] ?: none) }.groupBy { it.currency to it.amountMinor }
        val found = mutableListOf<DuplicatePair>()
        for (m in manuals) {
            for (c in captures[m.currency to m.amountMinor].orEmpty()) {
                val cs = sources[c.id] ?: none
                if (pairKey(m.id, c.id) in keptApart) continue
                if (matches(m, sources[m.id] ?: none, c, cs, blocked)) found += DuplicatePair(m, c, cs)
            }
        }
        return found.sortedWith(compareByDescending<DuplicatePair> { it.capture.occurredAtMillis }.thenBy { it.key })
    }
}
