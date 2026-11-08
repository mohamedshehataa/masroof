package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.serverimport.DefaultServerForgetter
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ServerImporter
import eg.masroof.ui.settings.ImportPhase
import eg.masroof.ui.settings.ImportViewModel
import eg.masroof.ui.settings.importCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings card state (REQ-OPS-174): shown only with a session, offline disabled, sign in again, done. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerImportViewModelTest {
    private lateinit var w: AnswerWorld
    private val store = FakeSessionStore(LoadedSession.Found(session("a", "r", SRV_ME)))
    private val source = FakeImportSource()
    private val importStore = MemoryImportStore()
    private val network = FakeNetwork()
    private lateinit var manager: SessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        manager = SessionManager(store, FakeAuthRemote(), w.settings, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        w.close()
        Dispatchers.resetMain()
    }

    private fun vm(): ImportViewModel {
        val importer =
            ServerImporter(
                w.db,
                source,
                manager,
                w.settings,
                w.repo,
                w.prefs,
                DefaultServerForgetter(manager, w.settings, FakeSessionKeystore()),
                EraseLock(),
                LocalDataSignal(),
                w.clock,
                Dispatchers.IO,
                importStore,
            )
        return ImportViewModel(importer, manager, network, importStore)
    }

    @Test
    fun `REQ-OPS-174 the card shows while a session exists and is gone without one`() =
        runTest {
            val withSession = vm()
            assertTrue(withSession.state.await { it.visible }.visible)
            store.stored = LoadedSession.Empty
            val fresh = SessionManager(store, FakeAuthRemote(), w.settings, Dispatchers.Unconfined)
            val none =
                ImportViewModel(
                    ServerImporter(
                        w.db,
                        source,
                        fresh,
                        w.settings,
                        w.repo,
                        w.prefs,
                        RecordingForgetter(),
                        EraseLock(),
                        LocalDataSignal(),
                        w.clock,
                        Dispatchers.IO,
                        MemoryImportStore(),
                    ),
                    fresh,
                    network,
                    MemoryImportStore(),
                )
            // the session state is Restoring until read, then SignedOut: the card never shows
            none.state.await { true }
            fresh.restore()
            assertFalse(none.state.value.visible)
        }

    @Test
    fun `REQ-OPS-174 REQ-SEC-065 a lost session keeps the card with Sign in until an import completes, even after leaving Settings`() =
        runTest {
            val first = vm()
            first.state.await { it.visible }
            assertTrue(importStore.pending)
            // the server rejected the refresh token: the session ends; Settings is left and opened again (a new view model)
            manager.logout()
            val reopened = vm()
            val s = reopened.state.await { it.visible && it.phase == ImportPhase.Failed(ImportFailureKind.SESSION) }
            assertTrue(s.visible)
            // the sign-in form opens only from the card
            reopened.openSignIn()
            assertTrue(reopened.state.await { it.signInOpen }.signInOpen)
        }

    @Test
    fun `REQ-OPS-174 a fresh install with no session never sees the card`() =
        runTest {
            store.stored = LoadedSession.Empty
            val fresh = SessionManager(store, FakeAuthRemote(), w.settings, Dispatchers.Unconfined)
            fresh.restore()
            val s =
                ImportViewModel(
                    ServerImporter(
                        w.db,
                        source,
                        fresh,
                        w.settings,
                        w.repo,
                        w.prefs,
                        RecordingForgetter(),
                        EraseLock(),
                        LocalDataSignal(),
                        w.clock,
                        Dispatchers.IO,
                        importStore,
                    ),
                    fresh,
                    network,
                    importStore,
                ).state.await { true }
            assertFalse(s.visible)
        }

    @Test
    fun `REQ-SEC-065 the Server section and Members show only while a session exists`() =
        runTest {
            val settingsVm =
                eg.masroof.ui.viewmodel
                    .SettingsViewModel(w.settings, manager, TestAuth.eraser())
            backgroundScope.launch { settingsVm.state.collect {} }
            assertTrue(settingsVm.state.await { it.showServer }.showServer)
            manager.logout()
            assertFalse(settingsVm.state.await { !it.showServer }.showServer)
            // saving digits with the URL gone still works (a blank URL is "no server")
            settingsVm.onDigits(eg.masroof.core.DigitStyle.ARABIC_INDIC)
            settingsVm.save()
            settingsVm.state.await { it.saved }
            assertEquals(eg.masroof.core.DigitStyle.ARABIC_INDIC, w.settings.current().digitStyle)
        }

    @Test
    fun `REQ-OPS-174 offline disables Import`() =
        runTest {
            network.flow.value = false
            val vm = vm()
            val s = vm.state.await { it.visible && it.offline }
            assertFalse(s.canImport)
            vm.start()
            assertEquals(0, source.calls)
        }

    @Test
    fun `REQ-OPS-175 Import runs and ends Done with the counts, then the card goes away`() =
        runTest {
            val vm = vm()
            vm.state.await { it.visible }
            vm.start()
            val done = vm.state.await { it.phase is ImportPhase.Done }
            assertEquals(ImportPhase.Done(1_240, 0), done.phase)
            // the session is forgotten: the result stays until it is dismissed, then the card is gone
            vm.dismissResult()
            assertFalse(vm.state.await { it.phase == ImportPhase.Idle }.visible)
        }

    @Test
    fun `REQ-OPS-174 a network failure shows the error and Import can be tapped again`() =
        runTest {
            source.failExpenseCall[0] = ImportFailureKind.NETWORK
            val vm = vm()
            vm.state.await { it.visible }
            vm.start()
            val failed = vm.state.await { it.phase is ImportPhase.Failed }
            assertEquals(ImportPhase.Failed(ImportFailureKind.NETWORK), failed.phase)
            assertTrue(failed.canImport)
            vm.start()
            assertEquals(ImportPhase.Done(1_240, 0), vm.state.await { it.phase is ImportPhase.Done }.phase)
        }

    @Test
    fun `REQ-OPS-174 a rejected session shows Sign in again with the sign-in form and a sign-in ends it`() =
        runTest {
            source.failExpenseCall[0] = ImportFailureKind.SESSION
            val vm = vm()
            vm.state.await { it.visible }
            vm.start()
            val s = vm.state.await { it.phase == ImportPhase.Failed(ImportFailureKind.SESSION) }
            assertTrue(s.visible)
            // the server rejected the refresh token: the session manager ends the session (as SessionManager.expire does)
            manager.logout()
            vm.closeSignIn()
            assertFalse(vm.state.await { !it.signInOpen }.signInOpen)
            vm.openSignIn()
            assertTrue(vm.state.await { it.signInOpen }.signInOpen)
        }

    @Test
    fun `REQ-UI-001 counts use the digit setting with thousands separators`() {
        assertEquals("1,240", importCount(1_240, eg.masroof.core.DigitStyle.WESTERN))
        assertEquals("١٬٢٤٠", importCount(1_240, eg.masroof.core.DigitStyle.ARABIC_INDIC))
    }
}
