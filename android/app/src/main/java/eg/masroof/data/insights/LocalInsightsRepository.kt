package eg.masroof.data.insights

import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.CategoryLine
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.data.RefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.toKotlinLocalDate
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import eg.masroof.api.models.AnalyticsSummary as ApiSummary
import eg.masroof.api.models.CategoryBreakdown as ApiBreakdown
import eg.masroof.api.models.CategoryTotal as ApiCategory
import eg.masroof.api.models.Heatmap as ApiHeatmap
import eg.masroof.api.models.Money as ApiMoney
import eg.masroof.api.models.PeriodRange as ApiPeriod
import eg.masroof.api.models.Trend as ApiTrend

/**
 * Insights from Room through the [AnalyticsEngine] (ADR-0009 D2): no network, no cache file, identical online and offline.
 * Trend, calendar and weekday data are STORY-179; until then those lists are empty. [refresh] recomputes once and reports
 * [RefreshOutcome.Failed] when the engine throws, which the screens show as "Couldn't compute insights" with Retry.
 */
@Singleton
class LocalInsightsRepository
    @Inject
    constructor(
        private val engine: AnalyticsEngine,
        private val clock: Clock,
    ) : InsightsRepository {
        private val version = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observe(period: InsightsPeriod): Flow<InsightsSnapshot?> =
            version
                .flatMapLatest {
                    flow {
                        emitAll(
                            engine
                                .observe(period.spec)
                                .map<PeriodAnalytics, InsightsSnapshot?> { it.toSnapshot(clock.millis()) }
                                .catch { e ->
                                    if (e is CancellationException) throw e
                                    emitAll(flowOf(null))
                                },
                        )
                    }
                }.distinctUntilChanged()

        override suspend fun refresh(period: InsightsPeriod): RefreshOutcome =
            try {
                engine.compute(period.spec)
                version.update { it + 1 }
                RefreshOutcome.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RefreshOutcome.Failed
            }

        override fun clear() {
            version.update { it + 1 }
        }
    }

private fun Long.money() = ApiMoney(this, "EGP")

private fun CategoryLine.toApi() =
    ApiCategory(
        categoryId = categoryId?.let { runCatching { UUID.fromString(it) }.getOrElse { _ -> UUID.nameUUIDFromBytes(it.toByteArray()) } },
        nameEn = nameEn,
        nameAr = nameAr,
        total = total.money(),
        shareBps = shareBps,
        count = count,
        color = color,
        previousTotal = previousTotal.money(),
        deltaPercentBps = deltaBps,
    )

internal fun PeriodAnalytics.toSnapshot(nowMillis: Long): InsightsSnapshot {
    val s = summary
    val range = ApiPeriod(s.period.label, s.period.from.toKotlinLocalDate(), s.period.to.toKotlinLocalDate())
    return InsightsSnapshot(
        summary =
            ApiSummary(
                period = range,
                total = s.total.money(),
                previousTotal = s.previousTotal.money(),
                deltaPercentBps = s.deltaBps,
                dailyAverage = s.dailyAverage.money(),
                projectedTotal = s.projectedTotal.money(),
                daysLeft = s.daysLeft,
                pendingReviewCount = s.pendingReviewCount,
                projectedTotalWeekdayAware = s.projectedWeekdayAware.money(),
                remainingBudget = null,
                income = s.income.money(),
                topCategories = s.topCategories.map { it.toApi() },
            ),
        categories = ApiBreakdown(range, categoryTotal.money(), categories.map { it.toApi() }),
        trend = ApiTrend(emptyList()),
        heatmap = ApiHeatmap(range, emptyList(), emptyList()),
        fetchedAtMillis = nowMillis,
        otherCurrencyCount = s.otherCurrencyCount,
    )
}

/** Removes the period caches an older build wrote (`insights-current.json`, `insights-previous.json`); budgets keep theirs. */
fun deleteLegacyInsightsCache(dir: File) {
    for (name in listOf("current", "previous")) {
        File(dir, "insights-$name.json").delete()
        File(dir, "insights-$name.tmp").delete()
    }
}
