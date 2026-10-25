package eg.masroof

import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.SessionState
import eg.masroof.ui.viewmodel.LoginError
import eg.masroof.ui.viewmodel.LoginViewModel
import eg.masroof.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val remote = FakeAuthRemote()
    private val scheduler = FakeScheduler()
    private val settings = FakeSettings()
    private val wiper = FakeWiper()
    private val network = FakeNetwork()
    private val sm = TestAuth.manager(FakeSessionStore(), remote, settings, wiper)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = LoginViewModel(sm, settings, scheduler, network)

    private fun LoginViewModel.fill(
        email: String = "a@b.eg",
        password: String = "pw-123456",
    ) {
        onServerUrl("https://masroof.example")
        onEmail(email)
        onPassword(password)
    }

    @Test
    fun `REQ-AND-006 empty fields are validated without a request`() =
        runTest {
            val vm = vm()
            vm.submit()
            assertTrue(vm.state.value.emailMissing)
            assertTrue(vm.state.value.passwordMissing)
            assertEquals(0, remote.logins)
        }

    @Test
    fun `REQ-AND-006 a bad server url is flagged without a request`() =
        runTest {
            val vm = vm()
            vm.fill()
            vm.onServerUrl("masroof")
            vm.submit()
            assertTrue(vm.state.value.urlInvalid)
            assertEquals(0, remote.logins)
        }

    @Test
    fun `REQ-AND-006 success signs in, clears the password and flushes the outbox`() =
        runTest {
            remote.loginResult = AuthResult.Success(session())
            val vm = vm()
            vm.fill()
            vm.submit()
            assertEquals(SessionState.SignedIn("Mohamed"), sm.state.value)
            assertEquals("", vm.state.value.password)
            assertFalse(vm.state.value.loading)
            assertEquals(1, scheduler.calls)
            assertEquals("https://masroof.example/", settings.current().serverUrl)
        }

    @Test
    fun `REQ-AND-006 a 401 shows the generic credentials error`() =
        runTest {
            remote.loginResult = AuthResult.Rejected
            val vm = vm()
            vm.fill()
            vm.submit()
            assertEquals(LoginError.INVALID_CREDENTIALS, vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertEquals(0, scheduler.calls)
        }

    @Test
    fun `REQ-AND-006 a 429 shows the wait rounded up to whole minutes`() =
        runTest {
            remote.loginResult = AuthResult.RateLimited(61)
            val vm = vm()
            vm.fill()
            vm.submit()
            assertEquals(LoginError.RATE_LIMITED, vm.state.value.error)
            assertEquals(2, vm.state.value.retryMinutes)
            remote.loginResult = AuthResult.RateLimited(null)
            vm.submit()
            assertNull(vm.state.value.retryMinutes)
        }

    @Test
    fun `REQ-AND-006 a network error is shown with retry and keeps the typed values`() =
        runTest {
            remote.loginResult = AuthResult.Network
            val vm = vm()
            vm.fill()
            vm.submit()
            assertEquals(LoginError.NETWORK, vm.state.value.error)
            assertEquals("a@b.eg", vm.state.value.email)
            vm.onEmail("c@d.eg")
            assertNull(vm.state.value.error)
        }

    @Test
    fun `REQ-AND-007 settings logout calls the backend and wipes the account's data`() =
        runTest {
            remote.loginResult = AuthResult.Success(session())
            sm.login("https://masroof.example", "a@b.eg", "pw")
            val settingsVm = SettingsViewModel(settings, sm, scheduler)
            settingsVm.logout()
            assertEquals(listOf("refresh-1"), remote.logoutTokens)
            assertEquals(1, wiper.wipes)
            assertEquals(SessionState.SignedOut(), sm.state.value)
        }

    private suspend fun TestScope.signedInSettingsVm(unsynced: Int): SettingsViewModel {
        wiper.unsynced = unsynced
        remote.loginResult = AuthResult.Success(session())
        sm.login("https://masroof.example", "a@b.eg", "pw")
        val vm = SettingsViewModel(settings, sm, scheduler)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `REQ-AND-007 logout with unsynced entries asks first and deletes nothing`() =
        runTest {
            val vm = signedInSettingsVm(unsynced = 4)
            vm.logout()
            assertEquals(4, vm.state.value.confirmLogoutUnsynced)
            assertEquals(0, wiper.wipes)
            assertTrue(remote.logoutTokens.isEmpty())
            assertTrue(sm.state.value is SessionState.SignedIn)
        }

    @Test
    fun `REQ-AND-007 sync now from the logout dialog schedules the upload and keeps the session`() =
        runTest {
            val vm = signedInSettingsVm(unsynced = 4)
            vm.logout()
            val before = scheduler.calls
            vm.syncNow()
            assertEquals(before + 1, scheduler.calls)
            assertNull(vm.state.value.confirmLogoutUnsynced)
            assertEquals(0, wiper.wipes)
            assertTrue(sm.state.value is SessionState.SignedIn)
        }

    @Test
    fun `REQ-AND-007 dismissing the logout dialog changes nothing`() =
        runTest {
            val vm = signedInSettingsVm(unsynced = 1)
            vm.logout()
            vm.dismissLogout()
            assertNull(vm.state.value.confirmLogoutUnsynced)
            assertEquals(0, wiper.wipes)
            assertTrue(sm.state.value is SessionState.SignedIn)
        }

    @Test
    fun `REQ-AND-007 sign out anyway wipes only after the confirmation`() =
        runTest {
            val vm = signedInSettingsVm(unsynced = 4)
            vm.logout()
            vm.confirmLogout()
            assertEquals(1, wiper.wipes)
            assertEquals(listOf("refresh-1"), remote.logoutTokens)
            assertEquals(SessionState.SignedOut(), sm.state.value)
            assertNull(vm.state.value.confirmLogoutUnsynced)
        }

    @Test
    fun `REQ-AND-007 login as a different member with unsynced entries asks before wiping`() =
        runTest {
            wiper.unsynced = 2
            val store = FakeSessionStore().apply { lastMemberId = "someone-else" }
            val manager = TestAuth.manager(store, remote, settings, wiper)
            remote.loginResult = AuthResult.Success(session())
            val vm = LoginViewModel(manager, settings, scheduler, network)
            vm.onServerUrl("https://masroof.example")
            vm.onEmail("a@b.eg")
            vm.onPassword("pw-123456")
            vm.submit()
            assertEquals(2, vm.state.value.confirmSwitchUnsynced)
            assertEquals(0, wiper.wipes)
            vm.cancelSwitch()
            assertNull(vm.state.value.confirmSwitchUnsynced)
            assertEquals(0, wiper.wipes)
            vm.onPassword("pw-123456")
            vm.submit()
            vm.confirmSwitch()
            assertEquals(1, wiper.wipes)
            assertEquals(SessionState.SignedIn("Mohamed"), manager.state.value)
        }

    @Test
    fun `REQ-AND-006 offline disables sign in without a request`() =
        runTest {
            val vm = vm()
            vm.fill()
            network.flow.value = false
            assertTrue(vm.state.value.offline)
            vm.submit()
            assertEquals(0, remote.logins)
            network.flow.value = true
            assertFalse(vm.state.value.offline)
        }
}
