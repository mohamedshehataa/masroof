package eg.masroof.backup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.core.DigitStyle
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreInfo
import eg.masroof.data.backup.RestoreState
import eg.masroof.ui.settings.RestoreActions
import eg.masroof.ui.settings.RestoreSectionContent
import eg.masroof.ui.settings.RestoreUiState
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.theme.MoneyStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The restore section in Egyptian Arabic, right to left, at font scale 1.5, with the Arabic-Indic digit setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class RestoreSectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val actions = RestoreActions({}, {}, {}, {}, {}, {}, {})

    private fun show(state: RestoreUiState) =
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale = 1.5f),
                LocalMoneyStyle provides MoneyStyle(arabic = true, digits = DigitStyle.ARABIC_INDIC),
            ) { MasroofTheme { RestoreSectionContent(state, actions) } }
        }

    @Test
    fun `REQ-OPS-178 the Restore button is in Arabic with a 48 dp target`() {
        show(RestoreUiState())
        compose.onNodeWithText("استرجع من نسخة احتياطية").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-OPS-178 the confirmation shows the date and counts with Arabic-Indic digits`() {
        // 11:05 UTC on 27 Oct 2026 is 14:05 in Cairo
        val info = RestoreInfo(1_793_012_700_000L, 1_240, 37, 4, 12)
        show(RestoreUiState(run = RestoreState.Confirm(info)))
        compose.onNodeWithText("١٤:٠٥", substring = true).assertIsDisplayed()
        compose.onNodeWithText("١,٢٤٠", substring = true).assertIsDisplayed()
        compose.onNodeWithText("استرجع").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-SEC-068 an error shows the one message and no stack trace`() {
        show(RestoreUiState(run = RestoreState.Failed(RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED)))
        compose.onNodeWithText("كلمة السر غلط أو الملف تالف").assertIsDisplayed()
    }
}
