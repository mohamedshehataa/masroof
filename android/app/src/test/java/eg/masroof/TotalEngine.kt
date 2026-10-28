package eg.masroof

import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.ForecastResult
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.core.analytics.SummaryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** An engine whose current-period total comes from [totals]; for view-model tests that do not need Room. */
class TotalEngine(
    private val totals: Flow<Long>,
) : AnalyticsEngine {
    override fun observe(spec: String): Flow<PeriodAnalytics> = totals.map { analyticsWithTotal(it) }

    override suspend fun compute(spec: String): PeriodAnalytics = error("not used")
}

fun analyticsWithTotal(total: Long): PeriodAnalytics {
    val period = AnalyticsPeriod.currentMonth(LocalDate.of(2026, 10, 27), 1)
    val summary = SummaryResult(period, total, 0, null, 0, 0, 0, 0, null, null, emptyList(), 0, 0, 0)
    return PeriodAnalytics(summary, ForecastResult(period, total, 0, 0), emptyList(), total)
}
