package eg.masroof

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.BudgetsContent
import eg.masroof.ui.budgets.HomeBudgetsCard
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.BudgetError
import eg.masroof.ui.viewmodel.BudgetsState
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.wallet.WalletActions
import eg.masroof.ui.wallet.WalletContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val rows = BudgetsFixtures.rows()
    private var direction: LayoutDirection? = null
    private var saved: List<Any?>? = null
    private var deleted: String? = null
    private var retries = 0
    private var fontScale = 1f

    private fun show(state: BudgetsState) =
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MasroofTheme {
                    direction = LocalLayoutDirection.current
                    BudgetsContent(
                        state,
                        onRetry = { retries++ },
                        onSave = { id, cat, period, amount, rollover, done ->
                            saved = listOf(id, cat, period, amount, rollover)
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
        }

    private fun ready(list: List<BudgetRowUi> = rows) = BudgetsState(list, BudgetsFixtures.categories, LoadStatus.READY)

    private fun empty() = BudgetsState(status = LoadStatus.READY, categories = BudgetsFixtures.categories)

    @Test
    fun `REQ-AND-138 list shows each budget with amounts, percent, level and days left`() {
        show(ready())
        compose.onNodeWithText("Budgets").assertIsDisplayed()
        compose.onNodeWithText("3,000 EGP of 4,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("600 EGP of 500 EGP").assertIsDisplayed()
        compose.onNodeWithText("100 EGP over").assertIsDisplayed()
        compose.onNodeWithText("1,000 EGP left").assertIsDisplayed()
        compose.onNodeWithText("Over budget").assertIsDisplayed()
        compose.onNodeWithText("120%").assertIsDisplayed()
        compose.onNodeWithText("75%").assertIsDisplayed()
        compose.onNodeWithText("All spending").assertIsDisplayed()
        compose.onNodeWithText("Close to the limit").assertIsDisplayed()
        compose.onAllNodesWithText("6 days left").onFirst().assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-138 TalkBack reads the budget name, percent used and what is left`() {
        show(ready())
        compose.onNodeWithContentDescription("Groceries, 75 percent used, 1,000 pounds left").assertIsDisplayed()
        compose.onNodeWithContentDescription("Car, 120 percent used, 100 pounds over").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-138 TalkBack reads the Arabic description`() {
        show(ready())
        compose.onNodeWithContentDescription("بقالة، ٧٥ بالمية مستخدمة، متبقي ١٬٠٠٠ جنيه").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-138 the screen never mentions offline or a last update`() {
        show(ready())
        compose.onAllNodesWithText("offline", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("last updated", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-138 a budget with nothing spent reads 0 percent and On track`() {
        show(ready(listOf(BudgetsFixtures.row("b", BudgetsFixtures.GROCERIES, "Groceries", "بقالة", 400_000, 0, 0))))
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithText("On track").assertIsDisplayed()
        compose.onNodeWithText("0 EGP of 4,000 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-138 when progress cannot be computed the budgets stay listed with the message and Retry`() {
        val list = listOf(BudgetsFixtures.row("b", BudgetsFixtures.GROCERIES, "Groceries", "بقالة", 400_000, 0, 0, hasProgress = false))
        show(ready(list).copy(progressFailed = true))
        compose.onNodeWithText("Couldn't compute progress").assertIsDisplayed()
        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onNodeWithText("4,000 EGP").assertIsDisplayed()
        compose.onNode(hasText("Retry") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `REQ-AND-136 empty state invites the first budget and Add opens the form`() {
        show(empty())
        compose.onNodeWithText("No budgets yet").assertIsDisplayed()
        compose.onNodeWithText("Add one to see your progress.").assertIsDisplayed()
        compose.onNode(hasText("Add budget") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Amount (EGP)").assertIsDisplayed()
        compose.onNodeWithText("Carry over unspent or overspent amount").assertIsDisplayed()
        compose.onNodeWithText("Monthly").assertIsDisplayed()
        compose.onNodeWithText("Weekly").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-136 creating a budget sends the typed amount, category and period and closes the form`() {
        show(empty())
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNode(hasText("Amount (EGP)")).performTextInput("1500")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(listOf<Any?>(null, null, BudgetPeriodKind.MONTHLY, "1500", false), saved)
        compose.onAllNodesWithText("Amount (EGP)").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-136 choosing Weekly saves a weekly budget`() {
        show(empty())
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNode(hasText("Weekly") and hasClickAction()).performClick()
        compose.onNode(hasText("Amount (EGP)")).performTextInput("300")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(BudgetPeriodKind.WEEKLY, saved!![2])
    }

    @Test
    fun `REQ-AND-136 Add starts on the first free category when All spending is already budgeted`() {
        val withTotal = listOf(BudgetsFixtures.row("t", null, null, null, 1_000_000, 0, 0))
        show(ready(withTotal))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNode(hasText("Amount (EGP)")).performTextInput("200")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(BudgetsFixtures.GROCERIES, saved!![1])
    }

    @Test
    fun `REQ-AND-136 Add is disabled with the message when every category and the total are taken`() {
        show(ready(BudgetsFixtures.everythingTaken()))
        compose.onNode(hasText("Add budget") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Every category already has a budget").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-136 Add stays enabled while some scope is free`() {
        show(ready())
        compose.onNode(hasText("Add budget") and hasClickAction()).assertIsEnabled()
        compose.onAllNodesWithText("Every category already has a budget").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-136 the amount and duplicate errors are shown in the form`() {
        show(empty().copy(error = BudgetError.INVALID_AMOUNT))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNodeWithText("Enter an amount greater than zero, e.g. 1500").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-136 the too large and duplicate messages are shown`() {
        show(empty().copy(error = BudgetError.AMOUNT_TOO_LARGE))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNodeWithText("The amount can be at most 10,000,000,000 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-136 the duplicate message is shown and the form stays open`() {
        show(empty().copy(error = BudgetError.DUPLICATE))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNodeWithText("A budget for this category and period already exists").assertIsDisplayed()
        compose.onNodeWithText("Amount (EGP)").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-137 editing prefills the amount and the category is not changeable`() {
        show(ready())
        compose.onNodeWithText("3,000 EGP of 4,000 EGP").performClick()
        compose.onNodeWithText("Edit budget").assertIsDisplayed()
        compose.onNodeWithText("4000").assertIsDisplayed()
        compose.onAllNodesWithText("Monthly").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-137 Cancel on the delete confirmation changes nothing`() {
        show(ready())
        compose.onNodeWithText("3,000 EGP of 4,000 EGP").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onNodeWithText("Delete this budget?").assertIsDisplayed()
        compose.onAllNodes(hasText("Cancel") and hasClickAction()).onLast().performClick()
        assertNull(deleted)
        compose.onAllNodesWithText("Delete this budget?").assertCountEquals(0)
        compose.onNodeWithText("Edit budget").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-137 confirming the delete reports the budget id`() {
        show(ready())
        compose.onNodeWithText("3,000 EGP of 4,000 EGP").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertEquals(BudgetsFixtures.ID_GROCERIES, deleted)
    }

    @Test
    fun `REQ-AN-155 a duplicate budget is listed with the Duplicate budget marker and can be deleted`() {
        val dup = BudgetsFixtures.row("dup", BudgetsFixtures.GROCERIES, "Groceries", "بقالة", 100_000, 0, 0).copy(duplicate = true)
        show(ready(listOf(dup)))
        compose.onNodeWithText("Duplicate budget").assertIsDisplayed()
        compose.onNodeWithText("0 EGP of 1,000 EGP").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertEquals("dup", deleted)
    }

    @Test
    fun `REQ-AND-137 a Deleted category budget is listed and can be deleted`() {
        val list = listOf(BudgetsFixtures.row("old", "gone", null, null, 100_000, 20_000, 2_000, deletedCategory = true))
        show(ready(list))
        compose.onNodeWithText("Deleted category").assertIsDisplayed()
        compose.onNodeWithText("200 EGP of 1,000 EGP").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertEquals("old", deleted)
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-138 Arabic mirrors the layout and uses Arabic text and digits`() {
        show(ready())
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الميزانيات").assertIsDisplayed()
        compose.onNodeWithText("٣٬٠٠٠ ج.م من ٤٬٠٠٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("تجاوز الميزانية").assertIsDisplayed()
        compose.onNodeWithText("١٢٠٪").assertIsDisplayed()
        compose.onNodeWithText("كل المصروفات").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-136 the Arabic form and its messages mirror`() {
        show(BudgetsState(BudgetsFixtures.everythingTaken(), BudgetsFixtures.categories, LoadStatus.READY))
        compose.onNodeWithText("كل فئة ليها ميزانية بالفعل").assertIsDisplayed()
        compose.onNode(hasText("إضافة ميزانية") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `REQ-AND-138 at font scale 1_5 the list and the form stay readable with 48dp targets`() {
        fontScale = 1.5f
        show(ready())
        compose.onNodeWithText("600 EGP of 500 EGP").assertIsDisplayed()
        compose.onNode(hasText("Add budget") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Amount (EGP)").assertIsDisplayed()
        compose.onNode(hasText("Save") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Cancel") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `REQ-AND-138 dark mode renders the same content`() {
        show(ready())
        compose.onNodeWithText("3,000 EGP of 4,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("Over budget").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-138 the first frame is a skeleton and then the list, never a spinner screen`() {
        show(BudgetsState())
        compose.onAllNodesWithText("No budgets yet").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-138 home card lists the top budgets and opens the screen`() {
        var opened = 0
        compose.setContent { MasroofTheme { HomeBudgetsCard(rows, onOpen = { opened++ }) } }
        compose.onNodeWithText("Budgets").assertIsDisplayed()
        compose.onNodeWithText("120%").assertIsDisplayed()
        compose.onNodeWithText("82.5%").assertIsDisplayed()
        compose.onNode(hasText("Budgets")).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `REQ-AND-138 home card without budgets prompts to set one`() {
        compose.setContent { MasroofTheme { HomeBudgetsCard(emptyList(), onOpen = {}) } }
        compose.onNodeWithText("Set a budget").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-138 home card mirrors in Arabic`() {
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

    @Test
    fun `REQ-AND-137 the Wallet budget tile shows what is left of the total budget, and over by when past it`() {
        val total = BudgetsFixtures.row("t", null, null, null, 400_000, 300_000, 7_500)
        compose.setContent { MasroofTheme { WalletContent(walletUi(), emptyList(), WalletActions(), totalBudget = total) } }
        compose.scrollWalletTo("1,000 EGP left of 4,000 EGP")
        compose.onNodeWithText("1,000 EGP left of 4,000 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-137 the Wallet budget tile says Over by when the total budget is exceeded`() {
        val total = BudgetsFixtures.row("t", null, null, null, 400_000, 450_000, 11_250)
        compose.setContent { MasroofTheme { WalletContent(walletUi(), emptyList(), WalletActions(), totalBudget = total) } }
        compose.scrollWalletTo("Over by 500 EGP")
        compose.onNodeWithText("Over by 500 EGP").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-137 the Wallet budget tile uses Arabic text and digits`() {
        val total = BudgetsFixtures.row("t", null, null, null, 400_000, 300_000, 7_500)
        compose.setContent { MasroofTheme { WalletContent(walletUi(), emptyList(), WalletActions(), totalBudget = total) } }
        compose.scrollWalletTo("متبقي ١٬٠٠٠ ج.م من ٤٬٠٠٠ ج.م")
        compose.onNodeWithText("متبقي ١٬٠٠٠ ج.م من ٤٬٠٠٠ ج.م").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-136 the category-gone and unsupported messages are shown`() {
        show(empty().copy(error = BudgetError.CATEGORY_GONE))
        compose.onNode(hasText("Add budget") and hasClickAction()).performClick()
        compose.onNodeWithText("This category no longer exists or is archived. Pick another one.").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-137 an Unsupported budget is listed and can be deleted`() {
        val odd = BudgetsFixtures.row("odd", null, null, null, 100_000, 0, 0, hasProgress = false).copy(unsupported = true)
        show(ready(listOf(odd)))
        compose.onNodeWithText("Unsupported budget").assertIsDisplayed()
        compose.onNodeWithText("1,000 EGP").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertEquals("odd", deleted)
    }
}
