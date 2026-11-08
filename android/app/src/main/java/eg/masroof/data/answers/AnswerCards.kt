package eg.masroof.data.answers

import eg.masroof.core.CairoTime
import eg.masroof.data.categories.MerchantKey
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AccountFlowRow
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.wealth.BalanceMath

/** A purchase a refund may reverse. Typed fields only. */
data class PurchaseOption(
    val id: String,
    val merchant: String?,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
)

data class AccountOption(
    val id: String,
    val label: String,
)

/**
 * One open question on Wallet. A card carries the structured record only (merchant, amount, currency, date) and, for the unknown
 * card, its four-digit tail: never message text.
 */
sealed interface AnswerCard {
    val key: String
    val merchant: String?
    val amountMinor: Long
    val currency: String
    val occurredAtMillis: Long

    /** REQ-EXP-064: "Is this a refund for ...?" */
    data class Refund(
        val entryId: String,
        override val merchant: String?,
        override val amountMinor: Long,
        override val currency: String,
        override val occurredAtMillis: Long,
        val candidates: List<PurchaseOption>,
    ) : AnswerCard {
        override val key get() = "refund-$entryId"
    }

    /** REQ-EXP-065: "Did this go to (come from) one of your accounts?" */
    data class Transfer(
        val entryId: String,
        override val merchant: String?,
        override val amountMinor: Long,
        override val currency: String,
        override val occurredAtMillis: Long,
        val outgoing: Boolean,
        val options: List<AccountOption>,
    ) : AnswerCard {
        override val key get() = "transfer-$entryId"
    }

    /** REQ-EXP-066: a card tail that matches no account. The excerpt is the newest capture from it. */
    data class UnknownCard(
        val last4: String,
        override val merchant: String?,
        override val amountMinor: Long,
        override val currency: String,
        override val occurredAtMillis: Long,
    ) : AnswerCard {
        override val key get() = "card-$last4"
    }

    /** REQ-EXP-067: "Keep track of this cash?" */
    data class Atm(
        val entryId: String,
        override val merchant: String?,
        override val amountMinor: Long,
        override val currency: String,
        override val occurredAtMillis: Long,
    ) : AnswerCard {
        override val key get() = "atm-$entryId"
    }
}

/** Card generation and the matching rules behind the answers: pure functions over entries and accounts (no clock, no database). */
object AnswerCards {
    const val MAX_CARDS = 10
    const val MAX_CANDIDATES = 3
    const val MAX_PICK = 30
    const val CARD_WINDOW_DAYS = 30L
    const val REFUND_WINDOW_DAYS = 60L

    /** How far back the entries are read: the card window plus the refund window. */
    const val LOOKBACK_DAYS = CARD_WINDOW_DAYS + REFUND_WINDOW_DAYS
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val PAIR_WINDOW_MS = DAY_MS
    private const val CHANNEL_SMS = "SMS"
    private const val CHANNEL_PUSH = "PUSH"
    private const val CASH_KIND = "CASH"
    private const val GAM3EYA_PREFIX = "GAM3EYA"
    private val transferMethods = setOf("BANK_TRANSFER", "INSTAPAY")

    /** Two spare days: the windows are Cairo calendar days, the query is in instants. */
    fun lookbackStart(nowMillis: Long): Long = nowMillis - (LOOKBACK_DAYS + 2) * DAY_MS

    private fun ExpenseEntity.captured() = source == CHANNEL_SMS || source == CHANNEL_PUSH

    private fun ExpenseEntity.live() = deletedAtMillis == null && !failed

    private fun ExpenseEntity.flowRow() =
        AccountFlowRow(accountId, instrumentLast4, type, status, amountMinor, currency, occurredAtMillis)

    /** The one active account the entry belongs to (named, or by a last four only one account has), else null. */
    fun ownerOf(
        accounts: List<AccountEntity>,
        e: ExpenseEntity,
        aliases: Map<String, String> = emptyMap(),
    ): String? {
        BalanceMath.accountOf(accounts, e.flowRow())?.let { return it }
        // a card tail linked to an account by the user ("Link to an existing account")
        val linked = e.instrumentLast4?.let { aliases[it] }
        return linked?.takeIf { id -> e.accountId == null && accounts.any { it.id == id && !it.archived } }
    }

    /**
     * The open cards, newest first, at most [MAX_CARDS]. Only captured entries of the last [CARD_WINDOW_DAYS] days ask anything;
     * an entry that was answered, dismissed or linked never asks again.
     */
    fun generate(
        entries: List<ExpenseEntity>,
        accounts: List<AccountEntity>,
        state: AnswerState,
        includeCash: Boolean,
        nowMillis: Long,
    ): List<AnswerCard> {
        val since = CairoTime.dateOf(nowMillis).minusDays(CARD_WINDOW_DAYS)
        val done = state.settledIds(entries.filter { it.live() }.mapTo(HashSet()) { it.id })
        val live = entries.filter { it.live() }
        val fresh = live.filter { it.captured() && !CairoTime.dateOf(it.occurredAtMillis).isBefore(since) && it.id !in done }
        val active = accounts.filter { !it.archived }
        val cards = mutableListOf<AnswerCard>()
        for (e in fresh) {
            val card =
                when (e.type) {
                    ExpenseTypes.REFUND -> refundCard(e, live, always = true)
                    ExpenseTypes.INCOME -> transferCard(e, active, state.aliases, outgoing = false) ?: refundCard(e, live, always = false)
                    ExpenseTypes.TRANSFER -> transferCard(e, active, state.aliases, outgoing = true)
                    else -> null
                }
            if (card != null) cards += card
        }
        cards += unknownCards(fresh, accounts, state)
        if (state.atmChoice == null && !includeCash && active.none { it.kind == CASH_KIND }) {
            fresh.firstOrNull { it.type == ExpenseTypes.CASH_WITHDRAWAL }?.let {
                cards += AnswerCard.Atm(it.id, it.merchant, it.amountMinor, it.currency, it.occurredAtMillis)
            }
        }
        return cards.sortedWith(compareByDescending<AnswerCard> { it.occurredAtMillis }.thenBy { it.key }).take(MAX_CARDS)
    }

    private fun refundCard(
        e: ExpenseEntity,
        live: List<ExpenseEntity>,
        always: Boolean,
    ): AnswerCard.Refund? {
        val candidates = candidates(e, live)
        if (candidates.isEmpty() && !always) return null
        return AnswerCard.Refund(e.id, e.merchant, e.amountMinor, e.currency, e.occurredAtMillis, candidates)
    }

    /** Same merchant key, EXPENSE, amount >= the refund, within [REFUND_WINDOW_DAYS] before it, newest first, at most [MAX_CANDIDATES]. */
    fun candidates(
        refund: ExpenseEntity,
        live: List<ExpenseEntity>,
    ): List<PurchaseOption> {
        val key = MerchantKey.of(refund.merchant)
        if (key.isEmpty()) return emptyList()
        return purchasesBefore(refund, live)
            .filter { MerchantKey.of(it.merchant) == key && it.amountMinor >= refund.amountMinor }
            .take(MAX_CANDIDATES)
            .map { it.toOption() }
    }

    /** "Pick another": every purchase of the same currency in the window, newest first. */
    fun pickList(
        refund: ExpenseEntity,
        live: List<ExpenseEntity>,
    ): List<PurchaseOption> =
        purchasesBefore(refund, live)
            .filter { it.amountMinor >= refund.amountMinor }
            .take(MAX_PICK)
            .map { it.toOption() }

    private fun purchasesBefore(
        refund: ExpenseEntity,
        live: List<ExpenseEntity>,
    ): List<ExpenseEntity> {
        val from = CairoTime.dateOf(refund.occurredAtMillis).minusDays(REFUND_WINDOW_DAYS)
        return live
            .filter {
                it.live() &&
                    it.type == ExpenseTypes.EXPENSE &&
                    it.id != refund.id &&
                    it.currency == refund.currency &&
                    !CairoTime.dateOf(it.occurredAtMillis).isBefore(from) &&
                    it.occurredAtMillis <= refund.occurredAtMillis
            }.sortedWith(compareByDescending<ExpenseEntity> { it.occurredAtMillis }.thenBy { it.id })
    }

    private fun ExpenseEntity.toOption() = PurchaseOption(id, merchant, amountMinor, currency, occurredAtMillis)

    private fun transferCard(
        e: ExpenseEntity,
        active: List<AccountEntity>,
        aliases: Map<String, String>,
        outgoing: Boolean,
    ): AnswerCard.Transfer? {
        val method = e.paymentMethod
        if (method != null && method.startsWith(GAM3EYA_PREFIX)) return null
        if (!outgoing && method !in transferMethods) return null
        // Both sides must be known: a pair with one unknown side would move one balance and change net worth.
        val own = ownerOf(active, e, aliases) ?: return null
        val options =
            active
                .filter { it.currency == e.currency && it.id != own }
                .map { AccountOption(it.id, it.label) }
        if (options.isEmpty()) return null
        return AnswerCard.Transfer(e.id, e.merchant, e.amountMinor, e.currency, e.occurredAtMillis, outgoing, options)
    }

    private fun unknownCards(
        fresh: List<ExpenseEntity>,
        accounts: List<AccountEntity>,
        state: AnswerState,
    ): List<AnswerCard> {
        val known = accounts.mapNotNullTo(HashSet()) { it.last4 }
        val activeIds = accounts.filter { !it.archived }.mapTo(HashSet()) { it.id }
        return fresh
            .filter {
                val last4 = it.instrumentLast4
                last4 != null &&
                    isLast4(last4) &&
                    last4 !in known &&
                    last4 !in state.ignoredCards &&
                    state.aliases[last4]?.let { it in activeIds } != true &&
                    it.accountId == null
            }.groupBy { it.instrumentLast4.orEmpty() }
            .map { (last4, list) ->
                val newest = list.maxWith(compareBy<ExpenseEntity> { it.occurredAtMillis }.thenBy { it.id })
                AnswerCard.UnknownCard(last4, newest.merchant, newest.amountMinor, newest.currency, newest.occurredAtMillis)
            }
    }

    /**
     * The entry on the other side of a transfer that the other bank already announced. It is converted (not duplicated) only when
     * it is certainly the user's own: attributed to [accountId] by its account or a card tail of that account (a linked tail
     * included), the same amount and currency, and on the right side of the send in time (an incoming entry arrives after the
     * send, an outgoing one is sent before the arrival), within a day. An unattributed entry is never converted: the answer adds
     * its own TRANSFER_IN and leaves the real income alone.
     */
    fun counterpart(
        entry: ExpenseEntity,
        accountId: String,
        live: List<ExpenseEntity>,
        accounts: List<AccountEntity>,
        state: AnswerState,
        outgoing: Boolean,
    ): ExpenseEntity? {
        val wanted = if (outgoing) ExpenseTypes.INCOME else ExpenseTypes.TRANSFER
        val taken = state.settledIds(live.filter { it.live() }.mapTo(HashSet()) { it.id })
        return live
            .filter { c -> c.type == wanted && c.id != entry.id && c.id !in taken && isMirror(entry, c, outgoing) }
            .filter { c -> ownerOf(accounts, c, state.aliases) == accountId }
            .minWithOrNull(compareBy<ExpenseEntity> { kotlin.math.abs(it.occurredAtMillis - entry.occurredAtMillis) }.thenBy { it.id })
    }

    private fun isMirror(
        entry: ExpenseEntity,
        c: ExpenseEntity,
        outgoing: Boolean,
    ): Boolean {
        if (!c.live() || !c.captured() || c.currency != entry.currency) return false
        if (c.amountMinor != entry.amountMinor) return false
        if (c.paymentMethod?.startsWith(GAM3EYA_PREFIX) == true) return false
        if (outgoing && c.paymentMethod !in transferMethods) return false
        val delta = c.occurredAtMillis - entry.occurredAtMillis
        return if (outgoing) delta in 0..PAIR_WINDOW_MS else delta in -PAIR_WINDOW_MS..0
    }
}
