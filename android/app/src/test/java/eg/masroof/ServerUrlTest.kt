package eg.masroof

import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.LoginResult
import eg.masroof.data.normalizeServerUrl
import eg.masroof.ui.viewmodel.LoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pasted-with-a-space server URL (`http://10.71.97.175 /`) used to crash Retrofit and show "server problem". */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerUrlTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-006 whitespace inside the url is stripped`() {
        assertEquals("http://10.71.97.175/", normalizeServerUrl("http://10.71.97.175 /"))
        assertEquals("http://10.71.97.175/", normalizeServerUrl(" http://10.71.97.175\t/\n"))
        assertEquals("https://masroof.example/", normalizeServerUrl("https://mas roof.example"))
        assertEquals("http://10.0.2.2:8080/", normalizeServerUrl("http://10.0.2.2: 8080"))
        assertEquals("https://masroof.example/api/", normalizeServerUrl("https://masroof.example/ api"))
    }

    @Test
    fun `REQ-AND-006 anything that is not a valid http url is rejected`() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
        assertNull(normalizeServerUrl("http:// /"))
        assertNull(normalizeServerUrl("ftp://masroof.example"))
        assertNull(normalizeServerUrl("masroof.example"))
        assertNull(normalizeServerUrl("http://exa mple:port/"))
        assertNull(normalizeServerUrl("http://[::1"))
        assertNull(normalizeServerUrl("http://masroof.example:99999/"))
    }

    @Test
    fun `REQ-AND-006 an already clean url is unchanged`() {
        assertEquals("https://masroof.example/", normalizeServerUrl("https://masroof.example/"))
        assertEquals("http://10.0.2.2/", normalizeServerUrl("http://10.0.2.2/"))
    }

    private class RecordingRemote(
        var result: AuthResult,
    ) : AuthRemote {
        val baseUrls = mutableListOf<String>()

        override suspend fun login(
            baseUrl: String,
            email: String,
            password: String,
            deviceName: String,
        ): AuthResult {
            baseUrls += baseUrl
            return result
        }

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ): AuthResult = AuthResult.Network

        override suspend fun logout(
            baseUrl: String,
            accessToken: String,
            refreshToken: String,
        ) = true
    }

    private fun loginVm(remote: AuthRemote): LoginViewModel {
        val settings = FakeSettings()
        val sm = TestAuth.manager(FakeSessionStore(), remote, settings, FakeWiper())
        return LoginViewModel(sm, settings, FakeScheduler(), FakeNetwork())
    }

    @Test
    fun `REQ-AND-006 login with a spaced url signs in against the cleaned url`() =
        runTest {
            val remote = RecordingRemote(AuthResult.Success(session()))
            val vm = loginVm(remote)
            vm.onServerUrl("http://10.71.97.175 /")
            vm.onEmail("a@b.eg")
            vm.onPassword("pw-123456")
            vm.submit()
            assertEquals(listOf("http://10.71.97.175/"), remote.baseUrls)
            assertFalse(vm.state.value.urlInvalid)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `REQ-AND-006 an unusable url shows the invalid url message not a server problem`() =
        runTest {
            val remote = RecordingRemote(AuthResult.InvalidUrl)
            val vm = loginVm(remote)
            vm.onServerUrl("http://10.71.97.175 /")
            vm.onEmail("a@b.eg")
            vm.onPassword("pw-123456")
            vm.submit()
            assertTrue(vm.state.value.urlInvalid)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }

    @Test
    fun `REQ-AND-006 the session maps a rejected base url to InvalidUrl`() =
        runTest {
            val sm = TestAuth.manager(FakeSessionStore(), RecordingRemote(AuthResult.InvalidUrl), FakeSettings(), FakeWiper())
            assertEquals(LoginResult.InvalidUrl, sm.login("https://masroof.example", "a@b.eg", "pw"))
        }

    @Test
    fun `REQ-AND-006 retrofit rejecting the base url maps to InvalidUrl not Server`() =
        runTest {
            val remote = ApiAuthRemote(OkHttpClient())
            assertEquals(AuthResult.InvalidUrl, remote.login("http://bad host/", "a@b.eg", "pw", "phone"))
            assertEquals(AuthResult.InvalidUrl, remote.refresh("not a url", "r"))
            assertFalse(remote.logout("http://bad host/", "a", "r"))
        }
}
