package eg.masroof.data.edit

import eg.masroof.api.models.ExpenseItem
import eg.masroof.api.models.ExpensePatch
import eg.masroof.api.models.ExpenseStatus
import eg.masroof.api.models.Money
import eg.masroof.api.models.TransactionType
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.HttpFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** [ExpenseEditRemote] over the generated `ExpensesApi` (getExpense / updateExpense / deleteExpense). */
@Singleton
class ApiExpenseEditRemote
    @Inject
    constructor(
        private val source: ApiRemoteSource,
    ) : ExpenseEditRemote {
        private fun <T> Response<T>.checked(): T {
            if (!isSuccessful) throw HttpFailure(code(), fieldOf(errorBody()?.string()))
            return body() ?: throw HttpFailure(code())
        }

        override suspend fun get(id: String): ExpenseEntity =
            source.entityOf(source.expensesApi().getExpense(UUID.fromString(id)).checked())

        override suspend fun patch(
            id: String,
            changes: ExpenseChanges,
        ): ExpenseEntity =
            source.entityOf(source.expensesApi().updateExpense(UUID.fromString(id), changes.toPatch()).checked())

        override suspend fun delete(id: String) {
            val response = source.expensesApi().deleteExpense(UUID.fromString(id))
            // 404 is surfaced as HttpFailure(404): the repository treats "already gone" as done.
            if (!response.isSuccessful) throw HttpFailure(response.code())
        }
    }

/**
 * The request body: only the changed fields (absent = unchanged). `version` is not sent: the server's PATCH ignores it today and the
 * Expense schema does not return one to echo back.
 */
@OptIn(ExperimentalTime::class)
internal fun ExpenseChanges.toPatch() =
    ExpensePatch(
        type = type?.let { v -> TransactionType.entries.first { it.value == v } },
        amount = amountMinor?.let { Money(it, currency ?: eg.masroof.core.Money.EGP) },
        occurredAt = occurredAtMillis?.let(Instant::fromEpochMilliseconds),
        categoryId = categoryId?.let(UUID::fromString),
        status = status?.let { v -> ExpenseStatus.entries.first { it.value == v } },
        merchantRaw = merchantRaw,
        notes = notes,
        items = items?.map { ExpenseItem(name = it.name, qty = it.qty, total = it.total?.let { m -> Money(m.amountMinor, m.currency) }) },
    )

/** The contract field named by the first entry of the problem's `errors`; the rest of the body is dropped (it may echo user text). */
internal fun fieldOf(body: String?): String? =
    try {
        body
            ?.let { Json.parseToJsonElement(it) }
            ?.jsonObject
            ?.get("errors")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("field")
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
