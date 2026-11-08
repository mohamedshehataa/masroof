package eg.masroof

import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.viewmodel.ADD_CURRENCIES
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.AddField
import eg.masroof.ui.viewmodel.MicPermission
import eg.masroof.ui.viewmodel.VoiceUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** STORY-200: the Add form's state for the quick line, the mic flow, income and currency. Clock: 2026-10-15 in Cairo. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickEntryViewModelTest {
    private val repo = FakeRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo.categories.value =
            listOf(
                CategoryEntity("cat-car.fuel", "Fuel", "بنزين", 1),
                CategoryEntity("cat-dining", "Dining out", "مطاعم", 2),
                CategoryEntity("cat-rent", "Rent & housing", "إيجار وسكن", 3),
            )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(): AddExpenseViewModel {
        val vm = AddExpenseViewModel(repo, clock)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun AddExpenseViewModel.form() = state.value.form

    // ---- REQ-AND-144: the quick-text line ----

    @Test
    fun `REQ-AND-144 the quick line fills amount merchant category and date and saves nothing`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("spent 85.5 at talabat yesterday")
            vm.applyQuickText()
            val f = vm.form()
            assertEquals("85.50", f.amountText)
            assertEquals("talabat", f.merchant)
            assertEquals("cat-dining", f.categoryId)
            assertEquals("2026-10-14", f.dateText)
            assertEquals("", f.quickText)
            assertEquals(setOf(AddField.AMOUNT, AddField.DATE, AddField.CATEGORY, AddField.MERCHANT), f.highlight)
            assertTrue(repo.added.isEmpty())
        }

    @Test
    fun `REQ-AND-144 a category that is not on this phone is left empty and never invented`() =
        runTest {
            repo.categories.value = emptyList()
            val vm = newVm()
            vm.onQuickText("150 pounds on fuel")
            vm.applyQuickText()
            assertNull(vm.form().categoryId)
            assertEquals("150", vm.form().amountText)
        }

    @Test
    fun `REQ-AND-144 a line with no number asks for an amount and keeps one already typed`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("fuel")
            vm.applyQuickText()
            assertTrue(vm.form().amountMissing)
            assertEquals("cat-car.fuel", vm.form().categoryId)
            vm.onAmount("40")
            assertFalse(vm.form().amountMissing)
            vm.onQuickText("lunch")
            vm.applyQuickText()
            assertEquals("40", vm.form().amountText)
            assertFalse(vm.form().amountMissing)
        }

    @Test
    fun `REQ-AND-144 a blank quick line does nothing`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("   ")
            vm.applyQuickText()
            assertEquals(emptySet<AddField>(), vm.form().highlight)
            assertEquals("", vm.form().amountText)
        }

    @Test
    fun `REQ-AND-144 filled fields stop being highlighted after two seconds`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("50 fuel")
            vm.applyQuickText()
            assertTrue(vm.form().highlight.isNotEmpty())
            advanceTimeBy(1_900)
            assertTrue(vm.form().highlight.isNotEmpty())
            advanceTimeBy(200)
            assertTrue(vm.form().highlight.isEmpty())
        }

    @Test
    fun `REQ-AND-144 a line without a currency or a day keeps the currency and the date already chosen`() =
        runTest {
            val vm = newVm()
            vm.onCurrency("USD")
            vm.onDate("2026-10-01")
            vm.onQuickText("50 lunch")
            vm.applyQuickText()
            assertEquals("USD", vm.form().currency)
            assertEquals("2026-10-01", vm.form().dateText)
            vm.onQuickText("30 euros yesterday")
            vm.applyQuickText()
            assertEquals("EUR", vm.form().currency)
            assertEquals("2026-10-14", vm.form().dateText)
        }

    @Test
    fun `REQ-AND-144 Back after a filled draft leaves nothing saved`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            vm.onVoiceText("150 pounds on fuel")
            assertEquals("150", vm.form().amountText)
            // Back: the screen is dropped without calling save().
            assertTrue(repo.added.isEmpty())
            assertFalse(vm.form().saved)
        }

    // ---- REQ-AND-144: the mic flow ----

    @Test
    fun `REQ-AND-144 a granted mic listens and the final text fills the form`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            assertTrue(vm.form().voice is VoiceUi.Listening)
            vm.onVoiceLevel(55)
            assertEquals(55, (vm.form().voice as VoiceUi.Listening).level)
            vm.onVoiceText("دفعت مية وخمسين في كارفور امبارح")
            assertEquals(VoiceUi.Idle, vm.form().voice)
            assertEquals("150", vm.form().amountText)
            assertEquals("كارفور", vm.form().merchant)
            assertEquals("2026-10-14", vm.form().dateText)
        }

    @Test
    fun `REQ-AND-144 Stop shows processing and the text that follows still fills the form`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            vm.onStopTapped()
            assertEquals(VoiceUi.Processing, vm.form().voice)
            vm.onVoiceText("20 dollars netflix")
            assertEquals("20", vm.form().amountText)
            assertEquals("USD", vm.form().currency)
        }

    @Test
    fun `REQ-AND-144 the permission is asked on the tap and a first refusal can be retried`() =
        runTest {
            val vm = newVm()
            assertEquals(VoiceUi.Idle, vm.form().voice)
            vm.onMicTapped(MicPermission.ASK)
            assertEquals(VoiceUi.Requesting, vm.form().voice)
            vm.onPermissionResult(granted = false, canAskAgain = true)
            assertEquals(VoiceUi.Denied, vm.form().voice)
            vm.onMicTapped(MicPermission.ASK)
            vm.onPermissionResult(granted = true, canAskAgain = true)
            assertTrue(vm.form().voice is VoiceUi.Listening)
        }

    @Test
    fun `REQ-AND-144 a refusal with do not ask again points to Settings and the quick line still works`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.ASK)
            vm.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(VoiceUi.DeniedForever, vm.form().voice)
            vm.onQuickText("75 pharmacy")
            vm.applyQuickText()
            assertEquals("75", vm.form().amountText)
            assertEquals(VoiceUi.DeniedForever, vm.form().voice)
            vm.dismissVoice()
            assertEquals(VoiceUi.Idle, vm.form().voice)
        }

    @Test
    fun `REQ-AND-144 a reason is shown first when the system says so`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.SHOW_RATIONALE)
            assertEquals(VoiceUi.Rationale, vm.form().voice)
            vm.onRationaleContinue()
            assertEquals(VoiceUi.Requesting, vm.form().voice)
        }

    @Test
    fun `REQ-AND-144 silence or a recognizer error fills nothing`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            vm.onVoiceError()
            assertEquals(VoiceUi.Error, vm.form().voice)
            assertEquals("", vm.form().amountText)
            vm.onMicTapped(MicPermission.GRANTED)
            vm.onVoiceText("   ")
            assertEquals(VoiceUi.Error, vm.form().voice)
            assertEquals("", vm.form().amountText)
            assertTrue(repo.added.isEmpty())
        }

    @Test
    fun `REQ-AND-144 text that arrives after the panel was closed is ignored`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            vm.dismissVoice()
            vm.onVoiceText("500 rent")
            vm.onVoiceError()
            assertEquals("", vm.form().amountText)
            assertEquals(VoiceUi.Idle, vm.form().voice)
        }

    @Test
    fun `REQ-AND-144 tapping the mic again while listening does not restart it`() =
        runTest {
            val vm = newVm()
            vm.onMicTapped(MicPermission.GRANTED)
            vm.onVoiceLevel(30)
            vm.onMicTapped(MicPermission.GRANTED)
            assertEquals(30, (vm.form().voice as VoiceUi.Listening).level)
        }

    // ---- REQ-EXP-052: income and currency ----

    @Test
    fun `REQ-EXP-052 the form opens as an EGP expense`() =
        runTest {
            val vm = newVm()
            assertFalse(vm.form().income)
            assertEquals("EGP", vm.form().currency)
            assertEquals(listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP", "KWD", "QAR"), ADD_CURRENCIES)
        }

    @Test
    fun `REQ-EXP-052 saving an income in USD writes type INCOME and currency USD without a category`() =
        runTest {
            val vm = newVm()
            vm.onAmount("20")
            vm.onCategory("cat-rent")
            vm.onType(true)
            vm.onCurrency("USD")
            vm.save()
            val saved = repo.added.single()
            assertEquals("INCOME", saved.type)
            assertEquals("USD", saved.amount.currency)
            assertEquals(2000L, saved.amount.amountMinor)
            assertNull(saved.categoryId)
        }

    @Test
    fun `REQ-EXP-052 saving an expense keeps EGP type EXPENSE and the category`() =
        runTest {
            val vm = newVm()
            vm.onAmount("20")
            vm.onCategory("cat-rent")
            vm.save()
            val saved = repo.added.single()
            assertEquals("EXPENSE", saved.type)
            assertEquals("EGP", saved.amount.currency)
            assertEquals("cat-rent", saved.categoryId)
        }

    @Test
    fun `REQ-EXP-052 an unknown currency code is ignored and income drops the chosen category`() =
        runTest {
            val vm = newVm()
            vm.onCurrency("XYZ")
            assertEquals("EGP", vm.form().currency)
            vm.onCategory("cat-rent")
            vm.onType(true)
            assertNull(vm.form().categoryId)
            vm.onType(false)
            assertNull(vm.form().categoryId)
        }

    @Test
    fun `REQ-EXP-052 a spoken income line switches the form to income and saves as income`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("received 12,000 salary")
            vm.applyQuickText()
            assertTrue(vm.form().income)
            assertEquals("12000", vm.form().amountText)
            assertNull(vm.form().categoryId)
            vm.save()
            assertEquals("INCOME", repo.added.single().type)
        }

    @Test
    fun `REQ-EXP-052 the merchant from a line is saved on the entry`() =
        runTest {
            val vm = newVm()
            vm.onQuickText("200 le uber")
            vm.applyQuickText()
            vm.save()
            assertEquals("uber", repo.added.single().merchant)
        }
}
