package eg.masroof

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import eg.masroof.data.categories.CategoryResult
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.MerchantRuleEntity
import eg.masroof.ui.categories.CategoriesContent
import eg.masroof.ui.categories.CategoryActions
import eg.masroof.ui.categories.CategoryEditorContent
import eg.masroof.ui.categories.MerchantRulesContent
import eg.masroof.ui.categories.RuleActions
import eg.masroof.ui.categories.RulePromptDialog
import eg.masroof.ui.components.CategoryAvatar
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.CategoriesUiState
import eg.masroof.ui.viewmodel.MerchantRulesUiState
import eg.masroof.ui.viewmodel.RulePromptStage
import eg.masroof.ui.viewmodel.RuleRow
import eg.masroof.ui.viewmodel.groupCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h3000dp")
class CategoriesScreenTest {
    @get:Rule val compose = createComposeRule()

    private var direction: LayoutDirection? = null

    private val dining = CategoryEntity("cat-dining", "Dining out", "مطاعم", 20, emoji = "🍔", system = true)
    private val coffee = CategoryEntity("custom-1", "Coffee", "قهوة", 10, parentId = "cat-dining", emoji = "☕")
    private val groceries = CategoryEntity("cat-groceries", "Groceries", "بقالة", 10, emoji = "🛒", system = true)
    private val old = CategoryEntity("custom-2", "Old", "قديم", 30, emoji = null, archived = true)

    private fun state(vararg cats: CategoryEntity) = CategoriesUiState(loaded = true, groups = groupCategories(cats.toList()))

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                content()
            }
        }

    // ---- REQ-EXP-060

    @Test
    fun `REQ-EXP-060 the avatar shows the emoji and falls back to the first letter`() {
        show {
            CategoryAvatar("Dining out", "cat-dining", emoji = "🍔")
            CategoryAvatar("Zed", "x")
        }
        compose.onNodeWithText("🍔", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Z", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-060 the list groups sub-categories under their parent, marks archived ones and has 48dp actions`() {
        show { CategoriesContent(state(dining, coffee, groceries, old), CategoryActions()) }
        compose.onNodeWithText("Categories").assertIsDisplayed()
        compose.onNodeWithText("Dining out").assertIsDisplayed()
        compose.onNodeWithText("Coffee").assertIsDisplayed()
        compose.onNodeWithText("☕", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("Restore").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move Coffee up").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Coffee").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-060 loading and empty states, and a delete that is refused shows why`() {
        show { CategoriesContent(CategoriesUiState(loaded = false), CategoryActions()) }
        compose.onNodeWithText("Loading categories").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-060 empty list shows its message and a blocked delete shows the archive hint`() {
        show { CategoriesContent(CategoriesUiState(loaded = true, deleteBlocked = CategoryResult.InUse), CategoryActions()) }
        compose.onNodeWithText("No categories yet").assertIsDisplayed()
        compose.onNodeWithText("This category is in use. Archive it instead of deleting it.").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-060 actions report the category id, archive asks first, built-in categories offer no Delete`() {
        val moves = mutableListOf<Pair<String, Boolean>>()
        val archives = mutableListOf<Pair<String, Boolean>>()
        val deletes = mutableListOf<String>()
        show {
            CategoriesContent(
                state(groceries, coffee, dining),
                CategoryActions(
                    onArchive = { id, a -> archives += id to a },
                    onMove = { id, up -> moves += id to up },
                    onDelete = { deletes += it },
                ),
            )
        }
        compose.onNodeWithContentDescription("Move Coffee down").performClick()
        assertEquals(listOf("custom-1" to false), moves)
        compose.onNodeWithText("Delete").performClick() // only the custom one has Delete
        compose.onNodeWithText("Delete Coffee?").assertIsDisplayed()
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("custom-1"), deletes)
        // rows are Groceries, Dining out, Coffee: the second Archive button is Dining out's
        compose.onAllNodes(hasText("Archive") and hasClickAction())[1].performClick()
        assertTrue(archives.isEmpty()) // confirm first
        compose.onNodeWithText("Archive Dining out?").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-060 the editor refuses an empty or duplicate name with a field error and saves a valid one`() {
        var answer: CategoryResult = CategoryResult.EmptyName
        val saved = mutableListOf<List<String?>>()
        show {
            CategoryEditorContent(null, listOf(dining), {}) { parent, en, ar, emoji, onResult ->
                saved += listOf(parent, en, ar, emoji)
                onResult(answer)
            }
        }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a name").assertExists()
        answer = CategoryResult.Duplicate
        compose.onNodeWithText("Name (English)").performTextInput("Dining out")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("A category with this name already exists here").assertExists()
        compose.onNodeWithText("Under").assertExists()
        compose.onNodeWithText("🍔 Dining out").performClick()
        compose.onNodeWithContentDescription("Emoji ☕").performClick()
        answer = CategoryResult.Ok("x")
        compose.onNodeWithText("Name (Arabic)").performTextInput("قهوة")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("cat-dining", "Dining out", "قهوة", "☕"), saved.last())
    }

    @Test
    fun `REQ-EXP-060 the emoji search matches English and Arabic keywords`() {
        show { CategoryEditorContent(null, emptyList(), {}) { _, _, _, _, _ -> } }
        compose.onNodeWithContentDescription("Emoji 🍔").assertIsDisplayed()
        compose.onNodeWithText("Search emoji").performTextInput("coffee")
        compose.onNodeWithContentDescription("Emoji ☕").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("🍔")).fetchSemanticsNodes().size)
        compose.onNodeWithText("coffee").performTextInput("zzzqq")
        compose.onNodeWithText("No emoji matches your search").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar-w411dp-h3000dp")
    fun `REQ-EXP-060 Arabic mirrors the categories list and the editor and uses Arabic names`() {
        show { CategoriesContent(state(dining, coffee), CategoryActions()) }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الأقسام").assertIsDisplayed()
        compose.onNodeWithText("مطاعم").assertIsDisplayed()
        compose.onNodeWithText("قهوة").assertIsDisplayed()
        compose.onNodeWithText("إضافة قسم").performClick()
        compose.onNodeWithText("قسم جديد").assertIsDisplayed()
        compose.onNodeWithText("ابحث عن إيموجي").assertIsDisplayed()
    }

    // ---- REQ-EXP-061

    private val rule = MerchantRuleEntity("r1", "talabat", "cat-dining", 1)

    @Test
    fun `REQ-EXP-061 the rules screen shows its empty state, and Add rule needs a category`() {
        val added = mutableListOf<Pair<String, String?>>()
        show {
            MerchantRulesContent(
                MerchantRulesUiState(loaded = true, categories = listOf(dining, groceries)),
                RuleActions(onAdd = { m, c, done ->
                    added += m to c
                    done()
                }),
            )
        }
        compose.onNodeWithText("Merchant rules").assertIsDisplayed()
        compose.onNodeWithText("No rules yet. Change an entry's category and the app offers to remember it.").assertIsDisplayed()
        compose.onNode(hasText("Add rule") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Merchant name").performTextInput("Talabat")
        compose.onNodeWithText("🍔 Dining out").performClick()
        compose.onNode(hasText("Add rule") and hasClickAction()).performClick()
        assertEquals(listOf("Talabat" to "cat-dining"), added)
    }

    @Test
    fun `REQ-EXP-061 rules are listed with their category and can be deleted, an empty key shows its error`() {
        val deleted = mutableListOf<String>()
        show {
            MerchantRulesContent(
                MerchantRulesUiState(
                    loaded = true,
                    rules = listOf(RuleRow(rule, dining)),
                    categories = listOf(dining),
                    emptyKeyError = true,
                ),
                RuleActions(onDelete = { deleted += it }),
            )
        }
        compose.onNodeWithText("talabat → Dining out").assertIsDisplayed()
        compose.onNodeWithText("Enter a merchant name with letters").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete the rule for talabat").performClick()
        assertEquals(listOf("r1"), deleted)
    }

    @Test
    fun `REQ-EXP-061 the loading state of the rules screen`() {
        show { MerchantRulesContent(MerchantRulesUiState(loaded = false), RuleActions()) }
        compose.onNodeWithText("Loading rules").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-061 the prompt asks Always file X under Y, then Also change the N past entries, then offers Undo`() {
        var stage by mutableStateOf<RulePromptStage>(RulePromptStage.AskRule)
        var yes = 0
        var no = 0
        var undo = 0
        show { RulePromptDialog(stage, "Talabat", "Dining out", { yes++ }, { no++ }, { undo++ }) }
        compose.onNodeWithText("Always file Talabat under Dining out?").assertIsDisplayed()
        compose.onNodeWithText("Yes, always").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1 to 1, yes to no)
        stage = RulePromptStage.AskPast("talabat", 6)
        compose.onNodeWithText("Also change the 6 past Talabat entries?").assertIsDisplayed()
        stage = RulePromptStage.Changed(6)
        compose.onNodeWithText("Changed 6 entries").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1, undo)
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar-w411dp-h3000dp")
    fun `REQ-EXP-061 Arabic mirrors the rules screen and the prompt with Arabic digits`() {
        var stage by mutableStateOf<RulePromptStage>(RulePromptStage.AskPast("talabat", 6))
        show {
            MerchantRulesContent(
                MerchantRulesUiState(loaded = true, rules = listOf(RuleRow(rule, dining)), categories = listOf(dining)),
                RuleActions(),
            )
            RulePromptDialog(stage, "طلبات", "مطاعم", {}, {}, {})
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("قواعد التجار").assertIsDisplayed()
        compose.onNodeWithText("تغيّر ٦ مصاريف قديمة من طلبات كمان؟").assertIsDisplayed()
        stage = RulePromptStage.AskRule
        compose.onNodeWithText("دايمًا تصنّف طلبات تحت مطاعم؟").assertIsDisplayed()
    }
}
