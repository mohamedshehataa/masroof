package eg.masroof.ask

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.BusinessCalendar
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Answers questions about the household's money from Room. Never touches the network. */
interface AskEngine {
    /** One answer; throws when the stored data cannot be read (the screen shows [AskAnswer.Failed]). */
    suspend fun answer(query: AskQuery): AskAnswer

    /** The greeting fact and the suggestion chips for the open screen. */
    suspend fun intro(): AskIntro
}

@Singleton
class RoomAskEngine
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SettingsRepository,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : AskEngine {
        override suspend fun answer(query: AskQuery): AskAnswer =
            withContext(io) {
                val s = settings.current()
                val today = BusinessCalendar.dateOf(clock.instant())
                val (from, to) = AskCalculator.span(query, today, s.monthStartDay)
                AskCalculator.answer(query, context(today, s.monthStartDay, s.includeCashWithdrawals, from, to))
            }

        override suspend fun intro(): AskIntro =
            withContext(io) {
                val s = settings.current()
                val today = BusinessCalendar.dateOf(clock.instant())
                val (from, to) = AskCalculator.span(AskQuery.NotUnderstood, today, s.monthStartDay)
                AskCalculator.intro(context(today, s.monthStartDay, s.includeCashWithdrawals, from, to))
            }

        private suspend fun context(
            today: java.time.LocalDate,
            monthStartDay: Int,
            includeCash: Boolean,
            from: Long,
            to: Long,
        ): AskContext {
            val categories =
                db
                    .lookups()
                    .observeEveryCategory()
                    .first()
                    .map { AnalyticsCategory(it.id, it.parentId, it.nameEn, it.nameAr, it.color, it.archived) }
            val accounts =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .filter { !it.archived }
                    .map { AskAccount(it.id, it.label, it.institution) }
            return AskContext(today, monthStartDay, includeCash, categories, accounts, db.expenses().askRows(from, to))
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AskModule {
    @Binds abstract fun askEngine(impl: RoomAskEngine): AskEngine
}
