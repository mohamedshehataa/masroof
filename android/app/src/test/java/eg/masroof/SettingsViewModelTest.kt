package eg.masroof

import eg.masroof.core.DigitStyle
import eg.masroof.data.AppSettings
import eg.masroof.data.SettingsRepository
import eg.masroof.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSettings : SettingsRepository {
    private val flow = MutableStateFlow(AppSettings())
    override val settings = flow

    override suspend fun current() = flow.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        flow.value = transform(flow.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val store = FakeSettings()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AND-017 an invalid server url is flagged and not stored`() =
        runTest {
            val vm = SettingsViewModel(store, TestAuth.manager(settings = store), TestAuth.eraser())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onUrl("masroof.example")
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.urlInvalid)
            assertEquals(AppSettings.DEFAULT_SERVER_URL, store.current().serverUrl)
        }

    @Test
    fun `REQ-AND-017 a valid url and digit style are stored and reported saved`() =
        runTest {
            val vm = SettingsViewModel(store, TestAuth.manager(settings = store), TestAuth.eraser())
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onUrl("https://masroof.example")
            vm.onDigits(DigitStyle.ARABIC_INDIC)
            vm.save()
            runCurrent()
            val stored = store.current()
            assertEquals("https://masroof.example/", stored.serverUrl)
            assertEquals(DigitStyle.ARABIC_INDIC, stored.digitStyle)
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.urlInvalid)
        }
}
