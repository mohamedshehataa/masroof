package eg.masroof.data.auth

import eg.masroof.api.apis.AuthApi
import eg.masroof.api.models.LoginRequest
import eg.masroof.api.models.LogoutRequest
import eg.masroof.api.models.RefreshRequest
import eg.masroof.api.models.TokenPair
import eg.masroof.data.remote.apiJson
import eg.masroof.di.AuthHttp
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY = 429
private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_TTL_SECONDS = 120L

/** Maps a login/refresh response to an [AuthResult]; unit tested without a server. */
fun Response<TokenPair>.toAuthResult(nowMillis: Long = System.currentTimeMillis()): AuthResult {
    val pair = body()
    return when {
        isSuccessful && pair != null ->
            AuthResult.Success(
                Session(
                    pair.accessToken,
                    pair.refreshToken,
                    pair.member.id.toString(),
                    pair.member.displayName,
                    // A tiny lifetime must not make every request refresh: keep at least twice the 60 s refresh margin.
                    accessExpiresAtMillis = nowMillis + pair.expiresIn.toLong().coerceAtLeast(MIN_TTL_SECONDS) * MILLIS_PER_SECOND,
                ),
            )
        code() == HTTP_BAD_REQUEST || code() == HTTP_UNAUTHORIZED -> AuthResult.Rejected
        code() == HTTP_TOO_MANY -> AuthResult.RateLimited(headers()["Retry-After"]?.trim()?.toIntOrNull())
        else -> AuthResult.Server
    }
}

/**
 * [AuthRemote] over the generated `AuthApi`. [bare] has no authenticator (a refresh can never recurse) and its own
 * Dispatcher (a refresh can never queue behind parked authenticated calls); see `newAuthClient()`.
 */
@Singleton
class ApiAuthRemote
    @Inject
    constructor(
        @AuthHttp private val bare: OkHttpClient,
    ) : AuthRemote {
        private fun api(
            baseUrl: String,
            client: OkHttpClient = bare,
        ): AuthApi =
            Retrofit
                .Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AuthApi::class.java)

        private suspend fun call(
            baseUrl: String,
            block: suspend (AuthApi) -> Response<TokenPair>,
        ): AuthResult {
            // Retrofit validates the base URL while building: that failure is "invalid URL", not "server problem".
            val api =
                try {
                    api(baseUrl)
                } catch (_: IllegalArgumentException) {
                    return AuthResult.InvalidUrl
                }
            return try {
                block(api).toAuthResult()
            } catch (_: IOException) {
                AuthResult.Network
            } catch (_: SerializationException) {
                AuthResult.Server
            } catch (_: IllegalArgumentException) {
                AuthResult.Server
            }
        }

        override suspend fun login(
            baseUrl: String,
            email: String,
            password: String,
            deviceName: String,
        ) = call(baseUrl) { it.login(LoginRequest(email, password, deviceName)) }

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ) = call(baseUrl) { it.refreshToken(RefreshRequest(refreshToken)) }

        override suspend fun logout(
            baseUrl: String,
            accessToken: String,
            refreshToken: String,
        ): Boolean =
            try {
                val client =
                    bare
                        .newBuilder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("Authorization", "Bearer $accessToken")
                                    .build(),
                            )
                        }.build()
                api(baseUrl, client).logout(LogoutRequest(refreshToken = refreshToken)).isSuccessful
            } catch (_: IOException) {
                false
            } catch (_: SerializationException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
    }
