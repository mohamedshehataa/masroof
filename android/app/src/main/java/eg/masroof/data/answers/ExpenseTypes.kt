package eg.masroof.data.answers

/**
 * The `expense.type` values (STORY-208 adds [TRANSFER_IN]; there is no schema change, the column is free text). A transfer
 * between the user's own accounts is a TRANSFER out plus a TRANSFER_IN: neither is spending or income (ADR-0009 D2).
 */
object ExpenseTypes {
    const val EXPENSE = "EXPENSE"
    const val INCOME = "INCOME"
    const val REFUND = "REFUND"
    const val TRANSFER = "TRANSFER"
    const val TRANSFER_IN = "TRANSFER_IN"
    const val CASH_WITHDRAWAL = "CASH_WITHDRAWAL"

    private val known = setOf(EXPENSE, INCOME, REFUND, TRANSFER, TRANSFER_IN, CASH_WITHDRAWAL)

    /** A type string this version does not know (a restored backup, a newer app) is a TRANSFER: never spending, never income. */
    fun normalize(type: String?): String = if (type != null && type in known) type else TRANSFER

    /** Money that moves between accounts without being spending or income: shown as a transfer. */
    fun isTransfer(type: String?): Boolean = normalize(type).let { it == TRANSFER || it == TRANSFER_IN }
}
