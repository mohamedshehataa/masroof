package eg.masroof.data.budgets

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.apiJson
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Network seam for budgets; faked in tests. All calls throw [IOException] when unreachable, [HttpFailure] on non-2xx. */
interface BudgetsRemote {
    suspend fun fetch(nowMillis: Long): BudgetsSnapshot

    suspend fun create(draft: BudgetDraft)

    suspend fun update(
        id: String,
        draft: BudgetDraft,
    )

    suspend fun delete(id: String)
}

interface BudgetsRepository {
    /** Last cached snapshot (null before the first successful refresh); re-emits after each refresh. */
    fun observe(): Flow<BudgetsSnapshot?>

    suspend fun refresh(): RefreshOutcome

    suspend fun create(draft: BudgetDraft): MutationOutcome

    suspend fun update(
        id: String,
        draft: BudgetDraft,
    ): MutationOutcome

    suspend fun delete(id: String): MutationOutcome
}

/** Caches the last good answer in the same private file store as Insights (wiped on logout). */
@Singleton
class DefaultBudgetsRepository
    @Inject
    constructor(
        private val cache: InsightsCache,
        private val remote: BudgetsRemote,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : BudgetsRepository {
        private val version = MutableStateFlow(0)

        override fun observe(): Flow<BudgetsSnapshot?> =
            version
                .map { decode(cache.read(CACHE_KEY)) }
                .flowOn(io)
                .distinctUntilChanged()

        override suspend fun refresh(): RefreshOutcome =
            try {
                val snapshot = remote.fetch(clock.millis())
                withContext(io) { cache.write(CACHE_KEY, apiJson.encodeToString(BudgetsSnapshot.serializer(), snapshot)) }
                version.update { it + 1 }
                RefreshOutcome.Ok
            } catch (_: IOException) {
                RefreshOutcome.Offline
            } catch (e: HttpFailure) {
                if (e.code == UNAUTHORIZED) RefreshOutcome.SignIn else RefreshOutcome.Failed
            } catch (_: SerializationException) {
                RefreshOutcome.Failed
            }

        override suspend fun create(draft: BudgetDraft): MutationOutcome = mutate { remote.create(draft) }

        override suspend fun update(
            id: String,
            draft: BudgetDraft,
        ): MutationOutcome = mutate { remote.update(id, draft) }

        override suspend fun delete(id: String): MutationOutcome = mutate { remote.delete(id) }

        private suspend fun mutate(call: suspend () -> Unit): MutationOutcome =
            try {
                call()
                refresh() // best effort: the change is saved either way
                MutationOutcome.Ok
            } catch (_: IOException) {
                MutationOutcome.Offline
            } catch (e: HttpFailure) {
                when (e.code) {
                    UNAUTHORIZED -> MutationOutcome.SignIn
                    NOT_FOUND -> MutationOutcome.NotFound
                    CONFLICT -> MutationOutcome.Duplicate
                    BAD_REQUEST, UNPROCESSABLE -> MutationOutcome.Invalid
                    else -> MutationOutcome.Failed
                }
            } catch (_: SerializationException) {
                MutationOutcome.Failed
            }

        private fun decode(json: String?): BudgetsSnapshot? =
            json?.let {
                try {
                    apiJson.decodeFromString(BudgetsSnapshot.serializer(), it)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        private companion object {
            const val CACHE_KEY = "budgets"
            const val BAD_REQUEST = 400
            const val UNAUTHORIZED = 401
            const val NOT_FOUND = 404
            const val CONFLICT = 409
            const val UNPROCESSABLE = 422
        }
    }
