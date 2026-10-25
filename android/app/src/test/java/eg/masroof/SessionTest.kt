package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.api.models.Member
import eg.masroof.api.models.MemberRole
import eg.masroof.api.models.TokenPair
import eg.masroof.api.models.TokenPairTokenType
import eg.masroof.data.auth.AeadTokenCipher
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.EncryptedSessionStore
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.LoginResult
import eg.masroof.data.auth.SessionState
import eg.masroof.data.auth.SignedOutNotice
import eg.masroof.data.auth.TokenAuthenticator
import eg.masroof.data.auth.toAuthResult
import eg.masroof.data.remote.UploadResult
import eg.masroof.data.remote.classifyUploadStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

private fun pair(
    access: String = "access-2",
    refresh: String = "refresh-2",
) = TokenPair(
    access,
    refresh,
    900,
    TokenPairTokenType.BEARER,
    Member(UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.randomUUID(), "Mohamed", MemberRole.ADMIN),
)

private fun http(
    code: Int,
    auth: String? = "Bearer access-1",
    prior: Response? = null,
): Response {
    val req =
        Request
            .Builder()
            .url("https://masroof.example/api/expenses")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
    return Response
        .Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .body("".toResponseBody("application/json".toMediaType()))
        .priorResponse(prior)
        .build()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTest {
    private fun signedIn(
        remote: FakeAuthRemote = FakeAuthRemote(),
        wiper: FakeWiper = FakeWiper(),
        store: FakeSessionStore = FakeSessionStore(LoadedSession.Found(session())),
    ) = TestAuth.manager(store, remote, wiper = wiper)

    // ---- token store (REQ-AND-007)

    @Test
    fun `REQ-AND-007 tokens are sealed at rest and round trip through the store`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t1", Context.MODE_PRIVATE)
        val store = EncryptedSessionStore(prefs, AeadTokenCipher(inMemoryAead()))
        store.save(session(access = "SECRET-ACCESS", refresh = "SECRET-REFRESH"))
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("SECRET"))
        val loaded = store.load() as LoadedSession.Found
        assertEquals("SECRET-REFRESH", loaded.session.refreshToken)
        assertEquals("SECRET-ACCESS", loaded.session.accessToken)
        store.clear()
        assertEquals(LoadedSession.Empty, store.load())
    }

    @Test
    fun `REQ-AND-007 an unreadable keystore blob is discarded and reported as invalidated`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t2", Context.MODE_PRIVATE)
        EncryptedSessionStore(prefs, AeadTokenCipher(inMemoryAead())).save(session())
        // A different key (as after a keystore reset) cannot open it.
        val other = EncryptedSessionStore(prefs, AeadTokenCipher(inMemoryAead()))
        assertEquals(LoadedSession.Invalidated, other.load())
        assertEquals(LoadedSession.Empty, other.load())
    }

    @Test
    fun `REQ-AND-007 session toString never contains a token`() {
        val text = session(access = "SECRET-ACCESS", refresh = "SECRET-REFRESH").toString()
        assertFalse(text.contains("SECRET"))
    }

    // ---- login (REQ-AND-006)

    @Test
    fun `REQ-AND-006 login stores the session and signs in`() =
        runTest {
            val remote = FakeAuthRemote().apply { loginResult = AuthResult.Success(session()) }
            val store = FakeSessionStore()
            val sm = TestAuth.manager(store, remote)
            assertEquals(LoginResult.Success, sm.login("https://masroof.example", " a@b.eg ", "pw-123456"))
            assertEquals(SessionState.SignedIn("Mohamed"), sm.state.value)
            assertEquals("access-1", sm.accessToken())
            assertEquals("member-1", store.lastMemberId)
        }

    @Test
    fun `REQ-AND-006 login failures map to results and store nothing`() =
        runTest {
            val remote = FakeAuthRemote()
            val store = FakeSessionStore()
            val sm = TestAuth.manager(store, remote)
            remote.loginResult = AuthResult.Rejected
            assertEquals(LoginResult.InvalidCredentials, sm.login("https://x.eg", "a@b.eg", "pw"))
            remote.loginResult = AuthResult.RateLimited(90)
            assertEquals(LoginResult.RateLimited(90), sm.login("https://x.eg", "a@b.eg", "pw"))
            remote.loginResult = AuthResult.Network
            assertEquals(LoginResult.Network, sm.login("https://x.eg", "a@b.eg", "pw"))
            remote.loginResult = AuthResult.Server
            assertEquals(LoginResult.Server, sm.login("https://x.eg", "a@b.eg", "pw"))
            assertEquals(LoginResult.InvalidUrl, sm.login("masroof", "a@b.eg", "pw"))
            assertTrue(store.saves.isEmpty())
            assertNull(sm.accessToken())
        }

    @Test
    fun `REQ-AND-006 signing in as a different member wipes the previous account's cache`() =
        runTest {
            val wiper = FakeWiper()
            val store = FakeSessionStore().apply { lastMemberId = "someone-else" }
            val remote = FakeAuthRemote().apply { loginResult = AuthResult.Success(session()) }
            TestAuth.manager(store, remote, wiper = wiper).login("https://x.eg", "a@b.eg", "pw")
            assertEquals(1, wiper.wipes)
            // Same member again: queued entries are kept.
            TestAuth.manager(store, remote, wiper = wiper).login("https://x.eg", "a@b.eg", "pw")
            assertEquals(1, wiper.wipes)
        }

    @Test
    fun `REQ-AND-007 an invalidated keystore opens on login with a notice`() =
        runTest {
            val sm = TestAuth.manager(FakeSessionStore(LoadedSession.Invalidated))
            sm.restore()
            assertEquals(SessionState.SignedOut(SignedOutNotice.KEYSTORE_RESET), sm.state.value)
        }

    // ---- refresh (REQ-AND-007)

    @Test
    fun `REQ-AND-007 concurrent 401s share one refresh and all retry with the new token`() =
        runTest {
            val remote =
                FakeAuthRemote().apply {
                    refreshResult = {
                        delay(50)
                        AuthResult.Success(session("access-2", "refresh-2"))
                    }
                }
            val sm = signedIn(remote)
            val tokens = (1..8).map { async { sm.refreshAfter("access-1") } }.awaitAll()
            assertEquals(1, remote.refreshes)
            assertEquals(listOf("refresh-1"), remote.refreshTokensSeen)
            assertTrue(tokens.all { it == "access-2" })
            assertEquals("access-2", sm.accessToken())
        }

    @Test
    fun `REQ-AND-007 a rejected refresh wipes the session but keeps cached data`() =
        runTest {
            val remote = FakeAuthRemote().apply { refreshResult = { AuthResult.Rejected } }
            val wiper = FakeWiper()
            val store = FakeSessionStore(LoadedSession.Found(session()))
            val sm = TestAuth.manager(store, remote, wiper = wiper)
            assertNull(sm.refreshAfter("access-1"))
            assertEquals(SessionState.SignedOut(SignedOutNotice.SESSION_EXPIRED), sm.state.value)
            assertEquals(1, store.clears)
            assertEquals(0, wiper.wipes)
            assertNull(sm.accessToken())
        }

    @Test
    fun `REQ-AND-007 a network failure during refresh keeps the session`() =
        runTest {
            val remote = FakeAuthRemote().apply { refreshResult = { AuthResult.Network } }
            val sm = signedIn(remote)
            assertNull(sm.refreshAfter("access-1"))
            assertEquals(SessionState.SignedIn("Mohamed"), sm.state.value)
            assertEquals("access-1", sm.accessToken())
        }

    // ---- authenticator

    private fun authenticator(remote: FakeAuthRemote) = TokenAuthenticator(signedIn(remote))

    @Test
    fun `REQ-AND-007 authenticator refreshes once and retries with the new bearer`() {
        val remote = FakeAuthRemote().apply { refreshResult = { AuthResult.Success(session("access-2", "refresh-2")) } }
        val retry = authenticator(remote).authenticate(null, http(401))
        assertNotNull(retry)
        assertEquals("Bearer access-2", retry!!.header("Authorization"))
        assertEquals(1, remote.refreshes)
    }

    @Test
    fun `REQ-AND-007 authenticator gives up on a second 401 and on requests that carried no token`() {
        val remote = FakeAuthRemote().apply { refreshResult = { AuthResult.Success(session("access-2", "refresh-2")) } }
        val auth = authenticator(remote)
        assertNull(auth.authenticate(null, http(401, prior = http(401))))
        assertNull(auth.authenticate(null, http(401, auth = null)))
        assertEquals(0, remote.refreshes)
    }

    @Test
    fun `REQ-AND-007 authenticator returns null and signs out when the refresh is rejected`() {
        val remote = FakeAuthRemote().apply { refreshResult = { AuthResult.Rejected } }
        val sm = signedIn(remote)
        assertNull(TokenAuthenticator(sm).authenticate(null, http(401)))
        assertTrue(sm.state.value is SessionState.SignedOut)
    }

    // ---- logout

    @Test
    fun `REQ-AND-007 logout revokes the refresh token then wipes tokens and cached data`() =
        runTest {
            val remote = FakeAuthRemote()
            val wiper = FakeWiper()
            val store = FakeSessionStore(LoadedSession.Found(session())).apply { lastMemberId = "member-1" }
            val sm = TestAuth.manager(store, remote, wiper = wiper)
            sm.logout()
            assertEquals(listOf("refresh-1"), remote.logoutTokens)
            assertEquals(LoadedSession.Empty, store.stored)
            assertNull(store.lastMemberId)
            assertEquals(1, wiper.wipes)
            assertEquals(SessionState.SignedOut(), sm.state.value)
            assertNull(sm.accessToken())
        }

    @Test
    fun `REQ-AND-007 logout still wipes locally when the server cannot be reached`() =
        runTest {
            val remote = FakeAuthRemote().apply { logoutOk = false }
            val wiper = FakeWiper()
            val sm = TestAuth.manager(FakeSessionStore(LoadedSession.Found(session())), remote, wiper = wiper)
            sm.logout()
            assertEquals(1, wiper.wipes)
            assertEquals(SessionState.SignedOut(), sm.state.value)
        }

    // ---- wire mapping

    @Test
    fun `REQ-AND-006 auth responses map to results including the 429 retry-after`() {
        assertTrue(retrofit2.Response.success(pair()).toAuthResult() is AuthResult.Success)
        val json = "{}".toResponseBody("application/problem+json".toMediaType())
        assertEquals(AuthResult.Rejected, retrofit2.Response.error<TokenPair>(401, json).toAuthResult())
        assertEquals(AuthResult.Rejected, retrofit2.Response.error<TokenPair>(400, json).toAuthResult())
        assertEquals(AuthResult.Server, retrofit2.Response.error<TokenPair>(503, json).toAuthResult())
        val limited =
            retrofit2.Response.error<TokenPair>(
                json,
                http(429).newBuilder().header("Retry-After", "120").build(),
            )
        assertEquals(AuthResult.RateLimited(120), limited.toAuthResult())
    }

    // ---- upload status mapping (MINOR from the last verify)

    @Test
    fun `REQ-AND-015 a concurrent Idempotency-Key 409 is retried, not rejected`() {
        assertEquals(UploadResult.Retry, classifyUploadStatus(409, false))
        assertEquals(UploadResult.Retry, classifyUploadStatus(429, false))
        assertEquals(UploadResult.Retry, classifyUploadStatus(503, false))
        assertEquals(UploadResult.Unauthorized, classifyUploadStatus(401, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(422, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(400, false))
    }
}
