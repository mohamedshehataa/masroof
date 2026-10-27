package eg.masroof.data.remote

import eg.masroof.api.apis.AccountsApi
import eg.masroof.api.apis.AnalyticsApi
import eg.masroof.api.apis.AuthApi
import eg.masroof.api.apis.BudgetsApi
import eg.masroof.api.apis.CategoriesApi
import eg.masroof.api.apis.ExpensesApi
import eg.masroof.api.apis.IngestApi
import eg.masroof.api.apis.MembersApi
import eg.masroof.api.models.Expense
import eg.masroof.api.models.ExpenseCreate
import eg.masroof.api.models.IngestParsedTransactionRequest
import eg.masroof.api.models.IngestParsedTransactionRequestKind
import eg.masroof.api.models.IngestParsedTransactionRequestPaymentMethod
import eg.masroof.api.models.IngestParsedTransactionRequestSource
import eg.masroof.api.models.Money
import eg.masroof.api.models.PaymentAccount
import eg.masroof.api.models.PaymentMethod
import eg.masroof.api.models.TransactionType
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.BearerInterceptor
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.TokenAuthenticator
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.normalizeServerUrl
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A category id is sent to the server only if it is a server UUID. The phone's own default categories (`cat-...`, ADR-0009)
 * never reach the server: they become "no category".
 */
internal fun serverUuidOrNull(id: String?): UUID? = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }

object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())
}

object UriSerializer : KSerializer<java.net.URI> {
    override val descriptor = PrimitiveSerialDescriptor("URI", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): java.net.URI = java.net.URI(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: java.net.URI,
    ) = encoder.encodeString(value.toString())
}

/** The contract's `number` fields (confidence) generate as BigDecimal; keep exact decimals, never a binary float. */
@OptIn(ExperimentalSerializationApi::class)
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal((decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) = (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
}

/** Json configuration for the generated models (they mark UUID/Instant/enums as @Contextual). */
@OptIn(ExperimentalTime::class)
val apiJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        serializersModule =
            SerializersModule {
                contextual(UuidSerializer)
                contextual(BigDecimalSerializer)
                contextual(Instant.serializer())
                contextual(UriSerializer)
                // The generator emits `List<@Contextual List<Money>>` for the heatmap's weekday x hour matrix.
                contextual(List::class) { args -> ListSerializer(args[0]) }
                contextual(
                    eg.masroof.api.models.TokenPairTokenType
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.MemberRole
                        .serializer(),
                )
                contextual(TransactionType.serializer())
                contextual(PaymentMethod.serializer())
                contextual(
                    eg.masroof.api.models.ExpenseStatus
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.AccountType
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.SplitMode
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.BudgetPeriod
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.MessageSource
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.ProcessingStatus
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.SourceRole
                        .serializer(),
                )
                contextual(
                    eg.masroof.api.models.IngestAcceptedOutcome
                        .serializer(),
                )
            }
    }

/** The exact wire shape of a parsed record. It has no field for message text; tests serialize it to prove that. */
fun parsedRequest(
    deviceId: String,
    item: ParsedOutboxEntity,
): IngestParsedTransactionRequest =
    IngestParsedTransactionRequest(
        deviceId = UUID.fromString(deviceId),
        localId = item.localId,
        source = IngestParsedTransactionRequestSource.valueOf(item.source),
        kind = IngestParsedTransactionRequestKind.valueOf(item.kind),
        amountMinor = item.amountMinor,
        currency = item.currency,
        occurredAt = Instant.fromEpochMilliseconds(item.occurredAtMillis),
        senderId = item.senderId,
        instrumentLast4 = item.instrumentLast4,
        institution = item.institution,
        paymentMethod = item.paymentMethod?.let { IngestParsedTransactionRequestPaymentMethod.valueOf(it) },
        merchant = item.merchant,
    )

private const val HTTP_NOT_FOUND = 404

/** [RemoteSource] backed by the Kotlin client generated from contracts/openapi.yaml. */
@Singleton
class ApiRemoteSource
    @Inject
    constructor(
        private val settings: SettingsRepository,
        bare: OkHttpClient,
        session: SessionManager,
    ) : RemoteSource, ParsedRemote, ExpenseDeleter {
        private var cachedKey: String? = null
        private var cachedRetrofit: Retrofit? = null

        // Bearer header on every call; a 401 triggers one single-flight refresh and one retry (TokenAuthenticator).
        private val client: OkHttpClient =
            bare
                .newBuilder()
                .addInterceptor(BearerInterceptor(session))
                .authenticator(TokenAuthenticator(session))
                // no request outlives the claim the uploader holds on its row (UPLOAD_CLAIM_TTL_MILLIS)
                .callTimeout(CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build()

        private suspend fun retrofit(): Retrofit {
            val s = settings.current()
            val base = normalizeServerUrl(s.serverUrl) ?: throw IOException("Invalid server URL")
            val key = base
            synchronized(this) {
                if (key == cachedKey) cachedRetrofit?.let { return it }
                val built =
                    Retrofit
                        .Builder()
                        .baseUrl(base)
                        .client(client)
                        .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
                        .build()
                cachedKey = key
                cachedRetrofit = built
                return built
            }
        }

        /** The generated analytics API on the authenticated client (used by the Insights tab). */
        suspend fun analyticsApi(): AnalyticsApi = retrofit().create(AnalyticsApi::class.java)

        /** The generated auth API (invites) on the authenticated client. */
        suspend fun authApi(): AuthApi = retrofit().create(AuthApi::class.java)

        suspend fun expensesApi(): ExpensesApi = retrofit().create(ExpensesApi::class.java)

        fun entityOf(expense: Expense): ExpenseEntity = expense.toEntity()

        suspend fun membersApi(): MembersApi = retrofit().create(MembersApi::class.java)

        /** The generated budgets API on the authenticated client. */
        suspend fun budgetsApi(): BudgetsApi = retrofit().create(BudgetsApi::class.java)

        private fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw HttpFailure(code())
            return body() ?: throw HttpFailure(code())
        }

        override suspend fun fetchExpenses(): List<ExpenseEntity> {
            val api = retrofit().create(ExpensesApi::class.java)
            // Spending, received money and transfers (listed, never counted as spending); refunds and cash are not shown here.
            val items =
                listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER).flatMap { type ->
                    api.listExpenses(type = type, limit = PAGE_LIMIT).bodyOrThrow().items
                }
            return items.map { it.toEntity() }
        }

        override suspend fun fetchCategories(): List<CategoryEntity> =
            retrofit()
                .create(CategoriesApi::class.java)
                .listCategories()
                .bodyOrThrow()
                .items
                .filter { !it.archived }
                .map { CategoryEntity(it.id.toString(), it.nameEn, it.nameAr, it.sortOrder) }

        override suspend fun fetchAccounts(): List<AccountEntity> =
            retrofit()
                .create(AccountsApi::class.java)
                .listAccounts()
                .bodyOrThrow()
                .items
                .map { it.toEntity() }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun upload(item: OutboxEntity): UploadResult {
            val body =
                ExpenseCreate(
                    type = TransactionType.EXPENSE,
                    amount = Money(item.amountMinor, item.currency),
                    occurredAt = Instant.fromEpochMilliseconds(item.occurredAtMillis),
                    categoryId = serverUuidOrNull(item.categoryId),
                    paymentAccountId = item.accountId?.let(UUID::fromString),
                    paymentMethod = PaymentMethod.UNKNOWN,
                    notes = item.notes,
                )
            return try {
                val response = retrofit().create(ExpensesApi::class.java).createExpense(body, item.clientId)
                val created = response.body()
                if (response.isSuccessful && created != null) {
                    UploadResult.Created(created.toEntity())
                } else {
                    classifyUploadStatus(
                        response.code(),
                        response.isSuccessful,
                        parseProblem(response.errorBody()?.string()),
                        response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
                    )
                }
            } catch (_: IOException) {
                UploadResult.Retry
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Anything unexpected (bad id, malformed body) must not kill the worker or lose the entry.
                UploadResult.Unexpected
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun uploadParsed(
            deviceId: String,
            item: ParsedOutboxEntity,
        ): SmsUploadResult =
            try {
                val body = parsedRequest(deviceId, item)
                val response = retrofit().create(IngestApi::class.java).ingestParsedTransaction(body)
                if (response.isSuccessful) {
                    SmsUploadResult.Sent(response.body()?.expenseId?.toString())
                } else {
                    classifySmsStatus(
                        response.code(),
                        parseProblem(response.errorBody()?.string()),
                        response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
                    )
                }
            } catch (_: IOException) {
                SmsUploadResult.Retry(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SmsUploadResult.RetryLimited
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun deleteExpense(id: String): Boolean =
            try {
                val response = retrofit().create(ExpensesApi::class.java).deleteExpense(UUID.fromString(id))
                // 404: it is already gone (deleted on another device): that is what Undo wanted
                response.isSuccessful || response.code() == HTTP_NOT_FOUND
            } catch (_: IOException) {
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

        private fun Expense.toEntity() =
            ExpenseEntity(
                id = id.toString(),
                amountMinor = amount.amountMinor,
                currency = amount.currency,
                occurredAtMillis = occurredAt.toEpochMilliseconds(),
                merchant = blankToNull(merchantName ?: merchantRaw),
                categoryId = categoryId?.toString(),
                accountId = paymentAccountId?.toString(),
                notes = blankToNull(notes),
                pending = false,
                source =
                    sources
                        .firstOrNull()
                        ?.rawMessage
                        ?.source
                        ?.value,
                type = type.value,
                status = status.value,
                // A row from the server keeps the server's id (ADR-0009 D3); the server sends no creation time here.
                serverId = id.toString(),
                createdAtMillis = occurredAt.toEpochMilliseconds(),
                updatedAtMillis = occurredAt.toEpochMilliseconds(),
            )

        private fun PaymentAccount.toEntity(): AccountEntity {
            val name =
                nickname ?: listOfNotNull(institution, last4?.let { "••$it" }).joinToString(" ").ifBlank { type.value }
            return AccountEntity(id.toString(), name, currency)
        }

        private companion object {
            const val PAGE_LIMIT = 200
            const val CALL_TIMEOUT_SECONDS = 90L
        }
    }
