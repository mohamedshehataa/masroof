package eg.masroof.backup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eg.masroof.core.DigitStyle
import eg.masroof.data.backup.BackupFailure
import eg.masroof.data.backup.BackupRun
import eg.masroof.data.backup.LastBackup
import eg.masroof.ui.settings.BackupActions
import eg.masroof.ui.settings.BackupNotice
import eg.masroof.ui.settings.BackupSectionContent
import eg.masroof.ui.settings.BackupStep
import eg.masroof.ui.settings.BackupUiState
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.theme.MoneyStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

private fun actions(log: MutableList<String> = mutableListOf()) =
    BackupActions(
        onBackUpNow = { log += "now" },
        onUnderstood = { log += "understood=$it" },
        onContinueExplain = { log += "continueExplain" },
        onPassphrase = {},
        onAgain = {},
        onContinuePassphrase = { log += "continuePass" },
        onDismiss = { log += "dismiss" },
        onPicked = {},
        onCancelBackup = { log += "cancel" },
        onAcknowledge = { log += "ack" },
    )

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BackupSectionScreenTest {
    @get:Rule val compose = createComposeRule()

    // 11:05 UTC on 27 Oct 2026 is 14:05 in Cairo (summer time, UTC+3, until 29 Oct)
    private val at = Instant.parse("2026-10-27T11:05:00Z")
    private val now = Instant.parse("2026-10-27T15:00:00Z")

    private fun show(
        state: BackupUiState,
        a: BackupActions = actions(),
    ) = compose.setContent { MasroofTheme { BackupSectionContent(state, a, now) } }

    @Test
    fun `REQ-OPS-177 never backed up shows the warning and Back up now with a 48 dp target`() {
        show(BackupUiState())
        compose.onNodeWithText("No backup yet - if this phone is lost your data is gone").assertIsDisplayed()
        compose.onNodeWithText("Back up now").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-OPS-177 the last backup line shows today, time, entries, receipts and size`() {
        show(BackupUiState(last = LastBackup(at.toEpochMilli(), 1_240, 37, 58L * 1024 * 1024)))
        compose.onNodeWithText("Last backup: today 14:05, 1,240 entries, 37 receipts, 58 MB").assertIsDisplayed()
    }

    @Test
    fun `REQ-OPS-177 a running backup shows percent and Cancel and disables Back up now`() {
        val log = mutableListOf<String>()
        show(BackupUiState(run = BackupRun.Running(42)), actions(log))
        compose.onNodeWithText("Backing up… 42%").assertIsDisplayed()
        compose.onNodeWithText("Back up now").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), log)
    }

    @Test
    fun `REQ-OPS-177 failure says the file may be incomplete with the reason, success lists the missing receipt`() {
        show(BackupUiState(run = BackupRun.Failed(BackupFailure.STORAGE_FULL)))
        compose.onNodeWithText("Backup failed - the file may be incomplete, please delete it").assertIsDisplayed()
        compose.onNodeWithText("Reason: there is not enough storage space.").assertIsDisplayed()
    }

    @Test
    fun `REQ-OPS-176 success shows the missing receipt message`() {
        show(BackupUiState(run = BackupRun.Succeeded(10, 2, 1, 1000)))
        compose.onNodeWithText("Backup saved").assertIsDisplayed()
        compose.onNodeWithText("1 receipt file was missing and is not in the backup").assertIsDisplayed()
    }

    @Test
    fun `REQ-SEC-067 the explanation warns about the lost passphrase and Continue needs the checkbox`() {
        val log = mutableListOf<String>()
        show(BackupUiState(step = BackupStep.EXPLAIN), actions(log))
        compose.onNodeWithText("If you forget this passphrase, nobody can open this backup - not even us").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("I understand").performClick()
        assertEquals(listOf("understood=true"), log)
    }

    @Test
    fun `REQ-SEC-067 the passphrase screen shows why Continue is off and Continue turns on when it is valid`() {
        show(BackupUiState(step = BackupStep.PASSPHRASE, passphrase = "12345678901", again = "12345678901"))
        compose.onNodeWithText("Use at least 12 characters (now 11)").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `REQ-SEC-067 two different entries show the mismatch reason, matching ones enable Continue`() {
        show(BackupUiState(step = BackupStep.PASSPHRASE, passphrase = "123456789012", again = "123456789013"))
        compose.onNodeWithText("The two entries are different").assertIsDisplayed()
    }

    @Test
    fun `REQ-SEC-067 a valid passphrase enables Continue`() {
        show(BackupUiState(step = BackupStep.PASSPHRASE, passphrase = "كلمة سر طويلة جدا", again = "كلمة سر طويلة جدا"))
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `REQ-SEC-067 the minimum length in the reason follows the digit setting`() {
        compose.setContent {
            MasroofTheme {
                CompositionLocalProvider(LocalMoneyStyle provides MoneyStyle(arabic = false, digits = DigitStyle.ARABIC_INDIC)) {
                    BackupSectionContent(
                        BackupUiState(step = BackupStep.PASSPHRASE, passphrase = "12345678901", again = "x"),
                        actions(),
                        now,
                    )
                }
            }
        }
        compose.onNodeWithText("Use at least ١٢ characters (now ١١)").assertIsDisplayed()
    }

    @Test
    fun `REQ-OPS-177 an interrupted backup says so and can be dismissed`() {
        val log = mutableListOf<String>()
        show(BackupUiState(notice = BackupNotice.INTERRUPTED), actions(log))
        compose.onNodeWithText("Backup was interrupted - please start again").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("ack"), log)
    }
}
