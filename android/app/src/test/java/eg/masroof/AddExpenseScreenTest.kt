package eg.masroof

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.add.AddExpenseContent
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.AddUiState
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
class AddExpenseScreenTest {
    @get:Rule val compose = createComposeRule()

    private val categories = (1..30).map { CategoryEntity("c$it", "Category $it", "فئة $it", it) }
    private var saves = 0
    private var picked: String? = null

    private fun show(viewportHeight: Int) =
        compose.setContent {
            MasroofTheme {
                // A short viewport is what the screen gets with the keyboard open.
                Box(Modifier.height(viewportHeight.dp)) {
                    AddExpenseContent(
                        state = AddUiState(categories = categories),
                        onAmount = {},
                        onCategory = { picked = it },
                        onAccount = {},
                        onDate = {},
                        onNotes = {},
                        onSave = { saves++ },
                    )
                }
            }
        }

    @Test
    fun `REQ-AND-013 Save stays visible with 30 categories in a keyboard sized viewport`() {
        show(viewportHeight = 360)
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun `REQ-AND-013 categories are chips that can be picked`() {
        show(viewportHeight = 800)
        compose.onNodeWithText("Category 1").performClick()
        assertEquals("c1", picked)
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-013 Arabic names are used in the Arabic locale`() {
        show(viewportHeight = 800)
        compose.onNodeWithText("فئة 1").assertIsDisplayed()
    }
}
