package eg.masroof.data.db

import eg.masroof.core.analytics.AnalyticsRow

/**
 * One stored entry as Wallet reads it (STORY-206): the analytics columns, the account it names and the card's last four.
 * SMS captures carry only [instrumentLast4], so the account is resolved with the same rule the balances use.
 */
data class WalletRow(
    val id: String,
    val type: String,
    val status: String,
    val currency: String,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val categoryId: String?,
    val merchant: String?,
    val accountId: String?,
    val instrumentLast4: String?,
) {
    fun toAnalytics() = AnalyticsRow(type, status, currency, amountMinor, occurredAtMillis, categoryId, merchant)

    fun toFlow() = AccountFlowRow(accountId, instrumentLast4, type, status, amountMinor, currency, occurredAtMillis)
}
