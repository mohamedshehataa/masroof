package eg.masroof.data.wealth

import eg.masroof.data.db.AccountBalanceEntity
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
                .sumOf { bankDelta(it.type, it.amountMinor) }
        return if (creditCard) check.balanceMinor - moved else check.balanceMinor + moved
    }
}
