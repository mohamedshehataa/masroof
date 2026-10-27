package eg.masroof.data.edit

import eg.masroof.data.LocalDataSignal
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.withDeadline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fields of PATCH /api/expenses/{id} the app changes; null = leave as it is. Money is minor units + currency, never a float.
 * The backend treats a null as "unchanged", so a cleared note or merchant is sent as an empty string.
 */
data class ExpenseChanges(
    val type: String? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val occurredAtMillis: Long? = null,
    val categoryId: String? = null,
    val merchantRaw: String? = null,
    val notes: String? = null,
    val status: String? = null,
) {
    val isEmpty: Boolean
        get() = this == ExpenseChanges()

    // Amount, merchant and notes are private: never printed.
    override fun toString() = "ExpenseChanges(type=$type, status=$status)"
}

/** Thin seam over the generated ExpensesApi (get / patch / delete) so the repository is testable offline. */
interface ExpenseEditRemote {
    /** @throws IOException when unreachable (or past the deadline), [HttpFailure] on a non-2xx answer. */
    suspend fun get(id: String): ExpenseEntity

    suspend fun patch(
        id: String,
        changes: ExpenseChanges,
    ): ExpenseEntity

    suspend fun delete(id: String)
}

sealed interface EditOutcome {
    data class Saved(
        val expense: ExpenseEntity,
    ) : EditOutcome

    data object Deleted : EditOutcome

    /** Unreachable or no answer within the deadline. The user's edits stay on screen. */
    data object Offline : EditOutcome

    data object SignIn : EditOutcome

    /** 409: the expense changed elsewhere. [latest] is the server's current version, already cached. */
    data class Conflict(
        val latest: ExpenseEntity?,
    ) : EditOutcome

    /** 400/422. [field] is the contract field the server named (amount, occurredAt, notes ...), when it did. */
    data class Invalid(
        val field: String?,
    ) : EditOutcome

    /** 404: gone on the server (deleted elsewhere); removed from the cache. */
    data object NotFound : EditOutcome

    data object Failed : EditOutcome
}

interface ExpenseEditor {
    suspend fun find(id: String): ExpenseEntity?

    fun observeCategories(): Flow<List<CategoryEntity>>

    suspend fun update(
        id: String,
        changes: ExpenseChanges,
    ): EditOutcome

    suspend fun delete(id: String): EditOutcome
}

@Singleton
class DefaultExpenseEditor
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: ExpenseEditRemote,
        private val signal: LocalDataSignal,
    ) : ExpenseEditor {
        override suspend fun find(id: String) = db.expenses().find(id)

        override fun observeCategories() = db.lookups().observeCategories()

        override suspend fun update(
            id: String,
            changes: ExpenseChanges,
        ): EditOutcome =
            attempt(id) {
                val updated = withDeadline { remote.patch(id, changes) }
                // Cache first, so lists and totals are right before the screen closes; then tell Home and Insights.
                db.expenses().upsert(updated)
                signal.changed()
                EditOutcome.Saved(updated)
            }

        override suspend fun delete(id: String): EditOutcome =
            attempt(id) {
                withDeadline { remote.delete(id) }
                db.expenses().delete(id)
                signal.changed()
                EditOutcome.Deleted
            }

        private suspend fun attempt(
            id: String,
            call: suspend () -> EditOutcome,
        ): EditOutcome =
            try {
                call()
            } catch (_: IOException) {
                EditOutcome.Offline
            } catch (e: HttpFailure) {
                when (e.code) {
                    UNAUTHORIZED -> EditOutcome.SignIn
                    NOT_FOUND -> {
                        db.expenses().delete(id)
                        signal.changed()
                        EditOutcome.NotFound
                    }
                    CONFLICT -> EditOutcome.Conflict(reload(id))
                    BAD_REQUEST, UNPROCESSABLE -> EditOutcome.Invalid(e.field)
                    else -> EditOutcome.Failed
                }
            } catch (_: SerializationException) {
                EditOutcome.Failed
            }

        /** The server's current version after a 409, cached; null when it cannot be fetched right now. */
        private suspend fun reload(id: String): ExpenseEntity? =
            try {
                withDeadline { remote.get(id) }.also {
                    db.expenses().upsert(it)
                    signal.changed()
                }
            } catch (_: IOException) {
                null
            } catch (_: HttpFailure) {
                null
            } catch (_: SerializationException) {
                null
            }

        private companion object {
            const val UNAUTHORIZED = 401
            const val NOT_FOUND = 404
            const val CONFLICT = 409
            const val BAD_REQUEST = 400
            const val UNPROCESSABLE = 422
        }
    }
