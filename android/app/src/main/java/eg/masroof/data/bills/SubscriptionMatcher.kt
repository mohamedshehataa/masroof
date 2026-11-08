package eg.masroof.data.bills

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** What the matcher and the suggester need of a captured or manual entry. [merchantKey] is [SubscriptionMatcher.keyOf]. */
data class EntryFact(
    val id: String,
    val merchantKey: String,
    val merchantName: String,
    val amountMinor: Long,
    val currency: String,
    val date: LocalDate,
    val income: Boolean = false,
)

/** "Looks like a monthly payment": a merchant paid about monthly with about the same amount. */
data class Suggestion(
    val key: String,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val nextRenewal: LocalDate,
)

object SubscriptionMatcher {
    const val AMOUNT_TOLERANCE_PERCENT = 10L
    const val DATE_WINDOW_DAYS = 3L
    const val SUGGEST_WINDOW_DAYS = 120L
    const val SUGGEST_MIN_COUNT = 3
    const val SUGGEST_MIN_GAP = 25L
    const val SUGGEST_MAX_GAP = 35L
    private const val PERCENT = 100L

    private val NOISE_TOKENS = setOf("com", "net", "eg", "www", "inc", "llc")

    /**
     * Merchant key for matching: lower-case, punctuation and any whitespace (NBSP included) become single spaces, and
     * domain or company tokens (com, net, eg, www, inc, llc) are dropped, so `NETFLIX.COM` and `Netflix` share `netflix`.
     */
    fun keyOf(merchant: String?): String? {
        if (merchant == null) return null
        val spaced = merchant.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        val tokens = spaced.split(' ').filter { it.isNotEmpty() && it !in NOISE_TOKENS }
        return tokens.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** The key a subscription matches by: its typed or preset merchant, else its name. */
    fun keyOfRow(
        merchantKey: String?,
        name: String,
    ): String? = keyOf(merchantKey) ?: keyOf(name)

    /** Words after a brand that make an entry a different merchant ("Orange Money", "Netflix Gift Card"). */
    private val DIFFERENT_MERCHANT_WORDS =
        setOf("money", "cash", "pay", "wallet", "gift", "card", "store", "shop", "eats", "food", "market", "mart", "express")
    private const val MIN_PREFIX_KEY = 3

    /**
     * Whether an entry's key stands for the subscription's key: equal, or (only this direction) the entry key extends the
     * subscription key by whole words ("netflix amsterdam" for "netflix"). Keys under 3 characters never extend, and a wallet or
     * gift-card word after the brand makes it another merchant.
     */
    fun entryKeyMatches(
        entryKey: String,
        subKey: String,
    ): Boolean {
        if (entryKey == subKey) return true
        if (subKey.length < MIN_PREFIX_KEY || !entryKey.startsWith("$subKey ")) return false
        return entryKey.removePrefix("$subKey ").split(' ').none { it in DIFFERENT_MERCHANT_WORDS }
    }

    /** [candidate] is within 10% of [reference] (integer maths, the boundary included). */
    fun withinTolerance(
        candidate: Long,
        reference: Long,
    ): Boolean = reference > 0 && abs(candidate - reference) * PERCENT <= reference * AMOUNT_TOLERANCE_PERCENT

    /** A subscription as the matcher sees it: its key, stored amount, next renewal and the entries it may be paid by. */
    data class RowFacts(
        val id: String,
        val key: String,
        val amountMinor: Long,
        val currency: String,
        val renewal: LocalDate,
        val entries: List<EntryFact>,
        /** Normalised extra keys of a built-in service; they match like the main key (short keys exactly only). */
        val aliases: List<String> = emptyList(),
    )

    /**
     * The best (row id, entry) pair over ALL rows, so one row cannot take an entry that is another row's exact match.
     * First pass: amount within 10%. Only when that finds nothing anywhere, the price-change pass: exact key equality and an
     * amount between half and double. Both need the same currency and a date within 3 days; ranking is exact key, then closest
     * date, then ids. Entries in [used] are skipped.
     */
    fun bestMatch(
        rows: List<RowFacts>,
        used: Set<String>,
    ): Pair<String, EntryFact>? = bestPair(rows, used, false) ?: bestPair(rows, used, true)

    private fun bestPair(
        rows: List<RowFacts>,
        used: Set<String>,
        priceChange: Boolean,
    ): Pair<String, EntryFact>? =
        rows
            .flatMap { row ->
                // A price change is believed only when the entry is the one candidate of the row in its window.
                val candidates = row.entries.filter { canPay(row, it, used) }
                if (priceChange && candidates.size != 1) {
                    emptyList()
                } else {
                    candidates.mapNotNull { e -> rankOf(row, e, priceChange)?.let { it to (row.id to e) } }
                }
            }.minWithOrNull { x, y -> compareKeys(x.first, y.first) }
            ?.second

    private fun canPay(
        row: RowFacts,
        e: EntryFact,
        used: Set<String>,
    ): Boolean {
        val days = abs(ChronoUnit.DAYS.between(row.renewal, e.date))
        return e.id !in used &&
            e.currency == row.currency &&
            days <= DATE_WINDOW_DAYS &&
            (listOf(row.key) + row.aliases).any { entryKeyMatches(e.merchantKey, it) }
    }

    /** Sort key (exact key first, closest date, entry id, row id) when [e] can pay [row] in this pass, else null. */
    private fun rankOf(
        row: RowFacts,
        e: EntryFact,
        priceChange: Boolean,
    ): List<Comparable<*>>? {
        val days = abs(ChronoUnit.DAYS.between(row.renewal, e.date))
        val exact = e.merchantKey == row.key || e.merchantKey in row.aliases
        val amountOk =
            if (priceChange) {
                exact &&
                    inPriceBand(
                        e.amountMinor,
                        row.amountMinor,
                    )
            } else {
                withinTolerance(e.amountMinor, row.amountMinor)
            }
        return if (amountOk) listOf(if (exact) 0 else 1, days, e.id, row.id) else null
    }

    private fun inPriceBand(
        candidate: Long,
        stored: Long,
    ): Boolean = stored > 0 && candidate * 2 >= stored && candidate <= stored * 2

    @Suppress("UNCHECKED_CAST")
    private fun compareKeys(
        a: List<Comparable<*>>,
        b: List<Comparable<*>>,
    ): Int {
        for (i in a.indices) {
            val c = (a[i] as Comparable<Any>).compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    /** The entry that would pay the single renewal due on [renewal] (see [bestMatch]); used by tests and one-row callers. */
    fun match(
        key: String,
        amountMinor: Long,
        currency: String,
        renewal: LocalDate,
        entries: List<EntryFact>,
        used: Set<String>,
    ): EntryFact? = bestMatch(listOf(RowFacts("row", key, amountMinor, currency, renewal, entries)), used)?.second

    /**
     * Merchants with at least 3 entries in the last 120 days, each 25 to 35 days after the one before, with amounts within 10%
     * of the latest one, that have no subscription ([existingKeys]) and were not dismissed.
     */
    fun suggest(
        expenses: List<EntryFact>,
        existingKeys: Set<String>,
        dismissedKeys: Set<String>,
        today: LocalDate,
    ): List<Suggestion> {
        val from = today.minusDays(SUGGEST_WINDOW_DAYS)
        return expenses
            .filter { !it.income && !it.date.isBefore(from) && !it.date.isAfter(today) }
            .groupBy { it.merchantKey to it.currency }
            .mapNotNull { (group, rows) ->
                val key = group.first
                if (existingKeys.any { entryKeyMatches(key, it) } || dismissedKeys.any { entryKeyMatches(key, it) }) return@mapNotNull null
                suggestionOf(key, group.second, rows, today)
            }.sortedBy { it.key }
    }

    /** The latest entry on or before [today] that looks like a payment of this subscription (same key, currency, amount within 10%). */
    fun lastPaid(
        key: String,
        amountMinor: Long,
        currency: String,
        income: Boolean,
        entries: List<EntryFact>,
        today: LocalDate,
    ): LocalDate? =
        entries
            .filter {
                it.income == income &&
                    it.merchantKey == key &&
                    it.currency == currency &&
                    withinTolerance(it.amountMinor, amountMinor) &&
                    !it.date.isAfter(today)
            }.maxOfOrNull { it.date }

    private fun suggestionOf(
        key: String,
        currency: String,
        rows: List<EntryFact>,
        today: LocalDate,
    ): Suggestion? {
        val sorted = rows.sortedWith(compareBy({ it.date }, { it.id }))
        if (sorted.size < SUGGEST_MIN_COUNT) return null
        val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.any { it < SUGGEST_MIN_GAP || it > SUGGEST_MAX_GAP }) return null
        val latest = sorted.last()
        if (sorted.any { !withinTolerance(it.amountMinor, latest.amountMinor) }) return null
        val next = RenewalMath.rollTo(RenewalMath.nextAfter(latest.date, Cadence.MONTHLY), Cadence.MONTHLY, today)
        return Suggestion(key, latest.merchantName.trim(), latest.amountMinor, currency, next)
    }
}
