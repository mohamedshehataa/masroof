package eg.masroof.data.remote

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TIMEOUT_SECONDS = 15L
private const val AUTH_CALL_TIMEOUT_SECONDS = 20L

/** The base of the authenticated API client: no auth interceptor or authenticator. */
fun newBareClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

/**
 * Login, refresh and logout. Built from scratch with its OWN [Dispatcher], never via `newBuilder()` of another
 * client: a refresh is issued while authenticated calls sit parked in the authenticator, and on a shared dispatcher
 * (5 requests per host) those calls hold every slot, so the refresh would queue behind the calls waiting for it.
 */
fun newAuthClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .dispatcher(Dispatcher())
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(AUTH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
