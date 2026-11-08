package eg.masroof.data.serverimport

import eg.masroof.api.apis.AccountsApi
import eg.masroof.api.apis.BudgetsApi
import eg.masroof.api.apis.CategoriesApi
import eg.masroof.api.apis.MembersApi
import eg.masroof.api.models.Expense
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.apiJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val MAX_CURSOR = 512
private const val MAX_NOTES = 500
private const val MAX_MERCHANT = 200
private const val MAX_TAGS = 10
private const val MAX_TAG = 30
private const val MAX_ANY_STRING = 4_000
private const val MAX_NAME = 200

/**
 * The two reads the typed generated calls cannot do for this import: the expense page is decoded row by row with the generated
 * `Expense` model (so one bad row is skipped, not the whole page), and only two settings are read (an enum this app never uses must
 * not fail the import). Same path, same authenticated client, same base URL as the generated apis.
 */
internal interface RawImportApi {
    @GET("api/expenses")
    suspend fun expenses(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
        @Query("sort") sort: String,
    ): Response<ResponseBody>

    @GET("api/settings")
    suspend fun settings(): Response<ResponseBody>
}

/**
 * [ServerImportSource] over the generated client on the app's one authenticated Retrofit (bearer + single-flight refresh). Every
 * response is untrusted: strings are bounded by the contract's limits, a page may not exceed the limit that was asked for, and a
 * body that is not the documented shape ends the run (nothing from that page is kept).
 */
@Singleton
class ApiServerImportSource
    @Inject
    constructor(
        private val remote: ApiRemoteSource,
    ) : ServerImportSource {
        private suspend fun <T> call(block: suspend (retrofit2.Retrofit) -> Response<T>): T =
            try {
                val response = block(remote.retrofit())
                when {
                    response.isSuccessful -> response.body() ?: throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
                    response.code() == HTTP_UNAUTHORIZED || response.code() == HTTP_FORBIDDEN ->
                        throw ImportException(ImportFailureKind.SESSION)
                    else -> {
                        response.errorBody()?.close()
                        throw ImportException(ImportFailureKind.SERVER)
                    }
                }
            } catch (_: IOException) {
                throw ImportException(ImportFailureKind.NETWORK)
            } catch (_: SerializationException) {
                // the generated client could not decode a typed response: the contract was broken
                throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            } catch (_: IllegalArgumentException) {
                throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            }

        override suspend fun expenses(
            cursor: String?,
            limit: Int,
        ): ExpensesBatch {
            val body = call { it.create(RawImportApi::class.java).expenses(cursor, limit, "createdAt") }
            val text = readBody(body)
            val root = parse(text) as? JsonObject ?: throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            val items = root["items"] as? JsonArray ?: throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            // a page larger than the page that was asked for violates the contract (the limit is 1..200)
            if (limit < 1 || items.size > limit) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            val cursorElement = root["nextCursor"]
            val next =
                when {
                    cursorElement == null || cursorElement is JsonNull -> null
                    cursorElement is JsonPrimitive && cursorElement.isString && cursorElement.content.length <= MAX_CURSOR ->
                        cursorElement.content
                    else -> throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
                }
            val rows = items.map { decodeRow(it) }
            return ExpensesBatch(rows, next)
        }

        private fun readBody(body: ResponseBody): String =
            try {
                body.use { it.string() }
            } catch (_: IOException) {
                throw ImportException(ImportFailureKind.NETWORK)
            }

        private fun parse(text: String): JsonElement =
            try {
                apiJson.parseToJsonElement(text)
            } catch (_: SerializationException) {
                throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            } catch (_: IllegalArgumentException) {
                throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            }

        private fun decodeRow(element: JsonElement): ServerExpenseRow {
            val obj = element as? JsonObject ?: throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            // contract limits are checked on the raw row: an oversized string stops the run, it is not a skippable bad row
            if (oversized(obj)) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            val e =
                try {
                    apiJson.decodeFromJsonElement(Expense.serializer(), obj)
                } catch (_: SerializationException) {
                    return ServerExpenseRow.Invalid
                } catch (_: IllegalArgumentException) {
                    return ServerExpenseRow.Invalid
                }
            return ServerExpenseRow.Valid(
                ServerExpense(
                    id = e.id.toString(),
                    type = e.type.value,
                    status = e.status.value,
                    amountMinor = e.amount.amountMinor,
                    currency = e.amount.currency,
                    occurredAtMillis = e.occurredAt.toEpochMilliseconds(),
                    createdAtMillis = e.createdAt.toEpochMilliseconds(),
                    categoryId = e.categoryId?.toString(),
                    accountId = e.paymentAccountId?.toString(),
                    paidByMemberId = e.paidByMemberId?.toString(),
                    merchant = (e.merchantName ?: e.merchantRaw)?.takeIf { it.isNotBlank() },
                    notes = e.notes?.takeIf { it.isNotBlank() },
                    paymentMethod = e.paymentMethod.value,
                    deleted = e.deletedAt != null,
                ),
            )
        }

        private fun oversized(row: JsonObject): Boolean {
            fun len(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.length ?: 0
            val tags = row["tags"] as? JsonArray
            return len("notes") > MAX_NOTES ||
                len("merchantRaw") > MAX_MERCHANT ||
                len("merchantName") > MAX_NAME ||
                (tags != null && (tags.size > MAX_TAGS || tags.any { ((it as? JsonPrimitive)?.content?.length ?: 0) > MAX_TAG })) ||
                row.values.any { it is JsonPrimitive && it.isString && it.content.length > MAX_ANY_STRING }
        }

        override suspend fun categories(): List<ServerCategory> =
            call { it.create(CategoriesApi::class.java).listCategories(includeArchived = true) }
                .items
                .map {
                    checkNames(it.nameEn, it.nameAr)
                    ServerCategory(it.id.toString(), it.nameEn, it.nameAr, it.sortOrder, it.parentId?.toString(), it.color, it.archived)
                }

        override suspend fun accounts(): List<ServerAccount> =
            call { it.create(AccountsApi::class.java).listAccounts() }
                .items
                .map {
                    checkNames(it.nickname.orEmpty(), it.institution.orEmpty(), it.last4.orEmpty())
                    val label =
                        it.nickname?.takeIf { n -> n.isNotBlank() }
                            ?: listOfNotNull(it.institution, it.last4?.let { l -> "••$l" }).joinToString(" ").ifBlank { it.type.value }
                    ServerAccount(it.id.toString(), label, it.currency, it.type.value, it.institution, it.last4)
                }

        override suspend fun members(): List<ServerMember> =
            call { it.create(MembersApi::class.java).listMembers() }
                .map {
                    checkNames(it.displayName)
                    ServerMember(it.id.toString(), it.displayName)
                }

        override suspend fun budgets(): List<ServerBudget> =
            call { it.create(BudgetsApi::class.java).listBudgets() }
                .items
                .map {
                    ServerBudget(
                        id = it.id.toString(),
                        categoryId = it.categoryId?.toString(),
                        period = it.period.value,
                        amountMinor = it.amount.amountMinor,
                        currency = it.amount.currency,
                        rollover = it.rollover,
                        alertThresholds = it.alertThresholds,
                    )
                }

        override suspend fun settings(): ServerSettings {
            val root = parse(readBody(call { it.create(RawImportApi::class.java).settings() })) as? JsonObject
            val day = (root?.get("monthStartDay") as? JsonPrimitive)?.intOrNull
            val atm = (root?.get("countAtmWithdrawalsAsSpending") as? JsonPrimitive)?.booleanOrNull
            if (day == null || atm == null) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
            return ServerSettings(day, atm)
        }

        private fun checkNames(vararg names: String) {
            if (names.any { it.length > MAX_NAME }) throw ImportException(ImportFailureKind.UNEXPECTED_DATA)
        }
    }
