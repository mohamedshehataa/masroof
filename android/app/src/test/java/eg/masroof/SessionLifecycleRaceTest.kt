package eg.masroof

import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.LoginResult
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.data.remote.newAuthClient
import eg.masroof.ingest.NoopCaptureGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun body(
    access: String,
    refresh: String,
    memberId: String,
    name: String,
) = "{\"accessToken\":\"$access\",\"refreshToken\":\"$refresh\",\"expiresIn\":900,\"tokenType\":\"Bearer\"," +
    "\"member\":{\"id\":\"$memberId\",\"householdId\":\"22222222-2222-2222-2222-222222222222\"," +
    "\"displayName\":\"$name\",\"role\":\"ADMIN\"}}"

/** A refresh whose reply is still on the wire when the user logs out, or signs in as someone else. */
class SessionLifecycleRaceTest {
    private val server = MockWebServer()
    private val arrived = CountDownLatch(1)
    private val proceed = CountDownLatch(1)
    private val revoked = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    val text = request.body?.utf8().orEmpty()
                    return when {
                        path.endsWith("/auth/refresh") -> {
                            arrived.countDown()
                            proceed.await(10, TimeUnit.SECONDS)
                            json(body("access-2", "refresh-2", "11111111-1111-1111-1111-111111111111", "Mohamed"))
                        }
                        path.endsWith(
                            "/auth/login",
                        ) -> json(body("other-access", "other-refresh", "33333333-3333-3333-3333-333333333333", "Other"))
                        path.endsWith("/auth/logout") -> {
                            revoked += text
                            MockResponse.Builder().code(204).build()
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        proceed.countDown()
        server.close()
    }

    private fun json(text: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(text)
            .build()

    private fun manager(store: FakeSessionStore): SessionManager {
        val settings = FakeSettings()
        runBlocking { settings.update { it.copy(serverUrl = server.url("/").toString()) } }
        return SessionManager(
            store,
            ApiAuthRemote(newAuthClient()),
            settings,
            FakeWiper(),
            Dispatchers.Unconfined,
            NoopCaptureGate,
            Clock.systemUTC(),
        )
    }

    @Test(timeout = 30_000)
    fun `REQ-AND-007 a refresh still in flight when the user logs out cannot bring the session back`() {
        val store = FakeSessionStore(LoadedSession.Found(session()))
        val sm = manager(store)
        runBlocking(Dispatchers.Default) {
            val refresh = async { sm.refreshAfter("access-1") }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            sm.logout()
            proceed.countDown() // the server's reply arrives after the logout finished
            assertNull(refresh.await())
        }
        assertEquals(LoadedSession.Empty, store.stored)
        assertEquals(SessionState.SignedOut(), sm.state.value)
        runBlocking { assertNull(sm.accessToken()) }
        assertTrue(revoked.any { it.contains("refresh-1") })
        assertTrue("the session the late reply created is revoked", revoked.any { it.contains("refresh-2") })
    }

    @Test(timeout = 30_000)
    fun `REQ-AND-007 a refresh still in flight does not overwrite another member who signed in meanwhile`() {
        val store = FakeSessionStore(LoadedSession.Found(session()))
        val sm = manager(store)
        runBlocking(Dispatchers.Default) {
            val refresh = async { sm.refreshAfter("access-1") }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            assertEquals(LoginResult.Success, sm.login(server.url("/").toString(), "other@b.eg", "pw"))
            proceed.countDown()
            refresh.await()
            assertEquals("other-access", sm.accessToken())
        }
        assertEquals(SessionState.SignedIn("Other"), sm.state.value)
        assertEquals("other-refresh", (store.stored as LoadedSession.Found).session.refreshToken)
        assertTrue(revoked.any { it.contains("refresh-2") })
    }

    @Test
    fun `REQ-AND-007 ten concurrent 401s with a refresh that fails on the network send exactly one refresh`() =
        runTest {
            val remote =
                FakeAuthRemote().apply {
                    refreshResult = {
                        delay(50)
                        AuthResult.Network
                    }
                }
            val sm = TestAuth.manager(FakeSessionStore(LoadedSession.Found(session())), remote)
            val results = (1..10).map { async { sm.refreshAfter("access-1") } }.awaitAll()
            assertEquals(1, remote.refreshes)
            assertTrue(results.all { it == null })
        }
}
