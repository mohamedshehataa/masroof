package eg.masroof.data.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Adds the current bearer token to every request that goes through the authenticated client. */
class BearerInterceptor(
    private val session: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Runs on an OkHttp dispatcher thread, never the main thread.
        val token = runBlocking { session.freshAccessToken() }
        val request =
            chain
                .request()
                .newBuilder()
                .apply { if (token != null) header(AUTHORIZATION, "$BEARER$token") }
                .build()
        return chain.proceed(request)
    }
}

/**
 * On a 401, refreshes the session once (single-flight inside [SessionManager]) and retries the request once.
 * Returning null hands the 401 to the caller; when the server rejected the refresh token the session is already gone
 * and the UI routes to login.
 */
class TokenAuthenticator(
    private val session: SessionManager,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        val sent = response.request.header(AUTHORIZATION)?.removePrefix(BEARER) ?: return null
        if (response.priorResponse != null) return null // already retried once
        // Bounded: on timeout the 401 goes back to the caller (an error the UI can show) instead of parking this thread.
        val fresh = runBlocking { withTimeoutOrNull(REFRESH_WAIT_MILLIS) { session.refreshAfter(sent) } } ?: return null
        return response.request
            .newBuilder()
            .header(AUTHORIZATION, "$BEARER$fresh")
            .build()
    }
}
