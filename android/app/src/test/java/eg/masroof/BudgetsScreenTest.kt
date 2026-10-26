package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.ui.budgets.BudgetsContent
import eg.masroof.ui.budgets.HomeBudgetsCard
import eg.masroof.ui.budgets.toRows
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsState
import eg.masroof.ui.viewmodel.LoadStatus
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
class BudgetsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val rows = BudgetsFixtures.snapshot().toRows(BudgetsFixtures.categories)
    private var direction: LayoutDirection? = null
    private var saved: List<Any?>? = null
    private var deleted: String? = null
    private var retries = 0

    private fun show(state: BudgetsState) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                BudgetsContent(
                    state,
                    onRetry = { retries++ },
                    onSave = { id, cat, amount, rollover, done ->
                        saved = listOf(id, cat, amount, rollover)
                        done()
                    },
                    onDelete = { id, done ->
                        deleted = id
                        done()
                    },
                    onClearError = {},
                )
            }
        }

    private fun ready() = BudgetsState(rows, BudgetsFixtures.categories, LoadStatus.READY)

    @Test
    fun `REQ-AND-073 list shows each budget with amounts, percent, level and days left`() {
        show(ready())
        compose.onNodeWithText("Budgets").assertIsDisplayed()
        compose.onNodeWithText("500 EGP of 1,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("600 EGP of 500 EGP").assertIsDisplayed()
        compose.onNodeWithText("100 EGP over").assertIsDisplayed()
        compose.onNodeWithText("Over budget").assertIsDisplayed()
        compose.onNodeWithText("120%").assertIsDisplayed()
        compose.onNodeWithText("All spending").assertIsDisplayed()
        compose.onNodeWithText("Close to the limit").assertIsDisplayed()
        compose.onNodeWithText("Halfway").assertIsDisplayed()
        compose.onAllNodesWithText("6 days left").onFirst().assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-073 each card has a content description with the numbers`() {
        show(ready())
        compose
            .onNodeWithContentDescription("Groceries budget: 500 EGP spent of 1,000 EGP, 50% used, Halfway")
            .assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-073 empty state invites the first budget and add opens the form`() {
        show(BudgetsState(status = LoadStatus.READY, categories = BudgetsFixtures.categories))
        compose.onNodeWithText("No budgets yet").assertIsDisplayed()
        compose.onNode(hasText("Add budget") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Monthly limit (EGP)").assertIsDisplayed()
        compose.onNodeWithText("Carry over unspent or overspent amount").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-073 creating a budget sends the typed amount and closes the form`() {
        show(BudgetsState(status = LoadStatus.READY, categories = BudgetsFixtures.categories))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNode(hasText("Monthly limit (EGP)")).performTextInput("1500")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(listOf<Any?>(null, null, "1500", false), saved)
        compose.onNodeWithText("Monthly limit (EGP)").assertDoesNotExistCompat()
    }

    @Test
    fun `REQ-AND-073 editing prefills the amount and delete reports the id`() {
        show(ready())
        compose.onNodeWithText("500 EGP of 1,000 EGP").performClick()
        compose.onNodeWithText("Edit budget").assertIsDisplayed()
        compose.onNodeWithText("1000").assertIsDisplayed()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        assertEquals(BudgetsFixtures.ID_GROCERIES, deleted)
    }

    @Test
    fun `REQ-AND-073 form errors are shown`() {
        show(ready().copy(error = BudgetError.INVALID_AMOUNT))
        compose.onNodeWithText("Add budget").performClick()
        compose.onNodeWithText("Enter an amount greater than zero, e.g. 1500").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-073 offline shows the cached rows with a banner`() {
        show(ready().copy(status = LoadStatus.OFFLINE))
        compose.onNodeWithText("500 EGP of 1,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-073 Arabic mirrors the layout and uses Arabic text and digits`() {
        show(ready())
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الميزانيات").assertIsDisplayed()
        compose.onNodeWithText("٥٠٠ ج.م من ١٬٠٠٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("تجاوز الميزانية").assertIsDisplayed()
        compose.onNodeWithText("١٢٠٪").assertIsDisplayed()
        compose.onNodeWithText("كل المصروفات").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `REQ-AND-073 dark mode renders the same content`() {
        show(ready())
        compose.onNodeWithText("500 EGP of 1,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("Over budget").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-074 home card lists the top budgets and opens the screen`() {
        var opened = 0
        compose.setContent { MasroofTheme { HomeBudgetsCard(rows, onOpen = { opened++ }) } }
        compose.onNodeWithText("Budgets").assertIsDisplayed()
        compose.onNodeWithText("120%").assertIsDisplayed()
        compose.onNodeWithText("82.5%").assertIsDisplayed()
        compose.onNode(hasText("Budgets")).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `REQ-AND-074 home card without budgets prompts to set one`() {
        compose.setContent { MasroofTheme { HomeBudgetsCard(emptyList(), onOpen = {}) } }
        compose.onNodeWithText("Set a budget").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-074 home card mirrors in Arabic`() {
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                HomeBudgetsCard(rows, onOpen = {})
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الميزانيات").assertIsDisplayed()
        compose.onNodeWithText("٨٢٫٥٪").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
