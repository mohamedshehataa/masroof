package eg.masroof.core.analytics

import eg.masroof.data.SettingsRepository
import eg.masroof.data.budgets.toSpec
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Figures for one household period, computed from Room on the phone (ADR-0009 D2). Never touches the network. */
interface AnalyticsEngine {
    /** [spec] is `current`, `previous`, `YYYY-MM` or `YYYY-Www`. Emits again whenever an entry, a category or a setting changes. */
    fun observe(spec: String): Flow<PeriodAnalytics>

    /** One computation; throws when the stored data cannot be read. */
    suspend fun compute(spec: String): PeriodAnalytics

    /**
     * Progress of every stored budget for the current period (a monthly budget the household month, a weekly one this week),
     * from Room only. Emits again when an entry, a category, a budget or a setting changes; fails when it cannot be computed.
     */
    fun observeBudgets(): Flow<List<BudgetStatus>>
}

@Singleton
class RoomAnalyticsEngine
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SettingsRepository,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : AnalyticsEngine {
        private data class Options(
            val monthStartDay: Int,
            val includeCash: Boolean,
        )

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observe(spec: String): Flow<PeriodAnalytics> =
            settings.settings
                .map { Options(it.monthStartDay, it.includeCashWithdrawals) }
                .distinctUntilChanged()
                .combine(cairoToday()) { o, today -> o to today }
                .flatMapLatest { (o, today) ->
                    val period = AnalyticsPeriod.parse(spec, today, o.monthStartDay)
                    val trendPeriods = AnalyticsExtras.trendPeriods(today, o.monthStartDay)
                    val (from, to) = span(period, trendPeriods)
                    combine(
                        db.expenses().observeAnalyticsRows(from, to),
                        db.lookups().observeEveryCategory(),
                        db.budgets().observeAll(),
                    ) { rows, cats, budgets ->
                        val specs = budgets.mapNotNull { it.toSpec() }
                        assemble(period, today, trendPeriods, rows, cats.map { it.toAnalytics() }, o.includeCash, specs)
                    }
                }.flowOn(io)

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observeBudgets(): Flow<List<BudgetStatus>> =
            settings.settings
                .map { Options(it.monthStartDay, it.includeCashWithdrawals) }
                .distinctUntilChanged()
                .combine(cairoToday()) { o, today -> o to today }
                .flatMapLatest { (o, today) ->
                    val requested = AnalyticsPeriod.parse("current", today, o.monthStartDay)
                    val (from, to) = budgetSpan(requested, today, o.monthStartDay)
                    combine(
                        db.expenses().observeAnalyticsRows(from, to),
                        db.lookups().observeEveryCategory(),
                        db.budgets().observeAll(),
                    ) { rows, cats, budgets ->
                        BudgetCalculator
                            .statuses(
                                budgets.mapNotNull { it.toSpec() },
                                requested,
                                o.monthStartDay,
                                today,
                                rows,
                                cats.map { it.toAnalytics() },
                                o.includeCash,
                            )
                    }
                }.flowOn(io)

        /** Rows from the start of the previous month (or week) to the end of the current ones: rollover needs the period before. */
        private fun budgetSpan(
            requested: AnalyticsPeriod,
            today: LocalDate,
            monthStartDay: Int,
        ): Pair<Long, Long> {
            val week = BudgetCalculator.periodFor(BudgetPeriodKind.WEEKLY, requested, monthStartDay, today)
            val periods = listOf(requested, requested.previous(), week, week.previous())
            return periods.minOf { it.start() }.toEpochMilli() to periods.maxOf { it.endExclusive() }.toEpochMilli()
        }

        /** Today's Cairo date, emitted again right after each Cairo midnight so an open screen moves to the new day or month. */
        private fun cairoToday(): Flow<LocalDate> =
            flow {
                while (true) {
                    val now = clock.instant()
                    val today = BusinessCalendar.dateOf(now)
                    emit(today)
                    delay(Duration.between(now, BusinessCalendar.startOfDay(today.plusDays(1))).toMillis() + 1)
                }
            }

        override suspend fun compute(spec: String): PeriodAnalytics =
            withContext(io) {
                val s = settings.current()
                val today = BusinessCalendar.dateOf(clock.instant())
                val period = AnalyticsPeriod.parse(spec, today, s.monthStartDay)
                val trendPeriods = AnalyticsExtras.trendPeriods(today, s.monthStartDay)
                val (from, to) = span(period, trendPeriods)
                val rows = db.expenses().analyticsRows(from, to)
                val cats =
                    db
                        .lookups()
                        .observeEveryCategory()
                        .first()
                        .map { it.toAnalytics() }
                val budgets = db.budgets().all().mapNotNull { it.toSpec() }
                assemble(period, today, trendPeriods, rows, cats, s.includeCashWithdrawals, budgets)
            }

        /** One range query for the period, the one before it and the whole trend: never one query per period. */
        private fun span(
            period: AnalyticsPeriod,
            trend: List<AnalyticsPeriod>,
        ): Pair<Long, Long> =
            minOf(period.previous().start(), trend.first().start()).toEpochMilli() to
                maxOf(period.endExclusive(), trend.last().endExclusive()).toEpochMilli()

        private fun assemble(
            period: AnalyticsPeriod,
            today: LocalDate,
            trendPeriods: List<AnalyticsPeriod>,
            rows: List<AnalyticsRow>,
            cats: List<AnalyticsCategory>,
            includeCash: Boolean,
            budgets: List<BudgetSpec>,
        ): PeriodAnalytics =
            AnalyticsCalculator
                .compute(period, today, rows, cats, includeCash, budgets)
                .copy(trend = AnalyticsExtras.trend(trendPeriods, rows, cats, includeCash))

        private fun CategoryEntity.toAnalytics() = AnalyticsCategory(id, parentId, nameEn, nameAr, color, archived)
    }
