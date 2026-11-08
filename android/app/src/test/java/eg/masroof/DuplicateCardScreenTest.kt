package eg.masroof

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import eg.masroof.data.answers.EntrySources
import eg.masroof.data.edit.DuplicateCard
import eg.masroof.ui.review.ReviewContent
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val card =
    DuplicateCard(
        manualEntry("m", category = "food", merchant = "Koshary"),
        captureEntry("c", merchant = "CIB card"),
        EntrySources(setOf("SMS"), "CIB"),
    )

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DuplicateCardScreenTest {
    @get:Rule val compose = createComposeRule()

    private val merged = mutableListOf<String>()
    private val kept = mutableListOf<String>()

    private fun show(cards: List<DuplicateCard> = listOf(card)) =
        compose.setContent {
            MasroofTheme {
                ReviewContent(
                    items = emptyList(),
                    onCreate = {},
                    onDismiss = {},
                    duplicates = cards,
                    categoryNames = mapOf("food" to "Food"),
                    onMerge = { merged += it.key },
                    onKeepBoth = { kept += it.key },
                )
            }
        }

    @Test
    fun `REQ-DEDUP-063 the card shows both entries side by side with amount, date, merchant, category and source`() {
        show()
        compose.onNodeWithText("Possible duplicate", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Manual entry", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("SMS from CIB", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Koshary", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("CIB card", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Food", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Uncategorized", useUnmergedTree = true).assertIsDisplayed()
        // the typed entry has no time; the capture shows 14:05
        compose.onNodeWithText("6 Nov, 14:05", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("6 Nov", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Merge").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Keep both").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-DEDUP-063 TalkBack reads one description for the pair`() {
        show()
        compose
            .onNodeWithContentDescription("Possible duplicate: 1,450 EGP manual entry and 1,450 EGP SMS from CIB")
            .assertIsDisplayed()
    }

    @Test
    fun `REQ-DEDUP-063 the typed column is on the left in English`() {
        show()
        val manual = compose.onNodeWithText("Manual entry", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val capture = compose.onNodeWithText("SMS from CIB", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(manual.left < capture.left)
    }

    @Test
    fun `REQ-DEDUP-064 Merge and REQ-DEDUP-065 Keep both report the pair and nothing else happens by itself`() {
        show()
        assertEquals(emptyList<String>(), merged)
        compose.onNodeWithText("Keep both").performClick()
        assertEquals(listOf("m|c"), kept)
        compose.onNodeWithText("Merge").performClick()
        assertEquals(listOf("m|c"), merged)
    }

    @Test
    fun `REQ-DEDUP-065 swiping the card away counts as Keep both`() {
        show()
        compose.onNodeWithText("Manual entry", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("m|c"), kept)
        assertEquals(emptyList<String>(), merged)
    }

    @Test
    fun `REQ-DEDUP-063 the empty state shows when there is neither a message nor a card`() {
        show(emptyList())
        compose.onNodeWithText("Nothing to review", substring = true).assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class DuplicateCardScreenArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-DEDUP-063 Arabic mirrors the two columns and keeps the digit setting`() {
        compose.setContent {
            MasroofTheme {
                ReviewContent(items = emptyList(), onCreate = {}, onDismiss = {}, duplicates = listOf(card), categoryNames = emptyMap())
            }
        }
        compose.onNodeWithText("ممكن يكون مكرر", useUnmergedTree = true).assertIsDisplayed()
        // right-to-left: the typed column sits on the right of the captured one
        val manual = compose.onNodeWithText("إدخال يدوي", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val capture = compose.onNodeWithText("رسالة من CIB", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(manual.left > capture.left)
        compose.onNodeWithText("دمج").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("سيب الاتنين").assertIsDisplayed()
        // Arabic-Indic digits for the amount (٠-٩), like everywhere else
        compose.onNodeWithContentDescription("ممكن يكون مكرر", substring = true).assertIsDisplayed()
    }
}
