package eg.masroof

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eg.masroof.core.Money
import eg.masroof.ui.home.HomeContent
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.transactions.TransactionsContent
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.HomeUiState
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.viewmodel.TransactionsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenStatesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AND-010 empty state is shown when ready with no rows`() {
        compose.setContent { MasroofTheme { HomeContent(HomeUiState(status = LoadStatus.READY), {}, {}) } }
        compose.onNodeWithText("No expenses yet").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-011 offline state with retry is shown when nothing is cached`() {
        compose.setContent {
            MasroofTheme { TransactionsContent(TransactionsUiState(status = LoadStatus.OFFLINE), onRetry = {}) }
        }
        compose.onNodeWithText("You are offline").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-011 error state with retry is shown when refresh failed and nothing is cached`() {
        compose.setContent {
            MasroofTheme { TransactionsContent(TransactionsUiState(status = LoadStatus.ERROR), onRetry = {}) }
        }
        compose.onNodeWithText("Could not load expenses").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-010 total and cached rows still render when offline`() {
        val row =
            ExpenseRow("1", "Cafe", Money(5_000), LocalDate.of(2026, 10, 15), false, false, null, null)
        val state =
            HomeUiState(
                total = Money(125_000),
                recent = listOf(row),
                status = LoadStatus.OFFLINE,
            )
        compose.setContent { MasroofTheme { HomeContent(state, {}, {}) } }
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNodeWithText("Offline - showing saved data").assertIsDisplayed()
    }

    @Test
    fun `REQ-SMS-083 an expense the phone captured by itself shows the Captured chip with Undo`() {
        val captured = ExpenseRow("e-1", "AMAZON", Money(50_000), LocalDate.of(2026, 10, 15), false, false, null, null, "SMS")
        val manual = ExpenseRow("e-2", "Cafe", Money(5_000), LocalDate.of(2026, 10, 15), false, false, null, null)
        var undone: String? = null
        compose.setContent {
            MasroofTheme {
                TransactionsContent(
                    TransactionsUiState(rows = listOf(captured, manual), status = LoadStatus.READY),
                    onRetry = {},
                    capturedIds = mapOf("e-1" to "local-1"),
                    onUndoCaptured = { undone = it },
                )
            }
        }
        compose.onNodeWithText("Captured").assertIsDisplayed()
        compose.onAllNodesWithText("Undo").assertCountEquals(1)
        compose.onNodeWithText("Undo").performClick()
        assertEquals("local-1", undone)
    }
}
