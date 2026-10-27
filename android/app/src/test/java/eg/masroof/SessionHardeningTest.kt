package eg.masroof

import eg.masroof.api.models.Member
import eg.masroof.api.models.MemberRole
import eg.masroof.api.models.TokenPair
import eg.masroof.api.models.TokenPairTokenType
import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.Session
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.data.auth.toAuthResult
import eg.masroof.data.remote.newAuthClient
import eg.masroof.ingest.NoopCaptureGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MEMBER_JSON =
    "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"householdId\":\"22222222-2222-2222-2222-222222222222\"," +
        "\"displayName\":\"Mohamed\",\"role\":\"ADMIN\"}"

private fun tokens(n: Int) =
    "{\"accessToken\":\"access-$n\",\"refreshToken\":\"refresh-$n\",\"expiresIn\":900,\"tokenType\":\"Bearer\",\"member\":$MEMBER_JSON}"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionHardeningTest {
    private fun manager(
        remote: eg.masroof.data.auth.AuthRemote,
        store: FakeSessionStore,
        settings: FakeSettings = FakeSettings(),
        wiper: FakeWiper = FakeWiper(),
        clock: Clock = Clock.systemUTC(),
    ) = SessionManager(store, remote, settings, wiper, Dispatchers.Unconfined, NoopCaptureGate, clock)

    @Test(timeout = 30_000)
    fun `REQ-AND-007 a caller cancelled after the server rotated the token does not lose the rotation`() {
        val server = MockWebServer()
        val arrived = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var current = 1
        val reuse = AtomicBoolean(false)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    proceed.await(10, TimeUnit.SECONDS)
                    val body = request.body?.utf8().orEmpty()
                    return if (body.contains("\"refresh-$current\"")) {
                        current++
                        MockResponse
                            .Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/json")
                            .body(tokens(current))
                            .build()
                    } else {
                        reuse.set(true) // a spent refresh token was replayed: the server would revoke the family
                        MockResponse.Builder().code(401).build()
                    }
                }
            }
        server.start()
        try {
            val settings = FakeSettings()
            runBlocking { settings.update { it.copy(serverUrl = server.url("/").toString()) } }
            val store = FakeSessionStore(LoadedSession.Found(session()))
            val sm = manager(ApiAuthRemote(newAuthClient()), store, settings)
            runBlocking(Dispatchers.Default) {
                val caller = launch { sm.refreshAfter("access-1") }
                assertTrue(arrived.await(10, TimeUnit.SECONDS))
                caller.cancel() // the caller gives up while the server is rotating the token
                proceed.countDown()
                caller.join()
                assertEquals("access-2", sm.refreshAfter("access-1")) // waits for the abandoned refresh, no second request
                assertEquals("access-3", sm.refreshAfter("access-2")) // sends refresh-2, not the spent refresh-1
            }
            assertFalse(reuse.get())
            assertEquals(SessionState.SignedIn("Mohamed"), sm.state.value)
            assertEquals("refresh-3", (store.stored as LoadedSession.Found).session.refreshToken)
        } finally {
            proceed.countDown()
            server.close()
        }
    }

    @Test(timeout = 30_000)
    fun `REQ-AND-007 the auth client never follows a redirect, so credentials stay on the configured host`() {
        val elsewhere = MockWebServer().apply { start() }
        val home = MockWebServer()
        home.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse
                        .Builder()
                        .code(307)
                        .addHeader("Location", elsewhere.url("/auth/refresh").toString())
                        .build()
            }
        home.start()
        try {
            val remote = ApiAuthRemote(newAuthClient())
            val refresh = runBlocking { remote.refresh(home.url("/").toString(), "refresh-1") }
            val login = runBlocking { remote.login(home.url("/").toString(), "a@b.eg", "pw", "d") }
            assertEquals(AuthResult.Server, refresh)
            assertEquals(AuthResult.Server, login)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            home.close()
            elsewhere.close()
        }
    }

    @Test
    fun `REQ-AND-007 a logout cancelled during the revoke wait still wipes the device`() =
        runTest {
            val remote =
                object : eg.masroof.data.auth.AuthRemote by FakeAuthRemote() {
                    override suspend fun logout(
                        baseUrl: String,
                        accessToken: String,
                        refreshToken: String,
                    ): Boolean = awaitCancellation()
                }
            val store = FakeSessionStore(LoadedSession.Found(session()))
            val wiper = FakeWiper()
            val sm = manager(remote, store, wiper = wiper)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { sm.logout() }
            job.cancel() // e.g. the user left Settings (viewModelScope cancelled)
            advanceUntilIdle()
            assertEquals(1, store.clears)
            assertEquals(1, wiper.wipes)
            assertEquals(SessionState.SignedOut(), sm.state.value)
        }

    @Test
    fun `REQ-AND-007 a timed out proactive refresh is not retried for 30 seconds`() =
        runTest {
            val remote = FakeAuthRemote().apply { refreshResult = { awaitCancellation() } }
            val now = Instant.parse("2026-10-27T10:00:00Z")
            val sm =
                manager(
                    remote,
                    FakeSessionStore(LoadedSession.Found(Session("access-1", "refresh-1", "m", "Mohamed", now.toEpochMilli() - 1))),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )
            assertEquals("access-1", sm.freshAccessToken()) // waited the bound, then sent the current token
            assertEquals("access-1", sm.freshAccessToken()) // backoff: no second refresh
            assertEquals(1, remote.refreshes)
        }

    @Test
    fun `REQ-AND-007 a very short token lifetime is clamped so it does not refresh on every request`() {
        val pair =
            TokenPair("a", "r", 5, TokenPairTokenType.BEARER, Member(UUID.randomUUID(), UUID.randomUUID(), "Mohamed", MemberRole.ADMIN))
        val result = Response.success(pair).toAuthResult(nowMillis = 1_000_000L) as AuthResult.Success
        assertEquals(1_000_000L + 120_000L, result.session.accessExpiresAtMillis)
    }
}
