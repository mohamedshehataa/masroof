package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.data.wealth.AccountDraft
import eg.masroof.data.wealth.AccountKinds
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.CardStatus
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.NetWorthMath
import eg.masroof.ui.accounts.AccountsActions
import eg.masroof.ui.accounts.AccountsContent
import eg.masroof.ui.accounts.AccountsUiState
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.wealth.WealthActions
import eg.masroof.ui.wealth.WealthContent
import eg.masroof.ui.wealth.WealthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class WealthScreenTest {
    @get:Rule val compose = createComposeRule()

    private var direction: LayoutDirection? = null
    private var savedDraft: AccountDraft? = null
    private var savedBalance: Long? = null

    private fun accounts(
        state: AccountsUiState,
        actions: AccountsActions =
            AccountsActions(
                onSave = { d, _ -> savedDraft = d },
                onSetBalance = { _, minor, _ -> savedBalance = minor },
            ),
    ) = compose.setContent {
        MasroofTheme {
            direction = LocalLayoutDirection.current
            AccountsContent(state, actions)
        }
    }

    private val noBalance =
        AccountView(AccountEntity("a1", "CIB debit", "EGP", AccountKinds.DEBIT, "CIB", "1234", emoji = "💳"), null, null, null)

    @Test
    fun `REQ-EXP-057 an account without a balance shows Set balance and no number, and saving a balance reports it`() {
        accounts(AccountsUiState(listOf(noBalance), loaded = true))
        compose.onNodeWithText("Balance not set").assertIsDisplayed()
        compose.onNodeWithText("Set balance").performClick()
        compose.onNodeWithText("Balance").performTextInput("12,500.50")
        compose.onNodeWithText("Save").performClick()
        assertEquals(1_250_050L, savedBalance)
    }

    @Test
    fun `REQ-EXP-057 empty accounts list shows the empty state`() {
        accounts(AccountsUiState(emptyList(), loaded = true))
        compose.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-057 adding an account with a full card number shows the field error and stores nothing`() {
        accounts(AccountsUiState(emptyList(), loaded = true))
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithText("Name").performTextInput("CIB debit")
        compose.onNodeWithText("Last 4 digits").performTextInput("1234567890123456")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter exactly 4 digits, never the full number").assertIsDisplayed()
        assertNull(savedDraft)
    }

    @Test
    fun `REQ-EXP-057 adding a valid account reports the draft`() {
        accounts(AccountsUiState(emptyList(), loaded = true))
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithText("Name").performTextInput("CIB debit")
        compose.onNodeWithText("Last 4 digits").performTextInput("1234")
        compose.onNodeWithText("Save").performClick()
        assertEquals("1234", savedDraft?.last4)
        assertEquals("CIB debit", savedDraft?.label)
    }

    @Test
    fun `REQ-EXP-058 credit card shows outstanding, available, spent since statement and next due`() {
        val card =
            AccountView(
                AccountEntity(
                    "c",
                    "Visa",
                    "EGP",
                    AccountKinds.CREDIT_CARD,
                    last4 = "4321",
                    creditLimitMinor = 5_000_000,
                    statementDay = 20,
                    dueDay = 5,
                ),
                AccountBalanceEntity("b", "c", 1_830_000, "EGP", 0, "MANUAL", 0),
                1_830_000,
                CardStatus(1_830_000, 3_170_000, 40_000, LocalDate.of(2026, 12, 5)),
            )
        accounts(AccountsUiState(listOf(card), loaded = true))
        compose.onNodeWithText("18,300 EGP").assertIsDisplayed()
        compose.onNodeWithText("31,700 EGP").assertIsDisplayed()
        compose.onNodeWithText("400 EGP").assertIsDisplayed()
        compose.onNodeWithText("Next payment due 5 Dec").assertIsDisplayed()
    }

    private fun wealth(state: WealthUiState) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                WealthContent(state, WealthActions())
            }
        }

    private fun snap(
        date: String,
        net: Long,
    ) = NetWorthSnapshotEntity("s$date", date, net, 0, net, "EGP", "", 0)

    private val today = LocalDate.of(2026, 11, 8)

    private fun filled(snapshots: List<NetWorthSnapshotEntity>): WealthUiState {
        val bank = AccountView(AccountEntity("a", "Bank", "EGP", AccountKinds.DEBIT, last4 = "1111"), null, 1_250_000, null)
        val unset = AccountView(AccountEntity("b", "Wallet", "EGP", AccountKinds.WALLET, last4 = "2222"), null, null, null)
        val net = NetWorthMath.compute(listOf(bank, unset), emptyList(), emptyList(), 0)
        return WealthUiState(net, emptyList(), emptyList(), snapshots, today, loaded = true)
    }

    @Test
    fun `REQ-AN-169 nothing added shows the empty state`() {
        wealth(WealthUiState(NetWorth.EMPTY, loaded = true, today = today))
        compose.onNodeWithText("Add an account balance or something you own").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-169 net worth tab shows the number, the not-counted hint and a one point history message`() {
        wealth(filled(listOf(snap("2026-11-08", 1_250_000))))
        compose.onAllNodesWithText("12,500 EGP", substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("1 account without a balance is not counted").assertExists()
        compose.onNodeWithText("History starts today").assertExists()
    }

    @Test
    fun `REQ-AN-169 with no snapshot at all the chart says history starts today and shows the current value`() {
        wealth(filled(emptyList()))
        compose.onNodeWithText("History starts today").assertExists()
    }

    @Test
    fun `REQ-AN-169 one earlier snapshot and none for today shows both points, not the starts-today message`() {
        wealth(filled(listOf(snap("2026-11-01", 1_000_000))))
        compose.onNodeWithText("History starts today").assertDoesNotExist()
        compose.onNodeWithText("8 Nov: 12,500 EGP").assertExists()
    }

    @Test
    fun `REQ-AN-168 unmatched captured entries are reported on Wealth`() {
        compose.setContent {
            MasroofTheme { WealthContent(filled(emptyList()).copy(unmatchedEntries = 3), WealthActions()) }
        }
        compose.onNodeWithText("3 captured entries could not be matched to an account").assertExists()
    }

    @Test
    fun `REQ-AN-169 year change shows no percent when it would be meaningless`() {
        wealth(filled(listOf(snap("2026-01-05", -9_587_400), snap("2026-11-08", 1_250_000))))
        compose.onNodeWithText("Change this year").assertIsDisplayed()
        compose.onNodeWithText("+", substring = true).assertIsDisplayed()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun `REQ-AN-169 tabs switch to assets and debt`() {
        wealth(filled(emptyList()))
        compose.onAllNodesWithText("Assets").onFirst().performClick()
        compose.onNodeWithText("Things you own").assertIsDisplayed()
        compose.onAllNodesWithText("Debt").onFirst().performClick()
        compose.onNodeWithText("No debts").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AN-169 Arabic mirrors the layout and uses Arabic-Indic digits`() {
        wealth(filled(listOf(snap("2026-11-08", 1_250_000))))
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onAllNodesWithText("الثروة").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("١٢٬٥٠٠ ج.م", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-EXP-057 Arabic accounts screen is RTL`() {
        accounts(AccountsUiState(listOf(noBalance), loaded = true))
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الرصيد مش محدد").assertIsDisplayed()
    }
}
