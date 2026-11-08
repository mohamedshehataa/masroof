package eg.masroof.data.db

import eg.masroof.core.analytics.AnalyticsRow

/** One stored entry as Ask Masroof reads it: the analytics columns plus the id (to open it) and the account (to filter by it). */
data class AskRow(
    val id: String,
    val type: String,
    val status: String,
    val currency: String,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val categoryId: String?,
    val merchant: String?,
    val accountId: String?,
) {
    fun toAnalytics() = AnalyticsRow(type, status, currency, amountMinor, occurredAtMillis, categoryId, merchant)
}
