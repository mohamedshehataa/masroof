package eg.masroof.core.analytics

import eg.masroof.data.SettingsRepository
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
                    val from = period.previous().start().toEpochMilli()
                    val to = period.endExclusive().toEpochMilli()
                    combine(db.expenses().observeAnalyticsRows(from, to), db.lookups().observeEveryCategory()) { rows, cats ->
                        AnalyticsCalculator.compute(period, today, rows, cats.map { it.toAnalytics() }, o.includeCash)
                    }
                }.flowOn(io)

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
                val rows = db.expenses().analyticsRows(period.previous().start().toEpochMilli(), period.endExclusive().toEpochMilli())
                val cats =
                    db
                        .lookups()
                        .observeEveryCategory()
                        .first()
                        .map { it.toAnalytics() }
                AnalyticsCalculator.compute(period, today, rows, cats, s.includeCashWithdrawals)
            }

        private fun CategoryEntity.toAnalytics() = AnalyticsCategory(id, parentId, nameEn, nameAr, color, archived)
    }
