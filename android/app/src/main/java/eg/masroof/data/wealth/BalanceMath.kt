package eg.masroof.data.wealth

import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AccountFlowRow

/** Balance of an account: the last confirmed check plus the money that moved after it. A card's balance is what is owed. */
object BalanceMath {
    private const val EXPENSE = "EXPENSE"
    private const val REFUND = "REFUND"
    private const val INCOME = "INCOME"
    private const val CASH = "CASH_WITHDRAWAL"
    private const val TRANSFER = "TRANSFER"

    /** The D2 status rule: a confirmed entry and one waiting for review both moved money. */
    fun counts(status: String): Boolean = status == "CONFIRMED" || status == "PENDING_REVIEW"

    /** Money in (+) or out (-) of a bank, cash or wallet account for one entry; unknown types move nothing. */
    fun bankDelta(
        type: String,
        amountMinor: Long,
    ): Long =
        when (type) {
            INCOME, REFUND -> amountMinor
            EXPENSE, CASH, TRANSFER -> -amountMinor
            else -> 0L
        }

    /** The latest check wins; a tie on time goes to the one written last. */
    fun latest(checks: List<AccountBalanceEntity>): AccountBalanceEntity? =
        checks.maxWithOrNull(compareBy({ it.atMillis }, { it.createdAtMillis }))

    /**
     * [check] plus the flows after it (strictly later, same currency, counted status). For a credit card the result is the
     * outstanding amount: spending raises it, refunds and payments lower it.
     */
    fun balance(
        check: AccountBalanceEntity,
        rows: List<AccountFlowRow>,
        creditCard: Boolean,
    ): Long {
        val moved =
            rows
                .filter { it.currency == check.currency && it.occurredAtMillis > check.atMillis && counts(it.status) }
                .fold(0L) { acc, r -> saturatingAdd(acc, bankDelta(r.type, r.amountMinor)) }
        return saturatingAdd(check.balanceMinor, if (creditCard) -moved else moved)
    }

    /**
     * Which entries belong to which active account. An entry names its account, or (SMS captures) only the card's last four:
     * then it goes to the one active account of the same currency with that last four. When two or more match, the entry is
     * attributed to none rather than counted twice (the user fixes it by giving the cards different last digits).
     */
    fun attribute(
        accounts: List<AccountEntity>,
        rows: List<AccountFlowRow>,
    ): Map<String, List<AccountFlowRow>> {
        val active = accounts.filter { !it.archived }
        val byId = active.associateBy { it.id }
        val result = mutableMapOf<String, MutableList<AccountFlowRow>>()
        rows.forEach { r ->
            val last4 = r.instrumentLast4
            val named = r.accountId
            val target =
                when {
                    named != null -> byId[named]
                    last4 != null -> active.filter { it.last4 == last4 && it.currency == r.currency }.singleOrNull()
                    else -> null
                }
            if (target != null) result.getOrPut(target.id) { mutableListOf() } += r
        }
        return result
    }
}

/** Addition that clamps at the Long limits instead of wrapping or throwing, so absurd stored amounts cannot crash a screen. */
internal fun saturatingAdd(
    a: Long,
    b: Long,
): Long =
    try {
        Math.addExact(a, b)
    } catch (_: ArithmeticException) {
        if (a > 0 || b > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
