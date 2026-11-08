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
            manual.amountMinor > 0 &&
            manual.currency == capture.currency &&
            manual.amountMinor == capture.amountMinor &&
            manual.type == capture.type &&
            manual.type in mergeableTypes &&
            isManual(manual, manualSources) &&
            isCaptureOnly(capture, captureSources) &&
            closeInTime(manual, capture)

    /** STORY-182: the channel and `source` of a row the one-time server import made. */
    const val SERVER_IMPORT = "SERVER_IMPORT"

    /** REQ-DEDUP-066: a server row and a local row are a pair when their times are at most two minutes apart (2:00 yes, 2:01 no). */
    const val SERVER_MAX_GAP_MS = 2L * 60 * 1000
    private const val PENDING_REVIEW = "PENDING_REVIEW"

    /** An imported row still waiting on the household's decision: only server sources, and the status the import set. */
    fun isServerPending(
        e: ExpenseEntity,
        s: EntrySources,
    ) = e.serverId != null && e.source == SERVER_IMPORT && e.status == PENDING_REVIEW && s.channels == setOf(SERVER_IMPORT)

    /** A row the phone made itself that the server never saw: no server id, and no server source (a merged survivor has one). */
    fun isLocalUnsynced(
        e: ExpenseEntity,
        s: EntrySources,
    ) = e.serverId == null && e.source != SERVER_IMPORT && SERVER_IMPORT !in s.channels

    /**
     * REQ-DEDUP-066: [local] (no server id: a converted capture or a typed entry) and [server] (an imported row stored as
     * PENDING_REVIEW) are one purchase when amount, currency and type are equal and the times are at most two minutes apart. The
     * same [blocked] ids as [matches] (a linked or referenced entry is never offered). This reuses the STORY-173 card; its own rule
     * [matches] is unchanged.
     */
    fun matchesServer(
        local: ExpenseEntity,
        localSources: EntrySources,
        server: ExpenseEntity,
        serverSources: EntrySources,
        blocked: Set<String>,
    ): Boolean =
        local.id != server.id &&
            usable(local) &&
            usable(server) &&
            local.id !in blocked &&
            server.id !in blocked &&
            local.amountMinor > 0 &&
            local.currency == server.currency &&
            local.amountMinor == server.amountMinor &&
            local.type == server.type &&
            local.type in mergeableTypes &&
            isLocalUnsynced(local, localSources) &&
            isServerPending(server, serverSources) &&
            kotlin.math.abs(local.occurredAtMillis - server.occurredAtMillis) <= SERVER_MAX_GAP_MS

    private fun closeInTime(
        manual: ExpenseEntity,
        capture: ExpenseEntity,
    ): Boolean =
        if (!manual.timeKnown) {
            CairoTime.dateOf(manual.occurredAtMillis) == CairoTime.dateOf(capture.occurredAtMillis)
        } else {
            capture.timeKnown && kotlin.math.abs(manual.occurredAtMillis - capture.occurredAtMillis) <= MAX_GAP_MS
        }

    /** REQ-DEDUP-066: an imported row waiting on a decision, paired with a local row of the same purchase (local side = `manual`). */
    private fun serverPairs(
        entries: List<ExpenseEntity>,
        sources: Map<String, EntrySources>,
        blocked: Set<String>,
        keptApart: Set<String>,
    ): List<DuplicatePair> {
        val none = EntrySources(emptySet())
        val servers = entries.filter { isServerPending(it, sources[it.id] ?: none) }.groupBy { it.currency to it.amountMinor }
        if (servers.isEmpty()) return emptyList()
        return entries
            .filter { isLocalUnsynced(it, sources[it.id] ?: none) }
            .flatMap { l ->
                servers[l.currency to l.amountMinor]
                    .orEmpty()
                    .filter { s ->
                        pairKey(l.id, s.id) !in keptApart &&
                            matchesServer(l, sources[l.id] ?: none, s, sources[s.id] ?: none, blocked)
                    }.map { s -> DuplicatePair(l, s, sources[s.id] ?: none) }
            }
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
        found += serverPairs(entries, sources, blocked, keptApart)
        return found.sortedWith(compareByDescending<DuplicatePair> { it.capture.occurredAtMillis }.thenBy { it.key })
    }
}
