package eg.masroof.data.budgets

import eg.masroof.api.models.BudgetInput
import eg.masroof.api.models.Money
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.withDeadline
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** [BudgetsRemote] over the generated `BudgetsApi` and `AnalyticsApi`. */
@Singleton
class ApiBudgetsRemote
    @Inject
    constructor(
        private val source: ApiRemoteSource,
    ) : BudgetsRemote {
        private fun <T> Response<T>.checked(): Response<T> {
            if (!isSuccessful) throw HttpFailure(code())
            return this
        }

        override suspend fun fetch(nowMillis: Long): BudgetsSnapshot =
            withDeadline {
                val response = source.analyticsApi().getAnalyticsBudgets("current").checked()
                val body = response.body() ?: throw HttpFailure(response.code())
                BudgetsSnapshot(body, nowMillis)
            }

        override suspend fun create(draft: BudgetDraft) =
            withDeadline {
                source.budgetsApi().createBudget(draft.toInput()).checked()
                Unit
            }

        override suspend fun update(
            id: String,
            draft: BudgetDraft,
        ) = withDeadline {
            source.budgetsApi().updateBudget(UUID.fromString(id), draft.toInput()).checked()
            Unit
        }

        override suspend fun delete(id: String) =
            withDeadline {
                source.budgetsApi().deleteBudget(UUID.fromString(id)).checked()
                Unit
            }
    }

/** The request body: the budget's own period, and the category (fixed server-side on update, so harmless there). */
internal fun BudgetDraft.toInput() =
    BudgetInput(
        categoryId = categoryId?.let(UUID::fromString),
        period = period,
        amount = Money(amountMinor, currency),
        rollover = rollover,
    )
