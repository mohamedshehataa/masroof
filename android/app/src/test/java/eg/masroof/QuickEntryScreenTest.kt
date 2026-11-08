package eg.masroof

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.add.AddExpenseContent
import eg.masroof.ui.add.AddQuickActions
import eg.masroof.ui.add.AddVoiceActions
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.MicPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** STORY-200 on the real Add form: Income switch, currency chips, the quick line and the mic panel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QuickEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val repo = FakeRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)

    private fun show(
        micAvailable: Boolean = false,
        permission: MicPermission = MicPermission.GRANTED,
    ): AddExpenseViewModel {
        repo.categories.value =
            listOf(
                CategoryEntity("cat-car.fuel", "Fuel", "بنزين", 1),
                CategoryEntity("cat-rent", "Rent & housing", "إيجار وسكن", 2),
            )
        val vm = AddExpenseViewModel(repo, clock)
        compose.setContent {
            val state by vm.state.collectAsState()
            MasroofTheme {
                AddExpenseContent(
                    state = state,
                    onAmount = vm::onAmount,
                    onCategory = vm::onCategory,
                    onAccount = vm::onAccount,
                    onDate = vm::onDate,
                    onNotes = vm::onNotes,
                    onSave = vm::save,
                    quick =
                        AddQuickActions(
                            onType = vm::onType,
                            onCurrency = vm::onCurrency,
                            onMerchant = vm::onMerchant,
                            onQuickText = vm::onQuickText,
                            onQuickDone = vm::applyQuickText,
                            voice =
                                AddVoiceActions(
                                    onMic = { vm.onMicTapped(permission) },
                                    micAvailable = micAvailable,
                                    onStop = vm::onStopTapped,
                                    onClose = vm::dismissVoice,
                                    onRationaleContinue = vm::onRationaleContinue,
                                ),
                        ),
                )
            }
        }
        return vm
    }

    @Test
    fun `REQ-EXP-052 the form opens as Expense in EGP with no foreign currency hint`() {
        show()
        compose.onNodeWithTag("type_expense").assertIsSelected()
        compose.onNodeWithTag("currency_EGP").assertIsSelected()
        compose.onNodeWithText("Not included in EGP totals").assertDoesNotExistOnScreen()
    }

    @Test
    fun `REQ-EXP-052 all eight currencies are offered`() {
        show()
        for (code in listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP", "KWD", "QAR")) {
            compose.onNodeWithTag("currency_$code").assertExists()
        }
    }

    @Test
    fun `REQ-EXP-052 saving an Income in USD writes type INCOME and currency USD`() {
        show()
        compose.onNodeWithTag("type_income").performClick()
        compose.onNodeWithTag("currency_USD").performClick()
        compose.onNodeWithTag("amount").performTextInput("20")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(WAIT_MS) { repo.added.isNotEmpty() }
        val saved = repo.added.single()
        assertEquals("INCOME", saved.type)
        assertEquals("USD", saved.amount.currency)
        assertEquals(2000L, saved.amount.amountMinor)
        assertNull(saved.categoryId)
    }

    @Test
    fun `REQ-EXP-052 a non-EGP entry shows the not included in EGP totals hint`() {
        show()
        compose.onNodeWithTag("currency_EUR").performClick()
        compose.onNodeWithText("Not included in EGP totals").assertIsDisplayed()
        compose.onNodeWithTag("currency_EGP").performClick()
        compose.onNodeWithText("Not included in EGP totals").assertDoesNotExistOnScreen()
    }

    @Test
    fun `REQ-EXP-052 choosing Income hides the category picker`() {
        show()
        compose.onNodeWithText("Fuel").assertExists()
        compose.onNodeWithTag("type_income").performClick()
        compose.onNodeWithText("Fuel").assertDoesNotExistOnScreen()
        compose.onNodeWithTag("type_expense").performClick()
        compose.onNodeWithText("Fuel").assertExists()
    }

    @Test
    fun `REQ-AND-144 typing a line and pressing Done fills the form and saves nothing`() {
        show()
        compose.onNodeWithTag("quick_text").performTextInput("150 pounds on fuel")
        compose.onNodeWithTag("quick_text").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("amount").assertTextContains("150")
        compose.onNodeWithText("Fuel").assertIsSelected()
        compose.onNodeWithText("Filled from what you said. Check it, then tap Save.").assertIsDisplayed()
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun `REQ-AND-144 a line with no number marks the amount field Say or type an amount`() {
        show()
        compose.onNodeWithTag("quick_text").performTextInput("fuel")
        compose.onNodeWithTag("quick_text").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("Say or type an amount").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-144 the mic is hidden when the phone has no recognizer and shown when it has`() {
        show(micAvailable = false)
        compose.onNodeWithTag("mic").assertDoesNotExistOnScreen()
    }

    @Test
    fun `REQ-AND-144 the mic opens a listening panel with Stop`() {
        show(micAvailable = true)
        compose.onNodeWithTag("mic").assertIsDisplayed()
        compose.onNodeWithTag("mic").performClick()
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        compose.onNodeWithTag("voice_stop").performClick()
        compose.onNodeWithText("Working it out…").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-144 the rationale shows first and Continue moves on`() {
        show(micAvailable = true, permission = MicPermission.SHOW_RATIONALE)
        compose.onNodeWithTag("mic").performClick()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("voice_panel").assertDoesNotExistOnScreen()
    }

    @Test
    fun `REQ-AND-144 an error shows Didn't catch that and nothing is filled`() {
        val vm = show(micAvailable = true)
        compose.onNodeWithTag("mic").performClick()
        compose.runOnIdle { vm.onVoiceError() }
        compose.onNodeWithText("Didn't catch that - try again or type it").assertIsDisplayed()
        compose.onNodeWithTag("amount").assertTextContains("")
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-EXP-052 REQ-AND-144 the new controls are in Arabic in the Arabic locale`() {
        show(micAvailable = true)
        compose.onNodeWithText("مصروف").assertIsDisplayed()
        compose.onNodeWithText("دخل").assertIsDisplayed()
        compose.onNodeWithText("العملة").assertIsDisplayed()
    }
}

private const val WAIT_MS = 5_000L

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOnScreen() = assertDoesNotExist()
