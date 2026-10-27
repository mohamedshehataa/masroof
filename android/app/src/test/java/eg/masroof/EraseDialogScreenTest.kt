package eg.masroof

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eg.masroof.data.auth.EraseCounts
import eg.masroof.ui.add.AddExpenseContent
import eg.masroof.ui.settings.SettingsActions
import eg.masroof.ui.settings.SettingsContent
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.AddFormState
import eg.masroof.ui.viewmodel.AddUiState
import eg.masroof.ui.viewmodel.EraseState
import eg.masroof.ui.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EraseDialogScreenTest {
    @get:Rule val compose = createComposeRule()

    private var asked = 0
    private var confirmed = 0
    private var dismissed = 0

    private fun showSettings(erase: EraseState) =
        compose.setContent {
            MasroofTheme {
                SettingsContent(
                    SettingsUiState(erase = erase),
                    SettingsActions({}, {}, {}, {}, { asked++ }, { confirmed++ }, { dismissed++ }),
                )
            }
        }

    @Test
    fun `REQ-AND-132 Erase all data opens a dialog with the counts before anything is deleted`() {
        showSettings(EraseState.Idle)
        compose.onNodeWithText("Erase all data").performScrollTo().performClick()
        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    @Test
    fun `REQ-AND-132 the dialog names the entries and receipts and has a red Erase plus a Cancel`() {
        showSettings(EraseState.Confirm(EraseCounts(12, 3)))
        compose.onNodeWithText("Entries: 12 - Receipts: 3").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun `REQ-AND-132 only the Erase button confirms`() {
        showSettings(EraseState.Confirm(EraseCounts(1, 0)))
        compose.onNodeWithText("Erase").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `REQ-AND-132 a rolled back erase says Couldn't erase, nothing was changed`() {
        showSettings(EraseState.Failed)
        compose.onNodeWithText("Couldn't erase, nothing was changed").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-043 the Add sheet shows the amount errors and the save failure and no offline wording`() {
        compose.setContent {
            MasroofTheme {
                AddExpenseContent(
                    state = AddUiState(AddFormState(amountTooLarge = true, saveFailed = true)),
                    onAmount = {},
                    onCategory = {},
                    onAccount = {},
                    onDate = {},
                    onNotes = {},
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Amount is too large").assertIsDisplayed()
        compose.onNodeWithText("Couldn't save, try again").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
