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

    /** Equal keys, or one a whole-word prefix of the other ("netflix" ~ "netflix subscription"). */
    fun keysMatch(
        a: String,
        b: String,
    ): Boolean = a == b || a.startsWith("$b ") || b.startsWith("$a ")

    /** [candidate] is within 10% of [reference] (integer maths, the boundary included). */
    fun withinTolerance(
        candidate: Long,
        reference: Long,
    ): Boolean = reference > 0 && abs(candidate - reference) * PERCENT <= reference * AMOUNT_TOLERANCE_PERCENT

    /**
     * The entry that paid the renewal due on [renewal]: same merchant key and currency, amount within 10%, date within 3 days;
     * the closest date wins (the lower id on a tie). Entries in [used] already paid another renewal and are skipped.
     */
    fun match(
        key: String,
        amountMinor: Long,
        currency: String,
        renewal: LocalDate,
        entries: List<EntryFact>,
        used: Set<String>,
    ): EntryFact? {
        val near =
            entries.filter {
                it.id !in used &&
                    keysMatch(it.merchantKey, key) &&
                    it.currency == currency &&
                    abs(ChronoUnit.DAYS.between(renewal, it.date)) <= DATE_WINDOW_DAYS
            }
        val byDate = compareBy<EntryFact>({ abs(ChronoUnit.DAYS.between(renewal, it.date)) }, { it.id })
        // First pass: the amount is within 10%. Second pass (a price change): between half and double the stored amount.
        return near.filter { withinTolerance(it.amountMinor, amountMinor) }.minWithOrNull(byDate)
            ?: near.filter { amountMinor > 0 && it.amountMinor * 2 >= amountMinor && it.amountMinor <= amountMinor * 2 }.minWithOrNull(
                byDate,
            )
    }

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
                if (existingKeys.any { keysMatch(it, key) } || dismissedKeys.any { keysMatch(it, key) }) return@mapNotNull null
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
