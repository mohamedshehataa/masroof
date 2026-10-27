package eg.masroof.data.insights

import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.withDeadline
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** [InsightsRemote] over the generated `AnalyticsApi`: four independent reads fetched in parallel. */
@Singleton
class ApiInsightsRemote
    @Inject
    constructor(
        private val source: ApiRemoteSource,
    ) : InsightsRemote {
        private fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw HttpFailure(code())
            return body() ?: throw HttpFailure(code())
        }

        override suspend fun fetch(
            period: InsightsPeriod,
            nowMillis: Long,
        ): InsightsSnapshot =
            withDeadline {
                val api = source.analyticsApi()
                coroutineScope {
                    val summary = async { api.getAnalyticsSummary(period.spec).bodyOrThrow() }
                    val categories = async { api.getAnalyticsByCategory(period.spec).bodyOrThrow() }
                    val trend = async { api.getAnalyticsTrend(TREND_MONTHS).bodyOrThrow() }
                    val heatmap = async { api.getAnalyticsHeatmap(period.spec).bodyOrThrow() }
                    InsightsSnapshot(summary.await(), categories.await(), trend.await(), heatmap.await(), nowMillis)
                }
            }
    }
