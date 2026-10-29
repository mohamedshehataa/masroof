package eg.masroof.data.insights

import eg.masroof.api.models.AnalyticsSummary
import eg.masroof.api.models.CategoryBreakdown
import eg.masroof.api.models.Heatmap
import eg.masroof.api.models.MerchantTotal
import eg.masroof.api.models.Trend
import kotlinx.serialization.Serializable

/** Which household period the Insights tab shows. [spec] is the contract's `period` query value. */
enum class InsightsPeriod(
    val spec: String,
) {
    CURRENT("current"),
    PREVIOUS("previous"),
}

/** Number of months in the trend chart (the contract allows 1..36). */
const val TREND_MONTHS = 6

/**
 * Everything the Insights tab needs for one period, exactly as the server computed it. Cached as one JSON document so
 * the screen renders offline from the last successful refresh.
 */
@Serializable
data class InsightsSnapshot(
    val summary: AnalyticsSummary,
    val categories: CategoryBreakdown,
    val trend: Trend,
    val heatmap: Heatmap,
    /** Top merchants of the period, largest first; empty before any merchant has a positive net. */
    val merchants: List<MerchantTotal> = emptyList(),
    val fetchedAtMillis: Long,
    /** Entries of the period in a currency other than EGP; they are not in any figure (REQ-AN-145). */
    val otherCurrencyCount: Int = 0,
)
