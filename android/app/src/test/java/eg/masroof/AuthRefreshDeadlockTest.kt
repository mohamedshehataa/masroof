package eg.masroof

import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.Session
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.newAuthClient
import eg.masroof.data.remote.newBareClient
import eg.masroof.ingest.NoopCaptureGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

private const val MEMBER =
    "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"householdId\":\"22222222-2222-2222-2222-222222222222\"," +
        "\"displayName\":\"Mohamed\",\"role\":\"ADMIN\"}"
private const val TOKENS =
    "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\",\"expiresIn\":900,\"tokenType\":\"Bearer\",\"member\":$MEMBER}"

/**
 * The production wiring (real OkHttp dispatcher limits, BearerInterceptor, TokenAuthenticator, SessionManager,
 * ApiAuthRemote, ApiRemoteSource) against a fake server. Reproduces the cold-start deadlock: more simultaneous 401s
 * than the dispatcher allows per host used to park every slot in the authenticator while the refresh sat queued.
 */
class AuthRefreshDeadlockTest {
    private val server = MockWebServer()
    private val refreshes = AtomicInteger()
    private val staleCalls = AtomicInteger()
    private val okCalls = AtomicInteger()

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path.endsWith("/auth/refresh") -> {
                            refreshes.incrementAndGet()
                            json(TOKENS)
                        }
                        request.headers["Authorization"] == "Bearer access-2" -> {
                            okCalls.incrementAndGet()
                            json("{\"items\":[]}")
                        }
                        else -> {
                            staleCalls.incrementAndGet()
                            MockResponse.Builder().code(401).build()
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun remote(expiresAtMillis: Long?): ApiRemoteSource {
        val settings = FakeSettings()
        runBlocking { settings.update { it.copy(serverUrl = server.url("/").toString()) } }
        val store = FakeSessionStore(LoadedSession.Found(Session("access-1", "refresh-1", "member-1", "Mohamed", expiresAtMillis)))
        val manager =
            SessionManager(
                store,
                ApiAuthRemote(newAuthClient()),
                settings,
                FakeWiper(),
                Dispatchers.IO,
                NoopCaptureGate,
                Clock.systemUTC(),
            )
        return ApiRemoteSource(settings, newBareClient(), manager)
    }

    private fun burst(source: ApiRemoteSource) =
        runBlocking(Dispatchers.Default) {
            (1..BURST).map { async { source.fetchCategories() } }.awaitAll()
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `REQ-AND-007 a burst of 401s with an unknown expiry makes exactly one refresh and every call succeeds`() {
        val results = burst(remote(expiresAtMillis = null))
        assertEquals(BURST, results.size)
        assertEquals(1, refreshes.get())
        assertEquals(BURST, okCalls.get())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `REQ-AND-007 an access token that is already expired is refreshed once before any request, with no 401 burst`() {
        val results = burst(remote(expiresAtMillis = System.currentTimeMillis() - 1_000))
        assertEquals(BURST, results.size)
        assertEquals(1, refreshes.get())
        assertEquals(0, staleCalls.get())
        assertEquals(BURST, okCalls.get())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `REQ-AND-007 a token that still looks valid but is rejected is refreshed reactively`() {
        val source = remote(expiresAtMillis = System.currentTimeMillis() + 10 * 60_000)
        runBlocking(Dispatchers.Default) { source.fetchCategories() }
        assertEquals(1, refreshes.get())
        assertEquals(1, staleCalls.get())
    }

    private companion object {
        const val BURST = 10
        const val TEST_TIMEOUT_MS = 20_000L
    }
}
