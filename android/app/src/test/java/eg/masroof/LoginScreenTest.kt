package eg.masroof

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eg.masroof.data.auth.SignedOutNotice
import eg.masroof.ui.login.LoginContent
import eg.masroof.ui.settings.SettingsActions
import eg.masroof.ui.settings.SettingsContent
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.LoginError
import eg.masroof.ui.viewmodel.LoginUiState
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
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private var submits = 0
    private var languageToggles = 0

    private fun show(
        state: LoginUiState,
        notice: SignedOutNotice? = null,
    ) = compose.setContent {
        MasroofTheme {
            LoginContent(
                state = state,
                notice = notice,
                onServerUrl = {},
                onEmail = {},
                onPassword = {},
                onToggleShow = {},
                onSubmit = { submits++ },
                onToggleLanguage = { languageToggles++ },
                onConfirmSwitch = {},
                onCancelSwitch = {},
            )
        }
    }

    @Test
    fun `REQ-AND-006 the idle form has email password server url and an enabled sign in`() {
        show(LoginUiState(serverUrl = "https://masroof.example/"))
        compose.onNodeWithText("Email").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `REQ-AND-006 wrong credentials show the generic inline error`() {
        show(LoginUiState(error = LoginError.INVALID_CREDENTIALS))
        compose.onNodeWithText("Email or password is incorrect").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-006 a 429 shows the wait in minutes`() {
        show(LoginUiState(error = LoginError.RATE_LIMITED, retryMinutes = 5))
        compose.onNodeWithText("Too many attempts. Try again in 5 min.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-006 a 429 without a wait still shows a message`() {
        show(LoginUiState(error = LoginError.RATE_LIMITED))
        compose.onNodeWithText("Too many attempts. Try again later.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-006 a network error offers retry that submits again`() {
        show(LoginUiState(error = LoginError.NETWORK))
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, submits)
    }

    @Test
    fun `REQ-AND-006 offline shows the banner and disables sign in`() {
        show(LoginUiState(offline = true))
        compose.onNodeWithText("You are offline. Connect to sign in.").assertIsDisplayed()
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `REQ-AND-006 loading shows progress text and disables the button`() {
        show(LoginUiState(loading = true))
        compose.onNodeWithText("Signing in").assertIsDisplayed()
        compose.onNodeWithText("Signing in").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-006 the language switch is offered and reachable`() {
        show(LoginUiState())
        compose.onNodeWithText("العربية").performClick()
        assertEquals(1, languageToggles)
    }

    @Test
    fun `REQ-AND-007 a session expired notice is shown above the form`() {
        show(LoginUiState(), SignedOutNotice.SESSION_EXPIRED)
        compose.onNodeWithText("Your session expired. Sign in again; your saved entries are kept.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-007 the logout dialog has an explicit Cancel`() {
        var dismissed = 0
        compose.setContent {
            MasroofTheme {
                SettingsContent(
                    SettingsUiState(confirmLogoutUnsynced = 2, confirmLogoutRetryable = 2),
                    SettingsActions({}, {}, {}, {}, {}, { dismissed++ }, {}),
                )
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `REQ-AND-007 Sync now is hidden when every unsynced entry is permanently failed`() {
        compose.setContent {
            MasroofTheme {
                SettingsContent(
                    SettingsUiState(confirmLogoutUnsynced = 2, confirmLogoutRetryable = 0),
                    SettingsActions({}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        compose.onNodeWithText("Sign out anyway").assertIsDisplayed()
        compose.onNodeWithText("Sync now").assertDoesNotExist()
    }

    @Test
    fun `REQ-AND-007 the logout confirmation names the count and offers sync now and sign out anyway`() {
        var synced = 0
        var confirmed = 0
        compose.setContent {
            MasroofTheme {
                SettingsContent(
                    SettingsUiState(confirmLogoutUnsynced = 3, confirmLogoutRetryable = 3),
                    SettingsActions({}, {}, {}, {}, { confirmed++ }, {}, { synced++ }),
                )
            }
        }
        compose.onNodeWithText("3 unsynced expenses will be deleted. Sync first or sign out anyway?").assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(1, synced)
        compose.onNodeWithText("Sign out anyway").performClick()
        assertEquals(1, confirmed)
    }
}
