package eg.masroof.data.remote

import eg.masroof.api.apis.AccountsApi
import eg.masroof.api.apis.CategoriesApi
import eg.masroof.api.apis.ExpensesApi
import eg.masroof.api.models.Expense
import eg.masroof.api.models.ExpenseCreate
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
import eg.masroof.data.normalizeServerUrl
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
            }
    }

/** [RemoteSource] backed by the Kotlin client generated from contracts/openapi.yaml. */
@Singleton
class ApiRemoteSource
    @Inject
    constructor(
        private val settings: SettingsRepository,
        bare: OkHttpClient,
        session: SessionManager,
    ) : RemoteSource {
        private var cachedKey: String? = null
        private var cachedRetrofit: Retrofit? = null

        // Bearer header on every call; a 401 triggers one single-flight refresh and one retry (TokenAuthenticator).
        private val client: OkHttpClient =
            bare
                .newBuilder()
                .addInterceptor(BearerInterceptor(session))
                .authenticator(TokenAuthenticator(session))
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

        private fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw HttpFailure(code())
            return body() ?: throw HttpFailure(code())
        }

        override suspend fun fetchExpenses(): List<ExpenseEntity> {
            val page =
                retrofit()
                    .create(ExpensesApi::class.java)
                    .listExpenses(type = TransactionType.EXPENSE, limit = PAGE_LIMIT)
                    .bodyOrThrow()
            return page.items.map { it.toEntity() }
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
                    categoryId = item.categoryId?.let(UUID::fromString),
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
                    classifyUploadStatus(response.code(), response.isSuccessful)
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

        private fun Expense.toEntity() =
            ExpenseEntity(
                id = id.toString(),
                amountMinor = amount.amountMinor,
                currency = amount.currency,
                occurredAtMillis = occurredAt.toEpochMilliseconds(),
                merchant = merchantName ?: merchantRaw,
                categoryId = categoryId?.toString(),
                accountId = paymentAccountId?.toString(),
                notes = notes,
                pending = false,
            )

        private fun PaymentAccount.toEntity(): AccountEntity {
            val name =
                nickname ?: listOfNotNull(institution, last4?.let { "••$it" }).joinToString(" ").ifBlank { type.value }
            return AccountEntity(id.toString(), name, currency)
        }

        private companion object {
            const val PAGE_LIMIT = 200
        }
    }

private const val SERVER_ERROR = 500
private const val UNAUTHORIZED = 401

/** 409 is the backend's "concurrent Idempotency-Key; retry": the first attempt is in flight, so retry, never reject. */
private val RETRYABLE = setOf(408, 409, 429)

/** Outcome of a non-created upload response. Pure so the status table is unit tested. */
fun classifyUploadStatus(
    code: Int,
    successful: Boolean,
): UploadResult =
    when {
        successful -> UploadResult.Unexpected
        code == UNAUTHORIZED -> UploadResult.Unauthorized
        code in RETRYABLE -> UploadResult.Retry
        code >= SERVER_ERROR -> UploadResult.Retry
        else -> UploadResult.Rejected
    }
